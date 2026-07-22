$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

. "$PSScriptRoot\lib-ui-smoke.ps1"

$usingInstalledProdPackage = $false

function Test-ProdOwnerUiSmokeSupport {
    $ownerOutput = adb shell dpm list-owners | Out-String
    if ($LASTEXITCODE -ne 0) {
        return $false
    }
    if ($ownerOutput -notmatch 'com\.punch\.app/.receiver\.KioskDeviceAdminReceiver') {
        return $false
    }

    $resolveOutput = adb shell cmd package resolve-activity --brief -n com.punch.app/com.punch.app.activity.UiTestLoginHostActivity | Out-String
    if ($LASTEXITCODE -ne 0) {
        return $false
    }
    return $resolveOutput -match 'com\.punch\.app/.activity\.UiTestLoginHostActivity'
}

if (Test-ProdOwnerUiSmokeSupport) {
    Write-Host '[ui] Device owner detected; using installed com.punch.app debug package for smoke.'
    Use-UiSmokeInstalledProdPackage
    $usingInstalledProdPackage = $true
} else {
    Write-Host '[ui] Building smoke APK...'
    & .\gradlew.bat assembleSmoke

    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

function Invoke-UiSmokeStep {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [scriptblock]$Action
    )

    $lastError = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            if ($attempt -gt 1) {
                Write-Warning "[ui] Retrying $Name ($attempt/3)..."
                Start-Sleep -Seconds 1
            }
            & $Action
            return
        } catch {
            $lastError = $_
        }
    }

    throw $lastError
}

Get-UiSmokeDeviceSerial | Out-Null
Install-UiSmokeApk -RepoRoot $repoRoot
try {
    Prepare-UiSmokeDevice

    Invoke-UiSmokeStep -Name 'login screen' -Action { Invoke-LoginScreenSmoke }
    Invoke-UiSmokeStep -Name 'wifi dialog' -Action { Invoke-WifiDialogSmoke }
    Invoke-UiSmokeStep -Name 'setup wizard' -Action { Invoke-SetupWizardSmoke }
    Invoke-UiSmokeStep -Name 'advanced config' -Action { Invoke-AdvancedConfigSmoke }
    if ($usingInstalledProdPackage) {
        Invoke-UiSmokeStep -Name 'main screen' -Action { Invoke-MainScreenSmoke }
    }

    Write-Host '[ui] Black-box smoke passed.'
}
finally {
    Reset-UiSmokeDevice
    if ($usingInstalledProdPackage) {
        Uninstall-UiSmokePackageIfPresent -PackageName 'com.punch.app.smoke'
    }
}
