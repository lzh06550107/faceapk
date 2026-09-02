Set-StrictMode -Version Latest

function Invoke-CameraFaceRecoveryBroadcast {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][ValidateSet('STATUS','PREPARE','RECOGNIZE','KILL','CLEANUP','TERMINATE')][string]$Action,
        [int]$Cycle=1
    )
    $component="$PackageName/.camerafacerecovery.CameraFaceRecoveryTestReceiver"
    $actionName="$PackageName.camerafacerecovery.$Action"
    $arguments=@('shell','am','broadcast','--receiver-foreground','-a',$actionName,'-n',$component)
    if($Action -in @('RECOGNIZE','KILL','TERMINATE')){
        $arguments += @('--ei','cycle',$Cycle.ToString())
    }
    return Get-AdbOutput -Serial $Serial -Arguments $arguments -IgnoreFailure
}

function Get-CameraFaceRecoveryStatus {
    param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$PackageName)
    $text=Invoke-CameraFaceRecoveryBroadcast -Serial $Serial -PackageName $PackageName -Action STATUS
    $m=[regex]::Match($text,'(?i)busy=(true|false);state=([^;]*);error=([^;]*);database_active=(true|false);token_valid=(true|false);device_registered=(true|false);face_initialized=(true|false);loaded_face_count=(-?\d+);punch_ready=(true|false);punch_preparing=(true|false);fixture_employee=(true|false);fixture_image=(true|false)')
    return [pscustomobject]@{
        Supported=$m.Success
        Busy=if($m.Success){$m.Groups[1].Value -ieq 'true'}else{$false}
        State=if($m.Success){$m.Groups[2].Value}else{''}
        Error=if($m.Success){$m.Groups[3].Value}else{''}
        DatabaseActive=if($m.Success){$m.Groups[4].Value -ieq 'true'}else{$false}
        TokenValid=if($m.Success){$m.Groups[5].Value -ieq 'true'}else{$false}
        DeviceRegistered=if($m.Success){$m.Groups[6].Value -ieq 'true'}else{$false}
        FaceInitialized=if($m.Success){$m.Groups[7].Value -ieq 'true'}else{$false}
        LoadedFaceCount=if($m.Success){[int]$m.Groups[8].Value}else{0}
        PunchReady=if($m.Success){$m.Groups[9].Value -ieq 'true'}else{$false}
        PunchPreparing=if($m.Success){$m.Groups[10].Value -ieq 'true'}else{$false}
        FixtureEmployee=if($m.Success){$m.Groups[11].Value -ieq 'true'}else{$false}
        FixtureImage=if($m.Success){$m.Groups[12].Value -ieq 'true'}else{$false}
        Raw=$text
    }
}

function Wait-CameraFaceRecoveryState {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][string[]]$ExpectedStates,
        [int]$TimeoutSeconds=180
    )
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    $last=$null
    do {
        $last=Get-CameraFaceRecoveryStatus -Serial $Serial -PackageName $PackageName
        if($last.Supported -and $last.State -in $ExpectedStates){return $last}
        Start-Sleep -Milliseconds 350
    } while((Get-Date) -lt $deadline)
    return $last
}

function Get-CameraFaceRecoveryTestFile {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][string]$RelativePath
    )
    return Get-AdbOutput -Serial $Serial -Arguments @(
        'shell','run-as',$PackageName,'cat',"files/camera-face-recovery-v273/$RelativePath"
    ) -IgnoreFailure
}

function Get-CameraFaceRecoveryPid {
    param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$PackageName)
    $text=(Get-AdbOutput -Serial $Serial -Arguments @('shell','pidof',$PackageName) -IgnoreFailure).Trim()
    if([string]::IsNullOrWhiteSpace($text)){return 0}
    $first=($text -split '\s+')[0]
    if($first -match '^\d+$'){return [int]$first}
    return 0
}

function Wait-CameraFaceRecoveryOldPidExit {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][int]$OldPid,
        [int]$TimeoutSeconds=45
    )
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $currentPid=Get-CameraFaceRecoveryPid -Serial $Serial -PackageName $PackageName
        if($currentPid -eq 0 -or $currentPid -ne $OldPid){return $true}
        Start-Sleep -Milliseconds 150
    } while((Get-Date) -lt $deadline)
    return $false
}

function Get-CameraFaceRecoveryKioskState {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName
    )
    $processId=Get-CameraFaceRecoveryPid -Serial $Serial -PackageName $PackageName
    $isProcessRunning=$processId -gt 0
    $activityState=Get-AdbOutput -Serial $Serial -Arguments @('shell','dumpsys','activity','activities') -IgnoreFailure
    $owners=Get-AdbOutput -Serial $Serial -Arguments @('shell','dpm','list-owners') -IgnoreFailure
    $escapedPackage=[Regex]::Escape($PackageName)
    $resumed=[regex]::Match($activityState,"(?im)(?:mResumedActivity|topResumedActivity|ResumedActivity)[^\r\n]*?($escapedPackage/(?:\.[A-Za-z0-9_.$]+|[A-Za-z0-9_.$]+))")
    $foregroundActivity=if($resumed.Success){$resumed.Groups[1].Value}else{''}
    $isPackageForeground=-not [string]::IsNullOrWhiteSpace($foregroundActivity)
    $isExpectedRoute=$foregroundActivity -match "(?i)^$escapedPackage/(?:\.activity\.(?:MainActivity|LoginActivity|SetupWizardActivity|SplashActivity|KioskHomeActivity)|$escapedPackage\.activity\.(?:MainActivity|LoginActivity|SetupWizardActivity|SplashActivity|KioskHomeActivity))$"
    $isLockTaskActive=$activityState -match '(?im)mLockTaskModeState\s*=\s*(?:LOCKED|PINNED)' -or $activityState -match '(?im)lockTaskModeState\s*=\s*(?:LOCKED|PINNED)'
    $isDeviceOwner=$owners -match "(?is)(?:DeviceOwner|device\s+owner).{0,600}$escapedPackage" -or $owners -match "(?is)$escapedPackage.{0,600}(?:DeviceOwner|device\s+owner)"
    $ready=$isProcessRunning -and $isPackageForeground -and $isExpectedRoute -and $isLockTaskActive -and $isDeviceOwner
    $parts=New-Object System.Collections.Generic.List[string]
    if(-not $isProcessRunning){[void]$parts.Add('process exited during preflight')}
    if(-not $isPackageForeground){[void]$parts.Add('package is not resumed')}
    elseif(-not $isExpectedRoute){[void]$parts.Add("unexpected foreground activity=$foregroundActivity")}
    if(-not $isLockTaskActive){[void]$parts.Add('LockTask is not active')}
    if(-not $isDeviceOwner){[void]$parts.Add('package is not Device Owner')}
    $reason=$parts -join '; '
    return [pscustomobject]@{
        Ready=[bool]$ready
        ProcessId=[int]$processId
        IsProcessRunning=[bool]$isProcessRunning
        ForegroundActivity=$foregroundActivity
        IsPackageForeground=[bool]$isPackageForeground
        IsExpectedRoute=[bool]$isExpectedRoute
        IsLockTaskActive=[bool]$isLockTaskActive
        IsDeviceOwner=[bool]$isDeviceOwner
        Reason=$reason
        ActivityDump=$activityState
        OwnersDump=$owners
        Raw=@(
            "process_id=$processId",
            "process_running=$isProcessRunning",
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

function Save-CameraFaceRecoveryPreflightDiagnostics {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][string]$OutputDirectory,
        [object]$KioskState
    )
    if($null -ne $KioskState){
        $KioskState.Raw | Set-Content -Path (Join-Path $OutputDirectory 'initial-kiosk-state.txt') -Encoding UTF8
    }
    $activityTop=Get-AdbOutput -Serial $Serial -Arguments @('shell','dumpsys','activity','top') -IgnoreFailure
    $windowDump=Get-AdbOutput -Serial $Serial -Arguments @('shell','dumpsys','window','windows') -IgnoreFailure
    $powerDump=Get-AdbOutput -Serial $Serial -Arguments @('shell','dumpsys','power') -IgnoreFailure
    $activityTop | Set-Content -Path (Join-Path $OutputDirectory 'preflight-activity-top.txt') -Encoding UTF8
    $windowDump | Set-Content -Path (Join-Path $OutputDirectory 'preflight-window.txt') -Encoding UTF8
    $powerDump | Set-Content -Path (Join-Path $OutputDirectory 'preflight-power.txt') -Encoding UTF8
}

function Test-CameraFaceRecoveryCameraActive {
    param([string]$CameraDump,[Parameter(Mandatory=$true)][string]$PackageName)
    if([string]::IsNullOrWhiteSpace($CameraDump)){return $false}
    $escaped=[Regex]::Escape($PackageName)
    $patterns=@(
        "(?is)Device\s+[^\r\n]+\s+is\s+open.*?(?:Client package name|Package name|clientPackageName)\s*[:=]\s*$escaped\b",
        "(?im)^\s*(?:Client package name|Package name|clientPackageName)\s*[:=]\s*$escaped\b",
        "(?is)(?:active camera clients|camera client|client info).*?$escaped\b"
    )
    foreach($pattern in $patterns){if($CameraDump -match $pattern){return $true}}
    return $false
}

function Get-CameraFaceRecoveryHealthSnapshot {
    param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$PackageName)
    $status=Get-CameraFaceRecoveryStatus -Serial $Serial -PackageName $PackageName
    $kiosk=Get-CameraFaceRecoveryKioskState -Serial $Serial -PackageName $PackageName
    $cameraDump=Get-AdbOutput -Serial $Serial -Arguments @('shell','dumpsys','media.camera') -IgnoreFailure
    $cameraActive=Test-CameraFaceRecoveryCameraActive -CameraDump $cameraDump -PackageName $PackageName
    $ready=$status.Supported -and $status.DatabaseActive -and $status.FaceInitialized -and $status.LoadedFaceCount -ge 1 -and $status.PunchReady -and $status.FixtureEmployee -and $status.FixtureImage -and $kiosk.Ready -and $cameraActive
    return [pscustomobject]@{
        Ready=[bool]$ready
        Status=$status
        Kiosk=$kiosk
        CameraActive=[bool]$cameraActive
        CameraDump=$cameraDump
        FaceInitialized=[bool]$status.FaceInitialized
        LoadedFaceCount=[int]$status.LoadedFaceCount
        PunchReady=[bool]$status.PunchReady
    }
}

function Wait-CameraFaceRecoveryHealthReady {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [int]$TimeoutSeconds=180
    )
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    $last=$null
    do {
        $last=Get-CameraFaceRecoveryHealthSnapshot -Serial $Serial -PackageName $PackageName
        if($last.Ready){return $last}
        Start-Sleep -Milliseconds 500
    } while((Get-Date) -lt $deadline)
    return $last
}

function Wait-CameraFaceRecoveryAutomaticReady {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][int]$OldPid,
        [int]$TimeoutSeconds=90
    )
    # Observer-only after the deliberate process death.
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    $lastHealth=$null
    $lastPid=0
    do {
        $lastPid=Get-CameraFaceRecoveryPid -Serial $Serial -PackageName $PackageName
        if($lastPid -gt 0 -and $lastPid -ne $OldPid){
            $lastHealth=Get-CameraFaceRecoveryHealthSnapshot -Serial $Serial -PackageName $PackageName
            if($lastHealth.Ready){
                return [pscustomobject]@{
                    Ready=$true
                    OldPid=$OldPid
                    NewPid=$lastPid
                    Health=$lastHealth
                    CameraActive=$lastHealth.CameraActive
                    FaceInitialized=$lastHealth.FaceInitialized
                    LoadedFaceCount=$lastHealth.LoadedFaceCount
                    PunchReady=$lastHealth.PunchReady
                    Reason=''
                }
            }
        }
        Start-Sleep -Milliseconds 350
    } while((Get-Date) -lt $deadline)
    $reason='new PID not observed'
    if($lastPid -eq $OldPid){$reason='old PID still active'}
    elseif($lastPid -gt 0 -and $null -ne $lastHealth){
        $parts=New-Object System.Collections.Generic.List[string]
        if(-not $lastHealth.Kiosk.Ready){[void]$parts.Add($lastHealth.Kiosk.Reason)}
        if(-not $lastHealth.CameraActive){[void]$parts.Add('camera is not active')}
        if(-not $lastHealth.FaceInitialized){[void]$parts.Add('face is not initialized')}
        if($lastHealth.LoadedFaceCount -lt 1){[void]$parts.Add('loaded face count is zero')}
        if(-not $lastHealth.PunchReady){[void]$parts.Add('punch recognition is not ready')}
        $reason=$parts -join '; '
    }
    return [pscustomobject]@{
        Ready=$false
        OldPid=$OldPid
        NewPid=$lastPid
        Health=$lastHealth
        CameraActive=if($null -ne $lastHealth){$lastHealth.CameraActive}else{$false}
        FaceInitialized=if($null -ne $lastHealth){$lastHealth.FaceInitialized}else{$false}
        LoadedFaceCount=if($null -ne $lastHealth){$lastHealth.LoadedFaceCount}else{0}
        PunchReady=if($null -ne $lastHealth){$lastHealth.PunchReady}else{$false}
        Reason=$reason
    }
}

function Wait-CameraFaceRecoveryKioskReady {
    param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$PackageName,[int]$TimeoutSeconds=30)
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    $last=$null
    do {
        $last=Get-CameraFaceRecoveryKioskState -Serial $Serial -PackageName $PackageName
        if($last.Ready){return $last}
        Start-Sleep -Milliseconds 500
    } while((Get-Date) -lt $deadline)
    return $last
}

function Save-CameraFaceRecoveryAutomaticEvidence {
    param(
        [Parameter(Mandatory=$true)][object]$Recovery,
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][int]$Cycle
    )
    $health=$Recovery.Health
    $kiosk=if($null -ne $health){$health.Kiosk}else{$null}
    @(
        "cycle=$Cycle",
        'autonomous_recovery=true',
        "old_pid=$($Recovery.OldPid)",
        "new_pid=$($Recovery.NewPid)",
        "ready=$($Recovery.Ready)",
        "reason=$($Recovery.Reason)",
        "foreground_activity=$(if($null -ne $kiosk){$kiosk.ForegroundActivity}else{''})",
        "lock_task_active=$(if($null -ne $kiosk){$kiosk.IsLockTaskActive}else{$false})",
        "device_owner=$(if($null -ne $kiosk){$kiosk.IsDeviceOwner}else{$false})",
        "camera_active=$($Recovery.CameraActive)",
        "face_initialized=$($Recovery.FaceInitialized)",
        "loaded_face_count=$($Recovery.LoadedFaceCount)",
        "punch_ready=$($Recovery.PunchReady)"
    ) | Set-Content -Path $Path -Encoding UTF8
}

function Write-CameraFaceRecoveryHealthJson {
    param([Parameter(Mandatory=$true)][object]$Health,[Parameter(Mandatory=$true)][string]$Path)
    $kiosk=$Health.Kiosk
    $status=$Health.Status
    [pscustomobject]@{
        success=[bool]$Health.Ready
        database_active=[bool]$status.DatabaseActive
        token_valid=[bool]$status.TokenValid
        device_registered=[bool]$status.DeviceRegistered
        face_initialized=[bool]$Health.FaceInitialized
        loaded_face_count=[int]$Health.LoadedFaceCount
        punch_ready=[bool]$Health.PunchReady
        fixture_employee=[bool]$status.FixtureEmployee
        fixture_image=[bool]$status.FixtureImage
        camera_active=[bool]$Health.CameraActive
        foreground_activity=$kiosk.ForegroundActivity
        lock_task_active=[bool]$kiosk.IsLockTaskActive
        device_owner=[bool]$kiosk.IsDeviceOwner
    } | ConvertTo-Json -Depth 4 | Set-Content -Path $Path -Encoding UTF8
}
