Set-StrictMode -Version Latest

function Invoke-NetworkFaultBroadcast {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][ValidateSet('RUN','STATUS','CLEANUP','PREPARE_OFFLINE','OFFLINE_SYNC','RECOVER_OFFLINE')][string]$Action,
        [string]$Scenario='AllSafe'
    )
    $component = "$PackageName/.network.NetworkFaultTestReceiver"
    $actionName = "$PackageName.networkfault.$Action"
    $args = @('shell','am','broadcast','--receiver-foreground','-a',$actionName,'-n',$component)
    if ($Action -eq 'RUN') { $args += @('--es','scenario',$Scenario) }
    return Get-AdbOutput -Serial $Serial -Arguments $args -IgnoreFailure
}

function Get-NetworkFaultStatus {
    param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$PackageName)
    $text = Invoke-NetworkFaultBroadcast -Serial $Serial -PackageName $PackageName -Action STATUS
    $m = [regex]::Match($text, '(?i)busy=(true|false);state=([^;]*);scenario=([^;]*);error=([^;]*);database_prepared=(true|false);token_valid=(true|false);device_registered=(true|false);base_url=([^"\r\n]*)')
    return [pscustomobject]@{
        Supported = $m.Success
        Busy = if($m.Success){$m.Groups[1].Value -ieq 'true'}else{$false}
        State = if($m.Success){$m.Groups[2].Value}else{''}
        Scenario = if($m.Success){$m.Groups[3].Value}else{''}
        Error = if($m.Success){$m.Groups[4].Value}else{''}
        DatabasePrepared = if($m.Success){$m.Groups[5].Value -ieq 'true'}else{$false}
        TokenValid = if($m.Success){$m.Groups[6].Value -ieq 'true'}else{$false}
        DeviceRegistered = if($m.Success){$m.Groups[7].Value -ieq 'true'}else{$false}
        BaseUrl = if($m.Success){$m.Groups[8].Value.Trim()}else{''}
        Raw = $text
    }
}

function Wait-NetworkFaultState {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][string[]]$ExpectedStates,
        [ValidateRange(5,600)][int]$TimeoutSeconds=120
    )
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    $last=$null
    do {
        $last=Get-NetworkFaultStatus -Serial $Serial -PackageName $PackageName
        if($last.Supported -and $last.State -in $ExpectedStates){ return $last }
        Start-Sleep -Milliseconds 500
    } while((Get-Date) -lt $deadline)
    return $last
}

function Get-NetworkFaultTestFile {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][string]$RelativePath
    )
    return Get-AdbOutput -Serial $Serial -Arguments @('shell','run-as',$PackageName,'cat',"files/network-fault-v25/$RelativePath") -IgnoreFailure
}

function Capture-DeviceNetworkState {
    param([Parameter(Mandatory=$true)][string]$Serial)
    $wifi=(Get-AdbOutput -Serial $Serial -Arguments @('shell','settings','get','global','wifi_on') -IgnoreFailure).Trim()
    $data=(Get-AdbOutput -Serial $Serial -Arguments @('shell','settings','get','global','mobile_data') -IgnoreFailure).Trim()
    return [pscustomobject]@{
        WifiEnabled = $wifi -eq '1'
        MobileDataEnabled = $data -eq '1'
        WifiRaw = $wifi
        MobileDataRaw = $data
    }
}

function Disable-DeviceNetwork {
    param([Parameter(Mandatory=$true)][string]$Serial)
    [void](Get-AdbOutput -Serial $Serial -Arguments @('shell','svc','wifi','disable') -IgnoreFailure)
    [void](Get-AdbOutput -Serial $Serial -Arguments @('shell','svc','data','disable') -IgnoreFailure)
}

function Restore-DeviceNetworkState {
    param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)]$State)
    if($State.WifiEnabled){ [void](Get-AdbOutput -Serial $Serial -Arguments @('shell','svc','wifi','enable') -IgnoreFailure) }
    else { [void](Get-AdbOutput -Serial $Serial -Arguments @('shell','svc','wifi','disable') -IgnoreFailure) }
    if($State.MobileDataEnabled){ [void](Get-AdbOutput -Serial $Serial -Arguments @('shell','svc','data','enable') -IgnoreFailure) }
    else { [void](Get-AdbOutput -Serial $Serial -Arguments @('shell','svc','data','disable') -IgnoreFailure) }
}
