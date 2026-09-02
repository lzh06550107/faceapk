Set-StrictMode -Version Latest

function Get-TestSuiteStages {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('Smoke','Regression','Release')][string]$Profile,
        [switch]$IncludeExperimental
    )

    $smokeStages = @(
        'EnvironmentPreflight',
        'UnitTests',
        'AssembleDebug',
        'UiSmoke',
        'PerformanceBaseline',
        'CameraFaceSoak'
    )
    $regressionStages = @($smokeStages + @(
        'FacePunchStress',
        'NetworkFault',
        'DbStress1000',
        'ProcessRecovery'
    ))
    $releaseStages = @(
        'EnvironmentPreflight',
        'SourceIsolationAudit',
        'UnitTests',
        'AssembleDebug',
        'UiSmoke',
        'PerformanceBaseline',
        'CameraFaceSoak',
        'FacePunchStress',
        'NetworkFault',
        'NetworkDeviceOffline',
        'DbStress1000',
        'DbStress5000',
        'ProcessRecovery',
        'AssembleRelease',
        'ApkIsolationAudit',
        'ReleaseReadinessAudit'
    )
    if ($IncludeExperimental) {
        $releaseStages = @($releaseStages[0..12] + @('CameraFaceRecoveryExperimental') + $releaseStages[13..($releaseStages.Count - 1)])
    }

    switch ($Profile) {
        'Smoke' { return $smokeStages }
        'Regression' { return $regressionStages }
        'Release' { return $releaseStages }
    }
}

function New-TestStageDirectory {
    param([string]$RunDirectory, [string]$StageName)
    $safe = $StageName -replace '([a-z0-9])([A-Z])', '$1-$2'
    $safe = $safe.ToLowerInvariant()
    $path = Join-Path $RunDirectory $safe
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    return $path
}

function Invoke-TestSuiteChildScript {
    param(
        [string]$ScriptsRoot,
        [string]$ScriptName,
        [string[]]$Arguments,
        [string]$StageDirectory
    )
    $script = Join-Path $ScriptsRoot $ScriptName
    $log = Join-Path $StageDirectory 'runner.log'
    return Invoke-ChildPowerShellScript -ScriptPath $script -Arguments $Arguments -LogPath $log
}


function Get-UiSmokeFailureReason {
    param([Parameter(Mandatory = $true)][string]$StageDirectory)

    $summaryPath = Join-Path $StageDirectory 'ui-smoke-summary.txt'
    if (-not (Test-Path $summaryPath)) { return '' }
    $line = Get-Content -Path $summaryPath -Encoding UTF8 -ErrorAction SilentlyContinue | Where-Object { $_ -like 'failure_reason=*' } | Select-Object -First 1
    if ($null -eq $line) { return '' }
    return ([string]$line).Substring('failure_reason='.Length).Trim()
}


function Get-DbStressFailureReason {
    param([Parameter(Mandatory = $true)][string]$StageDirectory)

    $transactionPath = Join-Path $StageDirectory 'transaction.txt'
    if (Test-Path $transactionPath) {
        $transactionLines = @(Get-Content -Path $transactionPath -Encoding UTF8 -ErrorAction SilentlyContinue)
        $restoreLine = $transactionLines | Where-Object { $_ -like 'restore_failure=*' } | Select-Object -First 1
        if ($null -ne $restoreLine) {
            $restoreFailure = ([string]$restoreLine).Substring('restore_failure='.Length).Trim()
            if (-not [string]::IsNullOrWhiteSpace($restoreFailure)) { return "restore_failure=$restoreFailure" }
        }
    }

    $summaryPath = Join-Path $StageDirectory 'summary.txt'
    if (Test-Path $summaryPath) {
        $summaryLines = @(Get-Content -Path $summaryPath -Encoding UTF8 -ErrorAction SilentlyContinue)
        $coreLine = $summaryLines | Where-Object { $_ -like 'core_status=*' } | Select-Object -First 1
        if ($null -ne $coreLine -and ([string]$coreLine).Trim() -eq 'core_status=FAIL') {
            return 'core_status=FAIL; inspect DB stress summary/result logs'
        }
    }

    $wrapperPath = Join-Path $StageDirectory 'wrapper-summary.txt'
    if (Test-Path $wrapperPath) {
        $reasonLine = Get-Content -Path $wrapperPath -Encoding UTF8 -ErrorAction SilentlyContinue | Where-Object { $_ -like 'reason=*' } | Select-Object -First 1
        if ($null -ne $reasonLine) { return "wrapper=$(([string]$reasonLine).Trim())" }
    }
    return ''
}

function Get-ProcessRecoveryFailureReason {
    param([Parameter(Mandatory = $true)][string]$StageDirectory)

    $transactionPath = Join-Path $StageDirectory 'transaction.txt'
    if (Test-Path $transactionPath) {
        $transactionLines = @(Get-Content -Path $transactionPath -Encoding UTF8 -ErrorAction SilentlyContinue)
        $scenarioLine = $transactionLines | Where-Object { $_ -like 'scenario_failures=*' } | Select-Object -First 1
        if ($null -ne $scenarioLine) {
            $scenarioFailure = ([string]$scenarioLine).Substring('scenario_failures='.Length).Trim()
            if (-not [string]::IsNullOrWhiteSpace($scenarioFailure)) { return "scenario_failure=$scenarioFailure" }
        }
        $restoreLine = $transactionLines | Where-Object { $_ -like 'restore_failure=*' } | Select-Object -First 1
        if ($null -ne $restoreLine) {
            $restoreFailure = ([string]$restoreLine).Substring('restore_failure='.Length).Trim()
            if (-not [string]::IsNullOrWhiteSpace($restoreFailure)) { return "restore_failure=$restoreFailure" }
        }
    }

    $runnerPath = Join-Path $StageDirectory 'runner.log'
    if (Test-Path $runnerPath) {
        $failureLine = Get-Content -Path $runnerPath -Encoding UTF8 -ErrorAction SilentlyContinue | Where-Object { $_ -match '^\[FAIL\]' } | Select-Object -Last 1
        if ($null -ne $failureLine) { return "runner=$(([string]$failureLine).Trim())" }
    }

    $summaryPath = Join-Path $StageDirectory 'summary.txt'
    if (Test-Path $summaryPath) {
        $summaryText = Get-Content -Path $summaryPath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($summaryText -match '(?im)^overall_status=FAIL\s*$') { return 'overall_status=FAIL; inspect process-recovery transaction/logs' }
    }
    return ''
}

function Invoke-TestSuiteStage {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Profile,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$ScriptsRoot,
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$Serial
    )

    $stageDirectory = New-TestStageDirectory -RunDirectory $RunDirectory -StageName $Name
    $started = Get-Date
    $exitCode = 0
    $status = 'PASS'
    $message = ''

    Write-Host "[GATE] $Name" -ForegroundColor Cyan
    try {
        switch ($Name) {
            'EnvironmentPreflight' {
                Assert-CommandAvailable -Name 'adb'
                [void](Assert-Java17)
                [void](Assert-GradleWrapper -ProjectRoot $ProjectRoot)
                $resolved = Resolve-AndroidSerial -RequestedSerial $Serial
                Write-DeviceInfo -Serial $resolved -OutputPath (Join-Path $stageDirectory 'device-info.txt')
                $message = 'Java 17, Gradle 8 wrapper, ADB device preflight passed.'
            }
            'SourceIsolationAudit' {
                $audit = Invoke-ReleaseSourceIsolationAudit -ProjectRoot $ProjectRoot -OutputDirectory $stageDirectory
                if (-not $audit.Success) { throw ($audit.Failures -join ' | ') }
                $message = 'Release source/test isolation static audit passed.'
            }
            'UnitTests' {
                $exitCode = Invoke-LoggedCommand -FilePath (Join-Path $ProjectRoot 'gradlew.bat') -Arguments @(':app:testDebugUnitTest','--stacktrace') -LogPath (Join-Path $stageDirectory 'gradle.log') -WorkingDirectory $ProjectRoot
                if ($exitCode -ne 0) { throw "testDebugUnitTest failed exit=$exitCode" }
                $message = 'JVM unit tests passed.'
            }
            'AssembleDebug' {
                $exitCode = Invoke-LoggedCommand -FilePath (Join-Path $ProjectRoot 'gradlew.bat') -Arguments @(':app:assembleDebug','--stacktrace') -LogPath (Join-Path $stageDirectory 'gradle.log') -WorkingDirectory $ProjectRoot
                if ($exitCode -ne 0) { throw "assembleDebug failed exit=$exitCode" }
                $message = 'Debug APK build passed.'
            }
            'UiSmoke' {
                $exitCode = Invoke-TestSuiteChildScript -ScriptsRoot $ScriptsRoot -ScriptName 'run-ui-smoke.ps1' -Arguments @('-Serial',$Serial,'-ReportDirectory',$stageDirectory,'-UseDeviceOwnerMaintenanceBridge') -StageDirectory $stageDirectory
                if ($exitCode -ne 0) {
                    $uiFailure = Get-UiSmokeFailureReason -StageDirectory $stageDirectory
                    if ([string]::IsNullOrWhiteSpace($uiFailure)) { throw "UI smoke failed exit=$exitCode" }
                    throw "UI smoke failed exit=${exitCode}: $uiFailure"
                }
                $message = 'Real-device UI smoke passed.'
            }
            'PerformanceBaseline' {
                $exitCode = Invoke-TestSuiteChildScript -ScriptsRoot $ScriptsRoot -ScriptName 'run-performance-baseline.ps1' -Arguments @('-Serial',$Serial,'-OutputDirectory',$stageDirectory) -StageDirectory $stageDirectory
                if ($exitCode -ne 0) { throw "performance baseline failed exit=$exitCode" }
                $message = 'Performance baseline passed.'
            }
            'CameraFaceSoak' {
                $minutes = if ($Profile -eq 'Smoke') { 2 } else { 5 }
                $args = @('-Serial',$Serial,'-Minutes',$minutes.ToString(),'-WarmupMinutes','0','-IntervalSeconds','30','-RequireFaceSdkProbe','-OutputDirectory',$stageDirectory)
                if ($Profile -eq 'Smoke') { $args += '-UseInstalledProductionApp' }
                $exitCode = Invoke-TestSuiteChildScript -ScriptsRoot $ScriptsRoot -ScriptName 'run-camera-face-soak.ps1' -Arguments $args -StageDirectory $stageDirectory
                if ($exitCode -ne 0) { throw "camera/face soak failed exit=$exitCode" }
                $message = "Camera + Face soak passed ($minutes min)."
            }
            'FacePunchStress' {
                $exitCode = Invoke-TestSuiteChildScript -ScriptsRoot $ScriptsRoot -ScriptName 'run-face-punch-stress.ps1' -Arguments @('-Serial',$Serial,'-Mode','All','-Count','10','-OutputDirectory',$stageDirectory) -StageDirectory $stageDirectory
                if ($exitCode -ne 0) { throw "face/punch stress failed exit=$exitCode" }
                $message = 'Face Native + Punch Pipeline stress x10 passed.'
            }
            'NetworkFault' {
                $exitCode = Invoke-TestSuiteChildScript -ScriptsRoot $ScriptsRoot -ScriptName 'run-network-fault-sync.ps1' -Arguments @('-Serial',$Serial,'-Scenario','AllSafe','-OutputDirectory',$stageDirectory) -StageDirectory $stageDirectory
                if ($exitCode -ne 0) { throw "network fault AllSafe failed exit=$exitCode" }
                $message = 'Network fault AllSafe suite passed.'
            }
            'NetworkDeviceOffline' {
                $exitCode = Invoke-TestSuiteChildScript -ScriptsRoot $ScriptsRoot -ScriptName 'run-network-fault-sync.ps1' -Arguments @('-Serial',$Serial,'-Scenario','DeviceOfflineRecovery','-OutputDirectory',$stageDirectory) -StageDirectory $stageDirectory
                if ($exitCode -ne 0) { throw "DeviceOfflineRecovery failed exit=$exitCode" }
                $message = 'Physical device offline/recovery passed.'
            }
            'DbStress1000' {
                $exitCode = Invoke-TestSuiteChildScript -ScriptsRoot $ScriptsRoot -ScriptName 'run-db-stress.ps1' -Arguments @('-Serial',$Serial,'-Count','1000','-OutputDirectory',$stageDirectory) -StageDirectory $stageDirectory
                if ($exitCode -ne 0) {
                    $dbFailure = Get-DbStressFailureReason -StageDirectory $stageDirectory
                    if ([string]::IsNullOrWhiteSpace($dbFailure)) { throw "DB stress 1000 failed exit=$exitCode" }
                    throw "DB stress 1000 failed exit=${exitCode}: $dbFailure"
                }
                $message = 'SQLite/offline queue stress 1000 passed.'
            }
            'DbStress5000' {
                $exitCode = Invoke-TestSuiteChildScript -ScriptsRoot $ScriptsRoot -ScriptName 'run-db-stress.ps1' -Arguments @('-Serial',$Serial,'-Count','5000','-OutputDirectory',$stageDirectory) -StageDirectory $stageDirectory
                if ($exitCode -ne 0) {
                    $dbFailure = Get-DbStressFailureReason -StageDirectory $stageDirectory
                    if ([string]::IsNullOrWhiteSpace($dbFailure)) { throw "DB stress 5000 failed exit=$exitCode" }
                    throw "DB stress 5000 failed exit=${exitCode}: $dbFailure"
                }
                $message = 'SQLite/offline queue stress 5000 passed.'
            }
            'ProcessRecovery' {
                $exitCode = Invoke-TestSuiteChildScript -ScriptsRoot $ScriptsRoot -ScriptName 'run-process-recovery.ps1' -Arguments @('-Serial',$Serial,'-Scenario','All','-OutputDirectory',$stageDirectory) -StageDirectory $stageDirectory
                if ($exitCode -ne 0) {
                    $processFailure = Get-ProcessRecoveryFailureReason -StageDirectory $stageDirectory
                    if ([string]::IsNullOrWhiteSpace($processFailure)) { throw "process recovery failed exit=$exitCode" }
                    throw "process recovery failed exit=${exitCode}: $processFailure"
                }
                $message = 'ForegroundKill + SyncKillRecovery passed.'
            }
            'CameraFaceRecoveryExperimental' {
                $exitCode = Invoke-TestSuiteChildScript -ScriptsRoot $ScriptsRoot -ScriptName 'run-camera-face-recovery.ps1' -Arguments @('-Serial',$Serial,'-Cycles','1','-OutputDirectory',$stageDirectory) -StageDirectory $stageDirectory
                if ($exitCode -ne 0) { throw "experimental V2.7.3 failed exit=$exitCode" }
                $message = 'Experimental Camera/Face kill recovery passed.'
            }
            'AssembleRelease' {
                $exitCode = Invoke-LoggedCommand -FilePath (Join-Path $ProjectRoot 'gradlew.bat') -Arguments @('clean',':app:assembleRelease','--stacktrace') -LogPath (Join-Path $stageDirectory 'gradle.log') -WorkingDirectory $ProjectRoot
                if ($exitCode -ne 0) { throw "assembleRelease failed exit=$exitCode" }
                $message = 'Release APK build completed; signing is checked separately.'
            }
            'ApkIsolationAudit' {
                $audit = Invoke-ReleaseApkIsolationAudit -ProjectRoot $ProjectRoot -OutputDirectory $stageDirectory
                if ($audit.Blocked) {
                    $status = 'BLOCKED'
                    $message = $audit.Message
                } elseif (-not $audit.Success) { throw $audit.Message }
                else { $message = $audit.Message }
            }
            'ReleaseReadinessAudit' {
                $audit = Invoke-ReleaseReadinessAudit -ProjectRoot $ProjectRoot -OutputDirectory $stageDirectory
                if ($audit.Blocked) {
                    $status = 'BLOCKED'
                    $message = $audit.Message
                } elseif (-not $audit.Success) { throw $audit.Message }
                else { $message = $audit.Message }
            }
            default { throw "Unknown test-suite stage: $Name" }
        }
    }
    catch {
        if ($Name -eq 'EnvironmentPreflight') { $status = 'BLOCKED' } else { $status = 'FAIL' }
        $message = $_.Exception.Message
        if ($exitCode -eq 0) { $exitCode = 1 }
        Write-Host "[$status] $Name - $message" -ForegroundColor Red
    }

    $finished = Get-Date
    return New-TestSuiteResult -Name $Name -Status $status -StartedAt $started -FinishedAt $finished -ExitCode $exitCode -Message $message -ReportDirectory $stageDirectory
}

function Invoke-TestSuiteRestoreSweep {
    param(
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$ScriptsRoot,
        [string]$Serial
    )

    if ([string]::IsNullOrWhiteSpace($Serial) -or -not (Test-Path $RunDirectory)) { return }
    $restoreScript = Join-Path $ScriptsRoot 'restore-production-from-report.ps1'
    $backupDirectories = @(Get-ChildItem -Path $RunDirectory -Directory -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -eq 'production-backup' })
    foreach ($backupDirectory in $backupDirectories) {
        $stageDirectory = $backupDirectory.Parent.FullName
        $transactionFiles = @(Get-ChildItem -Path $stageDirectory -Filter 'transaction.txt' -File -ErrorAction SilentlyContinue)
        $needsRestore = $true
        $uiSmokeSummary = Join-Path $stageDirectory 'ui-smoke-summary.txt'
        if (Test-Path $uiSmokeSummary) {
            $uiSummaryText = Get-Content -Path $uiSmokeSummary -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ($uiSummaryText -match '(?im)^production_restore_succeeded=true\s*$') {
                $needsRestore = $false
                Write-Host "[CLEANUP] Child harness already restored production state for $stageDirectory" -ForegroundColor DarkGray
            }
        }
        if ($needsRestore -and $transactionFiles.Count -gt 0) {
            $tx = Get-Content -Path $transactionFiles[0].FullName -Raw -Encoding UTF8
            if ($tx -match 'restore_failure=\s*(\r?\n|$)') {
                $needsRestore = $false
            }
        }
        if ($needsRestore) {
            Write-Host "[CLEANUP] Best-effort production restore from $stageDirectory" -ForegroundColor Yellow
            try {
                [void](Invoke-ChildPowerShellScript -ScriptPath $restoreScript -Arguments @('-Serial',$Serial,'-ReportDirectory',$stageDirectory) -LogPath (Join-Path $stageDirectory 'orchestrator-restore.log'))
            } catch {
                Write-Host "[WARN] Restore sweep failed for $stageDirectory : $($_.Exception.Message)" -ForegroundColor Yellow
            }
        }
    }
}

function Invoke-TestSuite {
    param(
        [Parameter(Mandatory = $true)][string]$Profile,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$ScriptsRoot,
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$Serial,
        [switch]$IncludeExperimental,
        [object[]]$PreviousResults = @(),
        [Parameter(Mandatory = $true)][string]$StatePath,
        [Parameter(Mandatory = $true)][datetime]$StartedAt
    )

    $stages = @(Get-TestSuiteStages -Profile $Profile -IncludeExperimental:$IncludeExperimental)
    $results = [System.Collections.Generic.List[object]]::new()
    $previousByName = @{}
    foreach ($previous in @($PreviousResults)) { $previousByName[[string]$previous.name] = $previous }
    $stopReason = $null

    foreach ($stage in $stages) {
        if ($null -ne $stopReason) {
            [void]$results.Add((New-TestSuiteResult -Name $stage -Status 'SKIPPED' -Message "Skipped after blocking gate: $stopReason"))
            continue
        }
        if ($previousByName.ContainsKey($stage) -and [string]$previousByName[$stage].status -eq 'PASS') {
            Write-Host "[RESUME] $stage already PASS; skipping execution." -ForegroundColor DarkGray
            [void]$results.Add($previousByName[$stage])
            continue
        }

        $result = Invoke-TestSuiteStage -Name $stage -Profile $Profile -ProjectRoot $ProjectRoot -ScriptsRoot $ScriptsRoot -RunDirectory $RunDirectory -Serial $Serial
        [void]$results.Add($result)
        Export-TestSuiteState -Path $StatePath -Profile $Profile -Serial $Serial -Results $results.ToArray() -StartedAt $StartedAt
        if ($result.status -in @('FAIL','BLOCKED')) { $stopReason = "$stage=$($result.status)" }
    }
    return $results.ToArray()
}
