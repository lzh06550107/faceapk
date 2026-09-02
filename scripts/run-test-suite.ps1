[CmdletBinding()]
param(
    [ValidateSet('Smoke','Regression','Release')]
    [string]$Profile = 'Smoke',
    [string]$Serial,
    [switch]$Resume,
    [switch]$IncludeExperimental,
    [string]$RunDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'lib\DeviceTestCommon.ps1')
. (Join-Path $PSScriptRoot 'lib\TestResult.ps1')
. (Join-Path $PSScriptRoot 'lib\ReleaseGate.ps1')
. (Join-Path $PSScriptRoot 'lib\TestOrchestrator.ps1')

$projectRoot = Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
$startedAt = Get-Date
$resolvedSerial = $Serial
$previousResults = @()
$exitCode = 1
$results = @()

function Find-LatestResumableRunDirectory {
    param([string]$ProjectRoot, [string]$Profile)
    $root = Join-Path $ProjectRoot 'test-results'
    if (-not (Test-Path $root)) { return $null }
    return Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*-suite-$($Profile.ToLowerInvariant())" -and (Test-Path (Join-Path $_.FullName 'state.json')) } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

if ($Resume -and -not $RunDirectory) {
    $candidate = Find-LatestResumableRunDirectory -ProjectRoot $projectRoot -Profile $Profile
    if ($null -eq $candidate) { throw "No resumable $Profile suite was found under test-results." }
    $RunDirectory = $candidate.FullName
}
elseif (-not $RunDirectory) {
    $RunDirectory = New-TestReportDirectory -ProjectRoot $projectRoot -Prefix ("suite-{0}" -f $Profile.ToLowerInvariant())
}
else {
    New-Item -ItemType Directory -Path $RunDirectory -Force | Out-Null
    $RunDirectory = (Resolve-Path $RunDirectory).Path
}

$statePath = Join-Path $RunDirectory 'state.json'
if ($Resume) {
    $state = Import-TestSuiteState -Path $statePath
    if ($null -eq $state) { throw "Resume requested but state.json is missing: $statePath" }
    if ([string]$state.profile -ne $Profile) { throw "Resume profile mismatch. State=$($state.profile), requested=$Profile" }
    $previousResults = @($state.results)
    if ($state.started_at) { $startedAt = [datetime]$state.started_at }
    if ([string]::IsNullOrWhiteSpace($resolvedSerial) -and $state.serial) { $resolvedSerial = [string]$state.serial }
}

Write-Host '========================================'
Write-Host " FaceAPK Test Suite - $Profile"
Write-Host '========================================'
Write-Host "Report: $RunDirectory"

try {
    Assert-CommandAvailable -Name 'adb'
    # -Serial is optional; Resolve-AndroidSerial automatically selects the only online device.
    $resolvedSerial = Resolve-AndroidSerial -RequestedSerial $resolvedSerial
    Write-Host "Device: $resolvedSerial"

    $results = @(Invoke-TestSuite -Profile $Profile -ProjectRoot $projectRoot -ScriptsRoot $PSScriptRoot -RunDirectory $RunDirectory -Serial $resolvedSerial -IncludeExperimental:$IncludeExperimental -PreviousResults $previousResults -StatePath $statePath -StartedAt $startedAt)
}
catch {
    $blockedStarted = Get-Date
    $results = @((New-TestSuiteResult -Name 'EnvironmentPreflight' -Status 'BLOCKED' -StartedAt $blockedStarted -FinishedAt (Get-Date) -ExitCode 1 -Message $_.Exception.Message -ReportDirectory $RunDirectory))
}
finally {
    Invoke-TestSuiteRestoreSweep -RunDirectory $RunDirectory -ScriptsRoot $PSScriptRoot -Serial $resolvedSerial
    $finishedAt = Get-Date
    if ($results.Count -eq 0 -and (Test-Path $statePath)) {
        $state = Import-TestSuiteState -Path $statePath
        if ($null -ne $state) { $results = @($state.results) }
    }
    if ($results.Count -gt 0) {
        Export-TestSuiteState -Path $statePath -Profile $Profile -Serial $resolvedSerial -Results $results -StartedAt $startedAt
        $overall = Write-TestSuiteSummary -RunDirectory $RunDirectory -Profile $Profile -Serial $resolvedSerial -Results $results -StartedAt $startedAt -FinishedAt $finishedAt
        Write-Host '----------------------------------------'
        Write-Host "FINAL RESULT: $overall"
        Write-Host "Summary: $(Join-Path $RunDirectory 'summary.md')"
        $exitCode = if ($overall -eq 'PASS') { 0 } else { 1 }
    }
    else {
        @(
            '# FaceAPK Test Suite Summary',
            '',
            "- Profile: **$Profile**",
            '- Final result: **BLOCKED**',
            '- No stage result was produced. Check launcher/preflight error output.'
        ) | Set-Content -Path (Join-Path $RunDirectory 'summary.md') -Encoding UTF8
        $exitCode = 1
    }
}

exit $exitCode
