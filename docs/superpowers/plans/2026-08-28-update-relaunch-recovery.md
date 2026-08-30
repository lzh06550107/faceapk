# Update Relaunch Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace destructive multi-Activity update alarms with one guarded broadcast retry and UI acknowledgement.

**Architecture:** A pure Java policy decides whether a persisted relaunch generation should launch, wait, acknowledge, or expire. `UpdateInstallStateReceiver` owns the single broadcast alarm and routes through the existing kiosk Home; terminal Activities acknowledge a visible UI.

**Tech Stack:** Java 8, Android SDK 34, AndroidX, JUnit 4, Gradle Android plugin already configured by the repository.

**Spec:** `docs/superpowers/specs/2026-08-28-update-relaunch-design.md`

## Global Constraints

- Keep the app Java-only and do not add or upgrade dependencies.
- Do not change database schema or `Constants.DB_VERSION`.
- Do not perform ADB, install, uninstall, Device Owner, commit, push, or release operations.
- Preserve existing update authentication, validation, result logging, and kiosk policy boundaries.

---

### Task 1: Pure relaunch decision policy

**Files:**
- Create: `app/src/main/java/com/punch/app/receiver/UpdateRelaunchPolicy.java`
- Create: `app/src/test/java/com/punch/app/receiver/UpdateRelaunchPolicyTest.java`

**Interfaces:**
- Consumes: phase, generation version, installed version, attempt, timestamps, and Activity visibility.
- Produces: `Decision` values `IGNORE`, `WAIT`, `LAUNCH`, and `EXHAUST` plus retry delay selection.

- [x] Write tests proving acknowledged, stale, visible, grace-period, retry, and deadline behavior.
- [x] Run `./gradlew.bat testDebugUnitTest --tests com.punch.app.receiver.UpdateRelaunchPolicyTest` and confirm failure because the policy does not exist.
- [x] Implement the minimal immutable policy API and retry schedule.
- [x] Re-run the targeted test and confirm it passes.

### Task 2: Persisted generation state

**Files:**
- Modify: `app/src/main/java/com/punch/app/utils/Constants.java`
- Modify: `app/src/main/java/com/punch/app/utils/SessionManager.java`
- Modify: `app/src/test/java/com/punch/app/utils/SessionManagerTest.java`

**Interfaces:**
- Produces: `beginUpdateRelaunch(long,long)`, `markUpdateRelaunchLaunching(int,long)`, `acknowledgeUpdateRelaunch()`, `exhaustUpdateRelaunch()`, and getters for version, phase, attempt, start, and last-launch elapsed times.

- [x] Add tests for beginning a fresh generation, launching, acknowledging, exhausting, and clearing state.
- [x] Run the SessionManager tests and confirm the new assertions fail because the state API is absent.
- [x] Implement critical generation transitions with synchronous `SharedPreferences.commit()`.
- [x] Run the SessionManager tests and confirm they pass.

### Task 3: Single broadcast alarm and package-replaced entry

**Files:**
- Modify: `app/src/main/java/com/punch/app/receiver/UpdateInstallStateReceiver.java`
- Modify: `app/src/main/java/com/punch/app/utils/UpdateManager.java`
- Modify: `app/src/main/java/com/punch/app/activity/KioskHomeActivity.java`

**Interfaces:**
- Consumes: `UpdateRelaunchPolicy`, persisted generation state, and `PunchApplication.wasNonHomeActivityRecentlyVisible(long)`.
- Produces: one `ACTION_UPDATE_RELAUNCH_RETRY` broadcast `PendingIntent`, an idempotent `acknowledgeUpdatedAppLaunch(Context)`, and a non-destructive kiosk Home launch.

- [x] Remove pre-commit relaunch scheduling and installer-result rescheduling.
- [x] Begin the generation only from `ACTION_MY_PACKAGE_REPLACED` after resolving installed version code.
- [x] Replace all `PendingIntent.getActivity()` retry alarms with one `PendingIntent.getBroadcast()`.
- [x] Schedule the next broadcast before an allowed launch, then launch `KioskHomeActivity` without `CLEAR_TASK`.
- [x] Ensure acknowledged, stale, visible, grace-period, and expired broadcasts do not launch.
- [x] Compile with `./gradlew.bat assembleDebug`.

### Task 4: Terminal UI acknowledgement

**Files:**
- Modify: `app/src/main/java/com/punch/app/activity/MainActivity.java`
- Modify: `app/src/main/java/com/punch/app/activity/LoginActivity.java`
- Modify: `app/src/main/java/com/punch/app/activity/SetupWizardActivity.java`

**Interfaces:**
- Consumes: `UpdateInstallStateReceiver.acknowledgeUpdatedAppLaunch(Context)`.
- Produces: idempotent acknowledgement from `onWindowFocusChanged(true)` for every terminal route.

- [x] Remove delayed 1.5-second cancellation and Lock Task dependency from Main/Login.
- [x] Add window-focus acknowledgement to Main/Login/SetupWizard.
- [x] Build debug and run all JVM unit tests.

### Task 5: Verification and review

**Files:**
- Review all files listed above.

- [x] Run `./gradlew.bat testDebugUnitTest assembleDebug assembleRelease lintDebug`.
- [x] Distinguish the documented pre-existing debug-manifest lint failure if it remains.
- [x] Run `git diff --check`, inspect `git diff`, and verify `git status --short` contains only intended files.
- [x] Do not commit; report tests actually run and any verification not run.
