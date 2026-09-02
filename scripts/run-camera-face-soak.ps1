[CmdletBinding()]
param(
    [string]$Serial,
    [string]$PackageName = "com.punch.app",
    [ValidateRange(2, 10080)][int]$Minutes = 480,
    [ValidateRange(10, 3600)][int]$IntervalSeconds = 60,
    [ValidateRange(0, 1440)][int]$WarmupMinutes = 15,
    [ValidateRange(1, 20)][int]$StableWindowSize = 5,
    [double]$MaxPssGrowthPercent = 5.0,
    [double]$MaxNativeHeapGrowthPercent = 5.0,
    [double]$MaxJavaHeapGrowthPercent = 20.0,
    [ValidateRange(0, 1000)][int]$MaxThreadGrowth = 10,
    [ValidateRange(0, 10000)][int]$MaxFdGrowth = 20,
    [ValidateRange(3, 120)][int]$ReadyTimeoutSeconds = 20,
    [switch]$RequireFaceSdkProbe,
    [switch]$UseInstalledProductionApp,
    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "lib\DeviceTestCommon.ps1")

$projectRoot = Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
Assert-CommandAvailable -Name "adb"
$resolvedSerial = Resolve-AndroidSerial -RequestedSerial $Serial
if (-not $OutputDirectory) {
    $OutputDirectory = New-TestReportDirectory -ProjectRoot $projectRoot -Prefix "camera-face-soak"
}
else {
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $OutputDirectory = (Resolve-Path $OutputDirectory).Path
}

$coreScript = Join-Path $PSScriptRoot "run-camera-face-soak-core.ps1"
$productionBackup = $null
$soakBuildInstalled = $false
$restoreFailure = $null
$coreExit = 1

function Invoke-CameraFaceCoreProcess {
    $powershellExe = (Get-Command "powershell.exe" -ErrorAction Stop).Source
    $args = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $coreScript,
        "-Serial", $resolvedSerial,
        "-PackageName", $PackageName,
        "-Minutes", $Minutes.ToString(),
        "-IntervalSeconds", $IntervalSeconds.ToString(),
        "-WarmupMinutes", $WarmupMinutes.ToString(),
        "-StableWindowSize", $StableWindowSize.ToString(),
        "-MaxPssGrowthPercent", $MaxPssGrowthPercent.ToString([Globalization.CultureInfo]::InvariantCulture),
        "-MaxNativeHeapGrowthPercent", $MaxNativeHeapGrowthPercent.ToString([Globalization.CultureInfo]::InvariantCulture),
        "-MaxJavaHeapGrowthPercent", $MaxJavaHeapGrowthPercent.ToString([Globalization.CultureInfo]::InvariantCulture),
        "-MaxThreadGrowth", $MaxThreadGrowth.ToString(),
        "-MaxFdGrowth", $MaxFdGrowth.ToString(),
        "-ReadyTimeoutSeconds", $ReadyTimeoutSeconds.ToString(),
        "-OutputDirectory", $OutputDirectory
    )
    if ($RequireFaceSdkProbe) { $args += "-RequireFaceSdkProbe" }
    # The controlled build starts fresh on PunchFragment and auto-enables punch.
    # Keeping the core's default AutoOpenPunchScreen=true is harmless and preserves
    # black-box navigation evidence; Enable-PunchIfNeeded becomes already_enabled.
    # IMPORTANT: Do not invoke powershell.exe directly here while this function is
    # assigned to $coreExit. In Windows PowerShell, every child stdout line becomes
    # part of the function output stream and turns $coreExit into an Object[].
    $coreLog = Join-Path $OutputDirectory "camera-face-soak-core.log"
    return Invoke-LoggedCommand `
        -FilePath $powershellExe `
        -Arguments $args `
        -LogPath $coreLog `
        -WorkingDirectory $projectRoot
}

try {
    if ($UseInstalledProductionApp) {
        Write-Host "[WARN] Using the currently installed production APK. UIAutomation remains the control path." -ForegroundColor Yellow
        $coreExit = Invoke-CameraFaceCoreProcess
    }
    else {
        Write-Host "========================================"
        Write-Host " Punch App Camera + Face SDK Soak V2.2.8"
        Write-Host " Deterministic cameraFaceSoak build"
        Write-Host "========================================"
        Write-Host "[INFO] Backing up installed production APK(s)."
        $backupDirectory = Join-Path $OutputDirectory "production-backup"
        $productionBackup = Backup-InstalledPackageApks `
            -Serial $resolvedSerial `
            -PackageName $PackageName `
            -BackupDirectory $backupDirectory

        Assert-LocalBuildEnvironment -ProjectRoot $projectRoot
        $buildLog = Join-Path $OutputDirectory "camera-face-soak-build.log"
        $buildExit = Invoke-LoggedCommand `
            -FilePath (Join-Path $projectRoot "gradlew.bat") `
            -Arguments @(":app:assembleCameraFaceSoak", "--stacktrace") `
            -LogPath $buildLog `
            -WorkingDirectory $projectRoot
        if ($buildExit -ne 0) {
            throw "cameraFaceSoak APK build failed with exit code $buildExit. See $buildLog"
        }

        $apkDirectory = Join-Path $projectRoot "app\build\outputs\apk\cameraFaceSoak"
        $soakApk = Get-ChildItem -Path $apkDirectory -Filter "*.apk" -File -ErrorAction Stop |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if (-not $soakApk) {
            throw "app-cameraFaceSoak.apk was not found under $apkDirectory after build."
        }
        if ($soakApk.Name -ne "app-cameraFaceSoak.apk") {
            Write-Host "[INFO] cameraFaceSoak APK resolved as: $($soakApk.Name)"
        }

        Write-Host "[INFO] Installing temporary cameraFaceSoak build over $PackageName."
        $installExit = Invoke-Adb -Serial $resolvedSerial `
            -Arguments @("install", "-r", "-d", $soakApk.FullName) `
            -LogPath (Join-Path $OutputDirectory "install-camera-face-soak.log")
        if ($installExit -ne 0) {
            throw "Unable to install cameraFaceSoak build (exit $installExit). It must use the same signing certificate as the installed Device Owner APK."
        }
        $soakBuildInstalled = $true

        Write-Host "[INFO] Starting cameraFaceSoak through the exported launcher."
        [void](Start-AndroidLauncherPackage `
            -Serial $resolvedSerial `
            -PackageName $PackageName `
            -OutputPath (Join-Path $OutputDirectory "start-camera-face-soak.log"))
        Start-Sleep -Seconds 2
        $coreExit = Invoke-CameraFaceCoreProcess
    }
}
catch {
    $coreExit = 1
    $message = $_.Exception.Message
    Write-Host "[FAIL] $message" -ForegroundColor Red
    @(
        "Punch App Camera + Face SDK Soak V2.2.8",
        "status=FAIL",
        "reason=$message"
    ) | Set-Content -Path (Join-Path $OutputDirectory "wrapper-summary.txt") -Encoding UTF8
}
finally {
    if ($soakBuildInstalled -and $null -ne $productionBackup) {
        try {
            Write-Host "[INFO] Restoring original production APK(s)."
            $restoreExit = Restore-InstalledPackageApks `
                -Serial $resolvedSerial `
                -ApkPaths $productionBackup.ApkPaths `
                -LogPath (Join-Path $OutputDirectory "restore-production-app.log")
            if ($restoreExit -ne 0) {
                $restoreFailure = "Restoring original production APK(s) failed with exit code $restoreExit. Backup remains in production-backup."
            }
            else {
                [void](Invoke-Adb -Serial $resolvedSerial -Arguments @("shell", "am", "start", "-W", "-n", "$PackageName/.activity.KioskHomeActivity") -LogPath (Join-Path $OutputDirectory "restore-kiosk.log"))
            }
        }
        catch {
            $restoreFailure = "Restoring original production APK(s) failed: $($_.Exception.Message). Backup remains in production-backup."
        }
    }
}

@(
    "mode=$(if ($UseInstalledProductionApp) { 'installed-production' } else { 'cameraFaceSoak-transactional' })",
    "core_exit=$coreExit",
    "temporary_build_installed=$soakBuildInstalled",
    "restore_failure=$restoreFailure"
) | Set-Content -Path (Join-Path $OutputDirectory "transaction.txt") -Encoding UTF8

if (-not [string]::IsNullOrWhiteSpace($restoreFailure)) {
    Write-Host "[FAIL] $restoreFailure" -ForegroundColor Red
    exit 1
}
exit $coreExit
