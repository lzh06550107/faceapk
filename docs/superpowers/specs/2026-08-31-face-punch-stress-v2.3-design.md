# Face/Punch Stress V2.3 Design

## Goal

Add a deterministic, unattended real-device stress subsystem that can exercise native face recognition and the local punch persistence pipeline at high repetition counts without requiring a person to repeatedly appear in front of the camera and without sending synthetic punches to the production backend.

## Scope

V2.3 is split into two independent modes so measurements remain attributable.

### Native recognition stress

The test-only build selects a registered active employee that already has a locally cached face image. It repeatedly decodes that image and calls the production `FaceManager.recognizeFromBitmap()` path. This exercises the real Baidu detect, pre-check, feature extraction, face search, and native allocation/release behavior. It records success/failure counts and latency distribution.

The runner must fail fast when no registered local face image is available. It must not fabricate or export biometric data.

### Punch pipeline stress

The test-only build selects an active employee and drives a dedicated stress service that constructs synthetic recognition-success events and writes isolated test punch rows through a reusable punch persistence component. Each iteration creates a local JPEG snapshot, inserts a punch record, and enqueues the corresponding sync queue item.

Synthetic stress punches must never be uploaded. They use a dedicated stress action and a client record id prefix so production sync code ignores them. The stress runner cleans up its own rows and snapshots after metrics/report generation unless `-KeepArtifacts` is specified.

## Isolation

A new `facePunchStress` build type keeps `applicationId` equal to `com.punch.app` so it can reuse the production database and local face cache on the dedicated test terminal. It is a temporary same-package upgrade, using the existing backup/restore transaction pattern from cameraFaceSoak.

All control/status components live under `app/src/facePunchStress/` and are absent from main/release/debug/smoke/deviceOwnerTest/cameraFaceSoak manifests.

The test build exposes one explicit component reachable by ADB only for stress orchestration. The production manifests do not export or declare it.

## Data safety

Stress punch rows use:

- `client_record_id` prefix `STRESS_V23_`
- a stress-only sync action `stress_punch_no_upload`
- snapshot files with the same stress prefix

Production `SyncCoordinator` must not upload stress actions. No real punch endpoint is called for synthetic stress rows.

Native recognition stress performs no punch insertion and no network call.

## Metrics

Each test records:

- total requested iterations
- completed iterations
- success/failure counts
- latency min/mean/p50/p95/p99/max
- PSS, Java Heap, Native Heap, RSS
- CPU, thread count, FD count
- process restarts
- Crash/ANR/OOM/SIGSEGV
- punch_records delta
- stress sync_queue delta
- snapshot file count/bytes

## Gates

### Native recognition stress

- requested iterations complete
- success rate >= 95% for a valid enrolled local image
- process restarts = 0
- Crash/ANR/OOM/SIGSEGV = 0
- Native Heap post-cooldown growth <= 10% for the first 100-iteration gate

### Punch pipeline stress

- requested inserts complete
- inserted rows == requested count
- stress queue rows == requested count
- snapshot files == requested count before cleanup
- process restarts = 0
- Crash/ANR/OOM/SIGSEGV = 0
- cleanup removes all V2.3 rows/queue items/snapshots unless `-KeepArtifacts`

## Runner UX

Primary command:

```powershell
.\scripts\run-face-punch-stress.ps1 -Serial 0010202606017717 -Mode All -Count 100
```

Modes: `Face`, `Punch`, `All`.

After the 100-count gate passes, repeat with 500 and 1000.

## Non-goals

V2.3 does not replace V2.2 real Camera soak. It does not test anti-spoof attacks, lighting, motion blur, or live camera exposure. It does not send synthetic punch records to the production backend.
