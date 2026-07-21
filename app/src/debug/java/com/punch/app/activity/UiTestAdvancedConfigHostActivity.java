package com.punch.app.activity;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.punch.app.fragment.AdvancedConfigFragment;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;

public class UiTestAdvancedConfigHostActivity extends AppCompatActivity {
    private static final int CONTAINER_ID = 0x7f0b7fff;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SessionManager.get().init(this);
        KioskManager.setUiTestBypassForTest(true);
        super.onCreate(savedInstanceState);

        FrameLayout container = new FrameLayout(this);
        container.setId(CONTAINER_ID);
        setContentView(container);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(CONTAINER_ID, new AdvancedConfigFragment())
                    .commitNow();
        }
    }

    @Override
    protected void onDestroy() {
        KioskManager.setUiTestBypassForTest(false);
        super.onDestroy();
    }
}
