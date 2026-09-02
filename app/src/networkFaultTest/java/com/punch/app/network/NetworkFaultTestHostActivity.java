package com.punch.app.network;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * Test-only foreground host for V2.5.
 *
 * Android 8+ blocks Context.startService() when the caller app is backgrounded.
 * Production SyncService callers normally run from activities/fragments, while the V2.5
 * control plane is an adb-driven BroadcastReceiver. Keeping this activity resumed makes
 * the temporary test APK foreground so the unmodified production SyncService path can run.
 */
public class NetworkFaultTestHostActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
    }
}
