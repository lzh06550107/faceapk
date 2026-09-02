package com.punch.app.db;

import android.content.Context;

/** Test-only bridge that keeps all V2.5 punch/queue mutations out of the production database. */
public final class NetworkFaultDatabaseController {
    public static final String TEST_DB_NAME = "punch_network_fault_v25.db";
    private static boolean prepared;

    private NetworkFaultDatabaseController() { }

    public static synchronized DatabaseHelper prepareFresh(Context context) {
        Context appContext = context.getApplicationContext();
        DatabaseHelper.resetForTest();
        appContext.deleteDatabase(TEST_DB_NAME);
        DatabaseHelper.setDatabaseNameForTest(TEST_DB_NAME);
        DatabaseHelper db = DatabaseHelper.get(appContext);
        db.getWritableDatabase();
        prepared = true;
        return db;
    }

    public static synchronized DatabaseHelper get(Context context) {
        if (!prepared) {
            throw new IllegalStateException("network_fault_database_not_prepared");
        }
        return DatabaseHelper.get(context.getApplicationContext());
    }

    public static synchronized boolean isPrepared() {
        return prepared;
    }

    public static synchronized void cleanup(Context context) {
        Context appContext = context.getApplicationContext();
        DatabaseHelper.resetForTest();
        appContext.deleteDatabase(TEST_DB_NAME);
        prepared = false;
    }
}
