Set-StrictMode -Version Latest

function Get-PunchUiDump {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [string]$OutputPath
    )

    $remotePath = "/sdcard/punch-harness-ui.xml"
    [void](Get-AdbOutput -Serial $Serial -Arguments @("shell", "uiautomator", "dump", $remotePath) -IgnoreFailure)
    $xmlText = Get-AdbOutput -Serial $Serial -Arguments @("shell", "cat", $remotePath) -IgnoreFailure
    if ($OutputPath) {
        Save-PerformanceRawText -Path $OutputPath -Text $xmlText
    }
    return $xmlText
}

function Get-UiNodeCenter {
    param(
        [string]$XmlText,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    if ([string]::IsNullOrWhiteSpace($XmlText)) { return $null }
    try {
        [xml]$xml = $XmlText
    }
    catch {
        return $null
    }

    $resourceId = "$PackageName`:id/nav_punch"
    $node = $xml.SelectSingleNode("//*[@resource-id='$resourceId']")
    if ($null -eq $node) {
        $node = $xml.SelectSingleNode("//*[@text='打卡']")
    }
    if ($null -eq $node) { return $null }

    $bounds = [string]$node.bounds
    $match = [Regex]::Match($bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $match.Success) { return $null }

    $left = [int]$match.Groups[1].Value
    $top = [int]$match.Groups[2].Value
    $right = [int]$match.Groups[3].Value
    $bottom = [int]$match.Groups[4].Value
    return [pscustomobject]@{
        X = [int][Math]::Round(($left + $right) / 2.0)
        Y = [int][Math]::Round(($top + $bottom) / 2.0)
        Bounds = $bounds
    }
}

function Open-PunchScreen {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory
    )

    New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
    $startOutput = Start-AndroidLauncherPackage `
        -Serial $Serial `
        -PackageName $PackageName `
        -OutputPath (Join-Path $EvidenceDirectory "launcher-start.txt") `
        -IgnoreFailure
    Start-Sleep -Milliseconds 1200

    $uiDumpPath = Join-Path $EvidenceDirectory "ui.xml"
    $uiDump = Get-PunchUiDump -Serial $Serial -PackageName $PackageName -OutputPath $uiDumpPath
    $center = Get-UiNodeCenter -XmlText $uiDump -PackageName $PackageName
    if ($null -ne $center) {
        $tapOutput = Get-AdbOutput -Serial $Serial -Arguments @("shell", "input", "tap", $center.X.ToString(), $center.Y.ToString()) -IgnoreFailure
        @(
            "x=$($center.X)",
            "y=$($center.Y)",
            "bounds=$($center.Bounds)",
            "adb_output=$tapOutput"
        ) | Set-Content -Path (Join-Path $EvidenceDirectory "nav-punch-tap.txt") -Encoding UTF8
        Start-Sleep -Milliseconds 1200
    }
    else {
        "nav_punch_not_found=true" | Set-Content -Path (Join-Path $EvidenceDirectory "nav-punch-tap.txt") -Encoding UTF8
        # MainActivity defaults to PunchFragment on a fresh launch. If the nav node is
        # unavailable (for example fullscreen mode), the punch-toggle probe remains authoritative.
        Start-Sleep -Milliseconds 800
    }

    $punchEnabled = Enable-PunchIfNeeded -Serial $Serial -PackageName $PackageName -EvidenceDirectory $EvidenceDirectory
    Start-Sleep -Milliseconds 800
    return $punchEnabled
}


function Enable-PunchIfNeeded {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory
    )

    $uiPath = Join-Path $EvidenceDirectory "ui-after-nav.xml"
    $xmlText = Get-PunchUiDump -Serial $Serial -PackageName $PackageName -OutputPath $uiPath
    if ([string]::IsNullOrWhiteSpace($xmlText)) {
        "found=false`r`nreason=ui_dump_empty" | Set-Content -Path (Join-Path $EvidenceDirectory "punch-toggle.txt") -Encoding UTF8
        return $false
    }

    try {
        [xml]$xml = $xmlText
    }
    catch {
        "found=false`r`nreason=ui_xml_invalid" | Set-Content -Path (Join-Path $EvidenceDirectory "punch-toggle.txt") -Encoding UTF8
        return $false
    }

    $resourceId = "$PackageName`:id/btn_punch_toggle"
    $node = $xml.SelectSingleNode("//*[@resource-id='$resourceId']")
    if ($null -eq $node) {
        $node = $xml.SelectSingleNode("//*[@text='打卡：关' or @text='打卡：开']")
    }
    if ($null -eq $node) {
        "found=false`r`nreason=btn_punch_toggle_not_found" | Set-Content -Path (Join-Path $EvidenceDirectory "punch-toggle.txt") -Encoding UTF8
        return $false
    }

    $text = [string]$node.text
    if ($text -eq "打卡：开") {
        @(
            "found=true",
            "before=打卡：开",
            "action=already_enabled",
            "after=打卡：开"
        ) | Set-Content -Path (Join-Path $EvidenceDirectory "punch-toggle.txt") -Encoding UTF8
        return $true
    }

    $bounds = [string]$node.bounds
    $match = [Regex]::Match($bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $match.Success) {
        @(
            "found=true",
            "before=$text",
            "action=none",
            "reason=invalid_bounds",
            "bounds=$bounds"
        ) | Set-Content -Path (Join-Path $EvidenceDirectory "punch-toggle.txt") -Encoding UTF8
        return $false
    }

    $x = [int][Math]::Round(([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2.0)
    $y = [int][Math]::Round(([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2.0)
    $tapOutput = Get-AdbOutput -Serial $Serial -Arguments @("shell", "input", "tap", $x.ToString(), $y.ToString()) -IgnoreFailure
    Start-Sleep -Milliseconds 1200

    $afterXml = Get-PunchUiDump -Serial $Serial -PackageName $PackageName -OutputPath (Join-Path $EvidenceDirectory "ui-after-toggle.xml")
    $afterText = ""
    if (-not [string]::IsNullOrWhiteSpace($afterXml)) {
        try {
            [xml]$afterDoc = $afterXml
            $afterNode = $afterDoc.SelectSingleNode("//*[@resource-id='$resourceId']")
            if ($null -eq $afterNode) {
                $afterNode = $afterDoc.SelectSingleNode("//*[@text='打卡：关' or @text='打卡：开']")
            }
            if ($null -ne $afterNode) { $afterText = [string]$afterNode.text }
        }
        catch {
            $afterText = "<invalid-ui-xml>"
        }
    }

    @(
        "found=true",
        "before=$text",
        "action=tap_enable",
        "x=$x",
        "y=$y",
        "bounds=$bounds",
        "adb_output=$tapOutput",
        "after=$afterText"
    ) | Set-Content -Path (Join-Path $EvidenceDirectory "punch-toggle.txt") -Encoding UTF8

    return ($afterText -eq "打卡：开")
}

function Get-ResumedActivityInfo {
    param(
        [Parameter(Mandatory = $true)][string]$ActivityDump,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    $escaped = [Regex]::Escape($PackageName)
    $lineMatch = [Regex]::Match(
        $ActivityDump,
        "(?im)^.*(?:mResumedActivity|ResumedActivity|topResumedActivity).*?$escaped/[^\s}]+.*$"
    )
    $line = if ($lineMatch.Success) { $lineMatch.Value.Trim() } else { "" }
    $mainForeground = $line -match ("(?i)" + $escaped + "/(?:\.activity\.)?MainActivity\b")
    return [pscustomobject]@{
        PackageForeground = [bool]$lineMatch.Success
        MainActivityForeground = [bool]$mainForeground
        ResumedLine = $line
    }
}

function Test-CameraDumpShowsActivePackage {
    param(
        [string]$CameraDump,
        [Parameter(Mandatory = $true)][string]$PackageName
    )
    if ([string]::IsNullOrWhiteSpace($CameraDump)) { return $false }
    $escaped = [Regex]::Escape($PackageName)
    $patterns = @(
        "(?is)Device\s+[^\r\n]+\s+is\s+open.*?(?:Client package name|Package name|clientPackageName)\s*[:=]\s*$escaped\b",
        "(?im)^\s*(?:Client package name|Package name|clientPackageName)\s*[:=]\s*$escaped\b",
        "(?is)(?:active camera clients|camera client|client info).*?$escaped\b"
    )
    foreach ($pattern in $patterns) {
        if ($CameraDump -match $pattern) { return $true }
    }
    return $false
}

function Get-AppProcMapsBestEffort {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][int]$AppProcessId
    )

    $direct = Get-AdbOutput -Serial $Serial -Arguments @("shell", "cat", "/proc/$AppProcessId/maps") -IgnoreFailure
    if (-not [string]::IsNullOrWhiteSpace($direct) -and $direct -notmatch '(?i)permission denied|not permitted|no such file') {
        return [pscustomobject]@{ Supported = $true; Method = "shell"; Text = $direct }
    }

    $runAs = Get-AdbOutput -Serial $Serial -Arguments @("shell", "run-as", $PackageName, "cat", "/proc/$AppProcessId/maps") -IgnoreFailure
    if (-not [string]::IsNullOrWhiteSpace($runAs) -and $runAs -notmatch '(?i)permission denied|not permitted|not debuggable|unknown package|no such file') {
        return [pscustomobject]@{ Supported = $true; Method = "run-as"; Text = $runAs }
    }

    $combined = "direct:`r`n$direct`r`n`r`nrun-as:`r`n$runAs"
    return [pscustomobject]@{ Supported = $false; Method = "unsupported"; Text = $combined }
}


function Get-CameraFaceRuntimeStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    $action = "$PackageName.action.CAMERA_FACE_SOAK_STATUS"
    $component = "$PackageName/.receiver.CameraFaceSoakStatusReceiver"
    $output = Get-AdbOutput -Serial $Serial -Arguments @(
        "shell", "am", "broadcast", "--receiver-foreground",
        "-a", $action,
        "-n", $component
    ) -IgnoreFailure

    $initialized = $null
    $loadedFaceCount = $null
    $match = [regex]::Match(
        $output,
        '(?i)face_initialized=(true|false);loaded_face_count=(-?\d+)'
    )
    if ($match.Success) {
        $initialized = $match.Groups[1].Value -ieq "true"
        $loadedFaceCount = [int]$match.Groups[2].Value
        return [pscustomobject]@{
            Supported = $true
            Method = "cameraFaceSoakStatusReceiver"
            Initialized = [bool]$initialized
            LoadedFaceCount = $loadedFaceCount
            Text = $output
        }
    }

    return [pscustomobject]@{
        Supported = $false
        Method = "cameraFaceSoakStatusReceiver"
        Initialized = $null
        LoadedFaceCount = $null
        Text = $output
    }
}

function Get-CameraFaceHealthSample {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][object]$PerformanceSample,
        [string]$RawRoot
    )

    $sampleDirectory = $null
    if ($RawRoot) {
        $sampleDirectory = Join-Path $RawRoot ("{0:D4}" -f [int]$PerformanceSample.Index)
        New-Item -ItemType Directory -Path $sampleDirectory -Force | Out-Null
    }

    $activityDump = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "activity", "activities") -IgnoreFailure
    $cameraDump = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "media.camera") -IgnoreFailure
    $activityInfo = Get-ResumedActivityInfo -ActivityDump $activityDump -PackageName $PackageName

    $procMapsSupported = $false
    $procMapsMethod = "process-missing"
    $procMapsText = ""
    $bdFaceLoaded = $false
    $onnxLoaded = $false
    $runtimeProbeSupported = $false
    $runtimeProbeMethod = "process-missing"
    $runtimeProbeText = ""
    $faceRuntimeInitialized = $null
    $loadedFaceCount = $null
    if ($PerformanceSample.ProcessAlive -and $null -ne $PerformanceSample.Pid) {
        # Keep /proc maps as diagnostic evidence only. Android may map native libraries
        # directly from base.apk, hiding the embedded .so entry names.
        $mapsResult = Get-AppProcMapsBestEffort -Serial $Serial -PackageName $PackageName -AppProcessId ([int]$PerformanceSample.Pid)
        $procMapsSupported = [bool]$mapsResult.Supported
        $procMapsMethod = [string]$mapsResult.Method
        $procMapsText = [string]$mapsResult.Text
        if ($procMapsSupported) {
            $bdFaceLoaded = $procMapsText -match '(?i)libbdface_sdk\.so'
            $onnxLoaded = $procMapsText -match '(?i)libonnxruntime\.so'
        }

        $runtimeStatus = Get-CameraFaceRuntimeStatus -Serial $Serial -PackageName $PackageName
        $runtimeProbeSupported = [bool]$runtimeStatus.Supported
        $runtimeProbeMethod = [string]$runtimeStatus.Method
        $runtimeProbeText = [string]$runtimeStatus.Text
        $faceRuntimeInitialized = $runtimeStatus.Initialized
        $loadedFaceCount = $runtimeStatus.LoadedFaceCount
    }

    $cameraActive = Test-CameraDumpShowsActivePackage -CameraDump $cameraDump -PackageName $PackageName
    # Hard Face readiness is the app's own runtime state, not process-map filenames.
    $faceSdkLoaded = if ($runtimeProbeSupported) { [bool]$faceRuntimeInitialized } else { $null }

    if ($sampleDirectory) {
        Save-PerformanceRawText -Path (Join-Path $sampleDirectory "activity.txt") -Text $activityDump
        Save-PerformanceRawText -Path (Join-Path $sampleDirectory "camera.txt") -Text $cameraDump
        Save-PerformanceRawText -Path (Join-Path $sampleDirectory "proc-maps.txt") -Text $procMapsText
        Save-PerformanceRawText -Path (Join-Path $sampleDirectory "face-runtime-status.txt") -Text $runtimeProbeText
        @(
            "package_foreground=$($activityInfo.PackageForeground)",
            "main_activity_foreground=$($activityInfo.MainActivityForeground)",
            "camera_active=$cameraActive",
            "face_sdk_probe_supported=$runtimeProbeSupported",
            "face_sdk_probe_method=$runtimeProbeMethod",
            "face_runtime_initialized=$faceRuntimeInitialized",
            "loaded_face_count=$loadedFaceCount",
            "face_sdk_loaded=$faceSdkLoaded",
            "native_map_probe_supported=$procMapsSupported",
            "native_map_probe_method=$procMapsMethod",
            "bdface_mapped=$bdFaceLoaded",
            "onnx_mapped=$onnxLoaded",
            "resumed_line=$($activityInfo.ResumedLine)"
        ) | Set-Content -Path (Join-Path $sampleDirectory "camera-face-health.txt") -Encoding UTF8
    }

    return [pscustomobject]@{
        PackageForeground = [bool]$activityInfo.PackageForeground
        MainActivityForeground = [bool]$activityInfo.MainActivityForeground
        ResumedActivityLine = [string]$activityInfo.ResumedLine
        CameraActive = [bool]$cameraActive
        FaceSdkProbeSupported = [bool]$runtimeProbeSupported
        FaceSdkProbeMethod = $runtimeProbeMethod
        FaceRuntimeInitialized = $faceRuntimeInitialized
        LoadedFaceCount = $loadedFaceCount
        FaceSdkLoaded = $faceSdkLoaded
        NativeMapProbeSupported = [bool]$procMapsSupported
        NativeMapProbeMethod = $procMapsMethod
        BdFaceSdkLoaded = [bool]$bdFaceLoaded
        OnnxRuntimeLoaded = [bool]$onnxLoaded
    }
}

function Add-CameraFaceHealthToSample {
    param(
        [Parameter(Mandatory = $true)][object]$PerformanceSample,
        [Parameter(Mandatory = $true)][object]$HealthSample
    )
    foreach ($name in @(
        "PackageForeground", "MainActivityForeground", "ResumedActivityLine", "CameraActive",
        "FaceSdkProbeSupported", "FaceSdkProbeMethod", "FaceRuntimeInitialized", "LoadedFaceCount", "FaceSdkLoaded",
        "NativeMapProbeSupported", "NativeMapProbeMethod", "BdFaceSdkLoaded", "OnnxRuntimeLoaded"
    )) {
        $PerformanceSample | Add-Member -NotePropertyName $name -NotePropertyValue $HealthSample.$name -Force
    }
    return $PerformanceSample
}

function Wait-CameraFaceReady {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [ValidateRange(3, 120)][int]$TimeoutSeconds = 20,
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory
    )

    New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
    $start = Get-Date
    $attempt = 0
    $last = $null
    while (((Get-Date) - $start).TotalSeconds -lt $TimeoutSeconds) {
        $pidText = Get-AdbOutput -Serial $Serial -Arguments @("shell", "pidof", $PackageName) -IgnoreFailure
        $appPid = $null
        if (-not [string]::IsNullOrWhiteSpace($pidText)) {
            $token = ($pidText.Trim() -split '\s+')[0]
            if ($token -match '^\d+$') { $appPid = [int]$token }
        }
        $probeSample = [pscustomobject]@{
            Index = $attempt
            ProcessAlive = ($null -ne $appPid)
            Pid = $appPid
        }
        $probeRoot = Join-Path $EvidenceDirectory "probes"
        $last = Get-CameraFaceHealthSample -Serial $Serial -PackageName $PackageName -PerformanceSample $probeSample -RawRoot $probeRoot

        # Fragment visibility/resume callbacks can reset punchEnabled after the first navigation tap.
        # Self-heal on every readiness attempt instead of trusting the one-shot preflight click.
        if ($last.MainActivityForeground -and -not $last.CameraActive) {
            $toggleEvidenceDirectory = Join-Path $EvidenceDirectory ("toggle-attempt-{0:D2}" -f $attempt)
            [void](Enable-PunchIfNeeded -Serial $Serial -PackageName $PackageName -EvidenceDirectory $toggleEvidenceDirectory)
            Start-Sleep -Milliseconds 900
            $last = Get-CameraFaceHealthSample -Serial $Serial -PackageName $PackageName -PerformanceSample $probeSample -RawRoot $probeRoot
        }

        $faceReady = $last.FaceSdkProbeSupported -and ($last.FaceRuntimeInitialized -eq $true)
        if ($last.MainActivityForeground -and $last.CameraActive -and $faceReady) {
            @(
                "ready=true",
                "attempts=$($attempt + 1)",
                "camera_active=$($last.CameraActive)",
                "main_activity_foreground=$($last.MainActivityForeground)",
                "face_sdk_probe_supported=$($last.FaceSdkProbeSupported)",
                "face_runtime_initialized=$($last.FaceRuntimeInitialized)",
                "loaded_face_count=$($last.LoadedFaceCount)",
                "face_sdk_loaded=$($last.FaceSdkLoaded)"
            ) | Set-Content -Path (Join-Path $EvidenceDirectory "ready.txt") -Encoding UTF8
            return $last
        }
        $attempt += 1
        Start-Sleep -Seconds 1
    }

    @(
        "ready=false",
        "attempts=$attempt",
        "camera_active=$(if ($null -ne $last) { $last.CameraActive } else { 'N/A' })",
        "main_activity_foreground=$(if ($null -ne $last) { $last.MainActivityForeground } else { 'N/A' })",
        "face_sdk_probe_supported=$(if ($null -ne $last) { $last.FaceSdkProbeSupported } else { 'N/A' })",
        "face_runtime_initialized=$(if ($null -ne $last) { $last.FaceRuntimeInitialized } else { 'N/A' })",
        "loaded_face_count=$(if ($null -ne $last) { $last.LoadedFaceCount } else { 'N/A' })",
        "face_sdk_loaded=$(if ($null -ne $last) { $last.FaceSdkLoaded } else { 'N/A' })"
    ) | Set-Content -Path (Join-Path $EvidenceDirectory "ready.txt") -Encoding UTF8
    return $null
}

function Find-CameraFaceHealthEvents {
    param(
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    if (-not (Test-Path $LogPath)) {
        "<logcat missing>" | Set-Content -Path $OutputPath -Encoding UTF8
        return 0
    }
    $patterns = @(
        'PunchFragment.*openCamera error',
        'FaceSDKManager.*(?:error|failed|exception)',
        'FaceManager.*(?:SDK license failed|SDK model failed)',
        'PunchApplication.*Face SDK init error',
        'CameraService.*(?:evict|fatal|error).*com\.punch\.app'
    )
    $matches = @(Select-String -Path $LogPath -Pattern $patterns -AllMatches -ErrorAction SilentlyContinue)
    if ($matches.Count -eq 0) {
        "<none>" | Set-Content -Path $OutputPath -Encoding UTF8
        return 0
    }
    @($matches | ForEach-Object { $_.Line }) | Set-Content -Path $OutputPath -Encoding UTF8
    return $matches.Count
}

function Get-CameraFaceSoakAnalysis {
    param(
        [Parameter(Mandatory = $true)][object[]]$Samples,
        [ValidateRange(0, 1440)][int]$WarmupMinutes = 15,
        [ValidateRange(1, 20)][int]$StableWindowSize = 5,
        [double]$MaxPssGrowthPercent = 5.0,
        [double]$MaxNativeHeapGrowthPercent = 5.0,
        [double]$MaxJavaHeapGrowthPercent = 20.0,
        [int]$MaxThreadGrowth = 10,
        [int]$MaxFdGrowth = 20,
        [int]$FatalEventCount = 0,
        [int]$GcEventCount = 0,
        [int]$CameraFaceErrorEventCount = 0,
        [switch]$RequireFaceSdkProbe
    )

    $base = Get-MemorySoakAnalysis `
        -Samples $Samples `
        -WarmupMinutes $WarmupMinutes `
        -StableWindowSize $StableWindowSize `
        -MaxPssGrowthPercent $MaxPssGrowthPercent `
        -MaxNativeHeapGrowthPercent $MaxNativeHeapGrowthPercent `
        -MaxJavaHeapGrowthPercent $MaxJavaHeapGrowthPercent `
        -MaxThreadGrowth $MaxThreadGrowth `
        -MaxFdGrowth $MaxFdGrowth `
        -FatalEventCount $FatalEventCount `
        -GcEventCount $GcEventCount

    $stable = @($Samples | Where-Object { $_.ProcessAlive -and [double]$_.ElapsedSeconds -ge ($WarmupMinutes * 60.0) })
    $cameraInactiveCount = @($stable | Where-Object { $_.CameraActive -ne $true }).Count
    $foregroundMissingCount = @($stable | Where-Object { $_.MainActivityForeground -ne $true }).Count
    $faceProbeSamples = @($stable | Where-Object { $_.FaceSdkProbeSupported -eq $true })
    $faceMissingCount = @($stable | Where-Object { $_.FaceRuntimeInitialized -ne $true }).Count
    $faceProbeUnsupportedCount = @($stable | Where-Object { $_.FaceSdkProbeSupported -ne $true }).Count
    $faceProbeSupported = $faceProbeSamples.Count -gt 0

    $reasons = New-Object System.Collections.Generic.List[string]
    foreach ($reason in $base.FailureReasons) { [void]$reasons.Add([string]$reason) }
    if ($cameraInactiveCount -gt 0) { [void]$reasons.Add("Camera was not reported active in $cameraInactiveCount stable sample(s).") }
    if ($foregroundMissingCount -gt 0) { [void]$reasons.Add("MainActivity was not foreground in $foregroundMissingCount stable sample(s).") }
    if ($faceMissingCount -gt 0) { [void]$reasons.Add("Face runtime was not initialized in $faceMissingCount stable sample(s).") }
    if ($faceProbeUnsupportedCount -gt 0) { [void]$reasons.Add("Face runtime status probe was unavailable in $faceProbeUnsupportedCount stable sample(s).") }
    if ($CameraFaceErrorEventCount -gt 0) { [void]$reasons.Add("Detected $CameraFaceErrorEventCount camera/face health error event(s).") }

    $result = [ordered]@{}
    foreach ($property in $base.PSObject.Properties) { $result[$property.Name] = $property.Value }
    $result["CameraInactiveStableSampleCount"] = $cameraInactiveCount
    $result["ForegroundMissingStableSampleCount"] = $foregroundMissingCount
    $result["FaceSdkProbeSupported"] = $faceProbeSupported
    $result["FaceSdkProbeSampleCount"] = $faceProbeSamples.Count
    $result["FaceSdkProbeUnsupportedStableSampleCount"] = $faceProbeUnsupportedCount
    $result["FaceSdkMissingStableSampleCount"] = $faceMissingCount
    $result["CameraFaceErrorEventCount"] = $CameraFaceErrorEventCount
    $result["FailureReasons"] = $reasons.ToArray()
    $result["Status"] = if ($reasons.Count -eq 0) { "PASS" } else { "FAIL" }
    return [pscustomobject]$result
}

function Write-CameraFaceSummary {
    param(
        [Parameter(Mandatory = $true)][object]$Analysis,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][double]$RequestedMinutes,
        [Parameter(Mandatory = $true)][double]$ActualMinutes,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($line in @(
        "Punch App Camera + Face SDK Soak V2.2",
        "status=$($Analysis.Status)",
        "serial=$Serial",
        "package=$PackageName",
        "requested_minutes=$(Convert-PerformanceValueToText $RequestedMinutes)",
        "actual_minutes=$(Convert-PerformanceValueToText $ActualMinutes)",
        "sample_count=$($Analysis.SampleCount)",
        "stable_sample_count=$($Analysis.StableSampleCount)",
        "process_restart_count=$($Analysis.ProcessRestartCount)",
        "camera_inactive_stable_samples=$($Analysis.CameraInactiveStableSampleCount)",
        "foreground_missing_stable_samples=$($Analysis.ForegroundMissingStableSampleCount)",
        "face_runtime_probe_supported=$($Analysis.FaceSdkProbeSupported)",
        "face_runtime_probe_samples=$($Analysis.FaceSdkProbeSampleCount)",
        "face_runtime_not_initialized_stable_samples=$($Analysis.FaceSdkMissingStableSampleCount)",
        "camera_face_error_events=$($Analysis.CameraFaceErrorEventCount)",
        "pss_growth_percent=$(Convert-PerformanceValueToText $Analysis.PssGrowthPercent)",
        "pss_slope_mb_per_hour=$(Convert-PerformanceValueToText $Analysis.PssSlopeMbPerHour)",
        "java_heap_growth_percent=$(Convert-PerformanceValueToText $Analysis.JavaHeapGrowthPercent)",
        "native_heap_growth_percent=$(Convert-PerformanceValueToText $Analysis.NativeHeapGrowthPercent)",
        "native_heap_slope_mb_per_hour=$(Convert-PerformanceValueToText $Analysis.NativeHeapSlopeMbPerHour)",
        "thread_growth=$(Convert-PerformanceValueToText $Analysis.ThreadGrowth)",
        "fd_growth=$(Convert-PerformanceValueToText $Analysis.FdGrowth)",
        "cpu_average_percent=$(Convert-PerformanceValueToText $Analysis.CpuAveragePercent)",
        "cpu_p95_percent=$(Convert-PerformanceValueToText $Analysis.CpuP95Percent)",
        "battery_temperature_max_c=$(Convert-PerformanceValueToText $Analysis.BatteryTemperatureMaxC)",
        "thermal_max_c=$(Convert-PerformanceValueToText $Analysis.ThermalMaxC)",
        "finished_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
    )) { [void]$lines.Add($line) }
    if ($Analysis.FailureReasons.Count -gt 0) {
        [void]$lines.Add("failure_reasons:")
        foreach ($reason in $Analysis.FailureReasons) { [void]$lines.Add("- $reason") }
    }
    else {
        [void]$lines.Add("failure_reasons=<none>")
    }
    $lines | Set-Content -Path $OutputPath -Encoding UTF8
}

function Write-CameraFaceHtmlReport {
    param(
        [Parameter(Mandatory = $true)][object[]]$Samples,
        [Parameter(Mandatory = $true)][object]$Analysis,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )

    $failureHtml = if ($Analysis.FailureReasons.Count -eq 0) { "<li>None</li>" } else {
        (@($Analysis.FailureReasons | ForEach-Object { "<li>$(Convert-PerformanceHtmlText $_)</li>" }) -join "`r`n")
    }
    $rows = New-Object System.Collections.Generic.List[string]
    foreach ($sample in $Samples) {
        [void]$rows.Add("<tr><td>$($sample.Index)</td><td>$(Convert-PerformanceHtmlText $sample.Timestamp)</td><td>$(Convert-PerformanceHtmlText $sample.Pid)</td><td>$(Convert-PerformanceHtmlText $sample.TotalPssMb)</td><td>$(Convert-PerformanceHtmlText $sample.JavaHeapMb)</td><td>$(Convert-PerformanceHtmlText $sample.NativeHeapMb)</td><td>$(Convert-PerformanceHtmlText $sample.RssMb)</td><td>$(Convert-PerformanceHtmlText $sample.CpuPercent)</td><td>$($sample.ThreadCount)</td><td>$($sample.FdCount)</td><td>$($sample.MainActivityForeground)</td><td>$($sample.CameraActive)</td><td>$($sample.FaceSdkProbeSupported)</td><td>$(Convert-PerformanceHtmlText $sample.FaceSdkLoaded)</td></tr>")
    }
    $html = @"
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Camera + Face SDK Soak V2.2</title>
<style>body{font-family:Arial,"Microsoft YaHei",sans-serif;margin:24px}table{border-collapse:collapse;width:100%;font-size:12px}th,td{border:1px solid #ccc;padding:5px;text-align:right}.cards{display:grid;grid-template-columns:repeat(4,minmax(150px,1fr));gap:10px}.card{border:1px solid #ddd;border-radius:6px;padding:10px}</style></head>
<body><h1>Punch App Camera + Face SDK Soak V2.2</h1><p>Serial: <b>$(Convert-PerformanceHtmlText $Serial)</b> Package: <b>$(Convert-PerformanceHtmlText $PackageName)</b></p>
<div class="cards"><div class="card">Status<br><b>$($Analysis.Status)</b></div><div class="card">PSS growth<br><b>$(Convert-PerformanceHtmlText $Analysis.PssGrowthPercent)%</b></div><div class="card">Native growth<br><b>$(Convert-PerformanceHtmlText $Analysis.NativeHeapGrowthPercent)%</b></div><div class="card">CPU p95<br><b>$(Convert-PerformanceHtmlText $Analysis.CpuP95Percent)%</b></div><div class="card">Camera inactive<br><b>$($Analysis.CameraInactiveStableSampleCount)</b></div><div class="card">Foreground misses<br><b>$($Analysis.ForegroundMissingStableSampleCount)</b></div><div class="card">Face runtime probe<br><b>$($Analysis.FaceSdkProbeSupported)</b></div><div class="card">Health errors<br><b>$($Analysis.CameraFaceErrorEventCount)</b></div></div>
<h2>Failure reasons</h2><ul>$failureHtml</ul>
<h2>Samples</h2><table><thead><tr><th>#</th><th>Time</th><th>PID</th><th>PSS</th><th>Java</th><th>Native</th><th>RSS</th><th>CPU%</th><th>Threads</th><th>FD</th><th>Main fg</th><th>Camera</th><th>Face probe</th><th>Face loaded</th></tr></thead><tbody>$($rows -join "`r`n")</tbody></table></body></html>
"@
    $html | Set-Content -Path $OutputPath -Encoding UTF8
}
