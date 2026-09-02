[CmdletBinding()]
# Usage: -Cycles 1 for quick validation; -Cycles 3 is the default reliability gate.
param(
    [string]$Serial,
    [string]$PackageName='com.punch.app',
    [ValidateRange(1,10)][int]$Cycles=3,
    [ValidateRange(30,600)][int]$PrepareTimeoutSeconds=180,
    [ValidateRange(20,300)][int]$RecoveryTimeoutSeconds=90,
    [ValidateRange(20,180)][int]$RecognitionTimeoutSeconds=60,
    [string]$OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib\DeviceTestCommon.ps1')
. (Join-Path $PSScriptRoot 'lib\CameraFaceRecovery.ps1')

$projectRoot=Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
Assert-CommandAvailable -Name 'adb'
$resolvedSerial=Resolve-AndroidSerial -RequestedSerial $Serial
if(-not $OutputDirectory){$OutputDirectory=New-TestReportDirectory -ProjectRoot $projectRoot -Prefix 'camera-face-recovery-v273'}
else{New-Item -ItemType Directory -Path $OutputDirectory -Force|Out-Null;$OutputDirectory=(Resolve-Path $OutputDirectory).Path}

$backup=$null
$testInstalled=$false
$exitCode=1
$restoreFailure=$null
$productionRestoreState=$null
$fatalCount=0
$completedCycles=0
$cycleEvidence=New-Object System.Collections.Generic.List[string]
$failures=New-Object System.Collections.Generic.List[string]

try {
    Write-Host '============================================='
    Write-Host ' Punch App Camera/Face Kill Recovery V2.7.3'
    Write-Host '============================================='
    Write-Host "Serial: $resolvedSerial  Cycles: $Cycles"
    Write-Host '[INFO] Backing up installed production APK(s).'
    $backup=Backup-InstalledPackageApks -Serial $resolvedSerial -PackageName $PackageName -BackupDirectory (Join-Path $OutputDirectory 'production-backup')

    Assert-LocalBuildEnvironment -ProjectRoot $projectRoot
    $buildExit=Invoke-LoggedCommand -FilePath (Join-Path $projectRoot 'gradlew.bat') -Arguments @(':app:assembleCameraFaceRecoveryTest','--stacktrace') -LogPath (Join-Path $OutputDirectory 'camera-face-recovery-build.log') -WorkingDirectory $projectRoot
    if($buildExit -ne 0){throw "cameraFaceRecoveryTest build failed with exit code $buildExit"}
    $apkDir=Join-Path $projectRoot 'app\build\outputs\apk\cameraFaceRecoveryTest'
    $apk=Get-ChildItem $apkDir -Filter '*.apk' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if(-not $apk){throw "cameraFaceRecoveryTest APK not found under $apkDir"}

    Write-Host '[INFO] Installing temporary cameraFaceRecoveryTest build.'
    $install=Invoke-Adb -Serial $resolvedSerial -Arguments @('install','-r','-d',$apk.FullName) -LogPath (Join-Path $OutputDirectory 'install-camera-face-recovery.log')
    if($install -ne 0){throw "cameraFaceRecoveryTest install failed with exit code $install"}
    $testInstalled=$true

    $testPidAfterInstall=Get-CameraFaceRecoveryPid -Serial $resolvedSerial -PackageName $PackageName
    if($testPidAfterInstall -gt 0){
        Write-Host '[INFO] Cold-starting V2.7.3 test process after install.'
        (Invoke-CameraFaceRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action TERMINATE -Cycle 1) | Set-Content -Path (Join-Path $OutputDirectory 'terminate-after-test-install.txt') -Encoding UTF8
        if(-not (Wait-CameraFaceRecoveryOldPidExit -Serial $resolvedSerial -PackageName $PackageName -OldPid $testPidAfterInstall -TimeoutSeconds 20)){
            throw 'cameraFaceRecoveryTest old PID did not exit before cold preflight start.'
        }
    }

    [void](Invoke-Adb -Serial $resolvedSerial -Arguments @('logcat','-c') -LogPath (Join-Path $OutputDirectory 'logcat-clear.txt'))
    Write-Host '[INFO] Starting V2.7.3 Kiosk entry for preflight.'
    $start=Invoke-Adb -Serial $resolvedSerial -Arguments @('shell','am','start','-W','-n',"$PackageName/.activity.KioskHomeActivity") -LogPath (Join-Path $OutputDirectory 'start-test-kiosk.log')
    if($start -ne 0){throw "initial KioskHomeActivity start failed exit=$start"}
    $initialKiosk=Wait-CameraFaceRecoveryKioskReady -Serial $resolvedSerial -PackageName $PackageName -TimeoutSeconds 30
    if($null -eq $initialKiosk -or -not $initialKiosk.Ready){
        Save-CameraFaceRecoveryPreflightDiagnostics -Serial $resolvedSerial -PackageName $PackageName -OutputDirectory $OutputDirectory -KioskState $initialKiosk
        [void](Invoke-Adb -Serial $resolvedSerial -Arguments @('logcat','-d','-v','threadtime') -LogPath (Join-Path $OutputDirectory 'preflight-startup-logcat.txt'))
        $preflightFatalCount=Find-AppFatalEvents -LogPath (Join-Path $OutputDirectory 'preflight-startup-logcat.txt') -PackageName $PackageName -OutputPath (Join-Path $OutputDirectory 'preflight-fatal-events.txt')
        $processIdNow=Get-CameraFaceRecoveryPid -Serial $resolvedSerial -PackageName $PackageName
        $reason=if($null -eq $initialKiosk){'no initial kiosk state'}else{$initialKiosk.Reason}
        if($processIdNow -le 0){throw "V2.7.3 preflight process exited during preflight; fatal_events=$preflightFatalCount; $reason"}
        if($preflightFatalCount -gt 0){throw "V2.7.3 preflight fatal event detected count=$preflightFatalCount; $reason"}
        throw "V2.7.3 preflight kiosk gate failed: $reason"
    }
    $initialKiosk.Raw | Set-Content -Path (Join-Path $OutputDirectory 'initial-kiosk-state.txt') -Encoding UTF8

    $preflight=Get-CameraFaceRecoveryStatus -Serial $resolvedSerial -PackageName $PackageName
    $preflight.Raw | Set-Content -Path (Join-Path $OutputDirectory 'preflight-status.txt') -Encoding UTF8
    if(-not $preflight.Supported){throw 'V2.7.3 status receiver is unavailable.'}
    if(-not $preflight.DatabaseActive){throw 'V2.7.3 isolated database selector is not active.'}
    if(-not $preflight.TokenValid){throw 'V2.7.3 requires a valid login token.'}
    if(-not $preflight.DeviceRegistered){throw 'V2.7.3 requires a registered device.'}

    Write-Host '[INFO] Preparing deterministic face fixture through production face-library rebuild.'
    (Invoke-CameraFaceRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action PREPARE) | Set-Content -Path (Join-Path $OutputDirectory 'prepare-broadcast.txt') -Encoding UTF8
    $prepareState=Wait-CameraFaceRecoveryState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('prepared','failed') -TimeoutSeconds $PrepareTimeoutSeconds
    if($null -eq $prepareState -or $prepareState.State -eq 'failed'){throw "V2.7.3 PREPARE failed: $($prepareState.Error)"}
    foreach($name in @('prepare-result.json','pre-kill-health.json','pre-kill-recognition.json')){
        $deviceText=Get-CameraFaceRecoveryTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath $name
        if([string]::IsNullOrWhiteSpace($deviceText)){throw "$name is missing after PREPARE."}
        $deviceText | Set-Content -Path (Join-Path $OutputDirectory $name) -Encoding UTF8
    }
    $initialHealth=Wait-CameraFaceRecoveryHealthReady -Serial $resolvedSerial -PackageName $PackageName -TimeoutSeconds 30
    if($null -eq $initialHealth -or -not $initialHealth.Ready){throw 'Initial Camera/Face health gate failed after PREPARE.'}

    for($cycle=1;$cycle -le $Cycles;$cycle++){
        $cycleTag='{0:D2}' -f $cycle
        Write-Host ("[INFO] Cycle {0}/{1}: validating recognition before kill." -f $cycle,$Cycles)
        (Invoke-CameraFaceRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action RECOGNIZE -Cycle $cycle) | Set-Content -Path (Join-Path $OutputDirectory "cycle-$cycleTag-pre-kill-recognize-broadcast.txt") -Encoding UTF8
        $preRecognitionState=Wait-CameraFaceRecoveryState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('recognition_ready','failed') -TimeoutSeconds $RecognitionTimeoutSeconds
        if($null -eq $preRecognitionState -or $preRecognitionState.State -eq 'failed'){throw "Cycle $cycle pre-kill recognition failed: $($preRecognitionState.Error)"}
        $preRecognition=Get-CameraFaceRecoveryTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath "cycle-$cycleTag-recognition.json"
        if([string]::IsNullOrWhiteSpace($preRecognition)){throw "Cycle $cycle pre-kill recognition evidence missing."}
        $preRecognition | Set-Content -Path (Join-Path $OutputDirectory "cycle-$cycleTag-pre-kill-recognition.json") -Encoding UTF8
        $preRecognitionObj=$preRecognition | ConvertFrom-Json
        if(-not [bool]$preRecognitionObj.success -or [string]$preRecognitionObj.emp_id -ne 'RECOVERY_FACE_V273'){throw "Cycle $cycle pre-kill recognition did not match RECOVERY_FACE_V273."}

        $oldProcessId=Get-CameraFaceRecoveryPid -Serial $resolvedSerial -PackageName $PackageName
        if($oldProcessId -le 0){throw "Cycle $cycle old PID is missing."}
        Write-Host ("[INFO] Cycle {0}/{1}: killing Camera/Face process old_pid={2} and observing autonomous recovery." -f $cycle,$Cycles,$oldProcessId)
        (Invoke-CameraFaceRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action KILL -Cycle $cycle) | Set-Content -Path (Join-Path $OutputDirectory "cycle-$cycleTag-kill-broadcast.txt") -Encoding UTF8
        if(-not (Wait-CameraFaceRecoveryOldPidExit -Serial $resolvedSerial -PackageName $PackageName -OldPid $oldProcessId -TimeoutSeconds $RecoveryTimeoutSeconds)){
            throw "Cycle $cycle old PID did not exit."
        }

        $recovery=Wait-CameraFaceRecoveryAutomaticReady -Serial $resolvedSerial -PackageName $PackageName -OldPid $oldProcessId -TimeoutSeconds $RecoveryTimeoutSeconds
        Save-CameraFaceRecoveryAutomaticEvidence -Recovery $recovery -Path (Join-Path $OutputDirectory "cycle-$cycleTag-autonomous-recovery.txt") -Cycle $cycle
        if(-not $recovery.Ready){throw "Cycle $cycle autonomous Camera/Face recovery failed: $($recovery.Reason)"}
        Write-CameraFaceRecoveryHealthJson -Health $recovery.Health -Path (Join-Path $OutputDirectory "cycle-$cycleTag-camera-face-health.json")

        $killJson=Get-CameraFaceRecoveryTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath "cycle-$cycleTag-kill.json"
        if([string]::IsNullOrWhiteSpace($killJson)){throw "Cycle $cycle kill evidence missing after restart."}
        $killJson | Set-Content -Path (Join-Path $OutputDirectory "cycle-$cycleTag-kill.json") -Encoding UTF8

        Write-Host ("[INFO] Cycle {0}/{1}: running real Detect/Feature/Search after restart." -f $cycle,$Cycles)
        (Invoke-CameraFaceRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action RECOGNIZE -Cycle $cycle) | Set-Content -Path (Join-Path $OutputDirectory "cycle-$cycleTag-post-restart-recognize-broadcast.txt") -Encoding UTF8
        $recognitionState=Wait-CameraFaceRecoveryState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('recognition_ready','failed') -TimeoutSeconds $RecognitionTimeoutSeconds
        if($null -eq $recognitionState -or $recognitionState.State -eq 'failed'){throw "Cycle $cycle post-restart recognition failed: $($recognitionState.Error)"}
        $recognition=Get-CameraFaceRecoveryTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath "cycle-$cycleTag-recognition.json"
        if([string]::IsNullOrWhiteSpace($recognition)){throw "Cycle $cycle post-restart recognition evidence missing."}
        $recognition | Set-Content -Path (Join-Path $OutputDirectory "cycle-$cycleTag-recognition.json") -Encoding UTF8
        $recognitionObj=$recognition | ConvertFrom-Json
        if(-not [bool]$recognitionObj.success -or [string]$recognitionObj.emp_id -ne 'RECOVERY_FACE_V273'){throw "Cycle $cycle post-restart recognition did not match RECOVERY_FACE_V273."}

        [void]$cycleEvidence.Add("cycle=$cycle;old_pid=$oldProcessId;new_pid=$($recovery.NewPid);camera=$($recovery.CameraActive);face=$($recovery.FaceInitialized);loaded=$($recovery.LoadedFaceCount);punch_ready=$($recovery.PunchReady)")
        $completedCycles=$cycle
        Write-Host "[PASS] Cycle $cycle autonomous Camera/Face recovery old=$oldProcessId new=$($recovery.NewPid)"
    }

    $killEvents=Get-CameraFaceRecoveryTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'kill-events.jsonl'
    if([string]::IsNullOrWhiteSpace($killEvents)){throw 'kill-events.jsonl is missing.'}
    $killEvents | Set-Content -Path (Join-Path $OutputDirectory 'kill-events.jsonl') -Encoding UTF8

    [void](Invoke-Adb -Serial $resolvedSerial -Arguments @('logcat','-d','-v','threadtime') -LogPath (Join-Path $OutputDirectory 'logcat.txt'))
    $fatalCount=Find-AppFatalEvents -LogPath (Join-Path $OutputDirectory 'logcat.txt') -PackageName $PackageName -OutputPath (Join-Path $OutputDirectory 'fatal-events.txt')
    if($fatalCount -gt 0){throw "Unexpected fatal events detected: $fatalCount"}

    $exitCode=0
} catch {
    $exitCode=1
    $message=$_.Exception.Message
    [void]$failures.Add($message)
    Write-Host "[FAIL] $message" -ForegroundColor Red
} finally {
    if($testInstalled){
        try { [void](Invoke-CameraFaceRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action CLEANUP) } catch {}
    }
    if($testInstalled -and $null -ne $backup){
        try {
            $testPidBeforeRestore=Get-CameraFaceRecoveryPid -Serial $resolvedSerial -PackageName $PackageName
            if($testPidBeforeRestore -gt 0){
                Write-Host '[INFO] Terminating V2.7.3 test process before production restore.'
                try { [void](Invoke-CameraFaceRecoveryBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action TERMINATE -Cycle ([Math]::Max(1,$completedCycles))) } catch {}
                if(-not (Wait-CameraFaceRecoveryOldPidExit -Serial $resolvedSerial -PackageName $PackageName -OldPid $testPidBeforeRestore -TimeoutSeconds 20)){
                    $restoreFailure='V2.7.3 test process did not terminate before production restore'
                }
            }
            Write-Host '[INFO] Restoring original production APK(s).'
            $restore=Restore-InstalledPackageApks -Serial $resolvedSerial -ApkPaths $backup.ApkPaths -LogPath (Join-Path $OutputDirectory 'restore-production-app.log')
            if($restore -ne 0){$restoreFailure="restore failed exit=$restore"}
            else{
                # Active launch is permitted only for the final production restoration gate.
                $restoreStart=Invoke-Adb -Serial $resolvedSerial -Arguments @('shell','am','start','-W','-n',"$PackageName/.activity.KioskHomeActivity") -LogPath (Join-Path $OutputDirectory 'restore-kiosk.log')
                if($restoreStart -ne 0){$restoreFailure="production KioskHomeActivity start failed exit=$restoreStart"}
                else{
                    $productionRestoreState=Wait-CameraFaceRecoveryKioskReady -Serial $resolvedSerial -PackageName $PackageName -TimeoutSeconds 30
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

$restoreReady=($null -ne $productionRestoreState -and $productionRestoreState.Ready)
$overallStatus=if($exitCode -eq 0 -and -not $restoreFailure){'PASS'}else{'FAIL'}
@(
    'Punch App Camera/Face Kill Recovery V2.7.3',
    "cycles_requested=$Cycles",
    "cycles_completed=$completedCycles",
    'autonomous_recovery=true',
    'fixture_employee=RECOVERY_FACE_V273',
    'database=punch_camera_face_recovery_v273.db',
    'network=http://127.0.0.1:1',
    "fatal_events=$fatalCount",
    'production_backend_requests=0',
    'production_database_touched=false',
    "production_restore_ready=$restoreReady",
    "restore_failure=$restoreFailure",
    "overall_status=$overallStatus"
) | Set-Content -Path (Join-Path $OutputDirectory 'summary.txt') -Encoding UTF8
@(
    "temporary_build_installed=$testInstalled",
    "completed_cycles=$completedCycles",
    "cycle_evidence=$($cycleEvidence -join ' | ')",
    "failures=$($failures -join ' | ')",
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
