package com.punch.app.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import com.punch.app.activity.SplashActivity;
import com.punch.app.network.InteractionLogger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UpdateManager;

public class UpdateInstallStateReceiver extends BroadcastReceiver {
    private static final long[] APP_RELAUNCH_DELAYS_MS = new long[]{
            1_000L,
            2_000L,
            3_000L,
            5_000L,
            8_000L,
            13_000L,
            21_000L,
            34_000L
    };
    private static final int REQ_UPDATE_RELAUNCH_BASE = 1002;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (UpdateManager.ACTION_PACKAGE_INSTALL_RESULT.equals(action)) {
            UpdateManager.handlePackageInstallerResult(context, intent);
            return;
        }
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        String version = resolveInstalledVersion(context);
        String message = isBlank(version) ? "Update installed" : "Updated to " + version;
        SessionManager.get().markUpdateInstallResult(
                Constants.UPDATE_INSTALL_STATUS_SUCCESS,
                message,
                0
        );
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_UPDATE,
                "App package replaced",
                "version=" + safeValue(version)
        );
        scheduleUpdatedAppLaunch(context, version);
    }

    public static void scheduleUpdatedAppLaunch(Context context, String version) {
        try {
            if (SessionManager.get().isUpdateAutoLaunchCompleted()) {
                InteractionLogger.logBusiness(
                        InteractionLogger.GROUP_UPDATE,
                        "Skip auto launch schedule",
                        "reason=already_completed\nversion=" + safeValue(version)
                );
                return;
            }
            if (SessionManager.get().isUpdateAutoLaunchScheduled()) {
                InteractionLogger.logBusiness(
                        InteractionLogger.GROUP_UPDATE,
                        "Skip auto launch schedule",
                        "reason=already_scheduled\nversion=" + safeValue(version)
                );
                return;
            }
            AlarmManager alarmManager =
                    (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_UPDATE,
                        "Schedule auto launch after update failed",
                        "AlarmManager unavailable\nversion=" + safeValue(version)
                );
                return;
            }
            for (int i = 0; i < APP_RELAUNCH_DELAYS_MS.length; i++) {
                long delayMs = APP_RELAUNCH_DELAYS_MS[i];
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        context,
                        REQ_UPDATE_RELAUNCH_BASE + i,
                        buildLaunchIntent(context),
                        PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag()
                );
                long triggerAt = SystemClock.elapsedRealtime() + delayMs;
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            }
            SessionManager.get().markUpdateAutoLaunchScheduled();
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_UPDATE,
                    "Scheduled auto launch retries after update",
                    "delaysMs=" + joinDelays() + "\nversion=" + safeValue(version)
            );
        } catch (Exception e) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_UPDATE,
                    "Auto launch after update failed",
                    e.getClass().getSimpleName() + ": " + safeValue(e.getMessage())
            );
        }
    }

    public static void cancelScheduledAutoLaunch(Context context) {
        if (context == null) {
            return;
        }
        try {
            SessionManager.get().markUpdateAutoLaunchCompleted();
            AlarmManager alarmManager =
                    (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            for (int i = 0; i < APP_RELAUNCH_DELAYS_MS.length; i++) {
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        context,
                        REQ_UPDATE_RELAUNCH_BASE + i,
                        buildLaunchIntent(context),
                        PendingIntent.FLAG_NO_CREATE | immutableFlag()
                );
                if (pendingIntent == null) {
                    continue;
                }
                if (alarmManager != null) {
                    alarmManager.cancel(pendingIntent);
                }
                pendingIntent.cancel();
            }
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_UPDATE,
                    "Cancelled scheduled auto launch retries",
                    "reason=app_started"
            );
        } catch (Exception e) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_UPDATE,
                    "Cancel auto launch retries failed",
                    e.getClass().getSimpleName() + ": " + safeValue(e.getMessage())
            );
        }
    }

    private static Intent buildLaunchIntent(Context context) {
        Intent launchIntent = new Intent(context, SplashActivity.class);
        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        return launchIntent;
    }

    private static String joinDelays() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < APP_RELAUNCH_DELAYS_MS.length; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append(APP_RELAUNCH_DELAYS_MS[i]);
        }
        return builder.toString();
    }

    private static int immutableFlag() {
        return PendingIntent.FLAG_IMMUTABLE;
    }

    private String resolveInstalledVersion(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String safeValue(String value) {
        return isBlank(value) ? "-" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
