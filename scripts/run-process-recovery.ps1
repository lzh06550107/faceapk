[CmdletBinding()]
param(
    [string]$Serial,
    [string]$PackageName='com.punch.app',
    [ValidateSet('ForegroundKill','SyncKillRecovery','All')][string]$Scenario='All',
    [ValidateRange(20,300)][int]$RecoveryTimeoutSeconds=60,
    [ValidateRange(30,600)][int]$SyncTimeoutSeconds=180,
    [string]$OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib\DeviceTestCommon.ps1')
. (Join-Path $PSScriptRoot 'lib\ProcessRecovery.ps1')

$projectRoot=Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
Assert-CommandAvailable -Name 'adb'
$resolvedSerial=Resolve-AndroidSerial -RequestedSerial $Serial
if(-not $OutputDirectory){$OutputDirectory=New-TestReportDirectory -ProjectRoot $projectRoot -Prefix 'process-recovery-v271'}
else{New-Item -ItemType Directory -Path $OutputDirectory -Force|Out-Null;$OutputDirectory=(Resolve-Path $OutputDirectory).Path}

$backup=$null
$testInstalled=$false
$exitCode=1
$restoreFailure=$null
$productionRestoreState=$null
$fatalCount=0
$foregroundRecovery=$null
$syncRecovery=$null
$scenarioFailures=New-Object System.Collections.Generic.List[string]

try {
    Write-Host '========================================'
    Write-Host ' Punch App Process Kill Recovery V2.7.1'
    Write-Host '========================================'
    Write-Host "Serial: $resolvedSerial  Scenario: $Scenario"
    Write-Host '[INFO] Backing up installed production APK(s).'
    $backup=Backup-InstalledPackageApks -Serial $resolvedSerial -PackageName $PackageName -BackupDirectory (Join-Path $OutputDirectory 'production-backup')

    Assert-LocalBuildEnvironment -ProjectRoot $projectRoot
    $buildExit=Invoke-LoggedCommand -FilePath (Join-Path $projectRoot 'gradlew.bat') -Arguments @(':app:assembleProcessRecoveryTest','--stacktrace') -LogPath (Join-Path $OutputDirectory 'process-recovery-build.log') -WorkingDirectory $projectRoot
    if($buildExit -ne 0){throw "processRecoveryTest build failed with exit code $buildExit"}
    $apkDir=Join-Path $projectRoot 'app\build\outputs\apk\processRecoveryTest'
    $apk=Get-ChildItem $apkDir -Filter '*.apk' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if(-not $apk){throw "processRecoveryTest APK not found under $apkDir"}

    Write-Host '[INFO] Installing temporary processRecoveryTest build.'
    $install=Invoke-Adb -Serial $resolvedSerial -Arguments @('install','-r','-d',$apk.FullName) -LogPath (Join-Path $OutputDirectory 'install-process-recovery.log')
    if($install -ne 0){throw "processRecoveryTest install failed with exit code $install"}
    $testInstalled=$true

    # Same-package Device Owner replacement does not guarantee the previously loaded process is gone.
    # Force one test-only cold transition before preflight so the processRecoveryTest Application
    # definitely installs the isolated DB selector and loopback-only ApiClient before production lifecycle code.
    $testPidAfterInstall=Get-ProcessRecoveryPid -Serial $resolvedSerial -PackageName $PackageName
    if($testPidAfterInstall -gt 0){
        Write-Host '[INFO] Cold-starting V2.7.1 test process after install.'
        (Invoke-ProcessRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action TERMINATE) | Set-Content -Path (Join-Path $OutputDirectory 'terminate-after-test-install.txt') -Encoding UTF8
        if(-not (Wait-ProcessRecoveryOldPidExit -Serial $resolvedSerial -PackageName $PackageName -OldPid $testPidAfterInstall -TimeoutSeconds 20)){
            throw 'processRecoveryTest old PID did not exit before cold preflight start.'
        }
    }

    # Initial setup may explicitly enter the Kiosk entry. Post-kill recovery below is observer-only.
    Write-Host '[INFO] Starting V2.7.1 Kiosk entry for preflight.'
    $start=Invoke-Adb -Serial $resolvedSerial -Arguments @('shell','am','start','-W','-n',"$PackageName/.activity.KioskHomeActivity") -LogPath (Join-Path $OutputDirectory 'start-test-kiosk.log')
    if($start -ne 0){throw "initial KioskHomeActivity start failed exit=$start"}
    $initial=Wait-ProcessRecoveryProductionKioskReady -Serial $resolvedSerial -PackageName $PackageName -TimeoutSeconds 30
    if($null -eq $initial -or -not $initial.Ready){
        $reason=if($null -eq $initial){'no initial kiosk state'}else{$initial.Reason}
        throw "V2.7.1 preflight kiosk gate failed: $reason"
    }
    $initial.Raw | Set-Content -Path (Join-Path $OutputDirectory 'initial-kiosk-state.txt') -Encoding UTF8

    $preflight=Get-ProcessRecoveryStatus -Serial $resolvedSerial -PackageName $PackageName
    $preflight.Raw | Set-Content -Path (Join-Path $OutputDirectory 'preflight-status.txt') -Encoding UTF8
    if(-not $preflight.Supported){throw 'V2.7.1 status receiver is unavailable.'}
    if(-not $preflight.DatabaseActive){throw 'V2.7.1 isolated database selector is not active.'}
    if(-not $preflight.TokenValid){throw 'V2.7.1 requires a valid login token.'}
    if(-not $preflight.DeviceRegistered){throw 'V2.7.1 requires a registered device.'}

    [void](Invoke-Adb -Serial $resolvedSerial -Arguments @('logcat','-c') -LogPath (Join-Path $OutputDirectory 'logcat-clear.txt'))

    if($Scenario -in @('ForegroundKill','All')){
        Write-Host '[INFO] ForegroundKill: killing foreground process and observing autonomous Kiosk recovery.'
        $oldForegroundPid=Get-ProcessRecoveryPid -Serial $resolvedSerial -PackageName $PackageName
        if($oldForegroundPid -le 0){throw 'ForegroundKill old PID is missing.'}
        (Invoke-ProcessRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action KILL_FOREGROUND) | Set-Content -Path (Join-Path $OutputDirectory 'foreground-kill-broadcast.txt') -Encoding UTF8
        if(-not (Wait-ProcessRecoveryOldPidExit -Serial $resolvedSerial -PackageName $PackageName -OldPid $oldForegroundPid -TimeoutSeconds $RecoveryTimeoutSeconds)){
            throw 'ForegroundKill old PID did not exit.'
        }
        $foregroundRecovery=Wait-ProcessRecoveryAutomaticKioskReady -Serial $resolvedSerial -PackageName $PackageName -OldPid $oldForegroundPid -TimeoutSeconds $RecoveryTimeoutSeconds
        Save-ProcessRecoveryAutomaticEvidence -Recovery $foregroundRecovery -Path (Join-Path $OutputDirectory 'foreground-autonomous-recovery.txt') -Scenario 'ForegroundKill'
        if(-not $foregroundRecovery.Ready){throw "ForegroundKill autonomous recovery failed: $($foregroundRecovery.Reason)"}
        $foregroundKillDevice=Get-ProcessRecoveryTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'foreground-kill.json'
        if([string]::IsNullOrWhiteSpace($foregroundKillDevice)){throw 'foreground-kill.json is missing after autonomous restart.'}
        $foregroundKillDevice | Set-Content -Path (Join-Path $OutputDirectory 'foreground-kill.json') -Encoding UTF8
        Write-Host "[PASS] ForegroundKill autonomous recovery old=$oldForegroundPid new=$($foregroundRecovery.NewPid)"
    }

    if($Scenario -in @('SyncKillRecovery','All')){
        Write-Host '[INFO] SyncKillRecovery: seeding 100 queued punches and killing after partial production sync progress.'
        $oldSyncPid=Get-ProcessRecoveryPid -Serial $resolvedSerial -PackageName $PackageName
        if($oldSyncPid -le 0){throw 'SyncKillRecovery old PID is missing.'}
        (Invoke-ProcessRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action PREPARE_SYNC_KILL) | Set-Content -Path (Join-Path $OutputDirectory 'prepare-sync-kill-broadcast.txt') -Encoding UTF8
        if(-not (Wait-ProcessRecoveryOldPidExit -Serial $resolvedSerial -PackageName $PackageName -OldPid $oldSyncPid -TimeoutSeconds $SyncTimeoutSeconds)){
            throw 'SyncKillRecovery process was not killed inside the partial-sync window.'
        }

        $syncRecovery=Wait-ProcessRecoveryAutomaticKioskReady -Serial $resolvedSerial -PackageName $PackageName -OldPid $oldSyncPid -TimeoutSeconds $RecoveryTimeoutSeconds
        Save-ProcessRecoveryAutomaticEvidence -Recovery $syncRecovery -Path (Join-Path $OutputDirectory 'sync-autonomous-recovery.txt') -Scenario 'SyncKillRecovery'
        if(-not $syncRecovery.Ready){throw "SyncKillRecovery autonomous recovery failed: $($syncRecovery.Reason)"}

        $checkpoint=Get-ProcessRecoveryTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'sync-kill-checkpoint.json'
        if([string]::IsNullOrWhiteSpace($checkpoint)){throw 'sync-kill-checkpoint.json is missing after restart.'}
        $checkpoint | Set-Content -Path (Join-Path $OutputDirectory 'sync-kill-checkpoint.json') -Encoding UTF8

        # This verification happens only after autonomous new-PID/Kiosk recovery.
        (Invoke-ProcessRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action VERIFY_SYNC_RESTART) | Set-Content -Path (Join-Path $OutputDirectory 'verify-sync-restart-broadcast.txt') -Encoding UTF8
        $verifyStatus=Wait-ProcessRecoveryState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('sync_restart_verified','failed') -TimeoutSeconds $SyncTimeoutSeconds
        if($null -eq $verifyStatus -or $verifyStatus.State -eq 'failed'){throw "Sync restart verification failed: $($verifyStatus.Error)"}
        $afterRestart=Get-ProcessRecoveryTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'sync-after-restart.json'
        if([string]::IsNullOrWhiteSpace($afterRestart)){throw 'sync-after-restart.json is missing.'}
        $afterRestart | Set-Content -Path (Join-Path $OutputDirectory 'sync-after-restart.json') -Encoding UTF8
        $afterRestartObj=$afterRestart | ConvertFrom-Json
        if(-not [bool]$afterRestartObj.success){throw 'Sync restart SQLite invariants failed.'}

        (Invoke-ProcessRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action RESUME_SYNC) | Set-Content -Path (Join-Path $OutputDirectory 'resume-sync-broadcast.txt') -Encoding UTF8
        $resumeStatus=Wait-ProcessRecoveryState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('sync_recovery_completed','failed') -TimeoutSeconds $SyncTimeoutSeconds
        if($null -eq $resumeStatus -or $resumeStatus.State -eq 'failed'){throw "Sync recovery drain failed: $($resumeStatus.Error)"}
        $syncResult=Get-ProcessRecoveryTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'sync-result.json'
        if([string]::IsNullOrWhiteSpace($syncResult)){throw 'sync-result.json is missing.'}
        $syncResult | Set-Content -Path (Join-Path $OutputDirectory 'sync-result.json') -Encoding UTF8
        $syncResultObj=$syncResult | ConvertFrom-Json
        if(-not [bool]$syncResultObj.success){throw 'Sync recovery final drain/cleanup gate failed.'}
        Write-Host "[PASS] SyncKillRecovery autonomous recovery old=$oldSyncPid new=$($syncRecovery.NewPid)"
    }

    $killEvents=Get-ProcessRecoveryTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'kill-events.jsonl'
    if([string]::IsNullOrWhiteSpace($killEvents)){throw 'kill-events.jsonl is missing.'}
    $killEvents | Set-Content -Path (Join-Path $OutputDirectory 'kill-events.jsonl') -Encoding UTF8

    [void](Invoke-Adb -Serial $resolvedSerial -Arguments @('logcat','-d','-v','threadtime') -LogPath (Join-Path $OutputDirectory 'logcat.txt'))
    $fatalCount=Find-AppFatalEvents -LogPath (Join-Path $OutputDirectory 'logcat.txt') -PackageName $PackageName -OutputPath (Join-Path $OutputDirectory 'fatal-events.txt')
    if($fatalCount -gt 0){throw "Unexpected fatal events detected: $fatalCount"}

    $exitCode=0
} catch {
    $exitCode=1
    $message=$_.Exception.Message
    [void]$scenarioFailures.Add($message)
    Write-Host "[FAIL] $message" -ForegroundColor Red
} finally {
    if($testInstalled){
        try { [void](Invoke-ProcessRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action CLEANUP) } catch {}
    }
    if($testInstalled -and $null -ne $backup){
        try {
            $testPidBeforeRestore=Get-ProcessRecoveryPid -Serial $resolvedSerial -PackageName $PackageName
            if($testPidBeforeRestore -gt 0){
                Write-Host '[INFO] Terminating V2.7.1 test process before production restore.'
                try { [void](Invoke-ProcessRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action TERMINATE) } catch {}
                if(-not (Wait-ProcessRecoveryOldPidExit -Serial $resolvedSerial -PackageName $PackageName -OldPid $testPidBeforeRestore -TimeoutSeconds 20)){
                    $restoreFailure='V2.7.1 test process did not terminate before production restore'
                }
            }
            Write-Host '[INFO] Restoring original production APK(s).'
            $restore=Restore-InstalledPackageApks -Serial $resolvedSerial -ApkPaths $backup.ApkPaths -LogPath (Join-Path $OutputDirectory 'restore-production-app.log')
            if($restore -ne 0){$restoreFailure="restore failed exit=$restore"}
            else{
                # Active start is permitted only for the final production restore gate, not for post-kill recovery observation.
                $restoreStart=Invoke-Adb -Serial $resolvedSerial -Arguments @('shell','am','start','-W','-n',"$PackageName/.activity.KioskHomeActivity") -LogPath (Join-Path $OutputDirectory 'restore-kiosk.log')
                if($restoreStart -ne 0){$restoreFailure="production KioskHomeActivity start failed exit=$restoreStart"}
                else{
                    $productionRestoreState=Wait-ProcessRecoveryProductionKioskReady -Serial $resolvedSerial -PackageName $PackageName -TimeoutSeconds 30
                    if($null -ne $productionRestoreState){$productionRestoreState.Raw | Set-Content -Path (Join-Path $OutputDirectory 'restore-kiosk-state.txt') -Encoding UTF8}
                    if($null -eq $productionRestoreState -or -not $productionRestoreState.Ready){
                        $reason=if($null -eq $productionRestoreState){'no production kiosk state'}else{$productionRestoreState.Reason}
                        $restoreFailure="production kiosk restore gate failed: $reason"
                    }
                }
            }
        } catch {$restoreFailure=$_.Exception.Message}
    }
}

$foregroundOld=if($null -ne $foregroundRecovery){$foregroundRecovery.OldPid}else{0}
$foregroundNew=if($null -ne $foregroundRecovery){$foregroundRecovery.NewPid}else{0}
$syncOld=if($null -ne $syncRecovery){$syncRecovery.OldPid}else{0}
$syncNew=if($null -ne $syncRecovery){$syncRecovery.NewPid}else{0}
$restoreReady=($null -ne $productionRestoreState -and $productionRestoreState.Ready)
$overallStatus=if($exitCode -eq 0 -and -not $restoreFailure){'PASS'}else{'FAIL'}
@(
    'Punch App Process Kill Recovery V2.7.1',
    "scenario=$Scenario",
    'autonomous_recovery=true',
    "foreground_old_pid=$foregroundOld",
    "foreground_new_pid=$foregroundNew",
    "sync_old_pid=$syncOld",
    "sync_new_pid=$syncNew",
    "fatal_events=$fatalCount",
    'production_backend_requests=0',
    'production_database_touched=false',
    "production_restore_ready=$restoreReady",
    "restore_failure=$restoreFailure",
    "overall_status=$overallStatus"
) | Set-Content -Path (Join-Path $OutputDirectory 'summary.txt') -Encoding UTF8
@(
    "temporary_build_installed=$testInstalled",
    "scenario_failures=$($scenarioFailures -join ' | ')",
    "old_pid_foreground=$foregroundOld",
    "new_pid_foreground=$foregroundNew",
    "old_pid_sync=$syncOld",
    "new_pid_sync=$syncNew",
    "restore_failure=$restoreFailure"
) | Set-Content -Path (Join-Path $OutputDirectory 'transaction.txt') -Encoding UTF8

Write-Host "Report: $OutputDirectory"
if($restoreFailure){
    Write-Host "[FAIL] $restoreFailure" -ForegroundColor Red
    Write-Host 'RESULT: FAIL' -ForegroundColor Red
    exit 1
}
if($exitCode -ne 0){
    Write-Host 'RESULT: FAIL' -ForegroundColor Red
    exit 1
}
Write-Host 'RESULT: PASS'
exit 0
