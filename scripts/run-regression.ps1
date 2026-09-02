[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$SkipDevice,
    [ValidateRange(60, 600)]
    [int]$InstrumentationTimeoutSeconds = 180,
    [ValidateRange(5, 60)]
    [int]$MaintenanceReadyTimeoutSeconds = 15,
    [switch]$UseDeviceOwnerMaintenanceBridge,
    [switch]$StopProductionAppForSmoke
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\DeviceTestCommon.ps1")

$projectRoot = Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
$reportDirectory = New-TestReportDirectory -ProjectRoot $projectRoot -Prefix "regression"
$summaryPath = Join-Path $reportDirectory "summary.txt"
$status = "FAIL"
$failureReason = "Unknown failure"
$resolvedSerial = $null

try {
    Assert-LocalBuildEnvironment -ProjectRoot $projectRoot

    $gates = @(
        @{ Name = "unit"; Task = ":app:testDebugUnitTest" },
        @{ Name = "assemble-debug"; Task = ":app:assembleDebug" },
        @{ Name = "assemble-release"; Task = ":app:assembleRelease" },
        @{ Name = "lint-debug"; Task = ":app:lintDebug" }
    )

    foreach ($gate in $gates) {
        Write-Host "[GATE] $($gate.Name): $($gate.Task)"
        $exitCode = Invoke-LoggedCommand -FilePath (Join-Path $projectRoot "gradlew.bat") `
            -Arguments @($gate.Task, "--stacktrace") `
            -LogPath (Join-Path $reportDirectory ("gradle-{0}.log" -f $gate.Name)) `
            -WorkingDirectory $projectRoot
        if ($exitCode -ne 0) {
            throw "Regression gate '$($gate.Name)' failed with exit code $exitCode."
        }
    }

    if (-not $SkipDevice) {
        Assert-CommandAvailable -Name "adb"
        $resolvedSerial = Resolve-AndroidSerial -RequestedSerial $Serial
        Write-DeviceInfo -Serial $resolvedSerial -OutputPath (Join-Path $reportDirectory "device-info.txt")

        $smokeArguments = @(
            "-Serial", $resolvedSerial,
            "-ReportDirectory", $reportDirectory,
            "-InstrumentationTimeoutSeconds", $InstrumentationTimeoutSeconds.ToString()
        )
        if ($UseDeviceOwnerMaintenanceBridge) {
            $smokeArguments += "-UseDeviceOwnerMaintenanceBridge"
        }
        if ($StopProductionAppForSmoke) {
            $smokeArguments += "-StopProductionAppForSmoke"
        }

        $smokeExit = Invoke-ChildPowerShellScript -ScriptPath (Join-Path $PSScriptRoot "run-ui-smoke.ps1") `
            -Arguments $smokeArguments `
            -LogPath (Join-Path $reportDirectory "ui-smoke-runner.log")
        if ($smokeExit -ne 0) {
            throw "Regression device smoke gate failed with exit code $smokeExit."
        }
    }

    $status = "PASS"
    $failureReason = ""
}
catch {
    $failureReason = $_.Exception.Message
    Write-Host "[FAIL] $failureReason" -ForegroundColor Red
}
finally {
    @(
        "Punch App Regression V1.4",
        "status=$status",
        "serial=$resolvedSerial",
        "skip_device=$SkipDevice",
        "instrumentation_timeout_seconds=$InstrumentationTimeoutSeconds",
        "maintenance_ready_timeout_seconds=$MaintenanceReadyTimeoutSeconds",
        "use_device_owner_maintenance_bridge=$UseDeviceOwnerMaintenanceBridge",
        "stop_production_app_for_smoke=$StopProductionAppForSmoke",
        "report_directory=$reportDirectory",
        "finished_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
        "failure_reason=$failureReason"
    ) | Set-Content -Path $summaryPath -Encoding UTF8
}

if ($status -eq "PASS") {
    Write-Host "RESULT: PASS" -ForegroundColor Green
    Write-Host "Report: $reportDirectory"
    exit 0
}

Write-Host "RESULT: FAIL" -ForegroundColor Red
Write-Host "Reason: $failureReason"
Write-Host "Report: $reportDirectory"
exit 1
