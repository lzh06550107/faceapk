Set-StrictMode -Version Latest

function New-TestSuiteResult {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][ValidateSet('PASS','FAIL','BLOCKED','SKIPPED')][string]$Status,
        [datetime]$StartedAt = (Get-Date),
        [datetime]$FinishedAt = (Get-Date),
        [int]$ExitCode = 0,
        [string]$Message = '',
        [string]$ReportDirectory = ''
    )

    $duration = [Math]::Max(0, [Math]::Round(($FinishedAt - $StartedAt).TotalSeconds, 3))
    return [pscustomobject]@{
        name = $Name
        status = $Status
        started_at = $StartedAt.ToString('o')
        finished_at = $FinishedAt.ToString('o')
        duration_seconds = $duration
        exit_code = $ExitCode
        message = $Message
        report_directory = $ReportDirectory
    }
}

function Export-TestSuiteState {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Profile,
        [string]$Serial,
        [Parameter(Mandatory = $true)][object[]]$Results,
        [datetime]$StartedAt
    )

    $state = [ordered]@{
        schema_version = 1
        profile = $Profile
        serial = $Serial
        started_at = $StartedAt.ToString('o')
        updated_at = (Get-Date).ToString('o')
        results = @($Results)
    }
    $state | ConvertTo-Json -Depth 8 | Set-Content -Path $Path -Encoding UTF8
}

function Import-TestSuiteState {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path $Path)) {
        return $null
    }
    return (Get-Content -Path $Path -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Get-TestSuiteOverallStatus {
    param([Parameter(Mandatory = $true)][object[]]$Results)
    if (@($Results | Where-Object { $_.status -eq 'FAIL' }).Count -gt 0) { return 'FAIL' }
    if (@($Results | Where-Object { $_.status -eq 'BLOCKED' }).Count -gt 0) { return 'BLOCKED' }
    return 'PASS'
}

function Write-TestSuiteSummary {
    param(
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$Profile,
        [string]$Serial,
        [Parameter(Mandatory = $true)][object[]]$Results,
        [datetime]$StartedAt,
        [datetime]$FinishedAt = (Get-Date)
    )

    $overall = Get-TestSuiteOverallStatus -Results $Results
    $counts = @{}
    foreach ($status in @('PASS','FAIL','BLOCKED','SKIPPED')) {
        $counts[$status] = @($Results | Where-Object { $_.status -eq $status }).Count
    }

    $summary = [ordered]@{
        schema_version = 1
        profile = $Profile
        serial = $Serial
        overall_status = $overall
        started_at = $StartedAt.ToString('o')
        finished_at = $FinishedAt.ToString('o')
        duration_seconds = [Math]::Round(($FinishedAt - $StartedAt).TotalSeconds, 3)
        counts = $counts
        results = @($Results)
    }
    $summaryJson = Join-Path $RunDirectory 'summary.json'
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryJson -Encoding UTF8

    $lines = New-Object System.Collections.Generic.List[string]
    [void]$lines.Add('# FaceAPK Test Suite Summary')
    [void]$lines.Add('')
    [void]$lines.Add("- Profile: **$Profile**")
    [void]$lines.Add("- Serial: ``$Serial``")
    [void]$lines.Add("- Final result: **$overall**")
    [void]$lines.Add("- PASS: $($counts['PASS']) / FAIL: $($counts['FAIL']) / BLOCKED: $($counts['BLOCKED']) / SKIPPED: $($counts['SKIPPED'])")
    [void]$lines.Add('')
    [void]$lines.Add('| Stage | Status | Seconds | Detail |')
    [void]$lines.Add('|---|---:|---:|---|')
    foreach ($result in $Results) {
        $detail = [string]$result.message
        $detail = $detail.Replace('|','\\|').Replace("`r",' ').Replace("`n",' ')
        [void]$lines.Add("| $($result.name) | $($result.status) | $($result.duration_seconds) | $detail |")
    }
    [void]$lines.Add('')
    [void]$lines.Add("Generated: $($FinishedAt.ToString('yyyy-MM-dd HH:mm:ss zzz'))")
    $summaryMd = Join-Path $RunDirectory 'summary.md'
    $lines | Set-Content -Path $summaryMd -Encoding UTF8

    return $overall
}
