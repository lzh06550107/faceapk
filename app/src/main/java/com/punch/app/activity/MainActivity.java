package com.punch.app.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.punch.app.PunchApplication;
import com.punch.app.R;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.fragment.ConfigFragment;
import com.punch.app.fragment.PunchFragment;
import com.punch.app.fragment.RecordsFragment;
import com.punch.app.network.ApiResult;
import com.punch.app.network.ApiService;
import com.punch.app.network.dto.AuthDto;
import com.punch.app.receiver.UpdateInstallStateReceiver;
import com.punch.app.service.HeartbeatManager;
import com.punch.app.service.SyncService;
import com.punch.app.service.SyncTrigger;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;

public class MainActivity extends AppCompatActivity {
    private static final long UPDATE_AUTO_LAUNCH_CANCEL_DELAY_MS = 1_500L;

    private TextView tvBanner;
    private BottomNavigationView bottomNav;

    private PunchFragment punchFragment;
    private RecordsFragment recordsFragment;
    private ConfigFragment configFragment;
    private Fragment currentFragment;

    private boolean wasOffline = false;
    private boolean punchFullscreen = false;

    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean online = isNetworkAvailable();
            updateBanner(online);
            if (online && wasOffline && SessionManager.get().isTokenValid()) {
                SyncService.triggerSync(MainActivity.this, SyncTrigger.NETWORK_RESTORED);
            }
            wasOffline = !online;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvBanner = findViewById(R.id.tv_banner);
        bottomNav = findViewById(R.id.bottom_nav);

        punchFragment = new PunchFragment();
        recordsFragment = new RecordsFragment();
        configFragment = new ConfigFragment();

        showFragment(punchFragment);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_punch) {
                showFragment(punchFragment);
            } else if (id == R.id.nav_records) {
                showFragment(recordsFragment);
            } else if (id == R.id.nav_config) {
                showFragment(configFragment);
            }
            return true;
        });

        registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        updateBanner(isNetworkAvailable());
        if (SessionManager.get().isTokenValid()) {
            SyncService.triggerSync(this, SyncTrigger.APP_START);
        }
        if (SessionManager.get().isTokenNearExpiry()) {
            refreshTokenAsync();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        KioskManager.enterIfPossible(this);
        scheduleStableUpdateAutoLaunchCancel();
    }

    private void scheduleStableUpdateAutoLaunchCancel() {
        bottomNav.postDelayed(() -> {
            if (isFinishing()) {
                return;
            }
            if (isDestroyed()) {
                return;
            }
            if (!KioskManager.isInLockedTaskMode(this)) {
                return;
            }
            UpdateInstallStateReceiver.cancelScheduledAutoLaunch(this);
        }, UPDATE_AUTO_LAUNCH_CANCEL_DELAY_MS);
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

    private void showFragment(Fragment fragment) {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (!fragment.isAdded()) {
            transaction.add(R.id.fragment_container, fragment);
        }
        if (currentFragment != null && currentFragment != fragment) {
            if (currentFragment == recordsFragment) {
                recordsFragment.onPanelExited();
            }
            transaction.hide(currentFragment);
        }
        transaction.show(fragment);
        transaction.commitAllowingStateLoss();
        currentFragment = fragment;
        if (fragment == recordsFragment) {
            recordsFragment.onPanelEntered();
        }
    }

    private void updateBanner(boolean online) {
        if (punchFullscreen) {
            tvBanner.setVisibility(View.GONE);
            return;
        }

        int pending = DatabaseHelper.get(this).getPendingCount();
        if (!online) {
            tvBanner.setVisibility(View.VISIBLE);
            tvBanner.setBackgroundColor(0xFFC62828);
            tvBanner.setText("\u79bb\u7ebf\u6a21\u5f0f\uff0c\u5f85\u540c\u6b65 " + pending + " \u6761");
        } else if (pending > 0 && SessionManager.get().isTokenValid()) {
            tvBanner.setVisibility(View.VISIBLE);
            tvBanner.setBackgroundColor(0xFFEF6C00);
            tvBanner.setText("\u5f85\u540c\u6b65 " + pending + " \u6761\uff0c\u914d\u7f6e\u9875\u70b9\u51fb\u7acb\u5373\u540c\u6b65");
            tvBanner.setOnClickListener(null);
        } else {
            tvBanner.setVisibility(View.GONE);
            tvBanner.setOnClickListener(null);
        }
    }

    public void setPunchFullscreen(boolean fullscreen) {
        punchFullscreen = fullscreen;
        bottomNav.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        if (fullscreen) {
            tvBanner.setVisibility(View.GONE);
        } else {
            updateBanner(isNetworkAvailable());
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private void refreshTokenAsync() {
        new Thread(() -> {
            ApiResult<AuthDto.TokenData> result = ApiService.refreshToken(SessionManager.get().getToken());
            if (result.success && result.data != null) {
                SessionManager.get().saveToken(result.data.token, result.data.tokenExpireAt);
                return;
            }
            handleRefreshTokenFailure();
        }).start();
    }

    private void handleRefreshTokenFailure() {
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.resetPunchRecognitionState();
            app.resetPunchStatusTimeline("\u8bf7\u91cd\u65b0\u767b\u5f55\u540e\u7ee7\u7eed\u4f7f\u7528", PunchApplication.STATUS_LEVEL_INFO);
        }
        SessionManager.get().clearLoginState();
        HeartbeatManager.get(this).stop();
        runOnUiThread(() -> {
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(networkReceiver);
    }
}
