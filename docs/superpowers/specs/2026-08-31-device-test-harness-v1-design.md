# Device Test Harness V1 Design

## Goal

Provide a Windows-first, one-command real-device test harness for the Punch Android app so a developer can validate the connected device, run JVM and smoke instrumentation tests, collect diagnostic evidence, and receive a deterministic PASS/FAIL result without manually issuing ADB and Gradle commands.

## Scope

V1 covers non-destructive daily/regression testing only. It must not factory reset a device, clear app data, set or remove Device Owner, reboot the device, or uninstall the production package.

## Architecture

The harness is split into four user-facing PowerShell entry points plus one shared library:

- `scripts/run-device-tests.ps1`: primary daily one-command gate. Runs local JVM tests and then real-device smoke tests.
- `scripts/run-ui-smoke.ps1`: builds the `smoke` app/test APKs, explicitly targets one ADB serial, installs both APKs, runs instrumentation, captures logs/metrics, scans for fatal indicators, and writes a summary.
- `scripts/run-regression.ps1`: heavier release-oriented gate. Runs JVM tests, debug/release assembly, lint, then real-device smoke unless explicitly skipped.
- `scripts/collect-device-metrics.ps1`: standalone snapshot collector for device/app diagnostics.
- `scripts/lib/DeviceTestCommon.ps1`: shared environment checks, device selection, process execution, ADB helpers, report creation, metrics capture, and fatal-log detection.

## Device Selection Safety

All ADB operations must use an explicit `adb -s <serial>` target. If `-Serial` is omitted, the harness may auto-select only when exactly one online `device` is present. Zero online devices or multiple online devices are hard failures with actionable messages.

The harness must not use destructive Device Owner commands in V1.

## Smoke Variant

Instrumentation tests target the `smoke` build type. `app/build.gradle` must set `testBuildType "smoke"` so Android Gradle Plugin creates `assembleSmokeAndroidTest` and the smoke test APK.

The smoke source set must contain every host activity referenced by `app/src/androidTest`, including `UiTestKioskHomeHostActivity`. The debug source set must contain `UiTestSetupWizardHostActivity` so the existing `lintDebug` manifest inconsistency is removed.

## Test Flow

`run-device-tests.ps1`:

1. Validate project root, ADB, Java 17, Gradle wrapper, and one target device.
2. Create `test-results/<timestamp>/`.
3. Capture device metadata.
4. Run `:app:testDebugUnitTest` and log output.
5. Invoke `run-ui-smoke.ps1` using the same serial and report directory.
6. Write a top-level summary and exit non-zero on any failed gate.

`run-ui-smoke.ps1`:

1. Validate environment and serial.
2. Build `:app:assembleSmoke` and `:app:assembleSmokeAndroidTest`.
3. Resolve generated app and androidTest APK files.
4. Clear logcat.
5. Capture before-test device/app metrics.
6. Install smoke app and smoke test APK via `adb -s <serial> install -r -t`.
7. Run `adb -s <serial> shell am instrument -w -r com.punch.app.smoke.test/com.punch.app.test.UiSmokeTestRunner`.
8. Capture after-test metrics and logcat even when instrumentation fails.
9. Fail if instrumentation does not report `OK (` or contains known failure markers.
10. Fail if app-specific crash/ANR/OOM/native-fatal indicators are found.
11. Write a human-readable summary.

`run-regression.ps1`:

1. Run `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleRelease`, and `:app:lintDebug` as separately logged gates.
2. Unless `-SkipDevice` is supplied, invoke `run-ui-smoke.ps1`.
3. Write a summary and propagate non-zero exit on failure.

## Reports

Each run gets a timestamped report directory. Expected evidence includes:

- `summary.txt`
- `device-info.txt`
- Gradle logs for each gate
- `instrumentation.txt`
- `logcat.txt`
- `fatal-events.txt`
- before/after metrics files (`meminfo`, `battery`, `thermal`, `cpuinfo`, `disk`)

## Environment Contracts

- Windows PowerShell 5.1+ or PowerShell 7+
- JDK major version 17
- Gradle wrapper usable from project root
- ADB available on PATH
- Android device state must be `device`
- Default package under smoke: `com.punch.app.smoke`
- Default instrumentation target: `com.punch.app.smoke.test/com.punch.app.test.UiSmokeTestRunner`

## Non-goals for V1

- 8h/24h/72h soak automation
- Perfetto/heapprofd capture
- Device Owner provisioning/removal
- automatic reboot/process-kill destructive scenarios
- network fault injection
- HTML/chart performance dashboards
