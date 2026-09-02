# V2.7.1 Process Kill Recovery Design

## Goal

Prove that the production lifecycle can recover from abrupt process death without touching the production SQLite database or production backend, and that a sync interrupted mid-flight resumes from a consistent SQLite/sync_queue state.

## Scope

V2.7.1 contains exactly two automated real-device scenarios:

1. **ForegroundKill** — kill the same-package test process while the authenticated Kiosk UI is foreground, then observe autonomous process/Kiosk recovery.
2. **SyncKillRecovery** — seed an isolated SQLite database with 100 real `punch_push` records, begin real production `SyncService -> HeartbeatManager -> SyncCoordinator -> ApiService` synchronization against a deliberately slow loopback server, kill the process after partial progress, then verify restart consistency and drain the remaining queue.

Camera/Face hard gates, reboot recovery, OTA, and long-duration soak are explicitly deferred to later V2.7/V2.8 phases.

## Architecture

### Same-package test build

Add build type `processRecoveryTest` with application id `com.punch.app` and no `applicationIdSuffix`. It uses production lifecycle code and Device Owner/Kiosk behavior.

The test Application **must not enable `PunchApplication.uiTestMode`**. Before `super.onCreate()` it only:

- selects `punch_process_recovery_v271.db` through the existing `DatabaseHelper` test override;
- installs a short-timeout loopback-only `ApiClient` configuration;
- explicitly clears UI-test/Kiosk test bypass flags if exposed by existing test hooks.

`UpdateInstallStateReceiver` is removed from this variant so package replacement does not execute OTA state handling.

### SQLite isolation

All V2.7.1 punch rows and sync_queue rows live in:

`punch_process_recovery_v271.db`

The production `punch.db` is never selected by the test process. Synthetic rows use prefix `PROC_V271_` and real queue action `punch_push` so production `SyncCoordinator` behavior is exercised.

### Network isolation

The test process is loopback-only for its entire lifetime:

- idle base URL: `http://127.0.0.1:1`
- active sync server: `http://127.0.0.1:<ephemeral-port>`

The loopback server returns controlled heartbeat failure and punch success. SyncKillRecovery adds a deterministic per-punch response delay to create an observable partial-sync window.

### Cold test-process entry

Same-package Device Owner replacement with `adb install -r` is not treated as proof that the previously loaded process died. Immediately after installing `processRecoveryTest`, the runner captures any surviving PID, sends the test-only `TERMINATE` action, waits for that PID to disappear, and only then starts the Kiosk entry. Preflight also requires `database_active=true`. This guarantees that `ProcessRecoveryTestApplication.onCreate()` ran in a fresh process and installed the isolated database selector plus loopback-only API hooks before production lifecycle code executes.

### Process death

The exported test-only receiver invokes `Process.killProcess(Process.myPid())`. This is preferred over `adb force-stop` because the package is Device Owner/protected, and over shell `kill -9` because shell UID permission differs across devices.

After kill, the PowerShell harness does **not** immediately start an Activity. It first observes whether Android/Device Owner/HOME/Kiosk policy autonomously creates a new PID and restores an expected Kiosk route with active LockTask.

### ForegroundKill flow

1. Launch Kiosk entry and wait until package is resumed on an expected production route and LockTask is active.
2. Capture old PID.
3. Broadcast `KILL_FOREGROUND`.
4. Require old PID to disappear.
5. Without `am start`, wait for a new PID and autonomous Kiosk readiness.
6. Require `new_pid != old_pid`, Device Owner true, expected route true, LockTask active.

### SyncKillRecovery flow

1. Prepare fresh isolated DB with 100 synthetic unsynced punch rows + 100 real `punch_push` queue rows.
2. Start slow loopback server and real `SyncService.triggerSync(..., MANUAL)`.
3. Poll isolated DB until `1 <= synced < 100`; persist a kill checkpoint.
4. Kill the process while sync is active.
5. Wait for autonomous Kiosk/process recovery.
6. Reopen isolated DB and verify:
   - total rows = 100;
   - `synced + unsynced = 100`;
   - queue count = unsynced count;
   - duplicate `client_record_id` count = 0;
   - retry state is non-corrupt;
   - `PRAGMA integrity_check = ok`.
7. Start a new loopback server and repeatedly trigger production sync until queue = 0.
8. Require synced = 100, unsynced = 0, queue = 0, duplicates = 0, integrity = ok.
9. Delete only `PROC_V271_%` test rows/queue entries and VACUUM the isolated DB.

## Test-only control interface

`ProcessRecoveryTestReceiver` actions:

- `STATUS`
- `PREPARE_SYNC_KILL`
- `KILL_FOREGROUND`
- `VERIFY_SYNC_RESTART`
- `RESUME_SYNC`
- `CLEANUP`
- `TERMINATE`

The receiver and helper Activity are exported only in `processRecoveryTest`.

## PowerShell runner

`scripts/run-process-recovery.ps1`

Supported scenarios:

- `ForegroundKill`
- `SyncKillRecovery`
- `All`

Transactional lifecycle:

1. backup installed production APK(s);
2. build/install `assembleProcessRecoveryTest`;
3. launch Kiosk entry and preflight Device Owner/LockTask/token;
4. execute requested scenario(s);
5. capture result/status/logcat/fatals;
6. test-only TERMINATE and wait for process exit;
7. restore exact production APK(s);
8. cold launch Kiosk entry;
9. require production Kiosk/Device Owner/LockTask recovery;
10. only then emit final `RESULT: PASS`.

## Hard gates

### ForegroundKill

- old PID exists before kill;
- old PID exits;
- new PID appears automatically;
- new PID differs from old PID;
- expected Kiosk route resumed;
- Device Owner true;
- LockTask active;
- no unexpected Crash/ANR/OOM.

### SyncKillRecovery

- 100 total rows before kill;
- kill occurs after partial progress and before all rows sync;
- after restart total remains 100;
- `synced + unsynced = 100`;
- queue count equals unsynced count;
- duplicate client IDs = 0;
- SQLite integrity = ok;
- final synced = 100, unsynced = 0, queue = 0;
- cleanup leaves zero V2.7.1 rows/queue entries;
- no unexpected Crash/ANR/OOM.

### Global safety

- production backend requests = 0 by construction (loopback-only);
- production `punch.db` is never selected by the test build;
- `app/src/main` behavior is not modified for V2.7.1;
- exact production APK is restored at the end;
- final production Device Owner/LockTask/Kiosk gate must pass before overall PASS.

## Evidence

Report directory contains:

- `summary.txt`
- `foreground-kill.json`
- `sync-prepare.json`
- `sync-kill-checkpoint.json`
- `sync-after-restart.json`
- `sync-result.json`
- `kill-events.jsonl`
- `restore-kiosk-state.txt`
- `fatal-events.txt`
- `logcat.txt`
- `transaction.txt`
- build/install/restore logs and production APK backup.
