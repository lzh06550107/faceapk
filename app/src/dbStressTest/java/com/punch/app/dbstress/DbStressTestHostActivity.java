package com.punch.app.dbstress;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

/** Test-only foreground host so production SyncService.startService remains legal on Android 8+. */
public class DbStressTestHostActivity extends Activity {
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
