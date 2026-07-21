package com.punch.app.activity;

import android.os.Bundle;

import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;

public class UiTestLoginHostActivity extends LoginActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SessionManager.get().init(this);
        KioskManager.setUiTestBypassForTest(true);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        KioskManager.setUiTestBypassForTest(false);
        super.onDestroy();
    }
}
