# Device Owner Maintenance Bridge Design

## Goal

Allow the real-device Smoke suite to run on a dedicated terminal where `com.punch.app` is the active Device Owner/Kiosk app, without removing Device Owner or leaving a test-only control surface in the release APK.

## Architecture

A `deviceOwnerTest` build type keeps applicationId `com.punch.app` and adds only a test maintenance Application/Activity. The Harness backs up the currently installed production APK set, updates the Device Owner package in place, asks the Device Owner itself to disable LockTask/foreground recovery and unsuspend the Smoke packages, runs instrumentation, then restores policies and reinstalls the original APK set in a `finally` path.

The maintenance control Activity exists only in `src/deviceOwnerTest`; release/debug/smoke manifests do not expose it. The in-place update requires the same signing certificate as the currently installed Device Owner package.

## Safety

The flow never removes Device Owner, clears production data, factory resets, reboots, or uninstalls the production package. Original APK files are retained in the report directory even after successful restoration.
