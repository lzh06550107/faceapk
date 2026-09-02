package com.punch.app;

public final class DeviceOwnerTestRuntimeFlags {
    public static final String MAINTENANCE_PROP = "debug.punch.device_test_maintenance";

    private DeviceOwnerTestRuntimeFlags() {
    }

    public static boolean isMaintenanceEnabled() {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String value = (String) clazz
                    .getMethod("get", String.class, String.class)
                    .invoke(null, MAINTENANCE_PROP, "0");
            return "1".equals(value);
        } catch (Exception ignored) {
            return false;
        }
    }
}
