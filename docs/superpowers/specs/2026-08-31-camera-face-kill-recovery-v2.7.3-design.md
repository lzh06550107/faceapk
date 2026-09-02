# Camera / Face Kill Recovery V2.7.3 Design

## Goal

Prove on the real Device Owner/Kiosk handset that when the whole `com.punch.app` process dies while the real Camera1 + Baidu Face SDK punch path is active, Android/Kiosk autonomously creates a new process and the app restores a usable punch state: production route, LockTask, camera, Face SDK, persisted face library, and real Detect/Feature/Search recognition.

## Scope

V2.7.3 is a test-only same-package Android variant plus PowerShell harness. It does not modify `app/src/main` and does not access the production backend or production `punch.db`.

Out of scope: device reboot, OTA, server-side validation, production employee data, repeated real punch persistence, and long-duration soak.

## Architecture

Create build type `cameraFaceRecoveryTest` with `applicationId=com.punch.app`, no suffix. Its Application subclass activates an isolated SQLite database and pins `ApiClient` to `http://127.0.0.1:1` before `PunchApplication.onCreate()`, while keeping `uiTestMode=false` so the real production Application/Kiosk/Camera/Face lifecycle runs.

Reuse the V2.2.8 runtime status concepts (`FaceManager.isInitialized()`, `getLoadedFaceCount()`, real Camera ownership from `dumpsys media.camera`) and the V2.7.1 autonomous-recovery rule (after kill, Harness does not call `am start`, monkey, launcher, or force-stop; it only observes a new PID and recovered Kiosk state).

Use a dedicated test database `punch_camera_face_recovery_v273.db` to avoid cross-test residue. This is stricter than reusing the V2.7.1 DB and preserves the approved isolation requirement.

Because production `UpdateManager` may resolve an absolute APK URL outside the `ApiClient` base override, the test Application also installs a test-only in-process update guard before `super.onCreate()`. It reflects the private `AUTO_UPDATE_RUNNING` `AtomicBoolean` to `true`, causing all production auto-update entry points to skip. This state is process-local and vanishes on process death / production APK restore; no production preference is changed.

## Deterministic Face Fixture

Bundle the already-validated public-domain portrait in the `cameraFaceRecoveryTest` source set only. Reserve employee ID `RECOVERY_FACE_V273`.

PREPARE waits for the real Face SDK to initialize, copies the fixture into the normal `files/faces/RECOVERY_FACE_V273.jpg` location, upserts one active employee in the isolated DB with `face_status=enabled`, `face_registered=1`, and `local_face_id=FACE_RECOVERY_FACE_V273`, resets Punch recognition state, and calls `PunchApplication.preparePunchRecognitionData()`.

The application must use the production preparation path to rebuild FaceSearch from SQLite + the persisted image. The test engine does not push the feature directly into FaceSearch.

Before the first kill, the engine decodes the fixture and calls the real `FaceManager.recognizeFromBitmap()`. The result must match `RECOVERY_FACE_V273`.

## Kill / Recovery Cycle

For each cycle (default 3):

1. Confirm old PID, production Kiosk route, Device Owner, active LockTask, active Camera client, Face SDK initialized, Punch data ready, and loaded face count >= 1.
2. Run real fixture recognition and require `empId=RECOVERY_FACE_V273`.
3. Through a test-only receiver, persist kill evidence and call `Process.killProcess(Process.myPid())` after the broadcast result is returned.
4. Harness waits for old PID to disappear and a different PID to appear. During this observation it must not start an Activity.
5. Require autonomous Kiosk recovery, camera active for `com.punch.app`, `FaceManager.isInitialized()=true`, `PunchApplication.isPunchRecognitionReady()=true`, and loaded face count >= 1.
6. Run the real fixture recognition again in the recovered process and require the same employee match.

A single failed cycle fails V2.7.3.

## Isolation

- Database: `punch_camera_face_recovery_v273.db` only.
- API: `http://127.0.0.1:1` only; no non-loopback URL is allowed in the test-only network controller or PowerShell harness.
- Fixture employee/image/mapping are test-owned and are removed during CLEANUP.
- The production package is backed up before installing the same-package test build.
- As with V2.6.2/V2.7.1, install transitions are made cold: test-only TERMINATE + wait for old PID exit before first test preflight and before restoring production APK.

## Hard Gates

Per cycle:

- `old_pid != new_pid`
- autonomous restart observed without Harness Activity launch
- expected production route is foreground
- Device Owner true
- LockTask state LOCKED/PINNED
- camera active and owned by `com.punch.app`
- Face SDK initialized
- Punch recognition ready
- loaded face count >= 1
- real post-restart recognition matched `RECOVERY_FACE_V273`
- no unexpected app Crash/ANR/OOM/SIGSEGV

Final restoration:

- production APK restored
- production Kiosk route foreground
- Device Owner true
- LockTask active

Only after final production restoration may `RESULT: PASS` be printed.

## Reports

`test-results/<timestamp>-camera-face-recovery-v273/` contains:

- `pre-kill-health.json`
- `pre-kill-recognition.json`
- `cycle-NN-kill.json`
- `cycle-NN-autonomous-recovery.txt`
- `cycle-NN-camera-face-health.json`
- `cycle-NN-recognition.json`
- `kill-events.jsonl`
- `fatal-events.txt`
- `logcat.txt`
- `restore-kiosk-state.txt`
- `summary.txt`
- `transaction.txt`
