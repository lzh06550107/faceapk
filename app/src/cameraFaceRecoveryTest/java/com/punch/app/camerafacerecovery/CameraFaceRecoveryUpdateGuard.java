package com.punch.app.camerafacerecovery;

import com.punch.app.utils.UpdateManager;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only guard preventing production background OTA from mutating the same-package test APK. */
public final class CameraFaceRecoveryUpdateGuard {
    private CameraFaceRecoveryUpdateGuard() { }

    public static void block() {
        try {
            Field field = UpdateManager.class.getDeclaredField("AUTO_UPDATE_RUNNING");
            field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof AtomicBoolean)) {
                throw new IllegalStateException("auto_update_guard_type_mismatch");
            }
            ((AtomicBoolean) value).set(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("auto_update_guard_failed", e);
        }
    }
}
