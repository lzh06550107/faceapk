package com.punch.app;

import com.punch.app.utils.KioskManager;

public class DebugPunchApplication extends PunchApplication {
    @Override
    public void onCreate() {
        boolean uiSmokeEnabled = UiSmokeRuntimeFlags.isEnabled();
        PunchApplication.setUiTestModeForTest(uiSmokeEnabled);
        KioskManager.setUiTestBypassForTest(uiSmokeEnabled);
        super.onCreate();
    }
}
