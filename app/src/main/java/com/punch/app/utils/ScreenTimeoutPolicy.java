package com.punch.app.utils;

public final class ScreenTimeoutPolicy {
    public static final long KEEP_SCREEN_ON = 0L;
    public static final long DEFAULT_TIMEOUT_MS = KEEP_SCREEN_ON;

    private static final long[] SUPPORTED_TIMEOUTS_MS = {
            KEEP_SCREEN_ON,
            30_000L,
            60_000L,
            120_000L,
            300_000L,
            600_000L
    };

    private ScreenTimeoutPolicy() {
    }

    public static ManagementAvailability getManagementAvailability(boolean deviceOwner,
                                                                    int sdkInt) {
        if (!deviceOwner) {
            return ManagementAvailability.NOT_DEVICE_OWNER;
        }
        if (sdkInt < 28) {
            return ManagementAvailability.UNSUPPORTED_ANDROID_VERSION;
        }
        return ManagementAvailability.AVAILABLE;
    }

    public static boolean isSupportedTimeoutMs(long timeoutMs) {
        for (long supportedTimeoutMs : SUPPORTED_TIMEOUTS_MS) {
            if (supportedTimeoutMs == timeoutMs) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldKeepScreenOn(long configuredTimeoutMs,
                                             boolean transientActivity,
                                             boolean screenActive) {
        return screenActive
                && (configuredTimeoutMs == KEEP_SCREEN_ON || transientActivity);
    }

    public enum ManagementAvailability {
        AVAILABLE,
        NOT_DEVICE_OWNER,
        UNSUPPORTED_ANDROID_VERSION
    }
}
