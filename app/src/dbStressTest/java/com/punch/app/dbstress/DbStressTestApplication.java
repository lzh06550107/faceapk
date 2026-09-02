package com.punch.app.dbstress;

import com.punch.app.PunchApplication;
import com.punch.app.db.DbStressDatabaseController;
import com.punch.app.network.DbStressNetworkController;

/** Test-only application that suppresses production startup jobs and selects the V2.6 DB. */
public class DbStressTestApplication extends PunchApplication {
    static {
        PunchApplication.setUiTestModeForTest(true);
    }

    @Override
    public void onCreate() {
        DbStressDatabaseController.activateExisting(this);
        DbStressNetworkController.reset();
        super.onCreate();
    }
}
