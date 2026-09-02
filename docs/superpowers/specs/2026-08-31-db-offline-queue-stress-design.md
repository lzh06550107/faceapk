# V2.6 Database / Offline Queue Stress Design

## Goal

Add an unattended Android real-device harness that measures SQLite/offline-queue capacity and recovery at 1,000 / 5,000 / 10,000 punch records without mutating the production `punch.db` or contacting the production backend.

## Architecture

`dbStressTest` is a same-package, debug-derived build type. A test-only `DbStressTestApplication` enables the existing UI-test startup bypass and activates a dedicated `punch_db_stress_v26.db` before any `DatabaseHelper` access. The test host Activity stays foreground so the unmodified production `SyncService.triggerSync()` can start on Android 8+.

The stress engine creates real `PunchRecord` rows using `DatabaseHelper.insertPunchRecord()` and real `punch_push` queue items using `enqueueSyncItem()`. A loopback HTTP server bound to `127.0.0.1` returns production-compatible success responses for punch uploads and a controlled heartbeat failure so heartbeat timestamps are not persisted. Queue draining therefore uses the production `SyncService -> HeartbeatManager -> SyncCoordinator -> ApiService -> ApiClient` path.

## Isolation

- Production DB `punch.db` is never opened by V2.6 after the test variant starts.
- V2.6 uses `punch_db_stress_v26.db` only.
- The HTTP target is loopback only. The test Application installs `http://127.0.0.1:1` before `PunchApplication.onCreate()` and cleanup returns to that closed loopback endpoint; the temporary test process never restores the production base URL.
- The test manifest removes `UpdateInstallStateReceiver` so temporary installation is not treated as a production OTA completion.
- Production startup jobs are suppressed using the existing `PunchApplication.setUiTestModeForTest(true)` hook.
- The runner backs up the installed production APK(s), installs the temporary same-package build, and restores the original APK(s) in `finally`.

## Test Phases

1. **Seed**: insert `Count` unsynced `DB_V26_*` punch rows and matching `punch_push` queue items. Record total duration, rows/sec, and sampled per-row latency distribution.
2. **Query**: verify counts, duplicate client IDs, queue retry state, page/date/line queries, DB file size, and `PRAGMA integrity_check`.
3. **Restart recovery**: persist the expected counts, self-terminate the test process, restart the test HostActivity, reactivate the same test DB, and verify record/queue counts and integrity are unchanged.
4. **Drain**: start a loopback success server and repeatedly trigger the real production sync service until the queue is empty. Record batch count, duration, throughput, unexpected retries, and HTTP punch request count.
5. **Cleanup/VACUUM**: delete V2.6 rows/queue, verify zero leftovers, record DB size before/after `VACUUM`, and run `PRAGMA integrity_check` again.

## Scale

Supported counts: `1000`, `5000`, `10000`. The runner defaults to `1000` and rejects other values unless explicitly expanded in a later version.

## Hard Gates

- Seeded punch count equals requested Count.
- Seeded queue count equals requested Count.
- Duplicate V2.6 client record IDs = 0.
- Integrity check = `ok` before restart, after restart, and after cleanup/VACUUM.
- After process restart: unsynced punch count and queue count still equal Count.
- Drain completes with synced count = Count, unsynced count = 0, queue count = 0.
- Unexpected retry sum = 0.
- Loopback punch request count = Count.
- No app Crash / ANR / OOM / SIGSEGV.
- Cleanup leaves zero V2.6 punch/queue rows.
- Production APK restoration succeeds.

## Metrics

- Seed total ms / rows per second.
- Sampled insert P50 / P95 / P99 / max ms.
- Query P50 / P95 / P99 / max ms for representative pending/page/date/line queries.
- DB bytes after seed, before VACUUM, after VACUUM.
- Drain total ms / rows per second / service-trigger batch count / HTTP punch request count.
- Process PSS / Java Heap / Native Heap / thread / FD snapshots from the PowerShell runner before seed, after seed/restart, and after drain where available.

V2.6 records these performance metrics but does not introduce arbitrary performance-failure thresholds. Numeric release gates are established from real-device 1k/5k/10k baselines later.

## Deliberate Exclusions

V2.6 does not create 10,000 JPEG snapshots. Snapshot/JPEG/Bitmap behavior is already covered by V2.3 Punch Stress; V2.6 isolates SQLite and queue capacity/recovery instead of letting image I/O dominate the result.
