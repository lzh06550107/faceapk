package com.punch.app.utils;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.punch.app.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WifiConfigDialogHelper {
    private static final long WIFI_CONNECT_TIMEOUT_MS = 15_000L;
    private static final long WIFI_CONNECT_POLL_INTERVAL_MS = 1_000L;

    private final AppCompatActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver wifiScanReceiver;
    private boolean wifiScanReceiverRegistered = false;
    private ArrayAdapter<String> pendingWifiScanAdapter;
    private AutoCompleteTextView pendingWifiScanSsidInput;
    private AlertDialog currentDialog;
    private Runnable wifiConnectCheckRunnable;

    public WifiConfigDialogHelper(AppCompatActivity activity) {
        this.activity = activity;
    }

    public void showConfigDialog() {
        Context context = activity;

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(4);
        content.setPadding(padding, padding, padding, 0);

        TextView hint = new TextView(context);
        hint.setText("可搜索附近 Wi-Fi，也可手动输入 SSID。搜索需要定位权限且系统定位已开启；连接配置仍要求当前应用是 Device Owner。");
        hint.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        hint.setTextSize(12f);
        content.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AutoCompleteTextView ssidInput = new AutoCompleteTextView(context);
        ssidInput.setHint("SSID");
        ssidInput.setSingleLine(true);
        ssidInput.setThreshold(0);
        ssidInput.setInputType(InputType.TYPE_CLASS_TEXT);
        ArrayAdapter<String> ssidAdapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_dropdown_item_1line,
                new ArrayList<>()
        );
        ssidInput.setAdapter(ssidAdapter);
        ssidInput.setOnClickListener(v -> ssidInput.showDropDown());
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputParams.topMargin = dp(12);
        content.addView(ssidInput, inputParams);

        EditText passwordInput = new EditText(context);
        passwordInput.setHint("Wi-Fi 密码（开放网络可留空）");
        passwordInput.setSingleLine(true);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        passwordParams.topMargin = dp(10);
        content.addView(passwordInput, passwordParams);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("配置 Wi-Fi")
                .setView(content)
                .setNegativeButton("关闭", null)
                .setNeutralButton("搜索 Wi-Fi", null)
                .setPositiveButton("连接", null)
                .create();

        dialog.setOnShowListener(d -> {
            currentDialog = dialog;
            Button scanButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            Button connectButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            scanButton.setOnClickListener(v -> scanWifiNetworksWithChecks(ssidAdapter, ssidInput));
            connectButton.setOnClickListener(v -> {
                String ssid = ssidInput.getText() == null ? "" : ssidInput.getText().toString().trim();
                String password = passwordInput.getText() == null ? "" : passwordInput.getText().toString();
                if (ssid.isEmpty()) {
                    Toast.makeText(context, "请输入或选择 SSID", Toast.LENGTH_SHORT).show();
                    ssidInput.requestFocus();
                    return;
                }
                connectConfiguredWifi(ssid, password, connectButton);
            });
        });
        dialog.setOnDismissListener(d -> {
            currentDialog = null;
            cancelPendingWifiConnectCheck();
            unregisterWifiScanReceiver();
            clearPendingWifiScan();
        });
        dialog.show();
    }

    public void onResume() {
        retryPendingWifiScanIfReady();
    }

    public void onDestroy() {
        cancelPendingWifiConnectCheck();
        unregisterWifiScanReceiver();
        clearPendingWifiScan();
    }

    private void scanWifiNetworks(ArrayAdapter<String> adapter, AutoCompleteTextView ssidInput) {
        WifiManager wifiManager = getWifiManager();
        if (wifiManager == null) {
            Toast.makeText(activity, "Wi-Fi 服务不可用", Toast.LENGTH_SHORT).show();
            return;
        }

        unregisterWifiScanReceiver();
        wifiScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                unregisterWifiScanReceiver();
                bindWifiScanResults(wifiManager, adapter, ssidInput);
            }
        };
        ContextCompat.registerReceiver(
                activity,
                wifiScanReceiver,
                new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        wifiScanReceiverRegistered = true;

        boolean started;
        try {
            started = wifiManager.startScan();
        } catch (SecurityException e) {
            unregisterWifiScanReceiver();
            Toast.makeText(activity, "无法扫描 Wi-Fi：权限不足", Toast.LENGTH_LONG).show();
            return;
        }
        if (!started) {
            unregisterWifiScanReceiver();
            bindWifiScanResults(wifiManager, adapter, ssidInput);
            Toast.makeText(activity, "扫描请求未启动，已显示缓存结果", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, "正在搜索 Wi-Fi...", Toast.LENGTH_SHORT).show();
    }

    private void bindWifiScanResults(WifiManager wifiManager,
                                     ArrayAdapter<String> adapter,
                                     AutoCompleteTextView ssidInput) {
        List<ScanResult> results;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException e) {
            Toast.makeText(activity, "读取 Wi-Fi 列表失败：权限不足", Toast.LENGTH_LONG).show();
            return;
        }
        Set<String> ssids = new LinkedHashSet<>();
        if (results != null) {
            for (ScanResult result : results) {
                if (result == null || result.SSID == null || result.SSID.trim().isEmpty()) {
                    continue;
                }
                ssids.add(result.SSID.trim());
            }
        }
        adapter.clear();
        adapter.addAll(ssids);
        adapter.notifyDataSetChanged();
        if (ssids.isEmpty()) {
            Toast.makeText(activity, "未搜索到 Wi-Fi，可手动输入 SSID", Toast.LENGTH_LONG).show();
            return;
        }
        ssidInput.requestFocus();
        ssidInput.post(() -> {
            ssidInput.showDropDown();
            Toast.makeText(activity, "SSID 搜索成功", Toast.LENGTH_SHORT).show();
        });
    }

    @SuppressWarnings("deprecation")
    private void connectConfiguredWifi(String ssid, String password, Button connectButton) {
        Context context = activity;
        if (!KioskManager.isDeviceOwner(context)) {
            Toast.makeText(context, "当前不是 Device Owner，无法静默配置 Wi-Fi", Toast.LENGTH_LONG).show();
            return;
        }
        WifiManager wifiManager = getWifiManager();
        if (wifiManager == null) {
            Toast.makeText(context, "Wi-Fi 服务不可用", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            wifiManager.setWifiEnabled(true);

            WifiConfiguration config = new WifiConfiguration();
            config.SSID = quoteWifiValue(ssid);
            if (password == null || password.isEmpty()) {
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                config.preSharedKey = quoteWifiValue(password);
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            }

            int networkId = wifiManager.addNetwork(config);
            if (networkId < 0) {
                Toast.makeText(context, "Wi-Fi 配置写入失败", Toast.LENGTH_LONG).show();
                return;
            }
            wifiManager.disconnect();
            boolean enabled = wifiManager.enableNetwork(networkId, true);
            wifiManager.reconnect();
            if (!enabled) {
                Toast.makeText(context, "Wi-Fi 连接请求失败", Toast.LENGTH_LONG).show();
                return;
            }
            waitForWifiConnection(ssid, password, connectButton);
        } catch (SecurityException e) {
            Toast.makeText(context, "配置 Wi-Fi 失败：权限不足", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(context, "配置 Wi-Fi 失败：" + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasWifiScanPermission() {
        boolean fineLocationGranted = ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return fineLocationGranted;
        }
        return fineLocationGranted
                && ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void scanWifiNetworksWithChecks(ArrayAdapter<String> adapter, AutoCompleteTextView ssidInput) {
        cachePendingWifiScan(adapter, ssidInput);
        if (!hasWifiScanPermission()) {
            KioskManager.ensureOwnerRuntimePermissions(activity);
            if (!hasWifiScanPermission()) {
                showWifiPermissionSettingsDialog();
                return;
            }
        }
        if (!isLocationServiceEnabled()) {
            showEnableLocationDialog();
            return;
        }
        clearPendingWifiScan();
        scanWifiNetworks(adapter, ssidInput);
    }

    private boolean isLocationServiceEnabled() {
        LocationManager locationManager =
                (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        }
        try {
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    private void showEnableLocationDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("开启定位")
                .setMessage("扫描附近 Wi-Fi 需要系统定位开关处于开启状态。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去开启", (dialog, which) -> openLocationSettings())
                .show();
    }

    private void showWifiPermissionSettingsDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("缺少权限")
                .setMessage("扫描附近 Wi-Fi 需要定位权限。请在系统设置中授予后重试。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置", (dialog, which) -> openAppPermissionSettings())
                .show();
    }

    private void openLocationSettings() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(activity, "无法打开定位设置", Toast.LENGTH_LONG).show();
        }
    }

    private void openAppPermissionSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "无法打开应用设置", Toast.LENGTH_LONG).show();
        }
    }

    private void cachePendingWifiScan(ArrayAdapter<String> adapter, AutoCompleteTextView ssidInput) {
        pendingWifiScanAdapter = adapter;
        pendingWifiScanSsidInput = ssidInput;
    }

    private void clearPendingWifiScan() {
        pendingWifiScanAdapter = null;
        pendingWifiScanSsidInput = null;
    }

    private void retryPendingWifiScanIfReady() {
        if (pendingWifiScanAdapter == null || pendingWifiScanSsidInput == null) {
            return;
        }
        if (!pendingWifiScanSsidInput.isAttachedToWindow()) {
            clearPendingWifiScan();
            return;
        }
        if (!hasWifiScanPermission() || !isLocationServiceEnabled()) {
            return;
        }
        ArrayAdapter<String> adapter = pendingWifiScanAdapter;
        AutoCompleteTextView ssidInput = pendingWifiScanSsidInput;
        clearPendingWifiScan();
        scanWifiNetworks(adapter, ssidInput);
    }

    private void waitForWifiConnection(String ssid, String password, Button connectButton) {
        WifiManager wifiManager = getWifiManager();
        if (wifiManager == null) {
            Toast.makeText(activity, "Wi-Fi 服务不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        cancelPendingWifiConnectCheck();
        connectButton.setEnabled(false);
        connectButton.setText("连接中...");

        long startedAt = System.currentTimeMillis();
        wifiConnectCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnectedToTargetSsid(wifiManager, ssid)) {
                    connectButton.setEnabled(true);
                    connectButton.setText("连接");
                    cancelPendingWifiConnectCheck();
                    SessionManager.get().saveLastWifiConfig(ssid, password);
                    Toast.makeText(activity, "Wi-Fi 连接成功", Toast.LENGTH_SHORT).show();
                    if (currentDialog != null && currentDialog.isShowing()) {
                        currentDialog.dismiss();
                    }
                    return;
                }
                if (System.currentTimeMillis() - startedAt >= WIFI_CONNECT_TIMEOUT_MS) {
                    connectButton.setEnabled(true);
                    connectButton.setText("连接");
                    cancelPendingWifiConnectCheck();
                    Toast.makeText(activity, "Wi-Fi 连接超时，请稍后重试", Toast.LENGTH_LONG).show();
                    return;
                }
                mainHandler.postDelayed(this, WIFI_CONNECT_POLL_INTERVAL_MS);
            }
        };
        mainHandler.post(wifiConnectCheckRunnable);
    }

    @SuppressWarnings("deprecation")
    private boolean isConnectedToTargetSsid(WifiManager wifiManager, String targetSsid) {
        try {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
                return false;
            }
            String connectedSsid = stripWifiQuotes(wifiInfo.getSSID());
            return connectedSsid.equals(stripWifiQuotes(targetSsid));
        } catch (Exception e) {
            return false;
        }
    }

    private void cancelPendingWifiConnectCheck() {
        if (wifiConnectCheckRunnable != null) {
            mainHandler.removeCallbacks(wifiConnectCheckRunnable);
            wifiConnectCheckRunnable = null;
        }
    }

    private WifiManager getWifiManager() {
        Context appContext = activity.getApplicationContext();
        return (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
    }

    private void unregisterWifiScanReceiver() {
        if (!wifiScanReceiverRegistered || wifiScanReceiver == null) {
            return;
        }
        try {
            activity.unregisterReceiver(wifiScanReceiver);
        } catch (Exception ignored) {
        } finally {
            wifiScanReceiverRegistered = false;
            wifiScanReceiver = null;
        }
    }

    private String quoteWifiValue(String value) {
        String safeValue = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + safeValue + "\"";
    }

    private String stripWifiQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private String safeMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) {
            return "未知错误";
        }
        return e.getMessage().trim();
    }
}