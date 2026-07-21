package com.punch.app;

public final class UiSmokeRuntimeFlags {
    private static final String UI_SMOKE_PROP = "debug.punch.ui_smoke";

    private UiSmokeRuntimeFlags() {
    }

    public static boolean isEnabled() {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String value = (String) clazz
                    .getMethod("get", String.class, String.class)
                    .invoke(null, UI_SMOKE_PROP, "0");
            return "1".equals(value);
        } catch (Exception ignored) {
            return false;
        }
    }
}
