[CmdletBinding()]
param(
    [string]$Serial,
    [string]$PackageName='com.punch.app',
    [ValidateSet('AllSafe','DisconnectRecovery','TimeoutRecovery','Http500Retry','Http401Recovery','RetryLimitManualRecovery','DeviceOfflineRecovery')]
    [string]$Scenario='AllSafe',
    [ValidateRange(30,600)][int]$TimeoutSeconds=180,
    [string]$OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib\DeviceTestCommon.ps1')
. (Join-Path $PSScriptRoot 'lib\NetworkFaultSync.ps1')

$projectRoot=Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
Assert-CommandAvailable -Name 'adb'
$resolvedSerial=Resolve-AndroidSerial -RequestedSerial $Serial
if(-not $OutputDirectory){$OutputDirectory=New-TestReportDirectory -ProjectRoot $projectRoot -Prefix 'network-fault-sync'}
else{New-Item -ItemType Directory -Path $OutputDirectory -Force|Out-Null;$OutputDirectory=(Resolve-Path $OutputDirectory).Path}

$backup=$null
$testInstalled=$false
$restoreFailure=$null
$networkState=$null
$networkRestored=$false
$exitCode=1
$fatalCount=0
$resultObj=$null

try {
    Write-Host '========================================'
    Write-Host ' Punch App Network Fault Sync V2.5.1'
    Write-Host '========================================'
    Write-Host "Serial: $resolvedSerial  Scenario: $Scenario"
    Write-Host '[INFO] Backing up installed production APK(s).'
    $backup=Backup-InstalledPackageApks -Serial $resolvedSerial -PackageName $PackageName -BackupDirectory (Join-Path $OutputDirectory 'production-backup')

    Assert-LocalBuildEnvironment -ProjectRoot $projectRoot
    $buildLog=Join-Path $OutputDirectory 'network-fault-test-build.log'
    $buildExit=Invoke-LoggedCommand -FilePath (Join-Path $projectRoot 'gradlew.bat') -Arguments @(':app:assembleNetworkFaultTest','--stacktrace') -LogPath $buildLog -WorkingDirectory $projectRoot
    if($buildExit -ne 0){throw "networkFaultTest build failed with exit code $buildExit"}
    $apkDir=Join-Path $projectRoot 'app\build\outputs\apk\networkFaultTest'
    $apk=Get-ChildItem $apkDir -Filter '*.apk' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if(-not $apk){throw "networkFaultTest APK not found under $apkDir"}

    Write-Host '[INFO] Installing temporary networkFaultTest build.'
    $install=Invoke-Adb -Serial $resolvedSerial -Arguments @('install','-r','-d',$apk.FullName) -LogPath (Join-Path $OutputDirectory 'install-network-fault-test.log')
    if($install -ne 0){throw "networkFaultTest install failed with exit code $install"}
    $testInstalled=$true
    Start-Sleep -Seconds 1

    Write-Host '[INFO] Bringing the V2.5 test host activity to the foreground.'
    $hostStart=Invoke-Adb -Serial $resolvedSerial -Arguments @('shell','am','start','-W','-n',"$PackageName/.network.NetworkFaultTestHostActivity") -LogPath (Join-Path $OutputDirectory 'start-network-fault-host.log')
    if($hostStart -ne 0){throw "NetworkFaultTestHostActivity start failed with exit code $hostStart"}
    Start-Sleep -Seconds 1

    $preflight=Get-NetworkFaultStatus -Serial $resolvedSerial -PackageName $PackageName
    $preflight.Raw | Set-Content -Path (Join-Path $OutputDirectory 'preflight-status.txt') -Encoding UTF8
    if(-not $preflight.Supported){throw 'NetworkFaultTestReceiver is not reachable.'}
    if(-not $preflight.TokenValid){throw 'Production session token is missing or expired; V2.5 cannot exercise the real SyncService.'}
    if(-not $preflight.DeviceRegistered){throw 'Device is not registered; V2.5 production SyncCoordinator would skip synchronization.'}
    [void](Get-AdbOutput -Serial $resolvedSerial -Arguments @('logcat','-c') -IgnoreFailure)

    if($Scenario -eq 'DeviceOfflineRecovery'){
        $networkState=Capture-DeviceNetworkState -Serial $resolvedSerial
        @("wifi_enabled=$($networkState.WifiEnabled)","mobile_data_enabled=$($networkState.MobileDataEnabled)") | Set-Content -Path (Join-Path $OutputDirectory 'network-state-before.txt') -Encoding UTF8

        Write-Host '[INFO] Preparing isolated offline punch queue.'
        (Invoke-NetworkFaultBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action PREPARE_OFFLINE) | Set-Content -Path (Join-Path $OutputDirectory 'prepare-offline-broadcast.txt') -Encoding UTF8
        $status=Wait-NetworkFaultState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('prepared','failed') -TimeoutSeconds $TimeoutSeconds
        if($status.State -eq 'failed'){throw "Offline prepare failed: $($status.Error)"}
        (Get-NetworkFaultTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'result.json') | Set-Content -Path (Join-Path $OutputDirectory 'device-offline-prepare.json') -Encoding UTF8

        Write-Host '[INFO] Disabling Wi-Fi and mobile data for the physical offline phase.'
        Disable-DeviceNetwork -Serial $resolvedSerial
        Start-Sleep -Seconds 2
        (Invoke-NetworkFaultBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action OFFLINE_SYNC) | Set-Content -Path (Join-Path $OutputDirectory 'offline-sync-broadcast.txt') -Encoding UTF8
        $status=Wait-NetworkFaultState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('offline_retained','failed') -TimeoutSeconds $TimeoutSeconds
        if($status.State -eq 'failed'){throw "Offline retention failed: $($status.Error)"}
        (Get-NetworkFaultTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'result.json') | Set-Content -Path (Join-Path $OutputDirectory 'device-offline-retained.json') -Encoding UTF8

        Write-Host '[INFO] Restoring original network state before recovery sync.'
        Restore-DeviceNetworkState -Serial $resolvedSerial -State $networkState
        $networkRestored=$true
        Start-Sleep -Seconds 3
        (Invoke-NetworkFaultBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action RECOVER_OFFLINE) | Set-Content -Path (Join-Path $OutputDirectory 'recover-offline-broadcast.txt') -Encoding UTF8
        $status=Wait-NetworkFaultState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('completed','failed') -TimeoutSeconds $TimeoutSeconds
        if($status.State -eq 'failed'){throw "Offline recovery failed: $($status.Error)"}
    } else {
        Write-Host "[INFO] Running isolated loopback fault suite: $Scenario"
        (Invoke-NetworkFaultBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action RUN -Scenario $Scenario) | Set-Content -Path (Join-Path $OutputDirectory 'run-broadcast.txt') -Encoding UTF8
        $status=Wait-NetworkFaultState -Serial $resolvedSerial -PackageName $PackageName -ExpectedStates @('completed','failed') -TimeoutSeconds $TimeoutSeconds
        if($status.State -eq 'failed'){throw "Network fault scenario failed: $($status.Error)"}
    }

    $resultText=Get-NetworkFaultTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'result.json'
    if([string]::IsNullOrWhiteSpace($resultText)){throw 'V2.5 result.json is empty or unavailable.'}
    $resultText | Set-Content -Path (Join-Path $OutputDirectory 'result.json') -Encoding UTF8
    $resultObj=$resultText | ConvertFrom-Json
    $requests=Get-NetworkFaultTestFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'requests.jsonl'
    $requests | Set-Content -Path (Join-Path $OutputDirectory 'requests.jsonl') -Encoding UTF8

    [void](Invoke-Adb -Serial $resolvedSerial -Arguments @('logcat','-d','-v','threadtime') -LogPath (Join-Path $OutputDirectory 'logcat.txt'))
    $fatalCount=Find-AppFatalEvents -LogPath (Join-Path $OutputDirectory 'logcat.txt') -PackageName $PackageName -OutputPath (Join-Path $OutputDirectory 'fatal-events.txt')
    $success=[bool]$resultObj.success -and $fatalCount -eq 0
    $http401Refresh=''
    if($Scenario -eq 'AllSafe' -and $null -ne $resultObj.results){
        $row=$resultObj.results | Where-Object {$_.scenario -eq 'Http401Recovery'} | Select-Object -First 1
        if($null -ne $row){$http401Refresh=$row.http_401_auto_refresh_observed}
    } elseif($Scenario -eq 'Http401Recovery'){$http401Refresh=$resultObj.http_401_auto_refresh_observed}
    $statusText=if($success){'PASS'}else{'FAIL'}
    @(
        'Punch App Network Fault Sync V2.5.1',
        "status=$statusText",
        "scenario=$Scenario",
        "result_success=$($resultObj.success)",
        "fatal_events=$fatalCount",
        "http_401_auto_refresh_observed=$http401Refresh",
        'production_database_touched=false',
        'http_target=loopback_or_reserved_test_network_only'
    ) | Set-Content -Path (Join-Path $OutputDirectory 'summary.txt') -Encoding UTF8
    Write-Host "Report: $OutputDirectory"
    Write-Host "RESULT: $statusText"
    $exitCode=if($success){0}else{1}

    [void](Invoke-NetworkFaultBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action CLEANUP)
} catch {
    $exitCode=1
    $message=$_.Exception.Message
    Write-Host "[FAIL] $message" -ForegroundColor Red
    @('Punch App Network Fault Sync V2.5.1','status=FAIL',"scenario=$Scenario","reason=$message") | Set-Content -Path (Join-Path $OutputDirectory 'wrapper-summary.txt') -Encoding UTF8
} finally {
    if($null -ne $networkState -and -not $networkRestored){
        try {
            Write-Host '[INFO] Restoring device network state after interruption/failure.'
            Restore-DeviceNetworkState -Serial $resolvedSerial -State $networkState
            $networkRestored=$true
        } catch {}
    }
    if($testInstalled){
        try { [void](Invoke-NetworkFaultBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action CLEANUP) } catch {}
    }
    if($testInstalled -and $null -ne $backup){
        try {
            Write-Host '[INFO] Restoring original production APK(s).'
            $restore=Restore-InstalledPackageApks -Serial $resolvedSerial -ApkPaths $backup.ApkPaths -LogPath (Join-Path $OutputDirectory 'restore-production-app.log')
            if($restore -ne 0){$restoreFailure="restore failed exit=$restore"}
            else{[void](Invoke-Adb -Serial $resolvedSerial -Arguments @('shell','am','start','-W','-n',"$PackageName/.activity.KioskHomeActivity") -LogPath (Join-Path $OutputDirectory 'restore-kiosk.log'))}
        } catch {$restoreFailure=$_.Exception.Message}
    }
}
@(
    "scenario=$Scenario",
    "temporary_build_installed=$testInstalled",
    "network_state_captured=$($null -ne $networkState)",
    "network_restored=$networkRestored",
    "restore_failure=$restoreFailure"
) | Set-Content -Path (Join-Path $OutputDirectory 'transaction.txt') -Encoding UTF8
if($restoreFailure){Write-Host "[FAIL] $restoreFailure" -ForegroundColor Red;exit 1}
exit $exitCode
