[CmdletBinding()]
param(
    [string[]]$Paths = @((Join-Path (Split-Path $PSScriptRoot -Parent) 'run-camera-face-recovery.ps1'))
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$failed=$false
foreach($path in $Paths){
    $resolved=(Resolve-Path $path).Path
    $tokens=$null
    $errors=$null
    [void][System.Management.Automation.Language.Parser]::ParseFile($resolved,[ref]$tokens,[ref]$errors)
    if($errors.Count -gt 0){
        $failed=$true
        Write-Host "[FAIL] PowerShell parser errors: $resolved" -ForegroundColor Red
        foreach($err in $errors){ Write-Host ("  {0}" -f $err.Message) -ForegroundColor Red }
    } else {
        Write-Host "[PASS] PowerShell parser errors=0: $resolved"
    }
}
if($failed){exit 1}
exit 0
