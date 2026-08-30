package com.punch.app.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.punch.app.PunchApplication;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.WifiAutoReconnectManager;

public class KioskHomeActivity extends Activity {
    public static final String EXTRA_FORCE_FRESH_TARGET = "force_fresh_target";

    private static final long RECENT_APP_VISIBLE_WINDOW_MS = 3_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (PunchApplication.isUiTestModeEnabled()) {
            return;
        }
        WifiAutoReconnectManager.ensureSavedWifiConnection(this);

        PunchApplication app = PunchApplication.get();
        boolean forceFreshTarget = getIntent().getBooleanExtra(
                EXTRA_FORCE_FRESH_TARGET,
                false
        );
        if (!forceFreshTarget
                && SessionManager.get().isKioskEnabled()
                && KioskManager.bringExistingAppTaskToFront(this, getTaskId())) {
            finishHomeTask();
            return;
        }

        if (!forceFreshTarget
                && SessionManager.get().isKioskEnabled()
                && app != null
                && app.wasNonHomeActivityRecentlyVisible(RECENT_APP_VISIBLE_WINDOW_MS)) {
            launchTarget(resolveRecentActivityClass(app));
            return;
        }

        if (!isTaskRoot()) {
            launchFreshTarget(resolveDefaultActivityClass(), forceFreshTarget);
            return;
        }

        launchFreshTarget(resolveDefaultActivityClass(), forceFreshTarget);
    }

    private Class<?> resolveRecentActivityClass(PunchApplication app) {
        Class<?> activityClass = app.getLastNonHomeActivityClass();
        return activityClass == null ? resolveDefaultActivityClass() : activityClass;
    }

    private Class<?> resolveDefaultActivityClass() {
        return LaunchRouteResolver.resolveNext(
                SessionManager.get().isTokenValid(),
                SessionManager.get().isSetupCompleted()
        );
    }

    private void launchTarget(Class<?> activityClass) {
        Intent intent = new Intent(this, activityClass);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        startActivity(intent);
        finishHomeTask();
        overridePendingTransition(0, 0);
    }

    private void launchFreshTarget(Class<?> activityClass, boolean preserveExistingTask) {
        Intent intent = new Intent(this, activityClass);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        if (!preserveExistingTask) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        startActivity(intent);
        finishHomeTask();
        overridePendingTransition(0, 0);
    }

    private void finishHomeTask() {
        finish();
    }

    @Override
    public void onBackPressed() {
        if (SessionManager.get().isKioskEnabled()) {
            return;
        }
        super.onBackPressed();
    }
}
