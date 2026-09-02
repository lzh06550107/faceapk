package com.punch.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.punch.app.activity.KioskHomeActivity;
import com.punch.app.utils.KioskManager;

/**
 * Test-only Device Owner maintenance bridge.
 *
 * Kept in the deviceOwnerTest source set so release/smoke builds do not expose
 * an external control surface capable of changing kiosk policy.
 */
public class DeviceOwnerTestControlReceiver extends BroadcastReceiver {
    private static final String TAG = "DeviceOwnerTestControl";
    private static final String EXTRA_MODE = "mode";
    private static final String MODE_ENTER = "enter";
    private static final String MODE_EXIT = "exit";
    private static final String[] TEST_PACKAGES = {
            "com.punch.app.smoke",
            "com.punch.app.smoke.test"
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        String mode = intent == null ? null : intent.getStringExtra(EXTRA_MODE);
        if (MODE_ENTER.equals(mode)) {
            KioskManager.setDeviceTestMaintenanceModeForTest(true);
            boolean success = KioskManager.enterDeviceTestMaintenanceMode(context, TEST_PACKAGES);
            Log.i(TAG, "maintenance ENTER result=" + success);
            setResultCode(success ? 0 : 1);
            return;
        }

        if (MODE_EXIT.equals(mode)) {
            boolean success = KioskManager.restoreDeviceOwnerKioskAfterTest(context, TEST_PACKAGES);
            Log.i(TAG, "maintenance EXIT result=" + success);
            if (success) {
                Intent home = new Intent(context, KioskHomeActivity.class);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(home);
            }
            setResultCode(success ? 0 : 1);
            return;
        }

        Log.e(TAG, "Unknown maintenance mode: " + mode);
        setResultCode(2);
    }
}
