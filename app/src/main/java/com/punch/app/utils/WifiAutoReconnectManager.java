package com.punch.app.utils;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.util.List;

public final class WifiAutoReconnectManager {
    private static final String TAG = "WifiAutoReconnect";
    private static final long MIN_RETRY_INTERVAL_MS = 10_000L;

    private static long lastAttemptAt = 0L;

    private WifiAutoReconnectManager() {
    }

    public static void ensureSavedWifiConnection(Context context) {
        if (context == null) {
            return;
        }
        ensureSavedWifiConnection(new AndroidDeps(context.getApplicationContext()));
    }

    static void ensureSavedWifiConnection(Deps deps) {
        if (deps == null) {
            return;
        }
        long now = deps.now();
        if (now - lastAttemptAt < MIN_RETRY_INTERVAL_MS) {
            return;
        }
        lastAttemptAt = now;

        String ssid = deps.getSavedSsid();
        if (ssid == null || ssid.trim().isEmpty()) {
            return;
        }
        if (!deps.isDeviceOwner()) {
            AppLogger.i(TAG, "Skip auto reconnect because app is not device owner");
            return;
        }
        if (!deps.hasWifiService()) {
            AppLogger.w(TAG, "Skip auto reconnect because wifi service is unavailable");
            return;
        }
        if (deps.isConnectedToTargetSsid(ssid)) {
            AppLogger.i(TAG, "Saved wifi already connected: " + ssid);
            return;
        }

        String password = deps.getSavedPassword();
        try {
            deps.setWifiEnabled(true);
            int networkId = deps.addOrFindNetworkId(ssid, password);
            if (networkId < 0) {
                AppLogger.w(TAG, "Saved wifi network not available for reconnect: " + ssid);
                return;
            }
            deps.disconnect();
            boolean enabled = deps.enableNetwork(networkId, true);
            deps.reconnect();
            AppLogger.i(TAG, "Auto reconnect submitted: ssid=" + ssid + ", enabled=" + enabled);
        } catch (SecurityException e) {
            AppLogger.e(TAG, "Auto reconnect failed because of missing permission", e);
        } catch (Exception e) {
            AppLogger.e(TAG, "Auto reconnect failed: " + e.getMessage(), e);
        }
    }

    static void resetForTest() {
        lastAttemptAt = 0L;
    }

    @SuppressWarnings("deprecation")
    private static int addOrFindNetworkId(WifiManager wifiManager, String ssid, String password) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = quoteWifiValue(ssid);
        if (password == null || password.isEmpty()) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            config.preSharedKey = quoteWifiValue(password);
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }

        int networkId = wifiManager.addNetwork(config);
        if (networkId >= 0) {
            return networkId;
        }
        return findConfiguredNetworkId(wifiManager, ssid);
    }

    @SuppressWarnings("deprecation")
    private static int findConfiguredNetworkId(WifiManager wifiManager, String ssid) {
        try {
            List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
            if (configuredNetworks == null) {
                return -1;
            }
            String targetSsid = stripWifiQuotes(ssid);
            for (WifiConfiguration item : configuredNetworks) {
                if (item == null) {
                    continue;
                }
                if (stripWifiQuotes(item.SSID).equals(targetSsid)) {
                    return item.networkId;
                }
            }
        } catch (SecurityException e) {
            AppLogger.e(TAG, "Read configured networks failed", e);
        }
        return -1;
    }

    @SuppressWarnings("deprecation")
    private static boolean isConnectedToTargetSsid(WifiManager wifiManager, String targetSsid) {
        try {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
                return false;
            }
            return stripWifiQuotes(wifiInfo.getSSID()).equals(stripWifiQuotes(targetSsid));
        } catch (Exception e) {
            return false;
        }
    }

    private static String quoteWifiValue(String value) {
        String safeValue = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + safeValue + "\"";
    }

    private static String stripWifiQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    interface Deps {
        long now();

        String getSavedSsid();

        String getSavedPassword();

        boolean isDeviceOwner();

        boolean hasWifiService();

        boolean isConnectedToTargetSsid(String ssid);

        void setWifiEnabled(boolean enabled);

        int addOrFindNetworkId(String ssid, String password);

        void disconnect();

        boolean enableNetwork(int networkId, boolean disableOthers);

        void reconnect();
    }

    private static final class AndroidDeps implements Deps {
        private final Context appContext;
        private final WifiManager wifiManager;

        private AndroidDeps(Context appContext) {
            this.appContext = appContext;
            this.wifiManager = appContext == null
                    ? null
                    : (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public String getSavedSsid() {
            return SessionManager.get().getLastWifiSsid();
        }

        @Override
        public String getSavedPassword() {
            return SessionManager.get().getLastWifiPassword();
        }

        @Override
        public boolean isDeviceOwner() {
            return appContext != null && KioskManager.isDeviceOwner(appContext);
        }

        @Override
        public boolean hasWifiService() {
            return wifiManager != null;
        }

        @Override
        public boolean isConnectedToTargetSsid(String ssid) {
            return wifiManager != null && WifiAutoReconnectManager.isConnectedToTargetSsid(wifiManager, ssid);
        }

        @Override
        public void setWifiEnabled(boolean enabled) {
            if (wifiManager != null) {
                wifiManager.setWifiEnabled(enabled);
            }
        }

        @Override
        public int addOrFindNetworkId(String ssid, String password) {
            if (wifiManager == null) {
                return -1;
            }
            return WifiAutoReconnectManager.addOrFindNetworkId(wifiManager, ssid, password);
        }

        @Override
        public void disconnect() {
            if (wifiManager != null) {
                wifiManager.disconnect();
            }
        }

        @Override
        public boolean enableNetwork(int networkId, boolean disableOthers) {
            return wifiManager != null && wifiManager.enableNetwork(networkId, disableOthers);
        }

        @Override
        public void reconnect() {
            if (wifiManager != null) {
                wifiManager.reconnect();
            }
        }
    }
}
