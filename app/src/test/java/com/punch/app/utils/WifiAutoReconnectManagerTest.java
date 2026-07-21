package com.punch.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WifiAutoReconnectManagerTest {

    @Before
    public void setUp() {
        WifiAutoReconnectManager.resetForTest();
    }

    @After
    public void tearDown() {
        WifiAutoReconnectManager.resetForTest();
    }

    @Test
    public void ensureSavedWifiConnection_shouldSkipWhenSavedSsidIsBlank() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "";

        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);

        assertFalse(deps.wifiEnabledCalled);
        assertEquals(0, deps.disconnectCalls);
    }

    @Test
    public void ensureSavedWifiConnection_shouldSkipWhenNotDeviceOwner() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Office-WiFi";
        deps.deviceOwner = false;

        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);

        assertFalse(deps.wifiEnabledCalled);
        assertEquals(0, deps.addOrFindNetworkCalls);
    }

    @Test
    public void ensureSavedWifiConnection_shouldSkipWhenAlreadyConnected() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Office-WiFi";
        deps.connectedToTarget = true;

        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);

        assertFalse(deps.wifiEnabledCalled);
        assertEquals(0, deps.addOrFindNetworkCalls);
    }

    @Test
    public void ensureSavedWifiConnection_shouldReconnectWhenSavedNetworkExists() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Office-WiFi";
        deps.savedPassword = "secret123";
        deps.networkIdToReturn = 42;

        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);

        assertTrue(deps.wifiEnabledCalled);
        assertEquals("Office-WiFi", deps.lastSsid);
        assertEquals("secret123", deps.lastPassword);
        assertEquals(1, deps.disconnectCalls);
        assertEquals(42, deps.lastEnabledNetworkId);
        assertEquals(1, deps.reconnectCalls);
    }

    @Test
    public void ensureSavedWifiConnection_shouldSkipReconnectWhenNetworkIdMissing() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Office-WiFi";
        deps.networkIdToReturn = -1;

        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);

        assertTrue(deps.wifiEnabledCalled);
        assertEquals(0, deps.disconnectCalls);
        assertEquals(0, deps.reconnectCalls);
    }

    @Test
    public void ensureSavedWifiConnection_shouldThrottleRepeatedAttempts() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Office-WiFi";
        deps.networkIdToReturn = 1;
        deps.now = 100_000L;

        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);
        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);

        assertEquals(1, deps.addOrFindNetworkCalls);
        assertEquals(1, deps.reconnectCalls);
    }

    private static final class FakeDeps implements WifiAutoReconnectManager.Deps {
        long now = 100_000L;
        String savedSsid = "Office-WiFi";
        String savedPassword = "";
        boolean deviceOwner = true;
        boolean hasWifiService = true;
        boolean connectedToTarget = false;
        int networkIdToReturn = 7;
        boolean enableNetworkResult = true;

        boolean wifiEnabledCalled;
        int addOrFindNetworkCalls;
        int disconnectCalls;
        int reconnectCalls;
        int lastEnabledNetworkId = -1;
        String lastSsid = "";
        String lastPassword = "";

        @Override
        public long now() {
            return now;
        }

        @Override
        public String getSavedSsid() {
            return savedSsid;
        }

        @Override
        public String getSavedPassword() {
            return savedPassword;
        }

        @Override
        public boolean isDeviceOwner() {
            return deviceOwner;
        }

        @Override
        public boolean hasWifiService() {
            return hasWifiService;
        }

        @Override
        public boolean isConnectedToTargetSsid(String ssid) {
            return connectedToTarget;
        }

        @Override
        public void setWifiEnabled(boolean enabled) {
            wifiEnabledCalled = enabled;
        }

        @Override
        public int addOrFindNetworkId(String ssid, String password) {
            addOrFindNetworkCalls++;
            lastSsid = ssid;
            lastPassword = password;
            return networkIdToReturn;
        }

        @Override
        public void disconnect() {
            disconnectCalls++;
        }

        @Override
        public boolean enableNetwork(int networkId, boolean disableOthers) {
            lastEnabledNetworkId = networkId;
            return enableNetworkResult;
        }

        @Override
        public void reconnect() {
            reconnectCalls++;
        }
    }
}
