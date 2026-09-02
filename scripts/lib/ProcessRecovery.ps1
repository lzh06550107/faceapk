Set-StrictMode -Version Latest

function Invoke-ProcessRecoveryBroadcast {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][ValidateSet('STATUS','PREPARE_SYNC_KILL','KILL_FOREGROUND','VERIFY_SYNC_RESTART','RESUME_SYNC','CLEANUP','TERMINATE')][string]$Action
    )
    $component="$PackageName/.processrecovery.ProcessRecoveryTestReceiver"
    $actionName="$PackageName.processrecovery.$Action"
    return Get-AdbOutput -Serial $Serial -Arguments @(
        'shell','am','broadcast','--receiver-foreground','-a',$actionName,'-n',$component
    ) -IgnoreFailure
}

function Get-ProcessRecoveryStatus {
    param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$PackageName)
    $text=Invoke-ProcessRecoveryBroadcast -Serial $Serial -PackageName $PackageName -Action STATUS
    $m=[regex]::Match($text,'(?i)busy=(true|false);state=([^;]*);error=([^;]*);database_active=(true|false);token_valid=(true|false);device_registered=(true|false)')
    return [pscustomobject]@{
        Supported=$m.Success
        Busy=if($m.Success){$m.Groups[1].Value -ieq 'true'}else{$false}
        State=if($m.Success){$m.Groups[2].Value}else{''}
        Error=if($m.Success){$m.Groups[3].Value}else{''}
        DatabaseActive=if($m.Success){$m.Groups[4].Value -ieq 'true'}else{$false}
        TokenValid=if($m.Success){$m.Groups[5].Value -ieq 'true'}else{$false}
        DeviceRegistered=if($m.Success){$m.Groups[6].Value -ieq 'true'}else{$false}
        Raw=$text
    }
}

function Wait-ProcessRecoveryState {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][string[]]$ExpectedStates,
        [int]$TimeoutSeconds=60
    )
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    $last=$null
    do {
        $last=Get-ProcessRecoveryStatus -Serial $Serial -PackageName $PackageName
        if($last.Supported -and $last.State -in $ExpectedStates){return $last}
        Start-Sleep -Milliseconds 350
    } while((Get-Date) -lt $deadline)
    return $last
}

function Get-ProcessRecoveryTestFile {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][string]$RelativePath
    )
    return Get-AdbOutput -Serial $Serial -Arguments @(
        'shell','run-as',$PackageName,'cat',"files/process-recovery-v271/$RelativePath"
    ) -IgnoreFailure
}

function Get-ProcessRecoveryPid {
    param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$PackageName)
    $text=(Get-AdbOutput -Serial $Serial -Arguments @('shell','pidof',$PackageName) -IgnoreFailure).Trim()
    if([string]::IsNullOrWhiteSpace($text)){return 0}
    $first=($text -split '\s+')[0]
    if($first -match '^\d+$'){return [int]$first}
    return 0
}

function Wait-ProcessRecoveryOldPidExit {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][int]$OldPid,
        [int]$TimeoutSeconds=45
    )
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $currentPid=Get-ProcessRecoveryPid -Serial $Serial -PackageName $PackageName
        if($currentPid -eq 0 -or $currentPid -ne $OldPid){return $true}
        Start-Sleep -Milliseconds 150
    } while((Get-Date) -lt $deadline)
    return $false
}

function Get-ProcessRecoveryKioskState {
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
    $isPackageForeground=-not [string]::IsNullOrWhiteSpace($foregroundActivity)
    $isExpectedRoute=$foregroundActivity -match "(?i)^$escapedPackage/(?:\.activity\.(?:MainActivity|LoginActivity|SetupWizardActivity|SplashActivity|KioskHomeActivity)|$escapedPackage\.activity\.(?:MainActivity|LoginActivity|SetupWizardActivity|SplashActivity|KioskHomeActivity))$"
    $isLockTaskActive=$activityState -match '(?im)mLockTaskModeState\s*=\s*(?:LOCKED|PINNED)' -or
        $activityState -match '(?im)lockTaskModeState\s*=\s*(?:LOCKED|PINNED)'
    $isDeviceOwner=$owners -match "(?is)(?:DeviceOwner|device\s+owner).{0,600}$escapedPackage" -or
        $owners -match "(?is)$escapedPackage.{0,600}(?:DeviceOwner|device\s+owner)"
    $ready=$isPackageForeground -and $isExpectedRoute -and $isLockTaskActive -and $isDeviceOwner
    $reason=''
    if(-not $ready){
        $parts=New-Object System.Collections.Generic.List[string]
        if(-not $isPackageForeground){[void]$parts.Add('package is not resumed')}
        elseif(-not $isExpectedRoute){[void]$parts.Add("unexpected foreground activity=$foregroundActivity")}
        if(-not $isLockTaskActive){[void]$parts.Add('LockTask is not active')}
        if(-not $isDeviceOwner){[void]$parts.Add('package is not Device Owner')}
        $reason=$parts -join '; '
    }
    return [pscustomobject]@{
        Ready=[bool]$ready
        ForegroundActivity=$foregroundActivity
        IsPackageForeground=[bool]$isPackageForeground
        IsExpectedRoute=[bool]$isExpectedRoute
        IsLockTaskActive=[bool]$isLockTaskActive
        IsDeviceOwner=[bool]$isDeviceOwner
        Reason=$reason
        Raw=@(
            "foreground_activity=$foregroundActivity",
            "package_foreground=$isPackageForeground",
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

function Wait-ProcessRecoveryAutomaticKioskReady {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][int]$OldPid,
        [int]$TimeoutSeconds=45
    )
    # Important: this function is observer-only. It must never start an Activity.
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    $lastState=$null
    $lastPid=0
    do {
        $lastPid=Get-ProcessRecoveryPid -Serial $Serial -PackageName $PackageName
        $lastState=Get-ProcessRecoveryKioskState -Serial $Serial -PackageName $PackageName
        if($lastPid -gt 0 -and $lastPid -ne $OldPid -and $lastState.Ready){
            return [pscustomobject]@{
                Ready=$true
                OldPid=$OldPid
                NewPid=$lastPid
                State=$lastState
                Reason=''
            }
        }
        Start-Sleep -Milliseconds 250
    } while((Get-Date) -lt $deadline)
    $reason=if($null -eq $lastState){'no kiosk state observed'}else{$lastState.Reason}
    if($lastPid -eq $OldPid){$reason="old PID still active; $reason"}
    elseif($lastPid -le 0){$reason="new PID not observed; $reason"}
    return [pscustomobject]@{
        Ready=$false
        OldPid=$OldPid
        NewPid=$lastPid
        State=$lastState
        Reason=$reason
    }
}

function Wait-ProcessRecoveryProductionKioskReady {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [int]$TimeoutSeconds=30
    )
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    $last=$null
    do {
        $last=Get-ProcessRecoveryKioskState -Serial $Serial -PackageName $PackageName
        if($last.Ready){return $last}
        Start-Sleep -Milliseconds 500
    } while((Get-Date) -lt $deadline)
    return $last
}

function Save-ProcessRecoveryAutomaticEvidence {
    param(
        [Parameter(Mandatory=$true)][object]$Recovery,
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Scenario
    )
    $state=$Recovery.State
    @(
        "scenario=$Scenario",
        'autonomous_recovery=true',
        "old_pid=$($Recovery.OldPid)",
        "new_pid=$($Recovery.NewPid)",
        "ready=$($Recovery.Ready)",
        "reason=$($Recovery.Reason)",
        "foreground_activity=$(if($null -ne $state){$state.ForegroundActivity}else{''})",
        "lock_task_active=$(if($null -ne $state){$state.IsLockTaskActive}else{$false})",
        "device_owner=$(if($null -ne $state){$state.IsDeviceOwner}else{$false})"
    ) | Set-Content -Path $Path -Encoding UTF8
}
