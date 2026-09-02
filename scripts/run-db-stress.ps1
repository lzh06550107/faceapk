[CmdletBinding()]
param(
    [string]$Serial,
    [string]$PackageName='com.punch.app',
    [ValidateRange(1000,10000)][int]$Count=1000,
    [ValidateRange(0,3600)][int]$TimeoutSeconds=0,
    [string]$OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib\DeviceTestCommon.ps1')
. (Join-Path $PSScriptRoot 'lib\PerformanceMetrics.ps1')
. (Join-Path $PSScriptRoot 'lib\DbStress.ps1')

if($Count -notin @(1000,5000,10000)){throw 'Count must be exactly 1000, 5000, or 10000.'}
if($TimeoutSeconds -eq 0){
    $TimeoutSeconds=if($Count -eq 1000){300}elseif($Count -eq 5000){900}else{1800}
}

$projectRoot=Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
Assert-CommandAvailable -Name 'adb'
$resolvedSerial=Resolve-AndroidSerial -RequestedSerial $Serial
if(-not $OutputDirectory){$OutputDirectory=New-TestReportDirectory -ProjectRoot $projectRoot -Prefix 'db-stress-v26'}
else{New-Item -ItemType Directory -Path $OutputDirectory -Force|Out-Null;$OutputDirectory=(Resolve-Path $OutputDirectory).Path}

$backup=$null
$testInstalled=$false
$restoreFailure=$null
$kioskRestoreState=$null
$kioskFreshTargetRetryUsed=$false
$kioskFreshTargetRetryExit=$null
$exitCode=1
$prepareObj=$null
$restartObj=$null
$resultObj=$null
$fatalCount=0
$samples=New-Object System.Collections.Generic.List[object]
$rawMetrics=Join-Path $OutputDirectory 'raw-metrics'

try {
    Write-Host '========================================'
    Write-Host ' Punch App DB / Offline Queue Stress V2.6.2'
    Write-Host '========================================'
    Write-Host "Serial: $resolvedSerial  Count: $Count"
    Write-Host '[INFO] Backing up installed production APK(s).'
    $backup=Backup-InstalledPackageApks -Serial $resolvedSerial -PackageName $PackageName -BackupDirectory (Join-Path $OutputDirectory 'production-backup')

    Assert-LocalBuildEnvironment -ProjectRoot $projectRoot
    $buildLog=Join-Path $OutputDirectory 'db-stress-build.log'
    $buildExit=Invoke-LoggedCommand -FilePath (Join-Path $projectRoot 'gradlew.bat') -Arguments @(':app:assembleDbStressTest','--stacktrace') -LogPath $buildLog -WorkingDirectory $projectRoot
    if($buildExit -ne 0){throw "dbStressTest build failed with exit code $buildExit"}
    $apkDir=Join-Path $projectRoot 'app\build\outputs\apk\dbStressTest'
    $apk=Get-ChildItem $apkDir -Filter '*.apk' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if(-not $apk){throw "dbStressTest APK not found under $apkDir"}

    Write-Host '[INFO] Installing temporary dbStressTest build.'
    $install=Invoke-Adb -Serial $resolvedSerial -Arguments @('install','-r','-d',$apk.FullName) -LogPath (Join-Path $OutputDirectory 'install-db-stress.log')
    if($install -ne 0){throw "dbStressTest install failed with exit code $install"}
    $testInstalled=$true
    Start-Sleep -Seconds 1

    Write-Host '[INFO] Bringing DbStressTestHostActivity to the foreground.'
    $hostStart=Invoke-Adb -Serial $resolvedSerial -Arguments @('shell','am','start','-W','-n',"$PackageName/.dbstress.DbStressTestHostActivity") -LogPath (Join-Path $OutputDirectory 'start-db-stress-host.log')
    if($hostStart -ne 0){throw "DbStressTestHostActivity start failed with exit code $hostStart"}
    Start-Sleep -Seconds 1

    $preflight=Get-DbStressStatus -Serial $resolvedSerial -PackageName $PackageName
    $preflight.Raw | Set-Content -Path (Join-Path $OutputDirectory 'preflight-status.txt') -Encoding UTF8
    if(-not $preflight.Supported){throw 'DbStressTestReceiver is not reachable.'}
    if(-not $preflight.TokenValid){throw 'Production session token is missing or expired; V2.6 cannot exercise the real SyncService.'}
    if(-not $preflight.DeviceRegistered){throw 'Device is not registered; V2.6 production SyncCoordinator would skip synchronization.'}
    [void](Get-AdbOutput -Serial $resolvedSerial -Arguments @('logcat','-c') -IgnoreFailure)

    [void]$samples.Add((Get-AppPerformanceSample -Serial $resolvedSerial -PackageName $PackageName -Index 0 -ElapsedSeconds 0 -RawRoot $rawMetrics))

    Write-Host "[INFO] Seeding $Count real punch_records + punch_push queue rows into isolated V2.6 DB."
    (Invoke-DbStressBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action PREPARE -Count $Count) | Set-Content -Path (Join-Path $OutputDirectory 'prepare-broadcast.txt') -Encoding UTF8
    $status=Wait-DbStressState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('prepared','failed') -TimeoutSeconds $TimeoutSeconds
    if($null -eq $status -or $status.State -eq 'failed'){throw "V2.6 prepare failed: $($status.Error)"}
    $prepareText=Get-DbStressTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'prepare.json'
    if([string]::IsNullOrWhiteSpace($prepareText)){throw 'V2.6 prepare.json is empty.'}
    $prepareText | Set-Content -Path (Join-Path $OutputDirectory 'prepare.json') -Encoding UTF8
    $prepareObj=$prepareText | ConvertFrom-Json
    if(-not [bool]$prepareObj.success){throw 'V2.6 seed/query gate failed.'}
    [void]$samples.Add((Get-AppPerformanceSample -Serial $resolvedSerial -PackageName $PackageName -Index 1 -ElapsedSeconds 1 -RawRoot $rawMetrics))

    $oldPid=Get-DbStressPid -Serial $resolvedSerial -PackageName $PackageName
    if($oldPid -le 0){throw 'Unable to resolve V2.6 process PID before restart test.'}
    Write-Host "[INFO] Killing V2.6 process PID $oldPid to verify SQLite restart recovery."
    [void](Invoke-DbStressBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action KILL -Count $Count)
    if(-not (Wait-DbStressProcessExit -Serial $resolvedSerial -PackageName $PackageName -OldPid $oldPid -TimeoutSeconds 20)){throw 'V2.6 process did not terminate for restart recovery test.'}
    Start-Sleep -Seconds 1

    Write-Host '[INFO] Restarting DbStressTestHostActivity on the same isolated database.'
    $restartHost=Invoke-Adb -Serial $resolvedSerial -Arguments @('shell','am','start','-W','-n',"$PackageName/.dbstress.DbStressTestHostActivity") -LogPath (Join-Path $OutputDirectory 'restart-db-stress-host.log')
    if($restartHost -ne 0){throw "V2.6 HostActivity restart failed with exit code $restartHost"}
    Start-Sleep -Seconds 1
    [void]$samples.Add((Get-AppPerformanceSample -Serial $resolvedSerial -PackageName $PackageName -Index 2 -ElapsedSeconds 2 -RawRoot $rawMetrics))

    (Invoke-DbStressBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action VERIFY_RESTART -Count $Count) | Set-Content -Path (Join-Path $OutputDirectory 'verify-restart-broadcast.txt') -Encoding UTF8
    $status=Wait-DbStressState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('restart_verified','failed') -TimeoutSeconds $TimeoutSeconds
    if($null -eq $status -or $status.State -eq 'failed'){throw "V2.6 restart verification failed: $($status.Error)"}
    $restartText=Get-DbStressTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'restart.json'
    $restartText | Set-Content -Path (Join-Path $OutputDirectory 'restart.json') -Encoding UTF8
    $restartObj=$restartText | ConvertFrom-Json
    if(-not [bool]$restartObj.success){throw 'V2.6 restart persistence gate failed.'}

    Write-Host "[INFO] Draining $Count queued punches through production SyncService / SyncCoordinator (batch size 50)."
    (Invoke-DbStressBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action DRAIN -Count $Count) | Set-Content -Path (Join-Path $OutputDirectory 'drain-broadcast.txt') -Encoding UTF8
    $status=Wait-DbStressState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('completed','failed') -TimeoutSeconds $TimeoutSeconds
    if($null -eq $status -or $status.State -eq 'failed'){throw "V2.6 drain failed: $($status.Error)"}
    $resultText=Get-DbStressTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'result.json'
    if([string]::IsNullOrWhiteSpace($resultText)){throw 'V2.6 result.json is empty.'}
    $resultText | Set-Content -Path (Join-Path $OutputDirectory 'result.json') -Encoding UTF8
    $resultObj=$resultText | ConvertFrom-Json
    [void]$samples.Add((Get-AppPerformanceSample -Serial $resolvedSerial -PackageName $PackageName -Index 3 -ElapsedSeconds 3 -RawRoot $rawMetrics))

    @($samples.ToArray()) | Export-Csv -Path (Join-Path $OutputDirectory 'metrics.csv') -NoTypeInformation -Encoding UTF8
    [void](Invoke-Adb -Serial $resolvedSerial -Arguments @('logcat','-d','-v','threadtime') -LogPath (Join-Path $OutputDirectory 'logcat.txt'))
    $fatalCount=Find-AppFatalEvents -LogPath (Join-Path $OutputDirectory 'logcat.txt') -PackageName $PackageName -OutputPath (Join-Path $OutputDirectory 'fatal-events.txt')

    $success=[bool]$prepareObj.success -and [bool]$restartObj.success -and [bool]$resultObj.success -and $fatalCount -eq 0
    $statusText=if($success){'PASS'}else{'FAIL'}
    @(
        'Punch App DB / Offline Queue Stress V2.6.1',
        "core_status=$statusText",
        "count=$Count",
        "seed_total_ms=$($prepareObj.seed_total_ms)",
        "seed_rows_per_sec=$($prepareObj.seed_rows_per_sec)",
        "db_bytes_after_seed=$($prepareObj.db_bytes_after_seed)",
        "restart_integrity=$($restartObj.integrity)",
        "drain_total_ms=$($resultObj.drain_total_ms)",
        "drain_rows_per_sec=$($resultObj.drain_rows_per_sec)",
        "service_trigger_count=$($resultObj.service_trigger_count)",
        "punch_http_requests=$($resultObj.punch_http_requests)",
        "unexpected_retry=$($resultObj.unexpected_retry)",
        "db_bytes_before_vacuum=$($resultObj.db_bytes_before_vacuum)",
        "db_bytes_after_vacuum=$($resultObj.db_bytes_after_vacuum)",
        "fatal_events=$fatalCount",
        'production_database_touched=false',
        'http_target=127.0.0.1_only'
    ) | Set-Content -Path (Join-Path $OutputDirectory 'summary.txt') -Encoding UTF8
    $exitCode=if($success){0}else{1}

    [void](Invoke-DbStressBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action CLEANUP -Count $Count)
} catch {
    $exitCode=1
    $message=$_.Exception.Message
    Write-Host "[FAIL] $message" -ForegroundColor Red
    @('Punch App DB / Offline Queue Stress V2.6.1','status=FAIL',"count=$Count","reason=$message") | Set-Content -Path (Join-Path $OutputDirectory 'wrapper-summary.txt') -Encoding UTF8
} finally {
    if($testInstalled){
        try { [void](Invoke-DbStressBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action CLEANUP -Count $Count) } catch {}
    }
    if($testInstalled -and $null -ne $backup){
        try {
            $testPidBeforeRestore=Get-DbStressPid -Serial $resolvedSerial -PackageName $PackageName
            if($testPidBeforeRestore -gt 0){
                Write-Host '[INFO] Terminating V2.6 test process before production restore.'
                try { [void](Invoke-DbStressBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action TERMINATE -Count $Count) } catch {}
                if(-not (Wait-DbStressProcessExit -Serial $resolvedSerial -PackageName $PackageName -OldPid $testPidBeforeRestore -TimeoutSeconds 20)){
                    $restoreFailure='V2.6 test process did not terminate before production restore'
                }
            }
            Write-Host '[INFO] Restoring original production APK(s).'
            $restore=Restore-InstalledPackageApks -Serial $resolvedSerial -ApkPaths $backup.ApkPaths -LogPath (Join-Path $OutputDirectory 'restore-production-app.log')
            if($restore -ne 0){$restoreFailure="restore failed exit=$restore"}
            else{
                $kioskStart=Invoke-Adb -Serial $resolvedSerial -Arguments @('shell','am','start','-W','-n',"$PackageName/.activity.KioskHomeActivity") -LogPath (Join-Path $OutputDirectory 'restore-kiosk.log')
                if($kioskStart -ne 0){
                    $restoreFailure="KioskHomeActivity start failed exit=$kioskStart"
                } else {
                    $kioskRestoreState=Wait-DbStressProductionKioskReady -Serial $resolvedSerial -PackageName $PackageName -TimeoutSeconds 20
                    if($null -ne $kioskRestoreState){
                        $kioskRestoreState.Raw | Set-Content -Path (Join-Path $OutputDirectory 'restore-kiosk-state.txt') -Encoding UTF8
                    }

                    $shouldRetryFreshTarget=($null -ne $kioskRestoreState `
                        -and $kioskRestoreState.IsProductionForeground `
                        -and $kioskRestoreState.IsExpectedRoute `
                        -and $kioskRestoreState.IsDeviceOwner `
                        -and -not $kioskRestoreState.IsLockTaskActive)
                    if($shouldRetryFreshTarget){
                        $kioskFreshTargetRetryUsed=$true
                        Write-Host '[WARN] Production route is foreground but LockTask is inactive; forcing a fresh kiosk target and retrying.' -ForegroundColor Yellow
                        $kioskFreshTargetRetryExit=Invoke-Adb -Serial $resolvedSerial -Arguments @(
                            'shell','am','start','-W','-n',"$PackageName/.activity.KioskHomeActivity",
                            '--ez','force_fresh_target','true'
                        ) -LogPath (Join-Path $OutputDirectory 'restore-kiosk-retry.log')
                        if($kioskFreshTargetRetryExit -eq 0){
                            $retryState=Wait-DbStressProductionKioskReady -Serial $resolvedSerial -PackageName $PackageName -TimeoutSeconds 30
                            if($null -ne $retryState){
                                $retryState.Raw | Set-Content -Path (Join-Path $OutputDirectory 'restore-kiosk-state-retry.txt') -Encoding UTF8
                                $kioskRestoreState=$retryState
                            }
                        }
                    }

                    if($null -eq $kioskRestoreState -or -not $kioskRestoreState.Ready){
                        $reason=if($null -eq $kioskRestoreState){'no kiosk state returned'}else{$kioskRestoreState.Reason}
                        if($kioskFreshTargetRetryUsed -and $null -ne $kioskFreshTargetRetryExit -and $kioskFreshTargetRetryExit -ne 0){
                            $reason="$reason; fresh target retry start failed exit=$kioskFreshTargetRetryExit"
                        }
                        $restoreFailure="production kiosk restore gate failed: $reason"
                    }
                }
            }
        } catch {$restoreFailure=$_.Exception.Message}
    }
}
$kioskRestoreReady=($null -ne $kioskRestoreState -and $kioskRestoreState.Ready)
$foregroundActivity=if($null -ne $kioskRestoreState){$kioskRestoreState.ForegroundActivity}else{''}
$lockTaskActive=if($null -ne $kioskRestoreState){$kioskRestoreState.IsLockTaskActive}else{$false}
$deviceOwner=if($null -ne $kioskRestoreState){$kioskRestoreState.IsDeviceOwner}else{$false}
@(
    "count=$Count",
    "temporary_build_installed=$testInstalled",
    "kiosk_restore_ready=$kioskRestoreReady",
    "foreground_activity=$foregroundActivity",
    "lock_task_active=$lockTaskActive",
    "device_owner=$deviceOwner",
    "kiosk_fresh_target_retry_used=$kioskFreshTargetRetryUsed",
    "kiosk_fresh_target_retry_exit=$kioskFreshTargetRetryExit",
    "restore_failure=$restoreFailure"
) | Set-Content -Path (Join-Path $OutputDirectory 'transaction.txt') -Encoding UTF8
$overallStatus=if($restoreFailure -or $exitCode -ne 0){'FAIL'}else{'PASS'}
$summaryPath=Join-Path $OutputDirectory 'summary.txt'
if(Test-Path $summaryPath){
    @(
        "kiosk_restore_ready=$kioskRestoreReady",
        "foreground_activity=$foregroundActivity",
        "lock_task_active=$lockTaskActive",
        "device_owner=$deviceOwner",
        "kiosk_fresh_target_retry_used=$kioskFreshTargetRetryUsed",
        "kiosk_fresh_target_retry_exit=$kioskFreshTargetRetryExit",
        "restore_failure=$restoreFailure",
        "overall_status=$overallStatus"
    ) | Add-Content -Path $summaryPath -Encoding UTF8
}
Write-Host "Report: $OutputDirectory"
if($restoreFailure){
    Write-Host "[FAIL] $restoreFailure" -ForegroundColor Red
    Write-Host 'RESULT: FAIL' -ForegroundColor Red
    exit 1
}
$finalStatus=if($exitCode -eq 0){'PASS'}else{'FAIL'}
Write-Host "RESULT: $finalStatus"
exit $exitCode
