[CmdletBinding()]
param(
    [string]$Serial,
    [string]$PackageName='com.punch.app',
    [ValidateSet('Face','Punch','All')][string]$Mode='All',
    [ValidateRange(1,10000)][int]$Count=100,
    [ValidateRange(1,60)][int]$SampleIntervalSeconds=5,
    [ValidateRange(30,7200)][int]$TimeoutSeconds=1800,
    [ValidateRange(30,600)][int]$PreflightTimeoutSeconds=330,
    [ValidateRange(0,100)][double]$MinFaceSuccessPercent=95.0,
    [ValidateRange(0,100)][double]$MaxNativeHeapGrowthPercent=10.0,
    [ValidateRange(0,100)][int]$WarmupFaceCount=3,
    [switch]$KeepArtifacts,
    [string]$OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib\DeviceTestCommon.ps1')
. (Join-Path $PSScriptRoot 'lib\PerformanceMetrics.ps1')
. (Join-Path $PSScriptRoot 'lib\FacePunchStress.ps1')

$projectRoot=Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
Assert-CommandAvailable -Name 'adb'
$resolvedSerial=Resolve-AndroidSerial -RequestedSerial $Serial
if (-not $OutputDirectory) { $OutputDirectory=New-TestReportDirectory -ProjectRoot $projectRoot -Prefix 'face-punch-stress' }
else { New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null; $OutputDirectory=(Resolve-Path $OutputDirectory).Path }
$backup=$null; $testInstalled=$false; $restoreFailure=$null; $exitCode=1
$samples=New-Object System.Collections.Generic.List[object]
$startTime=Get-Date
$rawRoot=Join-Path $OutputDirectory 'raw-samples'; New-Item -ItemType Directory -Path $rawRoot -Force | Out-Null

try {
    Write-Host '========================================'
    Write-Host ' Punch App Face/Punch Stress V2.3.6'
    Write-Host '========================================'
    Write-Host "Serial: $resolvedSerial  Mode: $Mode  Count: $Count"
    Write-Host '[INFO] Backing up installed production APK(s).'
    $backup=Backup-InstalledPackageApks -Serial $resolvedSerial -PackageName $PackageName -BackupDirectory (Join-Path $OutputDirectory 'production-backup')
    Assert-LocalBuildEnvironment -ProjectRoot $projectRoot
    $buildLog=Join-Path $OutputDirectory 'face-punch-stress-build.log'
    $buildExit=Invoke-LoggedCommand -FilePath (Join-Path $projectRoot 'gradlew.bat') -Arguments @(':app:assembleFacePunchStress','--stacktrace') -LogPath $buildLog -WorkingDirectory $projectRoot
    if ($buildExit -ne 0) { throw "facePunchStress build failed with exit code $buildExit" }
    $apkDir=Join-Path $projectRoot 'app\build\outputs\apk\facePunchStress'
    $apk=Get-ChildItem $apkDir -Filter '*.apk' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { throw "facePunchStress APK not found under $apkDir" }
    Write-Host '[INFO] Installing temporary facePunchStress build.'
    $install=Invoke-Adb -Serial $resolvedSerial -Arguments @('install','-r','-d',$apk.FullName) -LogPath (Join-Path $OutputDirectory 'install-face-punch-stress.log')
    if ($install -ne 0) { throw "facePunchStress install failed with exit code $install" }
    $testInstalled=$true
    [void](Start-AndroidLauncherPackage -Serial $resolvedSerial -PackageName $PackageName -OutputPath (Join-Path $OutputDirectory 'start-face-punch-stress.log'))
    Start-Sleep -Seconds 2
    $ready=Wait-FacePunchStressReady -Serial $resolvedSerial -PackageName $PackageName -Mode $Mode -TimeoutSeconds $PreflightTimeoutSeconds
    $ready.Raw | Set-Content -Path (Join-Path $OutputDirectory 'preflight-status.txt') -Encoding UTF8
    if (-not $ready.Supported) { throw 'Stress status receiver is not reachable.' }
    if ($Mode -ne 'Punch') {
        Write-Host ("[INFO] Face preflight initialized={0} preparing={1} ready={2} loadedFaces={3}" -f $ready.FaceInitialized,$ready.PunchDataPreparing,$ready.PunchDataReady,$ready.LoadedFaceCount)
        if (-not $ready.FaceInitialized) { throw 'FaceManager did not become initialized for native face stress.' }
        if ($ready.PunchDataPreparing) { throw "Face data preparation did not finish within $PreflightTimeoutSeconds seconds." }
    }

    [void](Invoke-FacePunchStressBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action CLEANUP)
    [void](Get-AdbOutput -Serial $resolvedSerial -Arguments @('logcat','-c') -IgnoreFailure)

    $cold=$null
    $coldToWarmNative=$null
    $nextSampleIndex=0
    if ($Mode -in @('Face','All') -and $WarmupFaceCount -gt 0) {
        $cold=Get-AppPerformanceSample -Serial $resolvedSerial -PackageName $PackageName -Index $nextSampleIndex -ElapsedSeconds 0 -RawRoot $rawRoot
        $samples.Add($cold); $nextSampleIndex++
        Write-Host ("[INFO] Face native warm-up: {0} recognition(s) before leak baseline." -f $WarmupFaceCount)
        $warmupOutput=Invoke-FacePunchStressBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action RUN -Mode Face -Count $WarmupFaceCount
        $warmupOutput | Set-Content -Path (Join-Path $OutputDirectory 'warmup-broadcast.txt') -Encoding UTF8
        $warmupDeadline=(Get-Date).AddSeconds([Math]::Min($TimeoutSeconds,300))
        do {
            Start-Sleep -Seconds 1
            $warmupStatus=Get-FacePunchStressStatus -Serial $resolvedSerial -PackageName $PackageName
            if ($warmupStatus.State -in @('completed','failed')) { break }
        } while ((Get-Date) -lt $warmupDeadline)
        if ($warmupStatus.State -notin @('completed','failed')) { throw 'Face native warm-up timed out.' }
        if ($warmupStatus.State -eq 'failed') { throw "Face native warm-up failed: $($warmupStatus.Error)" }
        Start-Sleep -Seconds 2
        [void](Invoke-Adb -Serial $resolvedSerial -Arguments @('logcat','-d','-v','threadtime') -LogPath (Join-Path $OutputDirectory 'warmup-logcat.txt'))
        $warmupFatalCount=Find-AppFatalEvents -LogPath (Join-Path $OutputDirectory 'warmup-logcat.txt') -PackageName $PackageName -OutputPath (Join-Path $OutputDirectory 'warmup-fatal-events.txt')
        if ($warmupFatalCount -gt 0) { throw "Face native warm-up produced fatal events: $warmupFatalCount" }
        [void](Get-AdbOutput -Serial $resolvedSerial -Arguments @('logcat','-c') -IgnoreFailure)
    }

    $initial=Get-AppPerformanceSample -Serial $resolvedSerial -PackageName $PackageName -Index $nextSampleIndex -ElapsedSeconds ((Get-Date)-$startTime).TotalSeconds -RawRoot $rawRoot
    $samples.Add($initial); $nextSampleIndex++
    if ($null -ne $cold -and $null -ne $cold.NativeHeapMb -and $cold.NativeHeapMb -gt 0 -and $null -ne $initial.NativeHeapMb) {
        $coldToWarmNative=100.0*($initial.NativeHeapMb-$cold.NativeHeapMb)/$cold.NativeHeapMb
        Write-Host (("[INFO] Native cold->warm allocation: {0:N3}% ({1:N3}MB -> {2:N3}MB); diagnostic only.") -f $coldToWarmNative,$cold.NativeHeapMb,$initial.NativeHeapMb)
    }

    $runOutput=Invoke-FacePunchStressBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action RUN -Mode $Mode -Count $Count
    $runOutput | Set-Content -Path (Join-Path $OutputDirectory 'run-broadcast.txt') -Encoding UTF8

    $deadline=(Get-Date).AddSeconds($TimeoutSeconds); $index=$nextSampleIndex
    do {
        Start-Sleep -Seconds $SampleIntervalSeconds
        $elapsed=((Get-Date)-$startTime).TotalSeconds
        $sample=Get-AppPerformanceSample -Serial $resolvedSerial -PackageName $PackageName -Index $index -ElapsedSeconds $elapsed -RawRoot $rawRoot
        $samples.Add($sample); $index++
        $status=Get-FacePunchStressStatus -Serial $resolvedSerial -PackageName $PackageName
        Write-Host ("[{0}] state={1} pid={2} PSS={3}MB Native={4}MB CPU={5}% Thr={6} FD={7}" -f (Get-Date -Format 'HH:mm:ss'),$status.State,$sample.Pid,$sample.TotalPssMb,$sample.NativeHeapMb,$sample.CpuPercent,$sample.ThreadCount,$sample.FdCount)
        if ($status.State -eq 'completed' -or $status.State -eq 'failed') { break }
    } while ((Get-Date) -lt $deadline)
    if ($status.State -notin @('completed','failed')) { throw "Stress run timed out after $TimeoutSeconds seconds." }
    if ($status.State -eq 'failed') { throw "Stress engine failed: $($status.Error)" }

    Start-Sleep -Seconds 5
    $final=Get-AppPerformanceSample -Serial $resolvedSerial -PackageName $PackageName -Index $index -ElapsedSeconds ((Get-Date)-$startTime).TotalSeconds -RawRoot $rawRoot
    $samples.Add($final)
    $sampleArray=$samples.ToArray()
    $sampleArray | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $OutputDirectory 'metrics.csv')

    $faceObj=$null; $punchObj=$null
    if ($Mode -in @('Face','All')) {
        $faceText=Get-FacePunchStressFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'face-result.json'
        $faceText | Set-Content -Path (Join-Path $OutputDirectory 'face-result.json') -Encoding UTF8
        $faceObj=$faceText | ConvertFrom-Json
        Convert-StressJsonToCsv -JsonObject $faceObj -Path (Join-Path $OutputDirectory 'face-results.csv')
    }
    if ($Mode -in @('Punch','All')) {
        $punchText=Get-FacePunchStressFile -Serial $resolvedSerial -PackageName $PackageName -RelativePath 'punch-result.json'
        $punchText | Set-Content -Path (Join-Path $OutputDirectory 'punch-result.json') -Encoding UTF8
        $punchObj=$punchText | ConvertFrom-Json
        Convert-StressJsonToCsv -JsonObject $punchObj -Path (Join-Path $OutputDirectory 'punch-results.csv')
    }

    [void](Invoke-Adb -Serial $resolvedSerial -Arguments @('logcat','-d','-v','threadtime') -LogPath (Join-Path $OutputDirectory 'logcat.txt'))
    $fatalCount=Find-AppFatalEvents -LogPath (Join-Path $OutputDirectory 'logcat.txt') -PackageName $PackageName -OutputPath (Join-Path $OutputDirectory 'fatal-events.txt')
    $failures=New-Object System.Collections.Generic.List[string]
    if ($fatalCount -gt 0) { $failures.Add("fatal_events=$fatalCount") }
    if ($Mode -in @('Face','All')) {
        $faceRate=if ([int]$faceObj.completed -gt 0) { 100.0*[int]$faceObj.success/[int]$faceObj.completed } else { 0.0 }
        if ([int]$faceObj.completed -ne $Count) { $failures.Add("face_completed=$($faceObj.completed)/$Count") }
        if ($faceRate -lt $MinFaceSuccessPercent) { $failures.Add(("face_success_rate={0:N2}%<{1:N2}%" -f $faceRate,$MinFaceSuccessPercent)) }
    } else { $faceRate=$null }
    if ($Mode -in @('Punch','All')) {
        if ([int]$punchObj.completed -ne $Count) { $failures.Add("punch_completed=$($punchObj.completed)/$Count") }
        if ([int]$punchObj.success -ne $Count) { $failures.Add("punch_success=$($punchObj.success)/$Count") }
        if ([int]$punchObj.punch_record_count -ne $Count) { $failures.Add("punch_records=$($punchObj.punch_record_count)/$Count") }
        if ([int]$punchObj.queue_count -ne $Count) { $failures.Add("stress_queue=$($punchObj.queue_count)/$Count") }
        if ([int]$punchObj.snapshot_count -ne $Count) { $failures.Add("snapshots=$($punchObj.snapshot_count)/$Count") }
    }
    $nativeGrowth=$null
    if ($null -ne $initial.NativeHeapMb -and $initial.NativeHeapMb -gt 0 -and $null -ne $final.NativeHeapMb) {
        $nativeGrowth=100.0*($final.NativeHeapMb-$initial.NativeHeapMb)/$initial.NativeHeapMb
        if ($nativeGrowth -gt $MaxNativeHeapGrowthPercent) { $failures.Add(("native_growth={0:N3}%>{1:N3}%" -f $nativeGrowth,$MaxNativeHeapGrowthPercent)) }
    }
    $failureArray=$failures.ToArray()
    $statusText=if ($failureArray.Count -eq 0) {'PASS'} else {'FAIL'}
    @(
        'Punch App Face/Punch Stress V2.3.6',
        "status=$statusText",
        "mode=$Mode",
        "count=$Count",
        "face_success_rate_percent=$faceRate",
        "face_fixture_source=$(if($faceObj){$faceObj.fixture_source}else{''})",
        "face_fixture_registered=$(if($faceObj){$faceObj.fixture_registered}else{''})",
        "face_p50_ms=$(if($faceObj){$faceObj.latency_p50_ms}else{''})",
        "face_p95_ms=$(if($faceObj){$faceObj.latency_p95_ms}else{''})",
        "face_p99_ms=$(if($faceObj){$faceObj.latency_p99_ms}else{''})",
        "punch_success=$(if($punchObj){$punchObj.success}else{''})",
        "punch_p50_ms=$(if($punchObj){$punchObj.latency_p50_ms}else{''})",
        "punch_p95_ms=$(if($punchObj){$punchObj.latency_p95_ms}else{''})",
        "punch_p99_ms=$(if($punchObj){$punchObj.latency_p99_ms}else{''})",
        "face_warmup_count=$WarmupFaceCount",
        "native_heap_cold_to_warm_percent=$coldToWarmNative",
        "native_heap_growth_percent=$nativeGrowth",
        "fatal_events=$fatalCount",
        "failures=$($failureArray -join '; ')"
    ) | Set-Content -Path (Join-Path $OutputDirectory 'summary.txt') -Encoding UTF8
    Write-Host "Report: $OutputDirectory"
    Write-Host "RESULT: $statusText"
    $exitCode=if ($statusText -eq 'PASS') {0} else {1}

    if (-not $KeepArtifacts) {
        [void](Invoke-FacePunchStressBroadcast -Serial $resolvedSerial -PackageName $PackageName -Action CLEANUP)
        'cleanup_requested=true' | Set-Content -Path (Join-Path $OutputDirectory 'cleanup.txt') -Encoding UTF8
    }
} catch {
    $exitCode=1
    $message=$_.Exception.Message
    Write-Host "[FAIL] $message" -ForegroundColor Red
    @('Punch App Face/Punch Stress V2.3.6','status=FAIL',"reason=$message") | Set-Content -Path (Join-Path $OutputDirectory 'wrapper-summary.txt') -Encoding UTF8
} finally {
    if ($testInstalled -and $null -ne $backup) {
        try {
            Write-Host '[INFO] Restoring original production APK(s).'
            $restore=Restore-InstalledPackageApks -Serial $resolvedSerial -ApkPaths $backup.ApkPaths -LogPath (Join-Path $OutputDirectory 'restore-production-app.log')
            if ($restore -ne 0) { $restoreFailure="restore failed exit=$restore" }
            else { [void](Invoke-Adb -Serial $resolvedSerial -Arguments @('shell','am','start','-W','-n',"$PackageName/.activity.KioskHomeActivity") -LogPath (Join-Path $OutputDirectory 'restore-kiosk.log')) }
        } catch { $restoreFailure=$_.Exception.Message }
    }
}
@("mode=$Mode","count=$Count","temporary_build_installed=$testInstalled","restore_failure=$restoreFailure") | Set-Content -Path (Join-Path $OutputDirectory 'transaction.txt') -Encoding UTF8
if ($restoreFailure) { Write-Host "[FAIL] $restoreFailure" -ForegroundColor Red; exit 1 }
exit $exitCode
