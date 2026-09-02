Set-StrictMode -Version Latest

function Invoke-FacePunchStressBroadcast {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][ValidateSet('RUN','STATUS','CLEANUP')][string]$Action,
        [ValidateSet('Face','Punch','All')][string]$Mode='All',
        [int]$Count=100
    )
    $component = "$PackageName/.stress.FacePunchStressReceiver"
    $actionName = "$PackageName.stress.$Action"
    $args = @('shell','am','broadcast','--receiver-foreground','-a',$actionName,'-n',$component)
    if ($Action -eq 'RUN') {
        $args += @('--es','mode',$Mode,'--ei','count',$Count.ToString())
    }
    return Get-AdbOutput -Serial $Serial -Arguments $args -IgnoreFailure
}

function Get-FacePunchStressStatus {
    param([string]$Serial,[string]$PackageName)
    $text = Invoke-FacePunchStressBroadcast -Serial $Serial -PackageName $PackageName -Action STATUS
    $state = ''
    $busy = $false
    $errorText = ''
    $faceInitialized = $false
    $punchDataPreparing = $false
    $punchDataReady = $false
    $m = [regex]::Match($text, '(?i)busy=(true|false);state=([^;]*);error=([^;]*);face_initialized=(true|false);loaded_face_count=(-?\d+);punch_data_preparing=(true|false);punch_data_ready=(true|false)')
    if ($m.Success) {
        $busy = $m.Groups[1].Value -ieq 'true'
        $state = $m.Groups[2].Value
        $errorText = $m.Groups[3].Value
        $faceInitialized = $m.Groups[4].Value -ieq 'true'
        $loaded = [int]$m.Groups[5].Value
        $punchDataPreparing = $m.Groups[6].Value -ieq 'true'
        $punchDataReady = $m.Groups[7].Value -ieq 'true'
    } else { $loaded = -1 }
    return [pscustomobject]@{
        Supported=$m.Success; Busy=$busy; State=$state; Error=$errorText;
        FaceInitialized=$faceInitialized; LoadedFaceCount=$loaded;
        PunchDataPreparing=$punchDataPreparing; PunchDataReady=$punchDataReady; Raw=$text
    }
}

function Get-FacePunchStressFile {
    param([string]$Serial,[string]$PackageName,[string]$RelativePath)
    return Get-AdbOutput -Serial $Serial -Arguments @('shell','run-as',$PackageName,'cat',"files/face_punch_stress/$RelativePath") -IgnoreFailure
}

function Wait-FacePunchStressReady {
    param([string]$Serial,[string]$PackageName,[string]$Mode,[int]$TimeoutSeconds=60)
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $s=Get-FacePunchStressStatus -Serial $Serial -PackageName $PackageName
        if ($s.Supported -and (($Mode -eq 'Punch') -or ($s.FaceInitialized -and -not $s.PunchDataPreparing))) { return $s }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $s
}

function Convert-StressJsonToCsv {
    param([Parameter(Mandatory=$true)]$JsonObject,[Parameter(Mandatory=$true)][string]$Path)
    $rows=@()
    if ($null -ne $JsonObject.iterations) {
        foreach ($row in $JsonObject.iterations) {
            $rows += [pscustomobject]@{Index=$row.index;LatencyMs=$row.latency_ms;Outcome=$row.outcome}
        }
    }
    $rows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $Path
}
