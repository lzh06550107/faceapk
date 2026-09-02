package com.punch.app.activity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.punch.app.ui.BaseUiSmokeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public class KioskHomeColdStartTest extends BaseUiSmokeTest {

    @Test
    public void coldLaunchKeepsHomeTaskAndDoesNotClearTargetTask() {
        try (ActivityScenario<UiTestKioskHomeHostActivity> scenario =
                     ActivityScenario.launch(UiTestKioskHomeHostActivity.class)) {
            scenario.onActivity(activity -> {
                invokeFreshTargetLaunch(activity);

                Intent startedIntent = activity.getStartedIntent();
                assertNotNull(startedIntent);
                assertFalse(activity.wasFinishRequested());
                assertEquals(0, startedIntent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP);
                activity.allowFinish();
            });
        }
    }

    @Test
    public void recentActivityFallbackKeepsHomeTaskAndDoesNotClearTargetTask() {
        try (ActivityScenario<UiTestKioskHomeHostActivity> scenario =
                     ActivityScenario.launch(UiTestKioskHomeHostActivity.class)) {
            scenario.onActivity(activity -> {
                invokeRecentTargetLaunch(activity);

                Intent startedIntent = activity.getStartedIntent();
                assertNotNull(startedIntent);
                assertFalse(activity.wasFinishRequested());
                assertEquals(0, startedIntent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP);
                activity.allowFinish();
            });
        }
    }

    @Test
    public void homeEntryIsStableAcrossTargetLaunches() throws Exception {
        PackageManager packageManager = context.getPackageManager();
        ActivityInfo info = packageManager.getActivityInfo(
                new ComponentName(context, KioskHomeActivity.class),
                0
        );

        assertEquals(0, info.flags & ActivityInfo.FLAG_NO_HISTORY);
        assertEquals(0, info.flags & ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH);
    }

    private static void invokeFreshTargetLaunch(UiTestKioskHomeHostActivity activity) {
        try {
            Method method = KioskHomeActivity.class.getDeclaredMethod(
                    "launchFreshTarget",
                    Class.class
            );
            method.setAccessible(true);
            method.invoke(activity, MainActivity.class);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to exercise the cold-start route", e);
        }
    }

    private static void invokeRecentTargetLaunch(UiTestKioskHomeHostActivity activity) {
        try {
            Method method = KioskHomeActivity.class.getDeclaredMethod(
                    "launchTarget",
                    Class.class
            );
            method.setAccessible(true);
            method.invoke(activity, MainActivity.class);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to exercise the recent-activity fallback route", e);
        }
    }
}
