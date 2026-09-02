Set-StrictMode -Version Latest

function Save-ReleaseGateEvidence {
    param([string]$Path, [string[]]$Lines)
    $parent = Split-Path -Parent $Path
    if ($parent -and -not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $Lines | Set-Content -Path $Path -Encoding UTF8
}

function Invoke-ReleaseSourceIsolationAudit {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$OutputDirectory
    )

    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $failures = New-Object System.Collections.Generic.List[string]
    $evidence = New-Object System.Collections.Generic.List[string]

    $mainManifest = Join-Path $ProjectRoot 'app\src\main\AndroidManifest.xml'
    $manifestText = Get-Content -Path $mainManifest -Raw -Encoding UTF8
    $forbiddenMainTokens = @(
        'CameraFaceRecoveryTest', 'ProcessRecoveryTest', 'DbStressTest',
        'NetworkFaultTest', 'FacePunchStress', 'DeviceOwnerTestControlReceiver'
    )
    foreach ($token in $forbiddenMainTokens) {
        if ($manifestText.Contains($token)) { [void]$failures.Add("main manifest contains test-only token: $token") }
        else { [void]$evidence.Add("PASS main manifest excludes $token") }
    }

    $flagsPath = Join-Path $ProjectRoot 'app\src\main\res\values\test_flags.xml'
    $flagsText = Get-Content -Path $flagsPath -Raw -Encoding UTF8
    foreach ($flag in @('camera_face_soak_auto_enable','face_punch_stress_bypass_prechecks')) {
        $expectedFlag = '<bool name="' + $flag + '">false</bool>'
        if (-not $flagsText.Contains($expectedFlag)) {
            [void]$failures.Add("production test flag is not false: $flag")
        } else { [void]$evidence.Add("PASS production default false: $flag") }
    }

    $gradlePath = Join-Path $ProjectRoot 'app\build.gradle'
    $gradleText = Get-Content -Path $gradlePath -Raw -Encoding UTF8
    $releaseBlock = [regex]::Match($gradleText, '(?s)release\s*\{(.*?)\n\s*\}')
    if (-not $releaseBlock.Success) { [void]$failures.Add('release buildType block was not found') }
    elseif ($releaseBlock.Groups[1].Value -match 'matchingFallbacks') { [void]$failures.Add('release buildType must not use matchingFallbacks') }
    else { [void]$evidence.Add('PASS release buildType has no matchingFallbacks') }

    $punchFragment = Join-Path $ProjectRoot 'app\src\main\java\com\punch\app\fragment\PunchFragment.java'
    $punchText = Get-Content -Path $punchFragment -Raw -Encoding UTF8
    if ($punchText -notmatch '(?s)PunchPersistence\.persist\s*\(.*?Constants\.ACTION_PUNCH_PUSH\s*\)') {
        [void]$failures.Add('production PunchPersistence.persist call does not clearly use ACTION_PUNCH_PUSH')
    } else { [void]$evidence.Add('PASS PunchPersistence.persist uses ACTION_PUNCH_PUSH') }

    $mainJava = Join-Path $ProjectRoot 'app\src\main\java'
    foreach ($token in @('RECOVERY_FACE_V273','STRESS_FACE_V23')) {
        $hits = @(Get-ChildItem -Path $mainJava -Recurse -File -Filter '*.java' | Select-String -Pattern $token -SimpleMatch)
        if ($hits.Count -gt 0) { [void]$failures.Add("src/main contains test-only action token: $token") }
        else { [void]$evidence.Add("PASS src/main excludes action token $token") }
    }

    $lines = @('FaceAPK Release Source Isolation Audit') + @($evidence) + @($failures | ForEach-Object { "FAIL $_" })
    Save-ReleaseGateEvidence -Path (Join-Path $OutputDirectory 'source-isolation-audit.txt') -Lines $lines
    return [pscustomobject]@{ Success = ($failures.Count -eq 0); Failures = @($failures); Evidence = @($evidence) }
}

function Get-ReleaseApkPath {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)
    $releaseDir = Join-Path $ProjectRoot 'app\build\outputs\apk\release'
    if (-not (Test-Path $releaseDir)) { return $null }
    return Get-ChildItem -Path $releaseDir -Filter '*.apk' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

function Invoke-ReleaseApkIsolationAudit {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$OutputDirectory
    )

    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $apk = Get-ReleaseApkPath -ProjectRoot $ProjectRoot
    if ($null -eq $apk) {
        return [pscustomobject]@{ Success = $false; Blocked = $true; Message = 'Release APK was not found.'; ApkPath = '' }
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $forbiddenTokens = @(
        'CameraFaceRecoveryTest','ProcessRecoveryTest','DbStressTest','NetworkFaultTest','FacePunchStress',
        'RECOVERY_FACE_V273','STRESS_FACE_V23','punch_camera_face_recovery_v273.db','punch_db_stress_v26.db'
    )
    $hits = New-Object System.Collections.Generic.List[string]
    $entries = New-Object System.Collections.Generic.List[string]
    # ZipFile.OpenRead returns a System.IO.Compression.ZipArchive used for entry-level DEX inspection.
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
    try {
        foreach ($entry in $zip.Entries) {
            [void]$entries.Add($entry.FullName)
            if ($entry.FullName -notmatch '^classes\d*\.dex$') { continue }
            $stream = $entry.Open()
            try {
                $memory = New-Object System.IO.MemoryStream
                $stream.CopyTo($memory)
                $text = [System.Text.Encoding]::UTF8.GetString($memory.ToArray())
                foreach ($token in $forbiddenTokens) {
                    if ($text.Contains($token)) { [void]$hits.Add("$($entry.FullName):$token") }
                }
            }
            finally { $stream.Dispose() }
        }
    }
    finally { $zip.Dispose() }

    foreach ($entryName in $entries) {
        if ($entryName -match '(?i)(cameraFaceRecoveryTest|processRecoveryTest|dbStressTest|networkFaultTest|facePunchStress|fixture)') {
            [void]$hits.Add("entry:$entryName")
        }
    }

    $evidence = @("apk=$($apk.FullName)", "classes entries audited=$(@($entries | Where-Object { $_ -match '^classes\d*\.dex$' }).Count)")
    if ($hits.Count -eq 0) { $evidence += 'PASS no forbidden test-only token found in Release APK' }
    else { $evidence += @($hits | ForEach-Object { "FAIL forbidden=$_" }) }
    Save-ReleaseGateEvidence -Path (Join-Path $OutputDirectory 'apk-isolation-audit.txt') -Lines $evidence

    return [pscustomobject]@{
        Success = ($hits.Count -eq 0)
        Blocked = $false
        Message = if ($hits.Count -eq 0) { 'Release APK test-code isolation audit passed.' } else { "Forbidden Release APK tokens found: $($hits -join ', ')" }
        ApkPath = $apk.FullName
        Hits = @($hits)
    }
}

function Invoke-ReleaseReadinessAudit {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$OutputDirectory
    )

    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $blockers = New-Object System.Collections.Generic.List[string]
    $warnings = New-Object System.Collections.Generic.List[string]

    $gradleText = Get-Content -Path (Join-Path $ProjectRoot 'app\build.gradle') -Raw -Encoding UTF8
    $releaseBlock = [regex]::Match($gradleText, '(?s)release\s*\{(.*?)\n\s*\}')
    if (-not $releaseBlock.Success -or $releaseBlock.Groups[1].Value -notmatch 'signingConfig') {
        [void]$blockers.Add('release signingConfig is not configured')
    }

    $syncPath = Join-Path $ProjectRoot 'app\src\main\java\com\punch\app\service\SyncService.java'
    $syncText = Get-Content -Path $syncPath -Raw -Encoding UTF8
    if ($syncText -match '(?s)triggerSync\s*\(.*?appContext\.startService\s*\(intent\)') {
        [void]$blockers.Add('known BackgroundServiceStartNotAllowedException risk remains: SyncService.triggerSync uses appContext.startService(intent)')
    }

    $lines = @('FaceAPK Release Readiness Audit')
    $lines += @($blockers | ForEach-Object { "BLOCKED $_" })
    $lines += @($warnings | ForEach-Object { "WARN $_" })
    if ($blockers.Count -eq 0) { $lines += 'PASS no known static release-readiness blocker found' }
    Save-ReleaseGateEvidence -Path (Join-Path $OutputDirectory 'release-readiness-audit.txt') -Lines $lines

    return [pscustomobject]@{
        Success = ($blockers.Count -eq 0)
        Blocked = ($blockers.Count -gt 0)
        Message = if ($blockers.Count -eq 0) { 'Release readiness static audit passed.' } else { $blockers -join ' | ' }
        Blockers = @($blockers)
    }
}
