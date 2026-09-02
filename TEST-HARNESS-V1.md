# Punch App Device Test Harness V1

## What this version provides

V1 turns the existing Android/JVM test suite into repeatable Windows real-device commands. It adds a `smoke` instrumentation target, completes the test host source sets, explicitly binds every ADB action to one device serial, collects diagnostics, scans for fatal events, and stores evidence in timestamped report directories.

V1 is intentionally non-destructive. It does **not** set/remove Device Owner, clear app data, reboot/factory-reset the device, or uninstall the production package.

## Prerequisites

From the project root, these must work:

```powershell
adb devices -l
java -version
.\gradlew.bat --version
```

Expected project baseline:

- JDK 17
- Gradle 8.x (current baseline: 8.0)
- Android SDK configured by the existing `local.properties`
- One USB-debug-authorized Android device, or pass `-Serial` explicitly

## 1. Recommended daily command

When exactly one device is connected:

```powershell
.\scripts\run-device-tests.ps1
```

With an explicit serial (recommended for lab/production-like environments):

```powershell
.\scripts\run-device-tests.ps1 -Serial 0010202606017717
```

This runs:

1. Java/Gradle/ADB/device checks
2. `:app:testDebugUnitTest`
3. smoke APK + smoke androidTest APK build
4. explicit-serial APK installation
5. real-device instrumentation tests
6. before/after memory, battery, thermal, CPU and disk snapshots
7. logcat capture
8. app-specific Crash/ANR/OOM/native-fatal scan
9. final PASS/FAIL summary

## 2. UI smoke only

```powershell
.\scripts\run-ui-smoke.ps1 -Serial 0010202606017717
```

Use this after UI/lifecycle changes when JVM tests have already run.

## 3. Full regression

```powershell
.\scripts\run-regression.ps1 -Serial 0010202606017717
```

Local gates:

```text
:app:testDebugUnitTest
:app:assembleDebug
:app:assembleRelease
:app:lintDebug
```

Then it runs real-device smoke.

To run only local gates without touching a device:

```powershell
.\scripts\run-regression.ps1 -SkipDevice
```

## 4. Metrics snapshot only

```powershell
.\scripts\collect-device-metrics.ps1 `
  -Serial 0010202606017717 `
  -PackageName com.punch.app.smoke
```

This captures:

- `dumpsys meminfo`
- `dumpsys battery`
- `dumpsys thermalservice`
- `dumpsys cpuinfo`
- `/data` disk usage
- device/build metadata

## Reports

Reports are written under:

```text
test-results\yyyyMMdd-HHmmss-<run-type>\
```

A typical daily run contains:

```text
summary.txt
device-info.txt
gradle-unit-test.log
smoke-build.log
install-app.log
install-test.log
instrumentation.txt
logcat.txt
fatal-events.txt
before-meminfo.txt
before-battery.txt
before-thermal.txt
before-cpuinfo.txt
before-disk.txt
after-meminfo.txt
after-battery.txt
after-thermal.txt
after-cpuinfo.txt
after-disk.txt
ui-smoke-summary.txt
```

## Multiple-device safety

If more than one online ADB device exists and `-Serial` is omitted, the harness fails instead of picking a device.

Use:

```powershell
adb devices -l
.\scripts\run-device-tests.ps1 -Serial <target-serial>
```

All device-changing ADB calls use the equivalent of:

```text
adb -s <serial> ...
```

## Execution policy

If Windows blocks local scripts, run the entry point through an explicit process policy override:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\run-device-tests.ps1 `
  -Serial 0010202606017717
```

This changes policy for that process only.

## Smoke Gradle change

V1 adds:

```gradle
testBuildType "smoke"
```

so AGP generates the smoke instrumentation variant, including `assembleSmokeAndroidTest`.

The source-set host mismatch is also corrected:

- `smoke`: adds `UiTestKioskHomeHostActivity`
- `debug`: adds `UiTestSetupWizardHostActivity`
- smoke manifest registers the Kiosk test host

## V1 limitations / V2 direction

V1 is a fast correctness/regression harness. The next layer should add long-running performance suites for:

- 8h Camera/Face memory soak
- Java/Native/PSS trend sampling
- 24h/72h stability
- Face SDK rebuild stress
- SQLite large-data stress
- Device Owner reboot/kill/Kiosk recovery
- OTA interruption/recovery
- Perfetto/heapprofd capture and trend reports
