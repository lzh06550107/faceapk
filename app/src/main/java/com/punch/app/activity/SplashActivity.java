package com.punch.app.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import androidx.appcompat.app.AppCompatActivity;

import com.punch.app.R;
import com.punch.app.receiver.UpdateInstallStateReceiver;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.WifiAutoReconnectManager;

public class SplashActivity extends AppCompatActivity {
    private static final long SPLASH_DELAY_MS = 800L;
    public static final String EXTRA_MAINTENANCE_EXIT_KIOSK = "maintenance_exit_kiosk";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        if (com.punch.app.PunchApplication.isUiTestModeEnabled()) {
            return;
        }
        KioskManager.enterIfPossible(this);
        WifiAutoReconnectManager.ensureSavedWifiConnection(this);

        if (shouldExitKioskForMaintenance()) {
            KioskManager.exitAndDisable(this);
            finish();
            return;
        }

        if (shouldRouteImmediately()) {
            routeNext();
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::routeNext, SPLASH_DELAY_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        KioskManager.enterIfPossible(this);
    }

    private boolean shouldRouteImmediately() {
        return LaunchRouteResolver.shouldRouteImmediately(getIntent());
    }

    private boolean shouldExitKioskForMaintenance() {
        Intent intent = getIntent();
        return intent != null && intent.getBooleanExtra(EXTRA_MAINTENANCE_EXIT_KIOSK, false);
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

    private void routeNext() {
        Intent nextIntent = new Intent(this,
                LaunchRouteResolver.resolveAuthenticatedEntry(SessionManager.get().isTokenValid()));
        nextIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(nextIntent);
        finish();
    }

}
