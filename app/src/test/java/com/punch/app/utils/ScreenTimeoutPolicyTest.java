package com.punch.app.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScreenTimeoutPolicyTest {
    @Test
    public void managementRequiresDeviceOwnerAndAndroidNine() {
        assertEquals(
                ScreenTimeoutPolicy.ManagementAvailability.NOT_DEVICE_OWNER,
                ScreenTimeoutPolicy.getManagementAvailability(false, 34)
        );
        assertEquals(
                ScreenTimeoutPolicy.ManagementAvailability.UNSUPPORTED_ANDROID_VERSION,
                ScreenTimeoutPolicy.getManagementAvailability(true, 27)
        );
        assertEquals(
                ScreenTimeoutPolicy.ManagementAvailability.AVAILABLE,
                ScreenTimeoutPolicy.getManagementAvailability(true, 28)
        );
    }

    @Test
    public void onlySupportedTimeoutOptionsCanBePersisted() {
        assertTrue(ScreenTimeoutPolicy.isSupportedTimeoutMs(0L));
        assertTrue(ScreenTimeoutPolicy.isSupportedTimeoutMs(30_000L));
        assertTrue(ScreenTimeoutPolicy.isSupportedTimeoutMs(600_000L));
        assertFalse(ScreenTimeoutPolicy.isSupportedTimeoutMs(-1L));
        assertFalse(ScreenTimeoutPolicy.isSupportedTimeoutMs(45_000L));
    }

    @Test
    public void idlePunchScreenOnlyStaysOnForKeepOnConfiguration() {
        assertTrue(ScreenTimeoutPolicy.shouldKeepScreenOn(0L, false, true));
        assertFalse(ScreenTimeoutPolicy.shouldKeepScreenOn(60_000L, false, true));
        assertTrue(ScreenTimeoutPolicy.shouldKeepScreenOn(60_000L, true, true));
        assertFalse(ScreenTimeoutPolicy.shouldKeepScreenOn(0L, true, false));
    }
}
