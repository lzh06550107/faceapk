package com.punch.app.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

/** Test-only database selector for V2.7.3 Camera/Face process recovery. */
public final class CameraFaceRecoveryDatabaseController {
    public static final String TEST_DB_NAME = "punch_camera_face_recovery_v273.db";
    private static boolean active;

    private CameraFaceRecoveryDatabaseController() { }

    public static synchronized void activateExisting(Context context) {
        DatabaseHelper.resetForTest();
        DatabaseHelper.setDatabaseNameForTest(TEST_DB_NAME);
        DatabaseHelper.get(context.getApplicationContext()).getWritableDatabase();
        active = true;
    }

    public static synchronized DatabaseHelper prepareFresh(Context context) {
        DatabaseHelper db = get(context);
        SQLiteDatabase sqlite = db.getWritableDatabase();
        sqlite.delete("sync_queue", null, null);
        sqlite.delete("punch_records", null, null);
        sqlite.delete("face_sdk_ids", null, null);
        sqlite.delete("employees", null, null);
        active = true;
        return db;
    }

    public static synchronized DatabaseHelper get(Context context) {
        if (!active) activateExisting(context);
        return DatabaseHelper.get(context.getApplicationContext());
    }

    public static synchronized boolean isActive() { return active; }

    public static synchronized void cleanup(Context context) {
        Context app = context.getApplicationContext();
        try {
            DatabaseHelper db = get(app);
            SQLiteDatabase sqlite = db.getWritableDatabase();
            sqlite.delete("sync_queue", null, null);
            sqlite.delete("punch_records", null, null);
            sqlite.delete("face_sdk_ids", null, null);
            sqlite.delete("employees", null, null);
        } catch (Throwable ignored) { }
        DatabaseHelper.resetForTest();
        app.deleteDatabase(TEST_DB_NAME);
        DatabaseHelper.setDatabaseNameForTest(TEST_DB_NAME);
        active = true;
    }
}
