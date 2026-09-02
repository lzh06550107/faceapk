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
    [bool]$AutoOpenPunchScreen = $true,
    [switch]$RequireFaceSdkProbe,
    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\DeviceTestCommon.ps1")
. (Join-Path $PSScriptRoot "lib\PerformanceMetrics.ps1")
. (Join-Path $PSScriptRoot "lib\CameraFaceMetrics.ps1")

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

$rawRoot = Join-Path $OutputDirectory "raw-samples"
New-Item -ItemType Directory -Path $rawRoot -Force | Out-Null
Write-DeviceInfo -Serial $resolvedSerial -OutputPath (Join-Path $OutputDirectory "device-info.txt")
Save-AdbOutputQuietly -Serial $resolvedSerial -Arguments @("shell", "dumpsys", "package", $PackageName) -OutputPath (Join-Path $OutputDirectory "package-info.txt")

$effectiveWarmupMinutes = $WarmupMinutes
if ($effectiveWarmupMinutes -ge $Minutes) {
    $effectiveWarmupMinutes = [int][Math]::Floor($Minutes / 4.0)
    Write-Host "[WARN] WarmupMinutes adjusted to $effectiveWarmupMinutes because requested warm-up was not shorter than the soak."
}

Write-Host "========================================"
Write-Host " Punch App Camera + Face SDK Soak V2.2.8"
Write-Host "========================================"
Write-Host "Serial:   $resolvedSerial"
Write-Host "Package:  $PackageName"
Write-Host "Duration: $Minutes minute(s)"
Write-Host "Interval: $IntervalSeconds second(s)"
Write-Host "Warm-up:  $effectiveWarmupMinutes minute(s)"
Write-Host "Auto-open punch screen: $AutoOpenPunchScreen"

$initialPidText = Get-AdbOutput -Serial $resolvedSerial -Arguments @("shell", "pidof", $PackageName) -IgnoreFailure
if ([string]::IsNullOrWhiteSpace($initialPidText)) {
    Write-Host "[INFO] App process is not running; launching the exported app launcher." 
    [void](Start-AndroidLauncherPackage -Serial $resolvedSerial -PackageName $PackageName -IgnoreFailure)
    Start-Sleep -Seconds 2
    $initialPidText = Get-AdbOutput -Serial $resolvedSerial -Arguments @("shell", "pidof", $PackageName) -IgnoreFailure
}
if ([string]::IsNullOrWhiteSpace($initialPidText)) {
    @("Punch App Camera + Face SDK Soak V2.2.8", "status=FAIL", "reason=Package process is not running: $PackageName") | Set-Content -Path (Join-Path $OutputDirectory "summary.txt") -Encoding UTF8
    Write-Host "[FAIL] Package process is not running: $PackageName" -ForegroundColor Red
    Write-Host "Report: $OutputDirectory"
    exit 1
}
$initialPid = (($initialPidText.Trim() -split '\s+')[0])

$preflightDirectory = Join-Path $OutputDirectory "preflight"
if ($AutoOpenPunchScreen) {
    [void](Open-PunchScreen -Serial $resolvedSerial -PackageName $PackageName -EvidenceDirectory $preflightDirectory)
}
$ready = Wait-CameraFaceReady -Serial $resolvedSerial -PackageName $PackageName -TimeoutSeconds $ReadyTimeoutSeconds -EvidenceDirectory $preflightDirectory
if ($null -eq $ready) {
    Save-AdbOutputQuietly -Serial $resolvedSerial -Arguments @("logcat", "-d", "-v", "threadtime") -OutputPath (Join-Path $preflightDirectory "logcat.txt")
    @(
        "Punch App Camera + Face SDK Soak V2.2.8",
        "status=FAIL",
        "reason=Camera/Face preflight did not become ready within $ReadyTimeoutSeconds seconds.",
        "evidence=preflight\\ready.txt"
    ) | Set-Content -Path (Join-Path $OutputDirectory "summary.txt") -Encoding UTF8
    Write-Host "[FAIL] Camera/Face preflight did not become ready. See preflight\ready.txt and preflight\probes." -ForegroundColor Red
    Write-Host "Report: $OutputDirectory"
    exit 1
}
[void](Invoke-Adb -Serial $resolvedSerial -Arguments @("logcat", "-c") -LogPath (Join-Path $OutputDirectory "logcat-clear.log"))

$samples = New-Object System.Collections.Generic.List[object]
$start = Get-Date
$durationSeconds = [double]$Minutes * 60.0
$index = 0
while ($true) {
    $elapsed = ((Get-Date) - $start).TotalSeconds
    if ($index -gt 0 -and $elapsed -gt ($durationSeconds + [Math]::Min($IntervalSeconds, 5))) { break }

    $sample = Get-AppPerformanceSample -Serial $resolvedSerial -PackageName $PackageName -Index $index -ElapsedSeconds $elapsed -RawRoot $rawRoot
    $health = Get-CameraFaceHealthSample -Serial $resolvedSerial -PackageName $PackageName -PerformanceSample $sample -RawRoot $rawRoot
    $sample = Add-CameraFaceHealthToSample -PerformanceSample $sample -HealthSample $health
    [void]$samples.Add($sample)

    $faceText = if (-not $sample.FaceSdkProbeSupported) { "probe:N/A" } elseif ($sample.FaceSdkLoaded) { "loaded" } else { "MISSING" }
    Write-Host ("[{0:HH:mm:ss}] #{1} pid={2} PSS={3}MB Java={4}MB Native={5}MB CPU={6}% Thr={7} FD={8} Main={9} Camera={10} Face={11}" -f `
        (Get-Date), $index, $sample.Pid, `
        (Convert-PerformanceValueToText $sample.TotalPssMb), `
        (Convert-PerformanceValueToText $sample.JavaHeapMb), `
        (Convert-PerformanceValueToText $sample.NativeHeapMb), `
        (Convert-PerformanceValueToText $sample.CpuPercent), `
        (Convert-PerformanceValueToText $sample.ThreadCount), `
        (Convert-PerformanceValueToText $sample.FdCount), `
        $sample.MainActivityForeground, $sample.CameraActive, $faceText)

    $index += 1
    $nextTargetSeconds = [double]$index * [double]$IntervalSeconds
    if ($nextTargetSeconds -gt $durationSeconds) { break }
    $remaining = $nextTargetSeconds - ((Get-Date) - $start).TotalSeconds
    if ($remaining -gt 0) { Start-Sleep -Milliseconds ([int][Math]::Round($remaining * 1000.0)) }
}

$actualMinutes = ((Get-Date) - $start).TotalMinutes
$sampleArray = $samples.ToArray()
$sampleArray | Export-Csv -Path (Join-Path $OutputDirectory "metrics.csv") -NoTypeInformation -Encoding UTF8

$logcatPath = Join-Path $OutputDirectory "logcat.txt"
Save-AdbOutputQuietly -Serial $resolvedSerial -Arguments @("logcat", "-d", "-v", "threadtime") -OutputPath $logcatPath
$fatalEventCount = Find-AppFatalEvents -LogPath $logcatPath -PackageName $PackageName -OutputPath (Join-Path $OutputDirectory "fatal-events.txt")
$gcEventCount = Get-BestEffortGcEventCount -LogPath $logcatPath -AppProcessId $initialPid
$cameraFaceErrorCount = Find-CameraFaceHealthEvents -LogPath $logcatPath -OutputPath (Join-Path $OutputDirectory "camera-face-events.txt")

$analysis = Get-CameraFaceSoakAnalysis `
    -Samples $sampleArray `
    -WarmupMinutes $effectiveWarmupMinutes `
    -StableWindowSize $StableWindowSize `
    -MaxPssGrowthPercent $MaxPssGrowthPercent `
    -MaxNativeHeapGrowthPercent $MaxNativeHeapGrowthPercent `
    -MaxJavaHeapGrowthPercent $MaxJavaHeapGrowthPercent `
    -MaxThreadGrowth $MaxThreadGrowth `
    -MaxFdGrowth $MaxFdGrowth `
    -FatalEventCount $fatalEventCount `
    -GcEventCount $gcEventCount `
    -CameraFaceErrorEventCount $cameraFaceErrorCount `
    -RequireFaceSdkProbe:$RequireFaceSdkProbe

Write-CameraFaceSummary -Analysis $analysis -Serial $resolvedSerial -PackageName $PackageName -RequestedMinutes $Minutes -ActualMinutes $actualMinutes -OutputPath (Join-Path $OutputDirectory "summary.txt")
Write-CameraFaceHtmlReport -Samples $sampleArray -Analysis $analysis -Serial $resolvedSerial -PackageName $PackageName -OutputPath (Join-Path $OutputDirectory "report.html")

Save-AdbOutputQuietly -Serial $resolvedSerial -Arguments @("shell", "dumpsys", "media.camera") -OutputPath (Join-Path $OutputDirectory "final-camera.txt")
Save-AdbOutputQuietly -Serial $resolvedSerial -Arguments @("shell", "dumpsys", "activity", "activities") -OutputPath (Join-Path $OutputDirectory "final-activity.txt")
Save-AdbOutputQuietly -Serial $resolvedSerial -Arguments @("shell", "dumpsys", "meminfo", $PackageName) -OutputPath (Join-Path $OutputDirectory "final-meminfo.txt")

Write-Host "----------------------------------------"
Write-Host "Camera inactive stable samples: $($analysis.CameraInactiveStableSampleCount)"
Write-Host "Foreground missing stable samples: $($analysis.ForegroundMissingStableSampleCount)"
Write-Host "Face runtime probe supported: $($analysis.FaceSdkProbeSupported)"
Write-Host "Face runtime not initialized stable samples: $($analysis.FaceSdkMissingStableSampleCount)"
Write-Host "PSS growth:    $(Convert-PerformanceValueToText $analysis.PssGrowthPercent)%"
Write-Host "Native growth: $(Convert-PerformanceValueToText $analysis.NativeHeapGrowthPercent)%"
Write-Host "Native slope:  $(Convert-PerformanceValueToText $analysis.NativeHeapSlopeMbPerHour) MB/h"
Write-Host "CPU p95:       $(Convert-PerformanceValueToText $analysis.CpuP95Percent)%"
Write-Host "Health errors: $($analysis.CameraFaceErrorEventCount)"
Write-Host "Report: $OutputDirectory"

if ($analysis.Status -eq "PASS") {
    Write-Host "RESULT: PASS" -ForegroundColor Green
    exit 0
}
Write-Host "RESULT: FAIL" -ForegroundColor Red
foreach ($reason in $analysis.FailureReasons) { Write-Host " - $reason" -ForegroundColor Red }
exit 1
