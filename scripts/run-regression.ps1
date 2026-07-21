$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

Write-Host '[all] Running local regression...'
& "$PSScriptRoot\\run-local-regression.ps1"

Write-Host '[all] Checking adb device for UI smoke...'
$deviceLines = adb devices | Select-Object -Skip 1 | Where-Object {
    $_ -match '\S+\s+device$'
}

if (-not $deviceLines) {
    Write-Warning 'No online adb device found. Local regression passed, UI smoke skipped.'
    exit 0
}

& "$PSScriptRoot\\run-ui-smoke.ps1"
