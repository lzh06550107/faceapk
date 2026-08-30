package com.punch.app.activity;

import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.appcompat.app.AppCompatActivity;

import com.punch.app.R;
import com.punch.app.receiver.UpdateInstallStateReceiver;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.WifiConfigDialogHelper;

public class SetupWizardActivity extends AppCompatActivity {
    private static final int PAGE_WIFI = 0;
    private static final int PAGE_SERVER = 1;

    private ViewFlipper flipper;
    private TextView tvWifiStatus;
    private TextView tvError;
    private EditText etBaseUrl;
    private EditText etCompanyId;
    private Button btnNext;
    private Button btnFinish;
    private WifiConfigDialogHelper wifiConfigDialogHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_wizard);

        flipper = findViewById(R.id.flipper);
        tvWifiStatus = findViewById(R.id.tv_wifi_status);
        tvError = findViewById(R.id.tv_error);
        etBaseUrl = findViewById(R.id.et_base_url);
        etCompanyId = findViewById(R.id.et_company_id);
        btnNext = findViewById(R.id.btn_next);
        btnFinish = findViewById(R.id.btn_finish);
        wifiConfigDialogHelper = new WifiConfigDialogHelper(this);

        etBaseUrl.setText(SessionManager.get().getBaseUrl());
        etCompanyId.setText(String.valueOf(SessionManager.get().getCompanyId()));

        findViewById(R.id.btn_configure_wifi).setOnClickListener(v -> wifiConfigDialogHelper.showConfigDialog());
        findViewById(R.id.btn_skip_wifi).setOnClickListener(v -> goToServerPage());
        btnNext.setOnClickListener(v -> goToServerPage());
        findViewById(R.id.btn_prev).setOnClickListener(v -> goToWifiPage());
        btnFinish.setOnClickListener(v -> completeSetup());

        showWifiPage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        KioskManager.enterIfPossible(this);
        wifiConfigDialogHelper.onResume();
        refreshWifiStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        wifiConfigDialogHelper.onDestroy();
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
        if (hasFocus) {
            UpdateInstallStateReceiver.acknowledgeUpdatedAppLaunch(this);
            return;
        }
        KioskManager.restoreAppTaskSoon(this);
    }

    @Override
    public void onBackPressed() {
        if (SessionManager.get().isKioskEnabled()) {
            return;
        }
        if (flipper.getDisplayedChild() == PAGE_SERVER) {
            goToWifiPage();
            return;
        }
        super.onBackPressed();
    }

    private void showWifiPage() {
        flipper.setDisplayedChild(PAGE_WIFI);
        refreshWifiStatus();
    }

    private void goToWifiPage() {
        showWifiPage();
    }

    private void goToServerPage() {
        flipper.setDisplayedChild(PAGE_SERVER);
        tvError.setVisibility(View.GONE);
    }

    private void refreshWifiStatus() {
        String ssid = readConnectedSsid();
        if (ssid.isEmpty()) {
            tvWifiStatus.setText("尚未连接 Wi-Fi");
        } else {
            tvWifiStatus.setText("已连接：" + ssid);
        }
    }

    @SuppressWarnings("deprecation")
    private String readConnectedSsid() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager == null) {
                return "";
            }
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) {
                return "";
            }
            String ssid = info.getSSID();
            if (ssid == null) {
                return "";
            }
            String trimmed = ssid.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            if ("<unknown ssid>".equals(trimmed)) {
                return "";
            }
            return trimmed;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void completeSetup() {
        String rawBaseUrl = etBaseUrl.getText() == null
                ? ""
                : etBaseUrl.getText().toString().trim();
        String baseUrl = SessionManager.normalizeBaseUrl(rawBaseUrl);
        if (rawBaseUrl.isEmpty() || !baseUrl.equals(rawBaseUrl.replaceAll("/+$", "").trim())) {
            showError("Base URL 必须是有效的 http/https 地址");
            etBaseUrl.requestFocus();
            return;
        }

        int companyId = parseCompanyId();
        if (companyId <= 0) {
            showError("Company ID 必须为正整数");
            etCompanyId.requestFocus();
            return;
        }

        SessionManager session = SessionManager.get();
        boolean serverChanged = !baseUrl.equals(session.getBaseUrl()) || companyId != session.getCompanyId();
        session.saveBaseUrl(baseUrl);
        session.saveCompanyId(companyId);
        if (serverChanged) {
            session.clearServerBoundState();
        }
        session.saveSetupCompleted(true);

        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private int parseCompanyId() {
        String value = etCompanyId.getText() == null
                ? ""
                : etCompanyId.getText().toString().trim();
        if (value.isEmpty() || !TextUtils.isDigitsOnly(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }
}
