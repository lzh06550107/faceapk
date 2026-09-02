# Face/Punch Stress V2.3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an unattended real-device stress harness for native face recognition and isolated local punch persistence, supporting 100/500/1000 iterations without production backend pollution.

**Architecture:** Add a same-package `facePunchStress` build type with test-only orchestration receiver/service. Split native face recognition stress from punch persistence stress, and drive both from a transactional PowerShell runner that backs up/restores the installed production APK and emits CSV/summary reports.

**Tech Stack:** Android Java, Baidu Face SDK, SQLite, PowerShell 5.1, ADB, existing PerformanceMetrics.ps1 and DeviceTestCommon.ps1.

**Spec:** `docs/superpowers/specs/2026-08-31-face-punch-stress-v2.3-design.md`

## Global Constraints

- Production `main/release/debug/smoke/deviceOwnerTest/cameraFaceSoak` must not expose V2.3 control components.
- Synthetic punch records must never call the production punch API.
- Native recognition mode must reuse device-local registered face data and must not export biometric data.
- The installed production APK must be backed up before the temporary same-package test upgrade and restored in `finally`.
- Windows PowerShell 5.1 compatibility is required.

---

### Task 1: Variant isolation and contracts

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/facePunchStress/AndroidManifest.xml`
- Create: `app/src/facePunchStress/res/values/test_flags.xml`
- Modify: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Produces build type `facePunchStress` with `applicationId=com.punch.app` and test-only manifest/resource overlay.

- [ ] Write static tests requiring the new variant and forbidding its receiver/service in production manifests.
- [ ] Run static suite and verify RED.
- [ ] Add minimal build type and manifest/resource overlay.
- [ ] Run static suite and verify GREEN.

### Task 2: Stress-safe persistence boundary

**Files:**
- Create: `app/src/main/java/com/punch/app/service/PunchPersistence.java`
- Modify: `app/src/main/java/com/punch/app/fragment/PunchFragment.java`
- Modify: `app/src/main/java/com/punch/app/db/DatabaseHelper.java`
- Create: `app/src/test/java/com/punch/app/service/PunchPersistenceTest.java`

**Interfaces:**
- Produces `PunchPersistence.persist(Context, PunchRecord, String queueAction)` returning boolean.
- Produces DB helpers to count/delete rows by `client_record_id` prefix and queue action.

- [ ] Write unit/static contracts for reusable persistence and stress cleanup helpers.
- [ ] Verify RED.
- [ ] Extract the existing insert+enqueue behavior into `PunchPersistence`, preserving production behavior.
- [ ] Add prefix count/delete helpers in DatabaseHelper.
- [ ] Verify unit/static GREEN.

### Task 3: Native face recognition stress engine

**Files:**
- Create: `app/src/facePunchStress/java/com/punch/app/stress/FaceStressEngine.java`
- Create: `app/src/facePunchStress/java/com/punch/app/stress/StressResult.java`
- Test: static contracts plus compile-with-stubs harness.

**Interfaces:**
- `FaceStressEngine.run(Context context, int count)` returns aggregate `StressResult`.
- Selects first active, registered employee whose local face file exists.
- Calls `FaceManager.recognizeFromBitmap()` per iteration and records latency/success.

- [ ] Add failing contracts for local face selection, repeated recognizeFromBitmap, bitmap recycle, and percentile result fields.
- [ ] Verify RED.
- [ ] Implement minimal engine.
- [ ] Compile with Android/FaceManager stubs and verify GREEN.

### Task 4: Punch pipeline stress engine

**Files:**
- Create: `app/src/facePunchStress/java/com/punch/app/stress/PunchStressEngine.java`
- Create: `app/src/facePunchStress/java/com/punch/app/stress/StressSnapshotFactory.java`
- Modify: `app/src/main/java/com/punch/app/utils/PunchSnapshotHelper.java` only if a reusable test-safe JPEG helper is needed.

**Interfaces:**
- `PunchStressEngine.run(Context context, int count)` creates `STRESS_V23_` client ids, local JPEG snapshots, `PunchRecord`s, and queue action `stress_punch_no_upload` through PunchPersistence.

- [ ] Add failing contracts for stress id prefix, no production action, one snapshot/record/queue per iteration, and cleanup capability.
- [ ] Verify RED.
- [ ] Implement engine and snapshot factory.
- [ ] Verify GREEN.

### Task 5: Test-only orchestration receiver

**Files:**
- Create: `app/src/facePunchStress/java/com/punch/app/stress/FacePunchStressReceiver.java`
- Modify: `app/src/facePunchStress/AndroidManifest.xml`

**Interfaces:**
- Actions: `com.punch.app.stress.RUN`, `STATUS`, `CLEANUP`.
- Extras: `mode`, `count`, `keep_artifacts`.
- Writes machine-readable state to app files and returns short ordered-broadcast status.

- [ ] Add failing contracts for actions/extras and variant-only registration.
- [ ] Verify RED.
- [ ] Implement receiver with a single background executor and explicit busy rejection.
- [ ] Verify GREEN.

### Task 6: PowerShell transactional runner and metrics

**Files:**
- Create: `scripts/run-face-punch-stress.ps1`
- Create: `scripts/lib/FacePunchStress.ps1`
- Modify: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Parameters: `-Serial`, `-Mode Face|Punch|All`, `-Count`, `-KeepArtifacts`, timeout/threshold switches.
- Produces `summary.txt`, `face-results.csv`, `punch-results.csv`, `metrics.csv`, `logcat.txt`, `fatal-events.txt`, and transaction logs.

- [ ] Add failing static contracts for transaction backup/build/install/run/status/report/restore and PowerShell 5.1 safety.
- [ ] Verify RED.
- [ ] Implement runner using existing DeviceTestCommon/PerformanceMetrics helpers.
- [ ] Verify GREEN.

### Task 7: Documentation and release verification

**Files:**
- Create: `FACE-PUNCH-STRESS-V2.3.md`
- Modify: `README.md`

**Interfaces:**
- Documents 10-count smoke, 100-count gate, then 500/1000 progression and recovery commands.

- [ ] Document commands, outputs, thresholds, and data-safety behavior.
- [ ] Run full static suite.
- [ ] Parse all manifests.
- [ ] Verify main/release sources do not expose stress receiver.
- [ ] Build/package overlay and verify SHA-256.
