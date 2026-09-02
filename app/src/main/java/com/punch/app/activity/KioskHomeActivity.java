package com.punch.app.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.punch.app.PunchApplication;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.WifiReconnectCoordinator;

public class KioskHomeActivity extends Activity {
    public static final String EXTRA_FORCE_FRESH_TARGET = "force_fresh_target";

    private static final long RECENT_APP_VISIBLE_WINDOW_MS = 3_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (PunchApplication.isUiTestModeEnabled()) {
            return;
        }
        WifiReconnectCoordinator.get(this).requestReconnect("kiosk_home");

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
            launchFreshTarget(resolveDefaultActivityClass());
            return;
        }

        launchFreshTarget(resolveDefaultActivityClass());
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
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private void launchFreshTarget(Class<?> activityClass) {
        Intent intent = new Intent(this, activityClass);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        startActivity(intent);
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
