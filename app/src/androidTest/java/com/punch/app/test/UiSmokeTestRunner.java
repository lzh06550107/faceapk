package com.punch.app.test;

import android.app.Application;
import android.content.Context;

import androidx.test.runner.AndroidJUnitRunner;

import com.punch.app.PunchApplication;
import com.punch.app.utils.KioskManager;

public class UiSmokeTestRunner extends AndroidJUnitRunner {
    @Override
    public Application newApplication(ClassLoader cl, String className, Context context)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        PunchApplication.setUiTestModeForTest(true);
        KioskManager.setUiTestBypassForTest(true);
        return super.newApplication(cl, "com.punch.app.PunchApplication", context);
    }
}
