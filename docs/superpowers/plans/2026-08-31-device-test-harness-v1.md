# Device Test Harness V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Build a safe one-command Windows/ADB real-device quality gate for the Punch Android app.

**Architecture:** Keep Gradle responsible for building/test artifacts and PowerShell responsible for deterministic device selection, ADB execution, diagnostics, and reporting. Shared device/process logic lives in one library so daily, smoke, regression, and metrics entry points stay focused.

**Tech Stack:** Android Gradle Plugin 8.1.4, Gradle 8.0, Java 17, Android instrumentation, ADB, PowerShell 5.1+.

**Spec:** `docs/superpowers/specs/2026-08-31-device-test-harness-v1-design.md`

## Global Constraints

- Preserve Java 8 source compatibility and existing Android dependencies.
- Do not introduce Kotlin or upgrade dependencies/build tooling.
- V1 must not execute `dpm set-device-owner`, factory reset, `pm clear`, reboot, or production-package uninstall operations.
- Every ADB operation must explicitly target one serial with `-s`.
- Smoke instrumentation must target `com.punch.app.smoke`.
- PowerShell scripts are Windows-first and must fail with non-zero exit codes when a gate fails.

---

### Task 1: Establish static harness contract tests

**Files:**
- Create: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Consumes: repository file layout and agreed V1 design.
- Produces: standard-library test suite that detects missing Gradle smoke configuration, source-set hosts, script entry points, safety constraints, and expected test commands.

- [x] Write static tests that fail against the current baseline because the smoke androidTest task configuration, host source files, and scripts are missing.
- [x] Run `python scripts/tests/test_harness_static.py` and verify RED failures are caused by missing V1 functionality.

### Task 2: Enable the smoke instrumentation variant

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/smoke/java/com/punch/app/activity/UiTestKioskHomeHostActivity.java`
- Create: `app/src/debug/java/com/punch/app/activity/UiTestSetupWizardHostActivity.java`
- Modify: `app/src/smoke/AndroidManifest.xml`

**Interfaces:**
- Consumes: existing `androidTest` classes.
- Produces: Gradle smoke androidTest variant and complete debug/smoke host activity sets.

- [x] Add `testBuildType "smoke"` to the Android configuration.
- [x] Add the smoke Kiosk host and manifest registration matching the existing debug host behavior.
- [x] Add the debug Setup Wizard host matching the existing smoke host behavior so lint can resolve the manifest class.
- [x] Run static contract tests and confirm the smoke variant/source-set assertions pass.

### Task 3: Implement shared PowerShell device/test helpers

**Files:**
- Create: `scripts/lib/DeviceTestCommon.ps1`

**Interfaces:**
- Produces: `Resolve-ProjectRoot`, `Resolve-AndroidSerial`, `Invoke-LoggedCommand`, `Invoke-Adb`, `New-TestReportDirectory`, `Write-DeviceInfo`, `Collect-DeviceSnapshot`, `Find-AppFatalEvents`, and environment validation helpers.

- [x] Implement project/tool/device validation with explicit single-device selection.
- [x] Implement logged command execution that preserves exit codes.
- [x] Implement ADB helper that always emits `-s <serial>`.
- [x] Implement report directory, device metadata, metrics, and fatal-log collection.
- [x] Run static contract tests and confirm destructive-command and explicit-serial safety assertions pass.

### Task 4: Implement smoke and daily one-command runners

**Files:**
- Create: `scripts/run-ui-smoke.ps1`
- Create: `scripts/run-device-tests.ps1`

**Interfaces:**
- `run-ui-smoke.ps1` consumes `-Serial`, optional `-ReportDirectory`, and shared helpers; produces smoke build/instrumentation evidence and a non-zero failure code.
- `run-device-tests.ps1` consumes optional `-Serial`; produces a timestamped daily report that includes JVM test and UI smoke gates.

- [x] Build smoke app/test APKs and resolve generated artifacts.
- [x] Install through explicit serial and run the smoke instrumentation runner.
- [x] Guarantee after-test diagnostics in a `finally` block.
- [x] Parse instrumentation and app fatal logs into PASS/FAIL.
- [x] Add daily wrapper that runs JVM tests then smoke using one shared report directory.
- [x] Run static contract tests.

### Task 5: Implement regression and standalone metrics entry points

**Files:**
- Create: `scripts/run-regression.ps1`
- Create: `scripts/collect-device-metrics.ps1`

**Interfaces:**
- `run-regression.ps1` runs four local Gradle gates and optionally device smoke.
- `collect-device-metrics.ps1` captures a named snapshot for one serial/package.

- [x] Add separately logged JVM, debug assembly, release assembly, and lint gates.
- [x] Add `-SkipDevice` for non-device regression while keeping device smoke as the default full regression behavior.
- [x] Add standalone device metrics snapshot command.
- [x] Run static contract tests and ensure all contracts pass.

### Task 6: Verify and package

**Files:**
- Create: `TEST-HARNESS-V1.md`
- Package: `/mnt/data/faceapk-device-test-harness-v1.zip`

**Interfaces:**
- Produces: usage documentation and a portable patch/full-source package for the user.

- [x] Document the primary commands, outputs, safety behavior, and known limitation that real ADB/Gradle device execution was not performed inside the container.
- [x] Run `python scripts/tests/test_harness_static.py`.
- [x] Run XML parsing and shell/text checks on all modified files.
- [x] Compare working copy to uploaded baseline and confirm only intended V1 files changed.
- [x] Create the ZIP package and provide its checksum.
