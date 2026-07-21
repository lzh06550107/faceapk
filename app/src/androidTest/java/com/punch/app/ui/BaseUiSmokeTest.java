package com.punch.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import com.punch.app.PunchApplication;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;

import org.junit.After;
import org.junit.Before;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public abstract class BaseUiSmokeTest {
    protected static final long UI_TIMEOUT_MS = 15_000L;

    protected Context context;
    protected UiDevice device;

    @Before
    public void setUpUiSmokeEnvironment() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        PunchApplication.setUiTestModeForTest(true);
        KioskManager.setUiTestBypassForTest(true);
        SessionManager.get().init(context);
    }

    @After
    public void tearDownUiSmokeEnvironment() {
        KioskManager.setUiTestBypassForTest(false);
    }

    protected final void startActivity(Class<? extends Activity> activityClass, String readyResId)
            throws Exception {
        startActivity(activityClass, readyResId, UI_TIMEOUT_MS, true);
    }

    protected final void startActivity(Class<? extends Activity> activityClass,
                                       String readyResId,
                                       long timeoutMs,
                                       boolean waitForIdle)
            throws Exception {
        Intent intent = new Intent(context, activityClass);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        if (waitForIdle) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        assertTrue(
                "Timed out waiting for " + readyResId,
                device.wait(Until.hasObject(By.res(context.getPackageName(), readyResId)), timeoutMs)
        );
    }

    protected final void scrollToResource(String resId) throws UiObjectNotFoundException {
        if (device.hasObject(By.res(context.getPackageName(), resId))) {
            return;
        }
        UiScrollable scrollable = new UiScrollable(new UiSelector().scrollable(true));
        scrollable.setAsVerticalList();
        for (int i = 0; i < 6; i++) {
            if (device.hasObject(By.res(context.getPackageName(), resId))) {
                return;
            }
            if (!scrollable.scrollForward()) {
                break;
            }
        }
        assertNotNull(
                "Expected resource not found after scroll: " + resId,
                device.wait(Until.findObject(By.res(context.getPackageName(), resId)), UI_TIMEOUT_MS)
        );
    }
}
