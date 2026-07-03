package com.punch.app.utils;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.punch.app.activity.SplashActivity;
import com.punch.app.receiver.KioskDeviceAdminReceiver;

public final class KioskManager {
    private static final String TAG = "KioskManager";

    private KioskManager() {
    }

    public static void enterIfPossible(Activity activity) {
        if (activity == null) {
            return;
        }
        if (!SessionManager.get().isKioskEnabled()) {
            AppLogger.i(TAG, "Kiosk disabled, skip enter");
            return;
        }
        tryAllowlistSelf(activity);
        if (!isLockTaskPermitted(activity)) {
            AppLogger.w(TAG, "Lock task not permitted for package " + activity.getPackageName());
            return;
        }
        if (isInLockTaskMode(activity)) {
            return;
        }
        try {
            activity.startLockTask();
            AppLogger.i(TAG, "Entered lock task mode");
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to enter lock task mode", e);
        }
    }

    public static void exitAndDisable(Activity activity) {
        if (activity == null) {
            return;
        }
        SessionManager.get().saveKioskEnabled(false);
        clearOwnerPolicies(activity);
        if (!isInLockTaskMode(activity)) {
            AppLogger.i(TAG, "Lock task already inactive");
            return;
        }
        try {
            activity.stopLockTask();
            AppLogger.i(TAG, "Exited lock task mode");
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to exit lock task mode", e);
        }
    }

    public static void enableAndEnter(Activity activity) {
        if (activity == null) {
            return;
        }
        SessionManager.get().saveKioskEnabled(true);
        enterIfPossible(activity);
    }

    public static boolean isDeviceOwner(Context context) {
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    public static boolean isLockTaskPermitted(Context context) {
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        return dpm != null && dpm.isLockTaskPermitted(context.getPackageName());
    }

    private static void tryAllowlistSelf(Context context) {
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        ComponentName admin = getAdminComponent(context);
        if (dpm == null || admin == null) {
            return;
        }
        if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }
        try {
            dpm.setLockTaskPackages(admin, new String[]{context.getPackageName()});
            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
            homeFilter.addCategory(Intent.CATEGORY_HOME);
            homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(
                    admin,
                    homeFilter,
                    new ComponentName(context, SplashActivity.class)
            );
            AppLogger.i(TAG, "Allowlisted package for lock task mode");
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to allowlist package for lock task mode", e);
        }
    }

    private static void clearOwnerPolicies(Context context) {
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        ComponentName admin = getAdminComponent(context);
        if (dpm == null || admin == null) {
            return;
        }
        if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }
        try {
            dpm.setLockTaskPackages(admin, new String[]{});
            dpm.clearPackagePersistentPreferredActivities(admin, context.getPackageName());
            AppLogger.i(TAG, "Cleared kiosk owner policies");
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to clear kiosk owner policies", e);
        }
    }

    private static boolean isInLockTaskMode(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    private static DevicePolicyManager getDevicePolicyManager(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private static ComponentName getAdminComponent(Context context) {
        return new ComponentName(context, KioskDeviceAdminReceiver.class);
    }
}
