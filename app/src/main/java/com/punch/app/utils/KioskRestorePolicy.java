package com.punch.app.utils;

public final class KioskRestorePolicy {
    private KioskRestorePolicy() {
    }

    public static boolean shouldRestore(
            boolean kioskEnabled,
            boolean deviceOwner,
            boolean screenInteractive,
            boolean keyguardLocked,
            boolean changingConfigurations,
            int resumedActivityCount
    ) {
        return kioskEnabled
                && deviceOwner
                && screenInteractive
                && !keyguardLocked
                && !changingConfigurations
                && resumedActivityCount <= 0;
    }
}
