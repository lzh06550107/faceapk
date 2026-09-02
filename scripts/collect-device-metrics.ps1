[CmdletBinding()]
param(
    [string]$Serial,
    [string]$PackageName = "com.punch.app.smoke",
    [string]$OutputDirectory,
    [string]$Prefix = "snapshot"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\DeviceTestCommon.ps1")

$projectRoot = Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
Assert-CommandAvailable -Name "adb"
$resolvedSerial = Resolve-AndroidSerial -RequestedSerial $Serial

if (-not $OutputDirectory) {
    $OutputDirectory = New-TestReportDirectory -ProjectRoot $projectRoot -Prefix "metrics"
}
else {
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $OutputDirectory = (Resolve-Path $OutputDirectory).Path
}

Write-DeviceInfo -Serial $resolvedSerial -OutputPath (Join-Path $OutputDirectory "device-info.txt")
Collect-DeviceSnapshot -Serial $resolvedSerial -PackageName $PackageName -OutputDirectory $OutputDirectory -Prefix $Prefix

Write-Host "[PASS] Metrics captured."
Write-Host "Serial: $resolvedSerial"
Write-Host "Package: $PackageName"
Write-Host "Output: $OutputDirectory"
