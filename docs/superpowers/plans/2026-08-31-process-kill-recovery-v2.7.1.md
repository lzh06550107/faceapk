# V2.7.1 Process Kill Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Build a transactional real-device harness that proves autonomous Kiosk recovery from abrupt process death and consistent recovery of a real production punch sync interrupted mid-flight.

**Architecture:** Add a same-package `processRecoveryTest` build type that runs normal production lifecycle/Kiosk code while selecting an isolated SQLite DB and loopback-only network. A test-only receiver creates deterministic kill points; PowerShell observes autonomous PID/Kiosk recovery and restores the exact production APK before final PASS.

**Tech Stack:** Android Java 8, Gradle Android plugin, SQLite, existing `DatabaseHelper`/`SyncService`/`SyncCoordinator`, OkHttp, PowerShell 5.1, adb, Python unittest static contracts.

**Spec:** `docs/superpowers/specs/2026-08-31-process-kill-recovery-v2.7.1-design.md`

## Global Constraints

- Package remains exactly `com.punch.app`; no `applicationIdSuffix`.
- Test database is exactly `punch_process_recovery_v271.db`.
- Test row prefix is exactly `PROC_V271_`.
- Test process may access only `127.0.0.1` endpoints.
- `PunchApplication.uiTestMode` must remain disabled in V2.7.1.
- Production `app/src/main` behavior must remain unchanged.
- Final `RESULT: PASS` is emitted only after exact production APK restore and Device Owner/LockTask/Kiosk gate success.
- The temporary test APK must be cold-started after install: terminate any surviving same-package PID before the first Kiosk preflight, then require `database_active=true`.

---

### Task 1: Variant and isolation contract

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/processRecoveryTest/AndroidManifest.xml`
- Create: `app/src/processRecoveryTest/java/com/punch/app/processrecovery/ProcessRecoveryTestApplication.java`
- Create: `app/src/processRecoveryTest/java/com/punch/app/db/ProcessRecoveryDatabaseController.java`
- Create: `app/src/processRecoveryTest/java/com/punch/app/network/ProcessRecoveryNetworkController.java`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Produces `processRecoveryTest` build type, `ProcessRecoveryDatabaseController.get/prepareFresh/cleanup`, and `ProcessRecoveryNetworkController.reset/startLoopback`.

- [x] Write static tests requiring the build type, manifest, isolated DB name, loopback-only URL, and absence of `setUiTestModeForTest(true)`.
- [x] Run the focused unittest and verify RED because files/build type are missing.
- [x] Implement the minimal variant/Application/controllers.
- [x] Re-run focused tests and verify GREEN.

### Task 2: Slow loopback server and SQLite engine

**Files:**
- Create: `app/src/processRecoveryTest/java/com/punch/app/network/ProcessRecoveryServer.java`
- Create: `app/src/processRecoveryTest/java/com/punch/app/processrecovery/ProcessRecoveryTestEngine.java`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- `ProcessRecoveryServer(long punchDelayMs)` exposes port and punch request count.
- `ProcessRecoveryTestEngine.prepareSyncKill(Context)` seeds exactly 100 rows.
- `ProcessRecoveryTestEngine.verifySyncRestart(Context)` validates restart invariants.
- `ProcessRecoveryTestEngine.resumeSyncAndCleanup(Context)` drains remaining queue and cleans test rows.

- [x] Write tests requiring real `PunchRecord` + `punch_push`, 100-row seed, partial-sync checkpoint, integrity/duplicate/queue invariants, delayed loopback punch responses, and real `SyncService.triggerSync`.
- [x] Run focused tests and verify RED.
- [x] Implement server and engine by adapting proven V2.6 patterns without changing main code.
- [x] Re-run focused tests and verify GREEN.

### Task 3: Kill receiver

**Files:**
- Create: `app/src/processRecoveryTest/java/com/punch/app/processrecovery/ProcessRecoveryTestReceiver.java`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Actions: `STATUS`, `PREPARE_SYNC_KILL`, `KILL_FOREGROUND`, `VERIFY_SYNC_RESTART`, `RESUME_SYNC`, `CLEANUP`, `TERMINATE`.

- [x] Write tests requiring test-only exported receiver and `Process.killProcess(Process.myPid())` for kill actions.
- [x] Run focused tests and verify RED.
- [x] Implement receiver with asynchronous file result writing and deterministic kill scheduling.
- [x] Re-run focused tests and verify GREEN.

### Task 4: PowerShell autonomous recovery harness

**Files:**
- Create: `scripts/lib/ProcessRecovery.ps1`
- Create: `scripts/run-process-recovery.ps1`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- `Wait-ProcessRecoveryAutomaticKioskReady` observes new PID + resumed expected route + Device Owner + LockTask without starting an Activity.
- Runner supports `ForegroundKill`, `SyncKillRecovery`, `All`.

- [x] Write tests requiring production backup/restore, `assembleProcessRecoveryTest`, PID-before/after checks, autonomous wait before any recovery `am start`, scenario actions, fatal scan, and final production Kiosk gate.
- [x] Add regression forbidding PowerShell `$PID` shadowing and non-loopback network targets.
- [x] Run focused tests and verify RED.
- [x] Implement library/runner with transactional `try/finally` production restore.
- [x] Re-run focused tests and verify GREEN.

### Task 5: Documentation and full verification

**Files:**
- Create: `PROCESS-RECOVERY-V2.7.1.md`
- Modify: `README.md`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Documents one-command real-device commands and result interpretation.

- [x] Add documentation contract test and verify RED.
- [x] Write docs and README entry.
- [x] Run `python3 -m unittest scripts.tests.test_harness_static` and require zero failures.
- [x] Parse all Android manifests.
- [x] Java 8 stub-compile all V2.7.1 test-only Java files.
- [x] Run a real local socket smoke against `ProcessRecoveryServer` response modes if possible in the container.
- [x] Verify PowerShell UTF-8 BOM/CRLF compatibility.
- [x] Verify `app/src/main` has zero diff relative to the V2.6.2 baseline.
- [x] Build a clean incremental ZIP + SHA-256 and replay it over a clean V2.6.2 baseline; rerun full static verification.
