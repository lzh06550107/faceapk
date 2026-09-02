Set-StrictMode -Version Latest

function Convert-KilobytesToMegabytes {
    param([AllowNull()][object]$Kilobytes)
    if ($null -eq $Kilobytes) { return $null }
    return [Math]::Round(([double]$Kilobytes / 1024.0), 3)
}

function Convert-ToInvariantDouble {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $normalized = $Value.Replace(",", "").Trim()
    $number = 0.0
    if ([double]::TryParse(
        $normalized,
        [System.Globalization.NumberStyles]::Float,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [ref]$number
    )) {
        return [double]$number
    }
    return $null
}

function Get-RegexNumber {
    param(
        [string]$Text,
        [string]$Pattern
    )
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    $match = [Regex]::Match($Text, $Pattern)
    if (-not $match.Success -or $match.Groups.Count -lt 2) { return $null }
    return Convert-ToInvariantDouble -Value $match.Groups[1].Value
}

function Get-Median {
    param([object[]]$Values)
    $numbers = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ } | Sort-Object)
    if ($numbers.Count -eq 0) { return $null }
    $middle = [int][Math]::Floor($numbers.Count / 2)
    if (($numbers.Count % 2) -eq 1) {
        return [double]$numbers[$middle]
    }
    return ([double]$numbers[$middle - 1] + [double]$numbers[$middle]) / 2.0
}

function Get-Percentile {
    param(
        [object[]]$Values,
        [ValidateRange(0.0, 1.0)][double]$Percentile
    )
    $numbers = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ } | Sort-Object)
    if ($numbers.Count -eq 0) { return $null }
    if ($numbers.Count -eq 1) { return [double]$numbers[0] }
    $rank = $Percentile * ($numbers.Count - 1)
    $low = [int][Math]::Floor($rank)
    $high = [int][Math]::Ceiling($rank)
    if ($low -eq $high) { return [double]$numbers[$low] }
    $weight = $rank - $low
    return ([double]$numbers[$low] * (1.0 - $weight)) + ([double]$numbers[$high] * $weight)
}

function Get-PercentGrowth {
    param(
        [AllowNull()][object]$StartValue,
        [AllowNull()][object]$EndValue
    )
    if ($null -eq $StartValue -or $null -eq $EndValue) { return $null }
    $start = [double]$StartValue
    if ([Math]::Abs($start) -lt 0.000001) { return $null }
    return [Math]::Round((([double]$EndValue - $start) / $start) * 100.0, 3)
}

function Get-LinearSlopePerHour {
    param(
        [object[]]$Samples,
        [Parameter(Mandatory = $true)][string]$PropertyName
    )
    $points = @()
    foreach ($sample in $Samples) {
        $value = $sample.$PropertyName
        if ($null -ne $value) {
            $points += [pscustomobject]@{
                X = [double]$sample.ElapsedSeconds / 3600.0
                Y = [double]$value
            }
        }
    }
    if ($points.Count -lt 2) { return $null }
    $meanX = ($points | Measure-Object -Property X -Average).Average
    $meanY = ($points | Measure-Object -Property Y -Average).Average
    $numerator = 0.0
    $denominator = 0.0
    foreach ($point in $points) {
        $dx = [double]$point.X - [double]$meanX
        $dy = [double]$point.Y - [double]$meanY
        $numerator += $dx * $dy
        $denominator += $dx * $dx
    }
    if ([Math]::Abs($denominator) -lt 0.0000001) { return 0.0 }
    return [Math]::Round(($numerator / $denominator), 3)
}

function Save-PerformanceRawText {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Text
    )
    $parent = Split-Path -Parent $Path
    if ($parent -and -not (Test-Path $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $Text | Set-Content -Path $Path -Encoding UTF8
}

function Get-AppPerformanceSample {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][int]$Index,
        [Parameter(Mandatory = $true)][double]$ElapsedSeconds,
        [string]$RawRoot
    )

    $timestamp = Get-Date
    $pidText = Get-AdbOutput -Serial $Serial -Arguments @("shell", "pidof", $PackageName) -IgnoreFailure
    $appPid = $null
    if (-not [string]::IsNullOrWhiteSpace($pidText)) {
        $pidToken = ($pidText.Trim() -split '\s+')[0]
        if ($pidToken -match '^\d+$') { $appPid = [int]$pidToken }
    }

    $sampleDirectory = $null
    if ($RawRoot) {
        $sampleDirectory = Join-Path $RawRoot ("{0:D4}" -f $Index)
        New-Item -ItemType Directory -Path $sampleDirectory -Force | Out-Null
    }

    if ($null -eq $appPid) {
        if ($sampleDirectory) {
            Save-PerformanceRawText -Path (Join-Path $sampleDirectory "process.txt") -Text "process_not_running=true"
        }
        return [pscustomobject]@{
            Index = $Index
            Timestamp = $timestamp.ToString("yyyy-MM-dd HH:mm:ss.fff zzz")
            ElapsedSeconds = [Math]::Round($ElapsedSeconds, 3)
            ProcessAlive = $false
            Pid = $null
            TotalPssMb = $null
            JavaHeapMb = $null
            NativeHeapMb = $null
            RssMb = $null
            CpuPercent = $null
            ThreadCount = $null
            FdCount = $null
            BatteryLevelPercent = $null
            BatteryTemperatureC = $null
            ThermalMaxC = $null
            DataDiskUsedPercent = $null
        }
    }

    $meminfo = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "meminfo", $PackageName) -IgnoreFailure
    $cpuinfo = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "cpuinfo") -IgnoreFailure
    $battery = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "battery") -IgnoreFailure
    $thermal = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "thermalservice") -IgnoreFailure
    $procStatus = Get-AdbOutput -Serial $Serial -Arguments @("shell", "cat", "/proc/$appPid/status") -IgnoreFailure
    $threadText = Get-AdbOutput -Serial $Serial -Arguments @("shell", "sh", "-c", "ls /proc/$appPid/task 2>/dev/null | wc -l") -IgnoreFailure
    $fdText = Get-AdbOutput -Serial $Serial -Arguments @("shell", "sh", "-c", "ls /proc/$appPid/fd 2>/dev/null | wc -l") -IgnoreFailure
    $disk = Get-AdbOutput -Serial $Serial -Arguments @("shell", "df", "/data") -IgnoreFailure

    if ($sampleDirectory) {
        Save-PerformanceRawText -Path (Join-Path $sampleDirectory "meminfo.txt") -Text $meminfo
        Save-PerformanceRawText -Path (Join-Path $sampleDirectory "cpuinfo.txt") -Text $cpuinfo
        Save-PerformanceRawText -Path (Join-Path $sampleDirectory "battery.txt") -Text $battery
        Save-PerformanceRawText -Path (Join-Path $sampleDirectory "thermal.txt") -Text $thermal
        Save-PerformanceRawText -Path (Join-Path $sampleDirectory "proc-status.txt") -Text $procStatus
        Save-PerformanceRawText -Path (Join-Path $sampleDirectory "disk.txt") -Text $disk
        Save-PerformanceRawText -Path (Join-Path $sampleDirectory "process.txt") -Text "pid=$appPid`r`nthreads=$threadText`r`nfds=$fdText"
    }

    $totalPssKb = Get-RegexNumber -Text $meminfo -Pattern '(?im)^\s*TOTAL PSS:\s*([\d,]+)'
    if ($null -eq $totalPssKb) {
        $totalPssKb = Get-RegexNumber -Text $meminfo -Pattern '(?im)^\s*TOTAL\s+([\d,]+)\s+'
    }
    $totalRssKb = Get-RegexNumber -Text $meminfo -Pattern '(?im)^\s*TOTAL RSS:\s*([\d,]+)'
    if ($null -eq $totalRssKb) {
        $totalRssKb = Get-RegexNumber -Text $procStatus -Pattern '(?im)^VmRSS:\s*([\d,]+)\s*kB'
    }
    $javaHeapKb = Get-RegexNumber -Text $meminfo -Pattern '(?im)^\s*Java Heap:\s*([\d,]+)'
    $nativeHeapKb = Get-RegexNumber -Text $meminfo -Pattern '(?im)^\s*Native Heap:\s*([\d,]+)'

    $escapedPackage = [Regex]::Escape($PackageName)
    $cpuPercent = 0.0
    $cpuMatches = [Regex]::Matches($cpuinfo, "(?im)^\s*([0-9]+(?:\.[0-9]+)?)%\s+\d+/$escapedPackage(?:[:\s]|$)")
    if ($cpuMatches.Count -eq 0) {
        $cpuPercent = $null
    }
    else {
        foreach ($cpuMatch in $cpuMatches) {
            $value = Convert-ToInvariantDouble -Value $cpuMatch.Groups[1].Value
            if ($null -ne $value) { $cpuPercent += [double]$value }
        }
        $cpuPercent = [Math]::Round($cpuPercent, 3)
    }

    $threadCount = Get-RegexNumber -Text $threadText -Pattern '^\s*(\d+)\s*$'
    $fdCount = Get-RegexNumber -Text $fdText -Pattern '^\s*(\d+)\s*$'
    $batteryLevel = Get-RegexNumber -Text $battery -Pattern '(?im)^\s*level:\s*(\d+)'
    $batteryTempTenths = Get-RegexNumber -Text $battery -Pattern '(?im)^\s*temperature:\s*(-?\d+)'
    $batteryTempC = if ($null -ne $batteryTempTenths) { [Math]::Round(([double]$batteryTempTenths / 10.0), 1) } else { $null }

    $thermalValues = @()
    foreach ($thermalMatch in [Regex]::Matches($thermal, '(?i)mValue=([-+]?[0-9]+(?:\.[0-9]+)?)')) {
        $value = Convert-ToInvariantDouble -Value $thermalMatch.Groups[1].Value
        if ($null -ne $value -and $value -gt -100 -and $value -lt 200) { $thermalValues += [double]$value }
    }
    $thermalMaxC = if ($thermalValues.Count -gt 0) { [Math]::Round((($thermalValues | Measure-Object -Maximum).Maximum), 1) } else { $null }
    $diskUsed = Get-RegexNumber -Text $disk -Pattern '(?im)^\S+\s+\S+\s+\S+\s+\S+\s+(\d+)%\s+/data(?:\s|$)'

    return [pscustomobject]@{
        Index = $Index
        Timestamp = $timestamp.ToString("yyyy-MM-dd HH:mm:ss.fff zzz")
        ElapsedSeconds = [Math]::Round($ElapsedSeconds, 3)
        ProcessAlive = $true
        Pid = $appPid
        TotalPssMb = Convert-KilobytesToMegabytes -Kilobytes $totalPssKb
        JavaHeapMb = Convert-KilobytesToMegabytes -Kilobytes $javaHeapKb
        NativeHeapMb = Convert-KilobytesToMegabytes -Kilobytes $nativeHeapKb
        RssMb = Convert-KilobytesToMegabytes -Kilobytes $totalRssKb
        CpuPercent = $cpuPercent
        ThreadCount = if ($null -ne $threadCount -and [double]$threadCount -gt 0) { [int]$threadCount } else { $null }
        FdCount = if ($null -ne $fdCount -and [double]$fdCount -gt 0) { [int]$fdCount } else { $null }
        BatteryLevelPercent = if ($null -ne $batteryLevel) { [int]$batteryLevel } else { $null }
        BatteryTemperatureC = $batteryTempC
        ThermalMaxC = $thermalMaxC
        DataDiskUsedPercent = if ($null -ne $diskUsed) { [int]$diskUsed } else { $null }
    }
}

function Get-MemorySoakAnalysis {
    param(
        [Parameter(Mandatory = $true)][object[]]$Samples,
        [ValidateRange(0, 1440)][int]$WarmupMinutes = 5,
        [ValidateRange(1, 20)][int]$StableWindowSize = 3,
        [double]$MaxPssGrowthPercent = 10.0,
        [double]$MaxNativeHeapGrowthPercent = 10.0,
        [double]$MaxJavaHeapGrowthPercent = 20.0,
        [int]$MaxThreadGrowth = 10,
        [int]$MaxFdGrowth = 20,
        [int]$FatalEventCount = 0,
        [int]$GcEventCount = 0
    )

    $aliveSamples = @($Samples | Where-Object { $_.ProcessAlive })
    $stableSamples = @($aliveSamples | Where-Object { [double]$_.ElapsedSeconds -ge ($WarmupMinutes * 60.0) })
    $missingProcessSamples = @($Samples | Where-Object { -not $_.ProcessAlive }).Count
    $requiredMetricMissingCount = @($stableSamples | Where-Object {
        $null -eq $_.TotalPssMb -or $null -eq $_.JavaHeapMb -or $null -eq $_.NativeHeapMb -or $null -eq $_.RssMb
    }).Count

    $pidValues = @($aliveSamples | ForEach-Object { $_.Pid } | Where-Object { $null -ne $_ } | Select-Object -Unique)
    $processRestartCount = if ($pidValues.Count -gt 1) { $pidValues.Count - 1 } else { 0 }

    $failureReasons = New-Object System.Collections.Generic.List[string]
    if ($Samples.Count -eq 0) { [void]$failureReasons.Add("No samples were captured.") }
    if ($missingProcessSamples -gt 0) { [void]$failureReasons.Add("App process was missing in $missingProcessSamples sample(s).") }
    if ($processRestartCount -gt 0) { [void]$failureReasons.Add("App PID changed $processRestartCount time(s) during the soak.") }
    if ($FatalEventCount -gt 0) { [void]$failureReasons.Add("Detected $FatalEventCount app fatal/ANR/OOM/native event(s).") }
    if ($stableSamples.Count -lt 3) { [void]$failureReasons.Add("Fewer than 3 stable samples remained after warm-up.") }
    if ($requiredMetricMissingCount -gt 0) { [void]$failureReasons.Add("Required memory metrics were missing in $requiredMetricMissingCount stable sample(s).") }

    $firstWindow = @()
    $lastWindow = @()
    $windowSize = 0
    if ($stableSamples.Count -gt 0) {
        $windowSize = [Math]::Min($StableWindowSize, [Math]::Max(1, [int][Math]::Floor($stableSamples.Count / 2)))
        $firstWindow = @($stableSamples | Select-Object -First $windowSize)
        $lastWindow = @($stableSamples | Select-Object -Last $windowSize)
    }

    $pssFirst = Get-Median -Values @($firstWindow | ForEach-Object { $_.TotalPssMb })
    $pssLast = Get-Median -Values @($lastWindow | ForEach-Object { $_.TotalPssMb })
    $javaFirst = Get-Median -Values @($firstWindow | ForEach-Object { $_.JavaHeapMb })
    $javaLast = Get-Median -Values @($lastWindow | ForEach-Object { $_.JavaHeapMb })
    $nativeFirst = Get-Median -Values @($firstWindow | ForEach-Object { $_.NativeHeapMb })
    $nativeLast = Get-Median -Values @($lastWindow | ForEach-Object { $_.NativeHeapMb })
    $rssFirst = Get-Median -Values @($firstWindow | ForEach-Object { $_.RssMb })
    $rssLast = Get-Median -Values @($lastWindow | ForEach-Object { $_.RssMb })
    $threadsFirst = Get-Median -Values @($firstWindow | ForEach-Object { $_.ThreadCount })
    $threadsLast = Get-Median -Values @($lastWindow | ForEach-Object { $_.ThreadCount })
    $fdsFirst = Get-Median -Values @($firstWindow | ForEach-Object { $_.FdCount })
    $fdsLast = Get-Median -Values @($lastWindow | ForEach-Object { $_.FdCount })

    $pssGrowth = Get-PercentGrowth -StartValue $pssFirst -EndValue $pssLast
    $javaGrowth = Get-PercentGrowth -StartValue $javaFirst -EndValue $javaLast
    $nativeGrowth = Get-PercentGrowth -StartValue $nativeFirst -EndValue $nativeLast
    $rssGrowth = Get-PercentGrowth -StartValue $rssFirst -EndValue $rssLast
    $threadGrowth = if ($null -ne $threadsFirst -and $null -ne $threadsLast) { [int][Math]::Round(([double]$threadsLast - [double]$threadsFirst)) } else { $null }
    $fdGrowth = if ($null -ne $fdsFirst -and $null -ne $fdsLast) { [int][Math]::Round(([double]$fdsLast - [double]$fdsFirst)) } else { $null }

    if ($null -ne $pssGrowth -and $pssGrowth -gt $MaxPssGrowthPercent) { [void]$failureReasons.Add("PSS growth $pssGrowth% exceeded $MaxPssGrowthPercent%.") }
    if ($null -ne $nativeGrowth -and $nativeGrowth -gt $MaxNativeHeapGrowthPercent) { [void]$failureReasons.Add("Native heap growth $nativeGrowth% exceeded $MaxNativeHeapGrowthPercent%.") }
    if ($null -ne $javaGrowth -and $javaGrowth -gt $MaxJavaHeapGrowthPercent) { [void]$failureReasons.Add("Java heap growth $javaGrowth% exceeded $MaxJavaHeapGrowthPercent%.") }
    if ($null -ne $threadGrowth -and $threadGrowth -gt $MaxThreadGrowth) { [void]$failureReasons.Add("Thread growth $threadGrowth exceeded $MaxThreadGrowth.") }
    if ($null -ne $fdGrowth -and $fdGrowth -gt $MaxFdGrowth) { [void]$failureReasons.Add("FD growth $fdGrowth exceeded $MaxFdGrowth.") }

    $cpuValues = @($stableSamples | ForEach-Object { $_.CpuPercent } | Where-Object { $null -ne $_ })
    $cpuAverage = if ($cpuValues.Count -gt 0) { [Math]::Round((($cpuValues | Measure-Object -Average).Average), 3) } else { $null }
    $cpuP95 = Get-Percentile -Values $cpuValues -Percentile 0.95
    $batteryTemperatureValues = @($aliveSamples | ForEach-Object { $_.BatteryTemperatureC } | Where-Object { $null -ne $_ })
    $thermalValues = @($aliveSamples | ForEach-Object { $_.ThermalMaxC } | Where-Object { $null -ne $_ })
    $batteryTemperatureMax = if ($batteryTemperatureValues.Count -gt 0) { ($batteryTemperatureValues | Measure-Object -Maximum).Maximum } else { $null }
    $thermalMax = if ($thermalValues.Count -gt 0) { ($thermalValues | Measure-Object -Maximum).Maximum } else { $null }

    $status = if ($failureReasons.Count -eq 0) { "PASS" } else { "FAIL" }
    return [pscustomobject]@{
        Status = $status
        SampleCount = $Samples.Count
        StableSampleCount = $stableSamples.Count
        WarmupMinutes = $WarmupMinutes
        WindowSize = $windowSize
        MissingProcessSampleCount = $missingProcessSamples
        RequiredMetricMissingCount = $requiredMetricMissingCount
        ProcessRestartCount = $processRestartCount
        FatalEventCount = $FatalEventCount
        GcEventCount = $GcEventCount
        PssFirstMedianMb = if ($null -ne $pssFirst) { [Math]::Round($pssFirst, 3) } else { $null }
        PssLastMedianMb = if ($null -ne $pssLast) { [Math]::Round($pssLast, 3) } else { $null }
        PssGrowthPercent = $pssGrowth
        JavaHeapFirstMedianMb = if ($null -ne $javaFirst) { [Math]::Round($javaFirst, 3) } else { $null }
        JavaHeapLastMedianMb = if ($null -ne $javaLast) { [Math]::Round($javaLast, 3) } else { $null }
        JavaHeapGrowthPercent = $javaGrowth
        NativeHeapFirstMedianMb = if ($null -ne $nativeFirst) { [Math]::Round($nativeFirst, 3) } else { $null }
        NativeHeapLastMedianMb = if ($null -ne $nativeLast) { [Math]::Round($nativeLast, 3) } else { $null }
        NativeHeapGrowthPercent = $nativeGrowth
        RssFirstMedianMb = if ($null -ne $rssFirst) { [Math]::Round($rssFirst, 3) } else { $null }
        RssLastMedianMb = if ($null -ne $rssLast) { [Math]::Round($rssLast, 3) } else { $null }
        RssGrowthPercent = $rssGrowth
        ThreadGrowth = $threadGrowth
        FdGrowth = $fdGrowth
        PssSlopeMbPerHour = Get-LinearSlopePerHour -Samples $stableSamples -PropertyName "TotalPssMb"
        JavaHeapSlopeMbPerHour = Get-LinearSlopePerHour -Samples $stableSamples -PropertyName "JavaHeapMb"
        NativeHeapSlopeMbPerHour = Get-LinearSlopePerHour -Samples $stableSamples -PropertyName "NativeHeapMb"
        RssSlopeMbPerHour = Get-LinearSlopePerHour -Samples $stableSamples -PropertyName "RssMb"
        CpuAveragePercent = $cpuAverage
        CpuP95Percent = $cpuP95
        BatteryTemperatureMaxC = $batteryTemperatureMax
        ThermalMaxC = $thermalMax
        MaxPssGrowthPercent = $MaxPssGrowthPercent
        MaxNativeHeapGrowthPercent = $MaxNativeHeapGrowthPercent
        MaxJavaHeapGrowthPercent = $MaxJavaHeapGrowthPercent
        MaxThreadGrowth = $MaxThreadGrowth
        MaxFdGrowth = $MaxFdGrowth
        FailureReasons = $failureReasons.ToArray()
    }
}

function Convert-PerformanceValueToText {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return "N/A" }
    if ($Value -is [double] -or $Value -is [float] -or $Value -is [decimal]) {
        return ([double]$Value).ToString("0.###", [System.Globalization.CultureInfo]::InvariantCulture)
    }
    return $Value.ToString()
}

function Write-PerformanceSummary {
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
        "Punch App Performance Harness V2.1",
        "status=$($Analysis.Status)",
        "serial=$Serial",
        "package=$PackageName",
        "requested_minutes=$(Convert-PerformanceValueToText $RequestedMinutes)",
        "actual_minutes=$(Convert-PerformanceValueToText $ActualMinutes)",
        "sample_count=$($Analysis.SampleCount)",
        "stable_sample_count=$($Analysis.StableSampleCount)",
        "warmup_minutes=$($Analysis.WarmupMinutes)",
        "process_restart_count=$($Analysis.ProcessRestartCount)",
        "missing_process_samples=$($Analysis.MissingProcessSampleCount)",
        "fatal_event_count=$($Analysis.FatalEventCount)",
        "gc_event_count=$($Analysis.GcEventCount)",
        "pss_first_median_mb=$(Convert-PerformanceValueToText $Analysis.PssFirstMedianMb)",
        "pss_last_median_mb=$(Convert-PerformanceValueToText $Analysis.PssLastMedianMb)",
        "pss_growth_percent=$(Convert-PerformanceValueToText $Analysis.PssGrowthPercent)",
        "pss_slope_mb_per_hour=$(Convert-PerformanceValueToText $Analysis.PssSlopeMbPerHour)",
        "java_heap_growth_percent=$(Convert-PerformanceValueToText $Analysis.JavaHeapGrowthPercent)",
        "native_heap_growth_percent=$(Convert-PerformanceValueToText $Analysis.NativeHeapGrowthPercent)",
        "rss_growth_percent=$(Convert-PerformanceValueToText $Analysis.RssGrowthPercent)",
        "thread_growth=$(Convert-PerformanceValueToText $Analysis.ThreadGrowth)",
        "fd_growth=$(Convert-PerformanceValueToText $Analysis.FdGrowth)",
        "cpu_average_percent=$(Convert-PerformanceValueToText $Analysis.CpuAveragePercent)",
        "cpu_p95_percent=$(Convert-PerformanceValueToText $Analysis.CpuP95Percent)",
        "battery_temperature_max_c=$(Convert-PerformanceValueToText $Analysis.BatteryTemperatureMaxC)",
        "thermal_max_c=$(Convert-PerformanceValueToText $Analysis.ThermalMaxC)",
        "threshold_pss_growth_percent=$($Analysis.MaxPssGrowthPercent)",
        "threshold_native_heap_growth_percent=$($Analysis.MaxNativeHeapGrowthPercent)",
        "threshold_java_heap_growth_percent=$($Analysis.MaxJavaHeapGrowthPercent)",
        "threshold_thread_growth=$($Analysis.MaxThreadGrowth)",
        "threshold_fd_growth=$($Analysis.MaxFdGrowth)",
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

function Convert-PerformanceHtmlText {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return "N/A" }
    return [System.Net.WebUtility]::HtmlEncode((Convert-PerformanceValueToText $Value))
}

function Write-PerformanceHtmlReport {
    param(
        [Parameter(Mandatory = $true)][object[]]$Samples,
        [Parameter(Mandatory = $true)][object]$Analysis,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )

    $failureHtml = if ($Analysis.FailureReasons.Count -eq 0) {
        "<li>None</li>"
    }
    else {
        (@($Analysis.FailureReasons | ForEach-Object { "<li>$(Convert-PerformanceHtmlText $_)</li>" }) -join "`r`n")
    }

    $rows = New-Object System.Collections.Generic.List[string]
    foreach ($sample in $Samples) {
        [void]$rows.Add("<tr><td>$(Convert-PerformanceHtmlText $sample.Index)</td><td>$(Convert-PerformanceHtmlText $sample.Timestamp)</td><td>$(Convert-PerformanceHtmlText $sample.ElapsedSeconds)</td><td>$(Convert-PerformanceHtmlText $sample.Pid)</td><td>$(Convert-PerformanceHtmlText $sample.TotalPssMb)</td><td>$(Convert-PerformanceHtmlText $sample.JavaHeapMb)</td><td>$(Convert-PerformanceHtmlText $sample.NativeHeapMb)</td><td>$(Convert-PerformanceHtmlText $sample.RssMb)</td><td>$(Convert-PerformanceHtmlText $sample.CpuPercent)</td><td>$(Convert-PerformanceHtmlText $sample.ThreadCount)</td><td>$(Convert-PerformanceHtmlText $sample.FdCount)</td><td>$(Convert-PerformanceHtmlText $sample.BatteryTemperatureC)</td><td>$(Convert-PerformanceHtmlText $sample.ThermalMaxC)</td></tr>")
    }

    $html = @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Punch App Performance Harness V2.1</title>
<style>
body { font-family: Arial, "Microsoft YaHei", sans-serif; margin: 24px; }
table { border-collapse: collapse; width: 100%; font-size: 13px; }
th, td { border: 1px solid #ccc; padding: 6px 8px; text-align: right; }
th:nth-child(2), td:nth-child(2) { text-align: left; }
.summary { display: grid; grid-template-columns: repeat(4, minmax(160px, 1fr)); gap: 10px; margin-bottom: 20px; }
.card { border: 1px solid #ddd; padding: 10px; border-radius: 6px; }
.status { font-size: 22px; font-weight: 700; }
</style>
</head>
<body>
<h1>Punch App Performance Harness V2.1</h1>
<p>Serial: <strong>$(Convert-PerformanceHtmlText $Serial)</strong> &nbsp; Package: <strong>$(Convert-PerformanceHtmlText $PackageName)</strong></p>
<div class="summary">
<div class="card"><div>Status</div><div class="status">$(Convert-PerformanceHtmlText $Analysis.Status)</div></div>
<div class="card"><div>PSS growth</div><div>$(Convert-PerformanceHtmlText $Analysis.PssGrowthPercent)%</div></div>
<div class="card"><div>Native growth</div><div>$(Convert-PerformanceHtmlText $Analysis.NativeHeapGrowthPercent)%</div></div>
<div class="card"><div>CPU p95</div><div>$(Convert-PerformanceHtmlText $Analysis.CpuP95Percent)%</div></div>
<div class="card"><div>PSS slope</div><div>$(Convert-PerformanceHtmlText $Analysis.PssSlopeMbPerHour) MB/h</div></div>
<div class="card"><div>Threads growth</div><div>$(Convert-PerformanceHtmlText $Analysis.ThreadGrowth)</div></div>
<div class="card"><div>FD growth</div><div>$(Convert-PerformanceHtmlText $Analysis.FdGrowth)</div></div>
<div class="card"><div>Fatal events</div><div>$(Convert-PerformanceHtmlText $Analysis.FatalEventCount)</div></div>
</div>
<h2>Failure reasons</h2>
<ul>$failureHtml</ul>
<h2>Samples</h2>
<table>
<thead><tr><th>#</th><th>Timestamp</th><th>Elapsed s</th><th>PID</th><th>PSS MB</th><th>Java MB</th><th>Native MB</th><th>RSS MB</th><th>CPU %</th><th>Threads</th><th>FD</th><th>Battery C</th><th>Thermal max C</th></tr></thead>
<tbody>
$($rows -join "`r`n")
</tbody>
</table>
</body>
</html>
"@
    $html | Set-Content -Path $OutputPath -Encoding UTF8
}

function Save-AdbOutputQuietly {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    $output = Get-AdbOutput -Serial $Serial -Arguments $Arguments -IgnoreFailure
    $output | Set-Content -Path $OutputPath -Encoding UTF8
}

function Get-BestEffortGcEventCount {
    param(
        [Parameter(Mandatory = $true)][string]$LogPath,
        [AllowNull()][object]$AppProcessId
    )
    if (-not (Test-Path $LogPath) -or $null -eq $AppProcessId) { return 0 }
    $pattern = "\s+$AppProcessId\s+\d+\s+.*(?:\bGC\b|garbage collector|concurrent copying)"
    return @(Select-String -Path $LogPath -Pattern $pattern -AllMatches -ErrorAction SilentlyContinue).Count
}
