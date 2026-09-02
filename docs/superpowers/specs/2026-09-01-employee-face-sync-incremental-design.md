# Employee Face Sync Incremental Design

## Goal

Optimize `/v3/handheld/employee/sync` so normal `person_changed` processing updates only changed faces and does not rebuild the entire runtime FaceSearch library. Persist extracted face features so application restart can rebuild FaceSearch without decoding every JPEG and rerunning detect/feature extraction.

## Constraints

- Keep Baidu Face native SDK calls serialized through `FaceSdkOperationGuard`; do not add concurrent FaceDetect/FaceFeature/FaceSearch calls.
- Preserve current employee add/update/delete and stale `op_time` semantics.
- Preserve current `event/result` per-employee acknowledgement behavior.
- Preserve SHA-256 local image verification behavior.
- A normal `person_changed` event must not call `featureClear()` or full `rebuildFaceLibrarySync()`.
- Full rebuild remains available for startup/recovery/manual consistency repair.
- Cached features are valid only when employee face version, image SHA-256, and local feature schema version match.

## Architecture

### Feature cache

Add a `face_features` SQLite table keyed by employee id. Each row stores `face_version`, `image_sha256`, `feature_schema_version`, the 512-byte feature BLOB, and `updated_at`. Database version increases from 9 to 10.

### FaceManager APIs

`FaceManager` becomes the single owner of feature extraction and runtime FaceSearch mutation. `registerFace(...)` extracts the feature once, persists it through `DatabaseHelper`, and pushes the same feature bytes into FaceSearch. `validateFaceImage(...)` still validates without runtime mutation but may persist the extracted feature for startup recovery. Full rebuild first tries the persisted feature cache; only a cache miss falls back to JPEG decode/detect/feature and then refreshes the cache.

Add explicit incremental removal that removes the employee from FaceSearch, invalidates runtime-state fingerprints, deletes the feature cache row, and leaves the employee metadata lifecycle to `SyncCoordinator`.

### SyncCoordinator

Track runtime face deltas while applying employee changes:

- add/new face or face changed -> registration target
- employee delete -> removal target
- face status changed from enabled to disabled -> removal target
- profile-only update -> no FaceSearch work

In event mode, remove deleted/disabled faces and call incremental registration for only registration targets. Do not run `rebuildFinalFaceLibrary()` afterward.

Preparation/startup mode may still perform a full runtime rebuild because the process-local FaceSearch library is empty after process restart. That rebuild uses cached features first, so it avoids repeated JPEG detect/feature work.

## Failure behavior

- A changed/new face that cannot download/verify/extract remains `face_registered=0` and is reported failed for that employee.
- A delete remains applied in SQLite even if no runtime face was present; runtime removal is idempotent.
- Failure to mutate one changed face does not clear the existing full runtime library.
- Full rebuild remains the recovery mechanism if runtime/library consistency is later found invalid.

## Performance target

For a runtime library of N employees and a `person_changed` event affecting K faces, normal event processing should perform O(K) face extraction/runtime operations rather than O(N). On application restart, rebuilding N faces should load cached 512-byte features and push them, with JPEG detect/feature only for cache misses.

## Tests

- Unit-test feature-cache validity rules independently of Android storage.
- Unit-test event delta classification independently of network/Android runtime.
- Unit-test Face SDK operation guard remains serialized.
- Compile debug/release Java sources after integration.
- Run existing local unit tests where the uploaded Gradle wrapper/runtime permits.
