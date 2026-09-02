[CmdletBinding()]
param(
    [string]$Serial,
    [string]$PackageName = "com.punch.app",
    [ValidateRange(1, 10080)][int]$Minutes = 30,
    [ValidateRange(5, 3600)][int]$IntervalSeconds = 60,
    [ValidateRange(0, 1440)][int]$WarmupMinutes = 5,
    [ValidateRange(1, 20)][int]$StableWindowSize = 3,
    [double]$MaxPssGrowthPercent = 10.0,
    [double]$MaxNativeHeapGrowthPercent = 10.0,
    [double]$MaxJavaHeapGrowthPercent = 20.0,
    [ValidateRange(0, 1000)][int]$MaxThreadGrowth = 10,
    [ValidateRange(0, 10000)][int]$MaxFdGrowth = 20,
    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\DeviceTestCommon.ps1")
. (Join-Path $PSScriptRoot "lib\PerformanceMetrics.ps1")

$projectRoot = Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
Assert-CommandAvailable -Name "adb"
$resolvedSerial = Resolve-AndroidSerial -RequestedSerial $Serial
if (-not $OutputDirectory) {
    $OutputDirectory = New-TestReportDirectory -ProjectRoot $projectRoot -Prefix "memory-soak"
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
Write-Host " Punch App Memory Soak V2.1.1"
Write-Host "========================================"
Write-Host "Serial:   $resolvedSerial"
Write-Host "Package:  $PackageName"
Write-Host "Duration: $Minutes minute(s)"
Write-Host "Interval: $IntervalSeconds second(s)"
Write-Host "Warm-up:  $effectiveWarmupMinutes minute(s)"

$initialPidText = Get-AdbOutput -Serial $resolvedSerial -Arguments @("shell", "pidof", $PackageName) -IgnoreFailure
if ([string]::IsNullOrWhiteSpace($initialPidText)) {
    @("Punch App Performance Harness V2.1", "status=FAIL", "reason=Package process is not running: $PackageName") | Set-Content -Path (Join-Path $OutputDirectory "summary.txt") -Encoding UTF8
    Write-Host "[FAIL] Package process is not running: $PackageName" -ForegroundColor Red
    Write-Host "Report: $OutputDirectory"
    exit 1
}
$initialPid = (($initialPidText.Trim() -split '\s+')[0])

[void](Invoke-Adb -Serial $resolvedSerial -Arguments @("logcat", "-c") -LogPath (Join-Path $OutputDirectory "logcat-clear.log"))

$samples = New-Object System.Collections.Generic.List[object]
$start = Get-Date
$durationSeconds = [double]$Minutes * 60.0
$index = 0
$nextTargetSeconds = 0.0

while ($true) {
    $elapsed = ((Get-Date) - $start).TotalSeconds
    if ($index -gt 0 -and $elapsed -gt ($durationSeconds + [Math]::Min($IntervalSeconds, 5))) { break }

    $sample = Get-AppPerformanceSample `
        -Serial $resolvedSerial `
        -PackageName $PackageName `
        -Index $index `
        -ElapsedSeconds $elapsed `
        -RawRoot $rawRoot
    [void]$samples.Add($sample)

    $aliveText = if ($sample.ProcessAlive) { "alive" } else { "MISSING" }
    Write-Host ("[{0:HH:mm:ss}] #{1} {2} pid={3} PSS={4}MB Java={5}MB Native={6}MB RSS={7}MB CPU={8}% T={9} FD={10}" -f `
        (Get-Date), $index, $aliveText, $sample.Pid, `
        (Convert-PerformanceValueToText $sample.TotalPssMb), `
        (Convert-PerformanceValueToText $sample.JavaHeapMb), `
        (Convert-PerformanceValueToText $sample.NativeHeapMb), `
        (Convert-PerformanceValueToText $sample.RssMb), `
        (Convert-PerformanceValueToText $sample.CpuPercent), `
        (Convert-PerformanceValueToText $sample.ThreadCount), `
        (Convert-PerformanceValueToText $sample.FdCount))

    $index += 1
    $nextTargetSeconds = [double]$index * [double]$IntervalSeconds
    if ($nextTargetSeconds -gt $durationSeconds) { break }
    $remaining = $nextTargetSeconds - ((Get-Date) - $start).TotalSeconds
    if ($remaining -gt 0) {
        Start-Sleep -Milliseconds ([int][Math]::Round($remaining * 1000.0))
    }
}

$actualMinutes = ((Get-Date) - $start).TotalMinutes
$metricsPath = Join-Path $OutputDirectory "metrics.csv"
# Windows PowerShell 5.1 can throw "Argument types do not match" when @()
# wraps System.Collections.Generic.List[object]. Convert explicitly instead.
$sampleArray = $samples.ToArray()
$sampleArray | Export-Csv -Path $metricsPath -NoTypeInformation -Encoding UTF8

$logcatPath = Join-Path $OutputDirectory "logcat.txt"
Save-AdbOutputQuietly -Serial $resolvedSerial -Arguments @("logcat", "-d", "-v", "threadtime") -OutputPath $logcatPath
$fatalPath = Join-Path $OutputDirectory "fatal-events.txt"
$fatalEventCount = Find-AppFatalEvents -LogPath $logcatPath -PackageName $PackageName -OutputPath $fatalPath
$gcEventCount = Get-BestEffortGcEventCount -LogPath $logcatPath -AppProcessId $initialPid

$analysis = Get-MemorySoakAnalysis `
    -Samples $sampleArray `
    -WarmupMinutes $effectiveWarmupMinutes `
    -StableWindowSize $StableWindowSize `
    -MaxPssGrowthPercent $MaxPssGrowthPercent `
    -MaxNativeHeapGrowthPercent $MaxNativeHeapGrowthPercent `
    -MaxJavaHeapGrowthPercent $MaxJavaHeapGrowthPercent `
    -MaxThreadGrowth $MaxThreadGrowth `
    -MaxFdGrowth $MaxFdGrowth `
    -FatalEventCount $fatalEventCount `
    -GcEventCount $gcEventCount

$summaryPath = Join-Path $OutputDirectory "summary.txt"
Write-PerformanceSummary `
    -Analysis $analysis `
    -Serial $resolvedSerial `
    -PackageName $PackageName `
    -RequestedMinutes $Minutes `
    -ActualMinutes $actualMinutes `
    -OutputPath $summaryPath
Write-PerformanceHtmlReport `
    -Samples $sampleArray `
    -Analysis $analysis `
    -Serial $resolvedSerial `
    -PackageName $PackageName `
    -OutputPath (Join-Path $OutputDirectory "report.html")

Collect-DeviceSnapshot -Serial $resolvedSerial -PackageName $PackageName -OutputDirectory $OutputDirectory -Prefix "final"

Write-Host "----------------------------------------"
Write-Host "PSS growth:    $(Convert-PerformanceValueToText $analysis.PssGrowthPercent)%  slope=$(Convert-PerformanceValueToText $analysis.PssSlopeMbPerHour) MB/h"
Write-Host "Java growth:   $(Convert-PerformanceValueToText $analysis.JavaHeapGrowthPercent)%"
Write-Host "Native growth: $(Convert-PerformanceValueToText $analysis.NativeHeapGrowthPercent)%  slope=$(Convert-PerformanceValueToText $analysis.NativeHeapSlopeMbPerHour) MB/h"
Write-Host "Threads delta: $(Convert-PerformanceValueToText $analysis.ThreadGrowth)"
Write-Host "FD delta:      $(Convert-PerformanceValueToText $analysis.FdGrowth)"
Write-Host "CPU p95:       $(Convert-PerformanceValueToText $analysis.CpuP95Percent)%"
Write-Host "Fatal events:  $($analysis.FatalEventCount)"
Write-Host "Report: $OutputDirectory"

if ($analysis.Status -eq "PASS") {
    Write-Host "RESULT: PASS" -ForegroundColor Green
    exit 0
}

Write-Host "RESULT: FAIL" -ForegroundColor Red
foreach ($reason in $analysis.FailureReasons) { Write-Host " - $reason" -ForegroundColor Red }
exit 1
