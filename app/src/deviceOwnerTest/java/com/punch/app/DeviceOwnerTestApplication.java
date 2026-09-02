package com.punch.app;

import com.punch.app.utils.KioskManager;

public class DeviceOwnerTestApplication extends PunchApplication {
    @Override
    public void onCreate() {
        KioskManager.setDeviceTestMaintenanceModeForTest(
                DeviceOwnerTestRuntimeFlags.isMaintenanceEnabled()
        );
        super.onCreate();
    }
}
