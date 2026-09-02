package com.punch.app.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.punch.app.utils.KioskManager;

public class DeviceOwnerTestControlActivity extends Activity {
    private static final String TAG = "DeviceOwnerTestControl";
    private static final String EXTRA_MODE = "mode";
    private static final String MODE_ENTER = "enter";
    private static final String MODE_EXIT = "exit";
    private static final String[] TEST_PACKAGES = {
            "com.punch.app.smoke",
            "com.punch.app.smoke.test"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String mode = intent == null ? null : intent.getStringExtra(EXTRA_MODE);
        boolean success;
        if (MODE_ENTER.equals(mode)) {
            KioskManager.setDeviceTestMaintenanceModeForTest(true);
            success = KioskManager.enterDeviceTestMaintenanceMode(this, TEST_PACKAGES);
            Log.i(TAG, "maintenance ENTER result=" + success);
            finish();
            return;
        }
        if (MODE_EXIT.equals(mode)) {
            success = KioskManager.restoreDeviceOwnerKioskAfterTest(this, TEST_PACKAGES);
            Log.i(TAG, "maintenance EXIT result=" + success);
            Intent home = new Intent(this, KioskHomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(home);
            finish();
            return;
        }
        Log.e(TAG, "Unknown maintenance mode: " + mode);
        finish();
    }
}
