package com.punch.app.activity;

import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.punch.app.R;
import com.punch.app.fragment.AdvancedConfigFragment;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;

public class AdvancedConfigActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced_config);

        ImageButton btnBack = findViewById(R.id.btn_back);
        TextView tvTitle = findViewById(R.id.tv_title);
        tvTitle.setText("\u9ad8\u7ea7\u914d\u7f6e");
        btnBack.setOnClickListener(v -> finish());

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, new AdvancedConfigFragment())
                    .commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        KioskManager.enterIfPossible(this);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && KioskManager.shouldBlockSystemKey(event.getKeyCode())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            KioskManager.restoreAppTaskSoon(this);
        }
    }

    @Override
    public void onBackPressed() {
        if (SessionManager.get().isKioskEnabled()) {
            return;
        }
        super.onBackPressed();
    }
}
