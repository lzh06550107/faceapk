$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

Write-Host '[local] Running unit tests, assemble, and lint...'
& .\gradlew.bat testDebugUnitTest assembleDebug lintDebug

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host '[local] Regression passed.'
