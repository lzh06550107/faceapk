package com.punch.app.processrecovery;

import com.punch.app.PunchApplication;
import com.punch.app.db.ProcessRecoveryDatabaseController;
import com.punch.app.network.ProcessRecoveryNetworkController;
import com.punch.app.utils.KioskManager;

/** V2.7.1 same-package test application that keeps production lifecycle/Kiosk behavior enabled. */
public class ProcessRecoveryTestApplication extends PunchApplication {
    @Override
    public void onCreate() {
        ProcessRecoveryDatabaseController.activateExisting(this);
        ProcessRecoveryNetworkController.reset();
        PunchApplication.setUiTestModeForTest(false);
        KioskManager.setUiTestBypassForTest(false);
        KioskManager.setDeviceTestMaintenanceModeForTest(false);
        super.onCreate();
    }
}
