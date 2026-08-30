# Update Relaunch Recovery Design

## Goal

Prevent a self-update from repeatedly clearing the Android task and reopening `SplashActivity`, while retaining unattended recovery on a Device Owner kiosk.

## Architecture

`ACTION_MY_PACKAGE_REPLACED` is the only event that starts a relaunch generation. The receiver persists the installed `versionCode`, enters `WAITING`, attempts one non-destructive launch through `KioskHomeActivity`, and keeps at most one broadcast `PendingIntent` scheduled. Every retry re-evaluates persisted state and current foreground progress before launching.

`KioskHomeActivity` first moves an existing non-Home app task to the foreground and exits without rewriting that task. Only a later recovery attempt, after no terminal UI acknowledgement arrives, requests a fresh default route; that fallback omits both `CLEAR_TASK` and `CLEAR_TOP` so it does not pop retained pages.

The generation phases are `IDLE`, `WAITING`, `LAUNCHING`, `UI_ACKED`, and `EXHAUSTED`. `MainActivity`, `LoginActivity`, and `SetupWizardActivity` acknowledge the generation after their window gains focus. Acknowledgement does not depend on Lock Task state, cancels the single alarm, and makes any late broadcast a no-op.

## Retry behavior

- Retry delays are 2, 5, 10, and 20 seconds, with a 60-second overall deadline.
- Only an actual Activity launch increments the launch attempt. Visibility and launch-grace checks reschedule without consuming an attempt.
- A retry for a different installed `versionCode` is stale and ignored.
- `UI_ACKED` and `EXHAUSTED` generations never launch.
- `LAUNCHING` suppresses another launch while an Activity is visible or the previous launch remains inside its grace period.
- A missing UI acknowledgement after the grace period permits the next recovery attempt.
- A new launch is not attempted when less than one full launch-grace window remains before the deadline.
- Relaunch intents must not contain `FLAG_ACTIVITY_CLEAR_TASK`.

## Installation behavior

The updater persists the install target before `PackageInstaller.Session.commit()` but does not schedule Activity launches before commit. Installer success updates install status; `ACTION_MY_PACKAGE_REPLACED` reconciles the actual installed version and starts the recovery generation.

## Scope

The change touches only update relaunch state, scheduling, kiosk entry routing, and terminal Activity acknowledgement. It does not change the database, API contracts, synchronization, Face SDK behavior, SDK levels, dependencies, or build system.

## Verification

Pure JVM tests cover policy decisions and persisted generation state. Android builds verify receiver, PendingIntent, Activity, and manifest integration. No device install, uninstall, Device Owner mutation, or ADB operation is authorized by this design.
