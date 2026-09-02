[CmdletBinding()]
param(
    [string]$Serial,
    [string]$ReportDirectory,
    [string]$ProductionPackageName = "com.punch.app"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\DeviceTestCommon.ps1")

$projectRoot = Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
Assert-CommandAvailable -Name "adb"
$resolvedSerial = Resolve-AndroidSerial -RequestedSerial $Serial

function Wait-ProductionKioskRestoreReady {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$ProductionPackageName,
        [int]$TimeoutSeconds = 20
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $last = $null
    do {
        $last = Get-SmokeDeviceConflict -Serial $Serial -ProductionPackageName $ProductionPackageName
        if ($last.IsDeviceOwner -and $last.IsProductionForeground -and $last.IsLockTaskActive) {
            return $last
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $last
}

if (-not $ReportDirectory) {
    $testResults = Join-Path $projectRoot "test-results"
    $candidate = Get-ChildItem -Path $testResults -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Where-Object { Test-Path (Join-Path $_.FullName "production-backup") } |
        Select-Object -First 1
    if (-not $candidate) {
        throw "No test report containing production-backup was found under $testResults."
    }
    $ReportDirectory = $candidate.FullName
}
else {
    $ReportDirectory = (Resolve-Path $ReportDirectory).Path
}

$backupDirectory = Join-Path $ReportDirectory "production-backup"
$apkPaths = @(Get-ChildItem -Path $backupDirectory -Filter "*.apk" -File -ErrorAction Stop |
    Sort-Object Name |
    ForEach-Object { $_.FullName })
if ($apkPaths.Count -eq 0) {
    throw "No APK files were found in $backupDirectory."
}

Write-Host "[INFO] Restoring $ProductionPackageName from: $backupDirectory"
[void](Invoke-Adb -Serial $resolvedSerial -Arguments @("shell", "setprop", "debug.punch.device_test_maintenance", "0"))
[void](Invoke-Adb -Serial $resolvedSerial -Arguments @("shell", "setprop", "debug.punch.ui_smoke", "0"))

$restoreExit = Restore-InstalledPackageApks `
    -Serial $resolvedSerial `
    -ApkPaths $apkPaths `
    -LogPath (Join-Path $ReportDirectory "manual-restore-production-apks.log")
if ($restoreExit -ne 0) {
    throw "Production APK restoration failed with exit code $restoreExit. See manual-restore-production-apks.log"
}

$component = "$ProductionPackageName/.activity.KioskHomeActivity"
$kioskExit = Invoke-Adb -Serial $resolvedSerial `
    -Arguments @("shell", "am", "start", "-n", $component) `
    -LogPath (Join-Path $ReportDirectory "manual-restore-kiosk.log")
if ($kioskExit -ne 0) {
    throw "Production APK restored, but KioskHomeActivity could not be started (exit $kioskExit)."
}

$finalKioskState = Wait-ProductionKioskRestoreReady `
    -Serial $resolvedSerial `
    -ProductionPackageName $ProductionPackageName `
    -TimeoutSeconds 20

if ($null -ne $finalKioskState -and $finalKioskState.IsDeviceOwner -and $finalKioskState.IsProductionForeground -and -not $finalKioskState.IsLockTaskActive) {
    Write-Host "[WARN] Production app is foreground but LockTask is inactive; forcing a fresh kiosk target." -ForegroundColor Yellow
    $retryExit = Invoke-Adb -Serial $resolvedSerial `
        -Arguments @("shell", "am", "start", "-W", "-n", $component, "--ez", "force_fresh_target", "true") `
        -LogPath (Join-Path $ReportDirectory "manual-restore-kiosk-retry.log")
    if ($retryExit -eq 0) {
        $finalKioskState = Wait-ProductionKioskRestoreReady `
            -Serial $resolvedSerial `
            -ProductionPackageName $ProductionPackageName `
            -TimeoutSeconds 30
    }
}

$productionPidText = Get-AdbOutput -Serial $resolvedSerial -Arguments @("shell", "pidof", $ProductionPackageName) -IgnoreFailure
if ([string]::IsNullOrWhiteSpace($productionPidText)) {
    throw "Production APK restored, but $ProductionPackageName is not running."
}
if ($null -eq $finalKioskState -or -not $finalKioskState.IsDeviceOwner) {
    throw "Production APK restored, but Device Owner state is not active."
}
if (-not $finalKioskState.IsProductionForeground) {
    throw "Production APK restored, but the production app is not the resumed foreground activity."
}
if (-not $finalKioskState.IsLockTaskActive) {
    throw "Production APK restored, but LockTask/Kiosk is not active."
}

[void](Get-SmokeDeviceConflict `
    -Serial $resolvedSerial `
    -ProductionPackageName $ProductionPackageName `
    -ReportDirectory (Join-Path $ReportDirectory "manual-restore-final-state"))

Write-Host "[PASS] Production Device Owner APK/Kiosk restored." -ForegroundColor Green
Write-Host "[INFO] Report: $ReportDirectory"
