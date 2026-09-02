package com.punch.app.camerafacerecovery;

import com.punch.app.PunchApplication;
import com.punch.app.db.CameraFaceRecoveryDatabaseController;
import com.punch.app.network.CameraFaceRecoveryNetworkController;
import com.punch.app.utils.KioskManager;

/** V2.7.3 keeps the complete production lifecycle while isolating DB and network. */
public class CameraFaceRecoveryTestApplication extends PunchApplication {
    @Override
    public void onCreate() {
        CameraFaceRecoveryUpdateGuard.block();
        CameraFaceRecoveryDatabaseController.activateExisting(this);
        CameraFaceRecoveryNetworkController.reset();
        CameraFaceRecoveryUpdateGuard.block();
        PunchApplication.setUiTestModeForTest(false);
        KioskManager.setUiTestBypassForTest(false);
        KioskManager.setDeviceTestMaintenanceModeForTest(false);
        super.onCreate();
    }
}
