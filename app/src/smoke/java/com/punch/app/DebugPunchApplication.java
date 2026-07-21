package com.punch.app;

import com.punch.app.utils.KioskManager;

public class DebugPunchApplication extends PunchApplication {
    @Override
    public void onCreate() {
        if (UiSmokeRuntimeFlags.isEnabled()) {
            PunchApplication.setUiTestModeForTest(true);
            KioskManager.setUiTestBypassForTest(true);
        }
        super.onCreate();
    }
}
