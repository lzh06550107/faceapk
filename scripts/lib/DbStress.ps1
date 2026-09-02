Set-StrictMode -Version Latest

function Invoke-DbStressBroadcast {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][ValidateSet('PREPARE','KILL','VERIFY_RESTART','DRAIN','STATUS','CLEANUP','TERMINATE')][string]$Action,
        [int]$Count=1000
    )
    $component="$PackageName/.dbstress.DbStressTestReceiver"
    $actionName="$PackageName.dbstress.$Action"
    $args=@('shell','am','broadcast','--receiver-foreground','-a',$actionName,'-n',$component)
    if($Action -in @('PREPARE','VERIFY_RESTART','DRAIN')){$args+=@('--ei','count',[string]$Count)}
    return Get-AdbOutput -Serial $Serial -Arguments $args -IgnoreFailure
}

function Get-DbStressStatus {
    param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$PackageName)
    $text=Invoke-DbStressBroadcast -Serial $Serial -PackageName $PackageName -Action STATUS
    $m=[regex]::Match($text,'(?i)busy=(true|false);state=([^;]*);error=([^;]*);count=(\d+);database_active=(true|false);token_valid=(true|false);device_registered=(true|false)')
    return [pscustomobject]@{
        Supported=$m.Success
        Busy=if($m.Success){$m.Groups[1].Value -ieq 'true'}else{$false}
        State=if($m.Success){$m.Groups[2].Value}else{''}
        Error=if($m.Success){$m.Groups[3].Value}else{''}
        Count=if($m.Success){[int]$m.Groups[4].Value}else{0}
        DatabaseActive=if($m.Success){$m.Groups[5].Value -ieq 'true'}else{$false}
        TokenValid=if($m.Success){$m.Groups[6].Value -ieq 'true'}else{$false}
        DeviceRegistered=if($m.Success){$m.Groups[7].Value -ieq 'true'}else{$false}
        Raw=$text
    }
}

function Wait-DbStressState {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][string[]]$ExpectedStates,
        [Parameter(Mandatory=$true)][int]$TimeoutSeconds
    )
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    $last=$null
    do {
        $last=Get-DbStressStatus -Serial $Serial -PackageName $PackageName
        if($last.Supported -and $last.State -in $ExpectedStates){return $last}
        Start-Sleep -Milliseconds 500
    } while((Get-Date) -lt $deadline)
    return $last
}

function Get-DbStressTestFile {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][string]$RelativePath
    )
    return Get-AdbOutput -Serial $Serial -Arguments @('shell','run-as',$PackageName,'cat',"files/db-stress-v26/$RelativePath") -IgnoreFailure
}

function Get-DbStressPid {
    param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$PackageName)
    $text=(Get-AdbOutput -Serial $Serial -Arguments @('shell','pidof',$PackageName) -IgnoreFailure).Trim()
    if([string]::IsNullOrWhiteSpace($text)){return 0}
    $first=($text -split '\s+')[0]
    if($first -match '^\d+$'){return [int]$first}
    return 0
}

function Wait-DbStressProcessExit {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][int]$OldPid,
        [int]$TimeoutSeconds=20
    )
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $currentPid=Get-DbStressPid -Serial $Serial -PackageName $PackageName
        if($currentPid -eq 0 -or $currentPid -ne $OldPid){return $true}
        Start-Sleep -Milliseconds 250
    } while((Get-Date) -lt $deadline)
    return $false
}

function Get-DbStressProductionKioskState {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName
    )
    $activityState=Get-AdbOutput -Serial $Serial -Arguments @('shell','dumpsys','activity','activities') -IgnoreFailure
    $owners=Get-AdbOutput -Serial $Serial -Arguments @('shell','dpm','list-owners') -IgnoreFailure
    $escapedPackage=[Regex]::Escape($PackageName)
    $resumed=[regex]::Match(
        $activityState,
        "(?im)(?:mResumedActivity|topResumedActivity|ResumedActivity)[^\r\n]*?($escapedPackage/(?:\.[A-Za-z0-9_.$]+|[A-Za-z0-9_.$]+))"
    )
    $foregroundActivity=if($resumed.Success){$resumed.Groups[1].Value}else{''}
    $isProductionForeground=-not [string]::IsNullOrWhiteSpace($foregroundActivity)
    $isExpectedRoute=$foregroundActivity -match "(?i)^$escapedPackage/(?:\.activity\.(?:MainActivity|LoginActivity|SetupWizardActivity|SplashActivity|KioskHomeActivity)|$escapedPackage\.activity\.(?:MainActivity|LoginActivity|SetupWizardActivity|SplashActivity|KioskHomeActivity))$"
    $isLockTaskActive=$activityState -match '(?im)mLockTaskModeState\s*=\s*(?:LOCKED|PINNED)' -or
        $activityState -match '(?im)lockTaskModeState\s*=\s*(?:LOCKED|PINNED)'
    $isDeviceOwner=$owners -match "(?is)(?:DeviceOwner|device\s+owner).{0,600}$escapedPackage" -or
        $owners -match "(?is)$escapedPackage.{0,600}(?:DeviceOwner|device\s+owner)"
    $ready=$isProductionForeground -and $isExpectedRoute -and $isLockTaskActive -and $isDeviceOwner
    $reason=''
    if(-not $ready){
        $parts=New-Object System.Collections.Generic.List[string]
        if(-not $isProductionForeground){[void]$parts.Add('production package is not resumed')}
        elseif(-not $isExpectedRoute){[void]$parts.Add("unexpected foreground activity=$foregroundActivity")}
        if(-not $isLockTaskActive){[void]$parts.Add('LockTask is not active')}
        if(-not $isDeviceOwner){[void]$parts.Add('production package is not Device Owner')}
        $reason=$parts -join '; '
    }
    return [pscustomobject]@{
        Ready=[bool]$ready
        ForegroundActivity=$foregroundActivity
        IsProductionForeground=[bool]$isProductionForeground
        IsExpectedRoute=[bool]$isExpectedRoute
        IsLockTaskActive=[bool]$isLockTaskActive
        IsDeviceOwner=[bool]$isDeviceOwner
        Reason=$reason
        Raw=@(
            "foreground_activity=$foregroundActivity",
            "production_foreground=$isProductionForeground",
            "expected_route=$isExpectedRoute",
            "lock_task_active=$isLockTaskActive",
            "device_owner=$isDeviceOwner",
            "ready=$ready",
            "reason=$reason",
            '',
            '--- dumpsys activity activities ---',
            $activityState,
            '',
            '--- dpm list-owners ---',
            $owners
        ) -join "`r`n"
    }
}

function Wait-DbStressProductionKioskReady {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [int]$TimeoutSeconds=20
    )
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    $last=$null
    do {
        $last=Get-DbStressProductionKioskState -Serial $Serial -PackageName $PackageName
        if($last.Ready){return $last}
        Start-Sleep -Milliseconds 500
    } while((Get-Date) -lt $deadline)
    return $last
}

