# V2.5 Network Fault / Offline Sync Design

## Goal
Build an unattended, transactional real-device harness that validates the production punch sync state machine under transport loss, timeout, HTTP 500, HTTP 401, retry exhaustion, recovery, and a real device-offline transition without sending any V2.5 punch to the production backend or mutating the production punch database.

## Architecture
- Add a same-package `networkFaultTest` build type, signed like debug and installed transactionally over `com.punch.app`.
- Keep the V2.5 receiver, server, scenario engine, isolated-DB bridge, and test Application under `app/src/networkFaultTest/`; release/debug/smoke/cameraFaceSoak/facePunchStress contain no V2.5 receiver/server.
- `NetworkFaultTestApplication` enables the existing application test-startup bypass before `PunchApplication.onCreate()`, preventing production heartbeat/update/face jobs from starting while the temporary APK is active.
- Remove `UpdateInstallStateReceiver` only from the `networkFaultTest` merged manifest so `adb install -r` is not treated as a production OTA event and does not mutate production update/relaunch state.
- Add a package-private DatabaseHelper test-name override. V2.5 selects `punch_network_fault_v25.db` before the first `DatabaseHelper.get(...)`; production `SyncService/SyncCoordinator` therefore run unchanged against an isolated database. `punch.db` is never opened by the V2.5 engine.
- Reuse the existing package-private `ApiClient.setBaseUrlForTest`, `setClientForTest`, and `resetForTest` hooks from test-only code in `com.punch.app.network`.
- Run a small in-process HTTP server bound to `127.0.0.1` with success, 401, 500, timeout, and dropped-connection modes. Punch requests are faulted deterministically. Heartbeat returns a controlled 503 because punch sync is enqueued before heartbeat; this prevents test heartbeat timestamps from being persisted to production SharedPreferences.
- Seed synthetic `NET_V25_*` PunchRecord rows into the isolated DB with `is_synced=0`, queue them with the real `ACTION_PUNCH_PUSH`, then call production `SyncService.triggerSync(...)`. The real `SyncCoordinator.syncPunches()` performs retry, retry-limit, mark-synced, and queue-removal behavior.

## Scenarios and gates
1. `DisconnectRecovery`: loopback server closes the punch connection. Gate: record stays unsynced, queue remains, retry increments, then success + MANUAL marks synced and removes queue.
2. `TimeoutRecovery`: test OkHttp read timeout is 2 seconds; server delays beyond it. Same recovery gate as disconnect.
3. `Http500Retry`: three records receive HTTP 500. Gate: all remain unsynced and all retry counts increment because HTTP errors do not stop the batch; success + MANUAL clears all.
4. `Http401Recovery`: one record receives HTTP 401. Gate: row remains queued/not synced. Current ApiClient has no automatic 401 refresh inside punch upload, so `http_401_auto_refresh_observed` is diagnostic rather than a PASS requirement; success + MANUAL then verifies no-data-loss recovery.
5. `RetryLimitManualRecovery`: fail one row to `SYNC_MAX_RETRY=3`. Gate: another `AFTER_PUNCH` does not reattempt it; `MANUAL` resets the limited retry in the isolated DB and success completes sync.
6. `DeviceOfflineRecovery`: runner captures Wi-Fi/mobile-data state, disables both, uses a reserved unreachable test address, verifies isolated queue retention, restores network state, switches to loopback success, and verifies MANUAL recovery. Restoration is in `finally`.

## Safety gates
- Synthetic HTTP traffic targets only `127.0.0.1` or reserved TEST-NET `192.0.2.1`; never the production backend.
- Production `punch.db` is not used; all V2.5 business rows and retry resets live in `punch_network_fault_v25.db`.
- No production employees are created or modified.
- Temporary APK startup suppresses normal background jobs and removes the production package-replaced receiver.
- ApiClient stays pinned to test routing for the temporary process lifetime; the original APK installation kills that process/static state.
- Original production APK(s), kiosk activity, and physical network state are restored after every run.

## Reports
`test-results/<timestamp>-network-fault-sync/` contains `result.json`, `requests.jsonl`, `logcat.txt`, `fatal-events.txt`, `summary.txt`, build/install/restore logs, transaction state, production APK backup, and the offline prepare/retained phase JSONs for the physical scenario.

## Initial execution
Run `AllSafe` first. It exercises DROP, timeout, HTTP 500, HTTP 401, and retry-limit/manual recovery without changing device Wi-Fi/mobile-data state. Run `DeviceOfflineRecovery` separately only after `AllSafe` passes.
