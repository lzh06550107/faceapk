# V2.5 Network Fault / Offline Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic, non-production network-fault/offline-sync real-device quality gate that drives the existing SyncService/SyncCoordinator.

**Architecture:** A same-package `networkFaultTest` build suppresses normal production startup, uses an isolated SQLite database selected before DatabaseHelper initialization, and routes ApiClient to a loopback fault server. The test-only receiver seeds real `punch_push` work into that isolated DB and drives production SyncService/SyncCoordinator. PowerShell performs APK and physical-network restoration.

**Tech Stack:** Java 8 Android, existing OkHttp, SQLite, PowerShell 5.1, adb.

**Spec:** `docs/superpowers/specs/2026-08-31-network-fault-offline-sync-v2.5-design.md`

## Global Constraints
- No new external dependencies.
- No V2.5 receiver/server in production source sets.
- No V2.5 synthetic punch request may reach the production backend.
- Production `punch.db` must not be used by V2.5.
- V2.5 IDs use prefix `NET_V25_`.
- Always restore production APK/Kiosk and physical network state.

---

### Task 1: Variant and startup isolation
**Files:** `app/build.gradle`, `app/src/networkFaultTest/AndroidManifest.xml`, `NetworkFaultTestApplication.java`, static contracts.
- [x] Add RED contract for same-package build type and test-only exported receiver.
- [x] Add startup bypass Application and remove `UpdateInstallStateReceiver` from only this variant.
- [x] Verify source-set isolation and XML parse.

### Task 2: Loopback fault server and ApiClient controller
**Files:** `NetworkFaultServer.java`, `NetworkFaultController.java`, static/runtime tests.
- [x] Add RED contract for loopback bind, ApiClient test hooks, and SUCCESS/401/500/TIMEOUT/DROP modes.
- [x] Implement HTTP capture/fault responses and short test OkHttp timeouts.
- [x] Suppress heartbeat persistence with controlled 503 while preserving punch-sync ordering.
- [x] Run real host socket verification for all fault modes.

### Task 3: Isolated DB and production sync scenario engine
**Files:** `DatabaseHelper.java`, `NetworkFaultDatabaseController.java`, `NetworkFaultTestEngine.java`, `NetworkFaultTestReceiver.java`.
- [x] Add RED contract proving an isolated DB name is selected before production sync.
- [x] Add package-private DatabaseHelper test-name override with default behavior unchanged.
- [x] Seed `NET_V25_*`, `is_synced=0`, real `ACTION_PUNCH_PUSH` rows only into isolated DB.
- [x] Drive production `SyncService.triggerSync` with AFTER_PUNCH/MANUAL and poll DB retry/sync state.
- [x] Implement DisconnectRecovery, TimeoutRecovery, Http500Retry, Http401Recovery, RetryLimitManualRecovery, and DeviceOfflineRecovery phases.

### Task 4: Transactional PowerShell runner
**Files:** `scripts/lib/NetworkFaultSync.ps1`, `scripts/run-network-fault-sync.ps1`.
- [x] Add RED contract for backup/restore, test APK build/install, receiver control, report collection, and physical network restoration.
- [x] Implement `AllSafe` orchestration and separate `DeviceOfflineRecovery` orchestration.
- [x] Restore network from `finally` and restore original APK/Kiosk transactionally.
- [x] Preserve UTF-8 BOM + CRLF for Windows PowerShell 5.1.

### Task 5: Verification and packaging
**Files:** `NETWORK-FAULT-SYNC-V2.5.md`, `README.md`, static contracts, overlay ZIP.
- [x] Document scenarios, gates, 401 behavior, DB/HTTP isolation, and commands.
- [x] Run full static suite on the exact V2.3.6 sequential baseline plus V2.5 overlay.
- [x] Parse all manifests and verify receiver source-set isolation.
- [x] Compile V2.5 Java against Java 8 Android-shaped stubs and run loopback server socket test.
- [x] Verify PowerShell BOM/CRLF.
- [ ] Build final incremental overlay ZIP and SHA-256.
