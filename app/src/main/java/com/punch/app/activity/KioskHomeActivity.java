package com.punch.app.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.punch.app.PunchApplication;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.WifiAutoReconnectManager;

public class KioskHomeActivity extends Activity {
    private static final long RECENT_APP_VISIBLE_WINDOW_MS = 3_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (PunchApplication.isUiTestModeEnabled()) {
            return;
        }
        WifiAutoReconnectManager.ensureSavedWifiConnection(this);

        PunchApplication app = PunchApplication.get();
        if (SessionManager.get().isKioskEnabled()
                && KioskManager.bringExistingAppTaskToFront(this, getTaskId())) {
            if (app != null && app.wasNonHomeActivityRecentlyVisible(RECENT_APP_VISIBLE_WINDOW_MS)) {
                finishHomeTask();
            } else {
                launchFreshTarget(resolveDefaultActivityClass());
            }
            return;
        }

        if (SessionManager.get().isKioskEnabled()
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
        return LaunchRouteResolver.resolveAuthenticatedEntry(SessionManager.get().isTokenValid());
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

    private void launchFreshTarget(Class<?> activityClass) {
        Intent intent = new Intent(this, activityClass);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
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
