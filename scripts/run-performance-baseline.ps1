[CmdletBinding()]
param(
    [string]$Serial,
    [string]$PackageName = "com.punch.app",
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
    $OutputDirectory = New-TestReportDirectory -ProjectRoot $projectRoot -Prefix "performance-baseline"
}
else {
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $OutputDirectory = (Resolve-Path $OutputDirectory).Path
}

Write-Host "========================================"
Write-Host " Punch App Performance Baseline V2.1"
Write-Host "========================================"
Write-DeviceInfo -Serial $resolvedSerial -OutputPath (Join-Path $OutputDirectory "device-info.txt")
Save-AdbOutputQuietly -Serial $resolvedSerial -Arguments @("shell", "dumpsys", "package", $PackageName) -OutputPath (Join-Path $OutputDirectory "package-info.txt")

$rawRoot = Join-Path $OutputDirectory "raw-samples"
$sample = Get-AppPerformanceSample -Serial $resolvedSerial -PackageName $PackageName -Index 0 -ElapsedSeconds 0 -RawRoot $rawRoot
if (-not $sample.ProcessAlive) {
    "status=FAIL`r`nreason=Package process is not running: $PackageName" | Set-Content -Path (Join-Path $OutputDirectory "summary.txt") -Encoding UTF8
    Write-Host "[FAIL] Package process is not running: $PackageName" -ForegroundColor Red
    Write-Host "Report: $OutputDirectory"
    exit 1
}

@($sample) | Export-Csv -Path (Join-Path $OutputDirectory "metrics.csv") -NoTypeInformation -Encoding UTF8
@(
    "Punch App Performance Baseline V2.1",
    "status=PASS",
    "serial=$resolvedSerial",
    "package=$PackageName",
    "pid=$($sample.Pid)",
    "total_pss_mb=$(Convert-PerformanceValueToText $sample.TotalPssMb)",
    "java_heap_mb=$(Convert-PerformanceValueToText $sample.JavaHeapMb)",
    "native_heap_mb=$(Convert-PerformanceValueToText $sample.NativeHeapMb)",
    "rss_mb=$(Convert-PerformanceValueToText $sample.RssMb)",
    "cpu_percent=$(Convert-PerformanceValueToText $sample.CpuPercent)",
    "thread_count=$(Convert-PerformanceValueToText $sample.ThreadCount)",
    "fd_count=$(Convert-PerformanceValueToText $sample.FdCount)",
    "battery_level_percent=$(Convert-PerformanceValueToText $sample.BatteryLevelPercent)",
    "battery_temperature_c=$(Convert-PerformanceValueToText $sample.BatteryTemperatureC)",
    "thermal_max_c=$(Convert-PerformanceValueToText $sample.ThermalMaxC)",
    "data_disk_used_percent=$(Convert-PerformanceValueToText $sample.DataDiskUsedPercent)",
    "captured_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
) | Set-Content -Path (Join-Path $OutputDirectory "summary.txt") -Encoding UTF8

Write-Host "[PASS] Performance baseline captured." -ForegroundColor Green
Write-Host ("PSS={0} MB  Java={1} MB  Native={2} MB  RSS={3} MB  CPU={4}%" -f `
    (Convert-PerformanceValueToText $sample.TotalPssMb), `
    (Convert-PerformanceValueToText $sample.JavaHeapMb), `
    (Convert-PerformanceValueToText $sample.NativeHeapMb), `
    (Convert-PerformanceValueToText $sample.RssMb), `
    (Convert-PerformanceValueToText $sample.CpuPercent))
Write-Host "Report: $OutputDirectory"
exit 0
