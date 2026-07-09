package com.punch.app.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.punch.app.R;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;

public class SplashActivity extends AppCompatActivity {
    private static final long SPLASH_DELAY_MS = 800L;
    public static final String EXTRA_MAINTENANCE_EXIT_KIOSK = "maintenance_exit_kiosk";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        if (shouldExitKioskForMaintenance()) {
            KioskManager.exitAndDisable(this);
        }

        if (shouldRouteImmediately()) {
            routeNext();
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::routeNext, SPLASH_DELAY_MS);
    }

    private boolean shouldRouteImmediately() {
        Intent intent = getIntent();
        if (intent == null) {
            return false;
        }
        return intent.hasCategory(Intent.CATEGORY_HOME);
    }

    private boolean shouldExitKioskForMaintenance() {
        Intent intent = getIntent();
        return intent != null && intent.getBooleanExtra(EXTRA_MAINTENANCE_EXIT_KIOSK, false);
    }

    private void routeNext() {
        Intent nextIntent;
        if (SessionManager.get().isTokenValid()) {
            nextIntent = new Intent(this, MainActivity.class);
        } else {
            nextIntent = new Intent(this, LoginActivity.class);
        }
        nextIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(nextIntent);
        finish();
    }

}
