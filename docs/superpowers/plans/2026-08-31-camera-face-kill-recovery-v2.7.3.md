# Camera / Face Kill Recovery V2.7.3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Build a one-command same-package real-device harness proving Camera1 + Baidu Face SDK + persisted FaceSearch recover autonomously after whole-process death.

**Architecture:** Add `cameraFaceRecoveryTest`, activate a dedicated test DB and loopback-only network before production `PunchApplication` lifecycle, seed one bundled fixture employee through normal SQLite/face-file storage, use production face-library rebuild and real recognition, then kill the process and only observe autonomous recovery. Reuse V2.7.1 PowerShell Kiosk/PID gates and V2.2.8 runtime Face metrics patterns.

**Tech Stack:** Android Java 8, Camera1, Baidu Face Native SDK, SQLite, BroadcastReceiver, PowerShell 5.1, ADB, Python unittest static contracts.

**Spec:** `docs/superpowers/specs/2026-08-31-camera-face-kill-recovery-v2.7.3-design.md`

## Global Constraints

- Same package `com.punch.app`; no `applicationIdSuffix`.
- No changes under `app/src/main`.
- Production `punch.db` must not be selected.
- Production backend requests must be zero; network controller is loopback-only.
- Post-kill recovery observation must not issue Activity-start/monkey/launcher/force-stop commands.
- Default cycles = 3; support `-Cycles 1` for fast validation.
- Final `RESULT: PASS` is printed only after production APK/Kiosk restoration gate passes.

---

### Task 1: Static contracts for the V2.7.3 safety boundary

**Files:**
- Modify: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Consumes: repository paths and existing static-test helpers.
- Produces: `CameraFaceRecoveryV273ContractTest` covering variant, isolation, fixture, runtime probe, autonomous recovery, reports, PS5.1 encoding, and production-source zero-scope assumptions.

- [x] Write tests that require `cameraFaceRecoveryTest`, no suffix, custom Application, dedicated DB, loopback-only network, bundled fixture, PREPARE/STATUS/RECOGNIZE/KILL/CLEANUP/TERMINATE actions, real `preparePunchRecognitionData()` and `recognizeFromBitmap()`, no Activity launch inside automatic-recovery helper, `-Cycles`, and final restore-before-result ordering.
- [x] Run `python -m unittest scripts.tests.test_harness_static.CameraFaceRecoveryV273ContractTest -v` and confirm RED because files do not exist.

### Task 2: Android test-only variant and deterministic fixture

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/cameraFaceRecoveryTest/AndroidManifest.xml`
- Create: `app/src/cameraFaceRecoveryTest/res/values/test_flags.xml`
- Create: `app/src/cameraFaceRecoveryTest/res/raw/recovery_face_fixture.jpg`
- Create: `app/src/cameraFaceRecoveryTest/java/com/punch/app/db/CameraFaceRecoveryDatabaseController.java`
- Create: `app/src/cameraFaceRecoveryTest/java/com/punch/app/network/CameraFaceRecoveryNetworkController.java`
- Create: `app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryTestApplication.java`
- Create: `app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryUpdateGuard.java`
- Create: `app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryUpdateGuard.java`

**Interfaces:**
- Produces: `CameraFaceRecoveryDatabaseController.activateExisting/prepareFresh/get/cleanup/isActive`, `CameraFaceRecoveryNetworkController.reset/getBaseUrl`, Application lifecycle isolation.

- [x] Add build type with `initWith debug`, debug signing, no suffix, and `matchingFallbacks=['debug']`.
- [x] Override `camera_face_soak_auto_enable=true` and `face_punch_stress_bypass_prechecks=true` only in this source set.
- [x] Copy the validated V2.3 fixture bytes into the new raw resource.
- [x] Activate dedicated DB and loopback controller before `super.onCreate()`, explicitly keep UI/maintenance bypasses false.
- [x] Block production background OTA in the test process before `super.onCreate()` via test-only `AUTO_UPDATE_RUNNING` guard.
- [x] Before `super.onCreate()`, set the test-process-only `UpdateManager.AUTO_UPDATE_RUNNING` guard true so absolute OTA URLs cannot bypass the loopback ApiClient override.
- [x] Manifest installs the custom Application, exported test-only receiver, and removes `UpdateInstallStateReceiver`.
- [x] Run V2.7.3 static tests and XML parse checks.

### Task 3: Face fixture preparation, health, recognition, and kill receiver

**Files:**
- Create: `app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryFixture.java`
- Create: `app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryEngine.java`
- Create: `app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryTestReceiver.java`

**Interfaces:**
- `prepare(Context) -> JSONObject`
- `health(Context) -> JSONObject`
- `recognize(Context) -> JSONObject`
- receiver STATUS returns key/value state; async PREPARE/RECOGNIZE writes JSON files; KILL writes evidence and terminates process.

- [x] PREPARE waits for Face SDK initialization, uses `CameraFaceRecoveryDatabaseController.prepareFresh`, copies raw fixture to `FaceFileManager.getFaceImagePath`, upserts `RECOVERY_FACE_V273`, resets Punch recognition state, calls `preparePunchRecognitionData`, waits for ready + loaded face, then performs real recognition.
- [x] HEALTH reports DB active, Face initialized, loaded face count, Punch ready/preparing/status, fixture row/image existence.
- [x] RECOGNIZE decodes persisted fixture and calls `FaceManager.recognizeFromBitmap`, requiring the fixture employee ID.
- [x] KILL writes `cycle-NN-kill.json` and `kill-events.jsonl`, sends broadcast result, then `Process.killProcess(Process.myPid())`.
- [x] CLEANUP removes runtime face, fixture employee, SDK mapping, fixture image, independent DB; TERMINATE performs cleanup and kills the process.
- [x] Run static tests and Java 8 stub compilation of all V2.7.3 test-only Java.

### Task 4: PowerShell autonomous camera/face recovery harness

**Files:**
- Create: `scripts/lib/CameraFaceRecovery.ps1`
- Create: `scripts/run-camera-face-recovery.ps1`

**Interfaces:**
- Reuse common APK backup/restore/fatal-event helpers.
- `Wait-CameraFaceRecoveryAutomaticReady` polls PID/Kiosk/Camera and receiver STATUS without issuing Activity-start commands.

- [x] Add CLI `-Serial`, `-Cycles=3`, recovery/prepare timeouts, timestamped output directory.
- [x] Backup production APK, build/install `assembleCameraFaceRecoveryTest`, perform cold test transition via TERMINATE + old-PID exit, then start Kiosk only for initial preflight.
- [x] PREPARE fixture and save pre-kill health/recognition evidence.
- [x] For each cycle: save old PID, request KILL, wait old PID exit, observe autonomous new PID/Kiosk/Camera/Face/Punch-ready, save health, run real recognition, require fixture match.
- [x] Detect unexpected fatal events while excluding the deliberate self-kill marker from failure semantics.
- [x] In `finally`, CLEANUP, TERMINATE test process, restore production APK, explicitly start Kiosk only for final restore, gate production route/Device Owner/LockTask.
- [x] Write summary/transaction and print final result only after restoration gate.
- [x] Ensure PS 5.1 UTF-8 BOM + CRLF and no `$pid=` assignment.

### Task 5: Documentation and package replay verification

**Files:**
- Create: `CAMERA-FACE-RECOVERY-V2.7.3.md`
- Modify: `README.md`
- Package: `/mnt/data/faceapk-camera-face-kill-recovery-v2.7.3-overlay.zip`

**Interfaces:**
- Produces user command and incremental overlay over V2.7.1/V2.6.2 baseline.

- [x] Document `-Cycles 1` and default `-Cycles 3`, isolation, autonomous-recovery definition, expected report files, and interpretation of failures.
- [x] Run full `python -m unittest scripts.tests.test_harness_static -v`.
- [x] Parse every AndroidManifest XML.
- [x] Run Java 8 stub compile for test-only Java and loopback/static smoke where applicable.
- [x] Verify `app/src/main` hash tree unchanged from pre-V2.7.3 baseline.
- [x] Build clean incremental ZIP, replay ZIP over a clean V2.7.1 baseline copy, rerun full static suite, and calculate SHA-256.
