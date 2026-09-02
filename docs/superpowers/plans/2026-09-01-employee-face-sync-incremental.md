# Employee Face Sync Incremental Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make employee face synchronization incremental and persist extracted face features so normal person changes no longer trigger a full face-library rebuild.

**Architecture:** Store validated 512-byte Face SDK features in SQLite with version/hash metadata. Normal `person_changed` events update only changed/deleted runtime faces; startup/recovery full rebuild remains but loads cached features first and recomputes only misses.

**Tech Stack:** Java 8, Android SQLite, Baidu Face SDK 8.5, JUnit 4, Gradle Android plugin.

**Spec:** `docs/superpowers/specs/2026-09-01-employee-face-sync-incremental-design.md`

## Global Constraints

- All Baidu native Face SDK operations remain serialized through `FaceSdkOperationGuard`.
- Normal `person_changed` must not call `featureClear()` or full rebuild.
- Full rebuild remains available for process-start/recovery.
- Feature cache validity requires matching face version, SHA-256, and feature schema version.
- Existing event/result semantics are preserved.

---

### Task 1: Feature Cache Model and Validity Policy

**Files:**
- Create: `app/src/main/java/com/punch/app/face/FaceFeatureCachePolicy.java`
- Test: `app/src/test/java/com/punch/app/face/FaceFeatureCachePolicyTest.java`

**Interfaces:**
- Consumes: employee face version, image SHA, cache schema version.
- Produces: `FaceFeatureCachePolicy.isReusable(...)`.

- [ ] Write tests covering exact-match reuse and invalidation on version/SHA/schema mismatch.
- [ ] Run the focused unit test and verify it fails before implementation.
- [ ] Implement the pure-Java policy.
- [ ] Re-run the focused test and verify pass.

### Task 2: SQLite Feature Persistence

**Files:**
- Modify: `app/src/main/java/com/punch/app/utils/Constants.java`
- Modify: `app/src/main/java/com/punch/app/db/DatabaseHelper.java`

**Interfaces:**
- Produces: `saveFaceFeature`, `getReusableFaceFeature`, `deleteFaceFeature`, `clearFaceFeatures`.

- [ ] Bump DB version from 9 to 10 and create/migrate `face_features`.
- [ ] Implement BLOB save/read/delete helpers using the policy from Task 1.
- [ ] Ensure employee deletion/clear paths can delete feature rows without changing existing employee-history semantics.
- [ ] Compile affected Java sources through Gradle.

### Task 3: Extract Once, Cache, and Incrementally Push

**Files:**
- Modify: `app/src/main/java/com/punch/app/face/FaceManager.java`
- Modify: `app/src/main/java/com/punch/app/face/FaceRegistrationManager.java`

**Interfaces:**
- `FaceManager.registerFace(Context,String,String,int,String)` extracts once, saves feature, and pushes same bytes.
- `FaceManager.validateFaceImage(Context,String,String,int,String)` extracts once and saves feature without runtime push.
- Full rebuild reads cached feature first, extracts only on miss.

- [ ] Extend `RegisterResult`/internal path so the same extracted feature is saved before return.
- [ ] Add version/SHA-aware overloads while retaining compatibility overloads.
- [ ] Make full rebuild use cached features before JPEG extraction.
- [ ] Make runtime push check native return code before updating Java mappings/count.
- [ ] Keep all native operations inside `FaceSdkOperationGuard`.

### Task 4: Incremental Event Delta Application

**Files:**
- Create: `app/src/main/java/com/punch/app/service/EmployeeFaceDeltaPolicy.java`
- Test: `app/src/test/java/com/punch/app/service/EmployeeFaceDeltaPolicyTest.java`
- Modify: `app/src/main/java/com/punch/app/service/SyncCoordinator.java`

**Interfaces:**
- Policy classifies profile-only, register/update, and remove operations.
- Event-mode sync registers only targets and removes deleted/disabled employees; it does not call full rebuild.

- [ ] Write pure-Java tests for add, profile-only update, face change, disable, and delete classifications.
- [ ] Implement policy and pass tests.
- [ ] Track removal targets while applying employee changes.
- [ ] Delete stale cached feature whenever a face changes or an employee is disabled/deleted.
- [ ] In event mode call incremental registration (`addToRuntimeLibrary=true`) and apply removals.
- [ ] Remove the event-mode call to `rebuildFinalFaceLibrary()`.
- [ ] Keep preparation/startup full rebuild path intact.

### Task 5: Verification and Patch Packaging

**Files:**
- Modify only if verification exposes defects.
- Generate: `/mnt/data/FaceEmployeeSync-Incremental-Optimization-Patch-20260901.zip`

- [ ] Run focused unit tests for cache policy, delta policy, and SDK guard.
- [ ] Run available project unit tests/build tasks.
- [ ] Search production event path to prove normal `person_changed` no longer reaches full rebuild.
- [ ] Package only changed/new files plus README and checksums into an incremental patch ZIP.
