$ErrorActionPreference = 'Stop'

Set-StrictMode -Version Latest

$Script:UiSmokePackage = 'com.punch.app.smoke'
$Script:UiSmokeProdPackage = 'com.punch.app'
$Script:UiSmokeCodePackage = 'com.punch.app'
$Script:UiSmokeDebugApk = 'app\build\outputs\apk\smoke\app-smoke.apk'
$Script:UiSmokeSystemProperty = 'debug.punch.ui_smoke'

function Use-UiSmokeInstalledProdPackage {
    $Script:UiSmokePackage = $Script:UiSmokeProdPackage
    $Script:UiSmokeCodePackage = 'com.punch.app'
    $Script:UiSmokeDebugApk = $null
}

function Get-UiSmokeDeviceSerial {
    $deviceLines = adb devices | Select-Object -Skip 1 | Where-Object {
        $_ -match '\S+\s+device$'
    }

    if (-not $deviceLines) {
        throw 'No online adb device found. Connect a device first.'
    }

    return (($deviceLines | Select-Object -First 1) -split '\s+')[0]
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & adb @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: adb $($Arguments -join ' ')"
    }
}

function Install-UiSmokeApk {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot
    )

    if ([string]::IsNullOrWhiteSpace($Script:UiSmokeDebugApk)) {
        Write-Host "[ui] Using installed package: $Script:UiSmokePackage"
        return
    }

    $apkPath = Join-Path $RepoRoot $Script:UiSmokeDebugApk
    if (-not (Test-Path $apkPath)) {
        throw "Debug APK not found: $apkPath"
    }

    Write-Host "[ui] Installing debug APK: $apkPath"
    & adb install --no-streaming -r $apkPath
    if ($LASTEXITCODE -ne 0) {
        throw 'adb install --no-streaming -r failed.'
    }
}

function Uninstall-UiSmokePackageIfPresent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PackageName
    )

    $packagePath = adb shell pm path $PackageName
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($packagePath)) {
        return
    }

    Write-Host "[ui] Uninstalling package: $PackageName"
    & adb uninstall $PackageName | Out-Null
    if ($LASTEXITCODE -ne 0) {
        $remainingPackage = adb shell pm list packages $PackageName
        if ($LASTEXITCODE -eq 0 -and [string]::IsNullOrWhiteSpace($remainingPackage)) {
            return
        }
        throw "adb uninstall failed: $PackageName"
    }
}

function Prepare-UiSmokeDevice {
    Write-Host '[ui] Waking device and clearing previous app state...'
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    Invoke-Adb -Arguments @('shell', 'wm', 'dismiss-keyguard')
    Invoke-Adb -Arguments @('shell', 'setprop', $Script:UiSmokeSystemProperty, '1')
    Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $Script:UiSmokeProdPackage)
    Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $Script:UiSmokePackage)
    Start-Sleep -Milliseconds 800
}

function Reset-UiSmokeDevice {
    Invoke-Adb -Arguments @('shell', 'setprop', $Script:UiSmokeSystemProperty, '0')
    Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $Script:UiSmokePackage)

    $prodPath = adb shell pm path $Script:UiSmokeProdPackage
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($prodPath)) {
        Invoke-Adb -Arguments @(
            'shell',
            'am',
            'start',
            '-W',
            '-n',
            "$Script:UiSmokeProdPackage/.activity.SplashActivity"
        )
    }
}

function Start-UiSmokeActivity {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ActivityClass
    )

    $component = "$Script:UiSmokePackage/$Script:UiSmokeCodePackage$ActivityClass"
    Write-Host "[ui] Starting activity: $component"
    Invoke-Adb -Arguments @(
        'shell',
        'am',
        'start',
        '-W',
        '-S',
        '-n',
        $component,
        '-f',
        '0x10008000'
    )
}

function Get-UiSmokeResourceId {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Id
    )

    return "${Script:UiSmokePackage}:id/$Id"
}

function Get-UiHierarchyXml {
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $xmlText = adb exec-out uiautomator dump /dev/tty 2>$null
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($xmlText)) {
            $start = $xmlText.IndexOf('<?xml')
            if ($start -ge 0) {
                $endMarker = 'UI hierchary dumped to: /dev/tty'
                $end = $xmlText.IndexOf($endMarker, $start)
                if ($end -lt 0) {
                    $end = $xmlText.Length
                }
                return [xml]$xmlText.Substring($start, $end - $start).Trim()
            }
        }
        Start-Sleep -Milliseconds 400
    }
    throw 'Failed to read UI hierarchy dump.'
}

function Find-UiNodeByResourceId {
    param(
        [Parameter(Mandatory = $true)]
        [xml]$Xml,
        [Parameter(Mandatory = $true)]
        [string]$ResourceId
    )

    return $Xml.SelectSingleNode("//node[@resource-id='$ResourceId']")
}

function Wait-UiNodeByResourceId {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ResourceId,
        [int]$TimeoutSeconds = 15
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Get-UiHierarchyXml
        $node = Find-UiNodeByResourceId -Xml $xml -ResourceId $ResourceId
        if ($null -ne $node) {
            return @{
                Xml  = $xml
                Node = $node
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for resource-id: $ResourceId"
}

function Assert-UiResourceIdsPresent {
    param(
        [Parameter(Mandatory = $true)]
        [xml]$Xml,
        [Parameter(Mandatory = $true)]
        [string[]]$ResourceIds
    )

    foreach ($resourceId in $ResourceIds) {
        $node = Find-UiNodeByResourceId -Xml $Xml -ResourceId $resourceId
        if ($null -eq $node) {
            throw "Missing expected resource-id: $resourceId"
        }
    }
}

function Get-UiNodeCenter {
    param(
        [Parameter(Mandatory = $true)]
        [System.Xml.XmlNode]$Node
    )

    $bounds = $Node.bounds
    if ([string]::IsNullOrWhiteSpace($bounds) -or $bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Invalid node bounds: $bounds"
    }

    $left = [int]$Matches[1]
    $top = [int]$Matches[2]
    $right = [int]$Matches[3]
    $bottom = [int]$Matches[4]

    return @{
        X = [int](($left + $right) / 2)
        Y = [int](($top + $bottom) / 2)
    }
}

function Tap-UiNode {
    param(
        [Parameter(Mandatory = $true)]
        [System.Xml.XmlNode]$Node
    )

    $center = Get-UiNodeCenter -Node $Node
    Invoke-Adb -Arguments @('shell', 'input', 'tap', "$($center.X)", "$($center.Y)")
}

function Invoke-LoginScreenSmoke {
    Write-Host '[ui] Checking login screen...'
    Start-UiSmokeActivity -ActivityClass '.activity.UiTestLoginHostActivity'
    Wait-UiNodeByResourceId -ResourceId (Get-UiSmokeResourceId -Id 'et_account') | Out-Null
    Wait-UiNodeByResourceId -ResourceId (Get-UiSmokeResourceId -Id 'et_password') | Out-Null
    Wait-UiNodeByResourceId -ResourceId (Get-UiSmokeResourceId -Id 'btn_login') | Out-Null
    Wait-UiNodeByResourceId -ResourceId (Get-UiSmokeResourceId -Id 'btn_configure_wifi') | Out-Null
}

function Invoke-WifiDialogSmoke {
    Write-Host '[ui] Checking Wi-Fi dialog...'
    Start-UiSmokeActivity -ActivityClass '.activity.UiTestLoginHostActivity'
    Wait-UiNodeByResourceId -ResourceId (Get-UiSmokeResourceId -Id 'et_account') | Out-Null
    $result = Wait-UiNodeByResourceId -ResourceId (Get-UiSmokeResourceId -Id 'btn_configure_wifi')
    Tap-UiNode -Node $result.Node

    Wait-UiNodeByResourceId -ResourceId 'android:id/button1' | Out-Null
    Wait-UiNodeByResourceId -ResourceId 'android:id/button2' | Out-Null
    Wait-UiNodeByResourceId -ResourceId 'android:id/button3' | Out-Null
}

function Invoke-AdvancedConfigSmoke {
    Write-Host '[ui] Checking advanced config screen...'
    Start-UiSmokeActivity -ActivityClass '.activity.UiTestAdvancedConfigHostActivity'
    $result = Wait-UiNodeByResourceId -ResourceId (Get-UiSmokeResourceId -Id 'et_base_url')
    Assert-UiResourceIdsPresent -Xml $result.Xml -ResourceIds @(
        (Get-UiSmokeResourceId -Id 'et_base_url'),
        (Get-UiSmokeResourceId -Id 'et_company_id'),
        (Get-UiSmokeResourceId -Id 'btn_copy_fingerprint')
    )
}

function Invoke-MainScreenSmoke {
    Write-Host '[ui] Checking main screen...'
    Start-UiSmokeActivity -ActivityClass '.activity.UiTestLoginHostActivity'
    $loginButton = Wait-UiNodeByResourceId -ResourceId (Get-UiSmokeResourceId -Id 'btn_login')
    Tap-UiNode -Node $loginButton.Node
    $result = Wait-UiNodeByResourceId -ResourceId (Get-UiSmokeResourceId -Id 'bottom_nav') -TimeoutSeconds 30
    Assert-UiResourceIdsPresent -Xml $result.Xml -ResourceIds @(
        (Get-UiSmokeResourceId -Id 'bottom_nav'),
        (Get-UiSmokeResourceId -Id 'layout_punch_root'),
        (Get-UiSmokeResourceId -Id 'tv_status'),
        (Get-UiSmokeResourceId -Id 'btn_fullscreen')
    )
}
