package com.punch.app.db;

import android.content.Context;

/** Test-only database selector for V2.6. */
public final class DbStressDatabaseController {
    public static final String TEST_DB_NAME = "punch_db_stress_v26.db";
    private static boolean active;

    private DbStressDatabaseController() { }

    public static synchronized void activateExisting(Context context) {
        DatabaseHelper.resetForTest();
        DatabaseHelper.setDatabaseNameForTest(TEST_DB_NAME);
        active = true;
    }

    public static synchronized DatabaseHelper prepareFresh(Context context) {
        Context appContext = context.getApplicationContext();
        DatabaseHelper.resetForTest();
        appContext.deleteDatabase(TEST_DB_NAME);
        DatabaseHelper.setDatabaseNameForTest(TEST_DB_NAME);
        DatabaseHelper db = DatabaseHelper.get(appContext);
        db.getWritableDatabase();
        active = true;
        return db;
    }

    public static synchronized DatabaseHelper get(Context context) {
        if (!active) {
            activateExisting(context);
        }
        return DatabaseHelper.get(context.getApplicationContext());
    }

    public static synchronized boolean isActive() {
        return active;
    }

    public static synchronized void cleanup(Context context) {
        Context appContext = context.getApplicationContext();
        DatabaseHelper.resetForTest();
        appContext.deleteDatabase(TEST_DB_NAME);
        active = false;
    }
}
