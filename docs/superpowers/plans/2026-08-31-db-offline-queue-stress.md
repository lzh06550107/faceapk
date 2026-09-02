# V2.6 Database / Offline Queue Stress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Build an unattended 1k/5k/10k SQLite/offline-queue stress harness that uses production persistence and sync code while isolating all test state from production DB and backend.

**Architecture:** Add a same-package `dbStressTest` build type with a test-only Application, HostActivity, Receiver, database controller, loopback server, and stress engine. Seed a dedicated SQLite DB through `DatabaseHelper`, self-restart the process, drain the real `punch_push` queue through production `SyncService/SyncCoordinator`, then cleanup/VACUUM and report metrics. A PowerShell wrapper performs transactional APK backup/install/restore and gathers logs/performance snapshots.

**Tech Stack:** Android Java 8, SQLite/SQLiteOpenHelper, production SyncService/SyncCoordinator/ApiService/OkHttp, test-only ServerSocket, Windows PowerShell 5.1, Python unittest static contracts.

**Spec:** `docs/superpowers/specs/2026-08-31-db-offline-queue-stress-design.md`

## Global Constraints

- Same application ID `com.punch.app`; no `applicationIdSuffix` for `dbStressTest`.
- Production `app/src/main` behavior must not change except reuse of already-existing DatabaseHelper/ApiClient test hooks; V2.6 source stays test-only.
- Test DB must be exactly `punch_db_stress_v26.db` and must be deleted during cleanup.
- HTTP must target only `127.0.0.1`.
- Supported counts are exactly 1000, 5000, 10000.
- Production SyncService/SyncCoordinator must execute the drain; no fake queue-drain implementation.
- PowerShell files must be UTF-8 BOM + CRLF for Windows PowerShell 5.1.

---

### Task 1: Variant and isolation shell

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/dbStressTest/AndroidManifest.xml`
- Create: `app/src/dbStressTest/java/com/punch/app/db/DbStressDatabaseController.java`
- Create: `app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestApplication.java`
- Create: `app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestHostActivity.java`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Produces `DbStressDatabaseController.TEST_DB_NAME`, `activateExisting(Context)`, `prepareFresh(Context)`, `cleanup(Context)`.
- Produces exported test-only HostActivity and Receiver manifest surface.

- [x] Write a static contract test asserting same-package build type, dedicated DB name, UI-test startup bypass, HostActivity, OTA receiver removal, and absence from other variants.
- [x] Run the focused test and confirm RED because `dbStressTest` does not exist.
- [x] Add the build type, manifest, Application, HostActivity, and DB controller with minimal code.
- [x] Re-run the focused test and confirm GREEN.

### Task 2: Loopback success server and production sync target

**Files:**
- Create: `app/src/dbStressTest/java/com/punch/app/dbstress/DbStressServer.java`
- Create: `app/src/dbStressTest/java/com/punch/app/dbstress/DbStressNetworkController.java`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- `DbStressNetworkController.startLoopback()` returns a running `DbStressServer`, installs a short-timeout OkHttp client, and sets ApiClient base URL to `http://127.0.0.1:<port>`.
- Server exposes `getPunchRequestCount()` and returns production-compatible `/clock/upload` success; heartbeat returns controlled 503.

- [x] Write a static contract test for loopback-only ApiClient hooks and punch/heartbeat responses.
- [x] Run focused test and confirm RED.
- [x] Implement minimal server/controller.
- [x] Run focused test and confirm GREEN.

### Task 3: Seed/query/restart/drain/cleanup engine

**Files:**
- Create: `app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestEngine.java`
- Create: `app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestReceiver.java`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Receiver actions: `PREPARE`, `VERIFY_RESTART`, `DRAIN`, `CLEANUP`, `STATUS`.
- `PREPARE` accepts integer `count`, creates DB, seeds and measures it, writes `prepare.json`, then schedules process self-termination.
- `VERIFY_RESTART` verifies exact persisted counts/integrity after new process startup.
- `DRAIN` repeatedly calls `SyncService.triggerSync(..., SyncTrigger.MANUAL)` and waits for queue progress until zero, then cleanup/VACUUM gates are evaluated and `result.json` is written.

- [x] Write static contracts requiring prefix `DB_V26_`, real `ACTION_PUNCH_PUSH`, production `SyncService.triggerSync`, supported counts, restart self-kill, integrity check, VACUUM, and zero-retry drain gate.
- [x] Run focused test and confirm RED.
- [x] Implement minimal engine/receiver and JSON report data.
- [x] Re-run focused test and confirm GREEN.

### Task 4: Windows one-command transactional runner

**Files:**
- Create: `scripts/lib/DbStress.ps1`
- Create: `scripts/run-db-stress.ps1`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Command: `./scripts/run-db-stress.ps1 -Serial <serial> -Count 1000|5000|10000`.
- Runner backs up production APK, builds/installs `assembleDbStressTest`, foregrounds HostActivity, PREPAREs, waits for process death, restarts HostActivity, VERIFY_RESTARTs, DRAINs, gathers JSON/logcat/perf snapshots, CLEANUPs, and restores production APK in `finally`.

- [x] Write static runner contracts for transactional restore, process restart, supported counts, HostActivity ordering, report files, fatal-event gate, and PS5.1 encoding.
- [x] Run focused test and confirm RED.
- [x] Implement library/runner with no production URL references.
- [x] Re-run focused test and confirm GREEN.

### Task 5: Documentation and full regression/package verification

**Files:**
- Create: `DB-STRESS-V2.6.md`
- Modify: `README.md`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Docs provide 1000, 5000, 10000 commands and explain hard gates, isolation, restart, and no-snapshot scope.

- [x] Add documentation contract and confirm RED.
- [x] Write docs and README entry; confirm GREEN.
- [x] Run full `python3 -m unittest scripts.tests.test_harness_static -v` and require zero failures.
- [x] Parse all Android manifests and Java-compile the V2.6 test-only classes against stubs where practical.
- [x] Verify PowerShell BOM/CRLF and confirm `app/src/main` has no V2.6-specific source changes.
- [x] Build an overlay ZIP relative to V2.5.1, reapply it to a fresh V2.5.1 baseline, rerun the full static suite, and write SHA-256.

### Verification notes

- [x] Added a regression for PowerShell `$PID` shadowing; observed RED, renamed to `$currentPid`, then GREEN.
- [x] Hardened the temporary test process so Application startup and cleanup both pin ApiClient to `http://127.0.0.1:1`; no production base URL is restored inside the test process.
- [x] Java 8 stub compilation covers all V2.6 test-only Java sources.
- [x] Loopback server socket smoke validates punch HTTP 200, heartbeat 503 suppression, and request counting.
