package com.punch.app.processrecovery;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.punch.app.db.DatabaseHelper;
import com.punch.app.db.ProcessRecoveryDatabaseController;
import com.punch.app.model.PunchRecord;
import com.punch.app.network.ProcessRecoveryNetworkController;
import com.punch.app.network.ProcessRecoveryServer;
import com.punch.app.service.HeartbeatManager;
import com.punch.app.service.SyncService;
import com.punch.app.service.SyncTrigger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** V2.7.1 isolated SQLite + production sync driver. */
public final class ProcessRecoveryTestEngine {
    public static final String PREFIX = "PROC_V271_";
    public static final int SYNC_RECORD_COUNT = 100;
    private static final String LINE_CODE = "V271_TEST";
    private static final long SLOW_PUNCH_DELAY_MS = 150L;
    private static final long PARTIAL_SYNC_TIMEOUT_MS = 30_000L;
    private static final long POLL_MS = 50L;
    private static final long BATCH_QUIET_MS = 400L;
    private static final long BATCH_TIMEOUT_MS = 30_000L;

    private ProcessRecoveryTestEngine() { }

    public static JSONObject prepareSyncKill(Context context) throws Exception {
        requireSessionReady();
        stopHeartbeat(context);
        ProcessRecoveryNetworkController.reset();
        DatabaseHelper db = ProcessRecoveryDatabaseController.prepareFresh(context);
        seedPunches(db);
        DbState seeded = readState(db);
        String seededIntegrity = integrityCheck(db);
        if (seeded.total != SYNC_RECORD_COUNT
                || seeded.unsynced != SYNC_RECORD_COUNT
                || seeded.queueCount != SYNC_RECORD_COUNT
                || seeded.duplicateClientIds != 0
                || !"ok".equalsIgnoreCase(seededIntegrity)) {
            throw new IllegalStateException("seed_gate_failed");
        }

        ProcessRecoveryServer server = ProcessRecoveryNetworkController.startLoopback(SLOW_PUNCH_DELAY_MS);
        SyncService.triggerSync(context, SyncTrigger.MANUAL);

        long deadline = System.currentTimeMillis() + PARTIAL_SYNC_TIMEOUT_MS;
        DbState state = seeded;
        while (System.currentTimeMillis() < deadline) {
            state = readState(db);
            int synced = state.synced;
            if (synced > 0 && synced < SYNC_RECORD_COUNT) {
                JSONObject result = baseResult("partial_sync_ready");
                result.put("success", true);
                result.put("db", state.toJson());
                result.put("integrity", integrityCheck(db));
                result.put("punch_http_requests", server.getPunchRequestCount());
                result.put("base_url", ProcessRecoveryNetworkController.getBaseUrl());
                result.put("kill_ready", true);
                return result;
            }
            Thread.sleep(POLL_MS);
        }
        throw new IllegalStateException("partial_sync_window_not_observed");
    }

    public static JSONObject verifySyncRestart(Context context) throws Exception {
        requireSessionReady();
        stopHeartbeat(context);
        DatabaseHelper db = ProcessRecoveryDatabaseController.get(context);
        DbState state = readState(db);
        String integrity = integrityCheck(db);
        boolean retrySane = state.maxRetry >= 0 && state.maxRetry <= Constants.SYNC_MAX_RETRY;
        boolean success = state.total == SYNC_RECORD_COUNT
                && state.synced > 0
                && state.synced < SYNC_RECORD_COUNT
                && state.synced + state.unsynced == SYNC_RECORD_COUNT
                && state.queueCount == state.unsynced
                && state.duplicateClientIds == 0
                && retrySane
                && "ok".equalsIgnoreCase(integrity);

        JSONObject result = baseResult("sync_restart_verified");
        result.put("success", success);
        result.put("db", state.toJson());
        result.put("integrity", integrity);
        result.put("retry_sane", retrySane);
        return result;
    }

    public static JSONObject resumeSyncAndCleanup(Context context) throws Exception {
        requireSessionReady();
        DatabaseHelper db = ProcessRecoveryDatabaseController.get(context);
        DbState before = readState(db);
        if (before.total != SYNC_RECORD_COUNT
                || before.unsynced <= 0
                || before.queueCount != before.unsynced
                || before.synced <= 0) {
            throw new IllegalStateException("restart_state_not_ready_for_resume");
        }

        int initialUnsynced = before.unsynced;
        ProcessRecoveryServer server = ProcessRecoveryNetworkController.startLoopback(0L);
        DbState current = before;
        int triggerCount = 0;
        while (current.queueCount > 0) {
            int queueBefore = current.queueCount;
            SyncService.triggerSync(context, SyncTrigger.MANUAL);
            triggerCount++;
            current = waitForBatchProgress(db, queueBefore);
            stopHeartbeat(context);
        }
        stopHeartbeat(context);

        DbState drained = readState(db);
        String integrityAfterDrain = integrityCheck(db);
        int requestCount = server.getPunchRequestCount();

        SQLiteDatabase sqlite = db.getWritableDatabase();
        sqlite.delete("sync_queue", "record_id LIKE ?", new String[]{PREFIX + "%"});
        sqlite.delete("punch_records", "client_record_id LIKE ?", new String[]{PREFIX + "%"});
        DbState cleaned = readState(db);
        sqlite.execSQL("VACUUM");
        String integrityAfterVacuum = integrityCheck(db);

        boolean success = drained.total == SYNC_RECORD_COUNT
                && drained.synced == SYNC_RECORD_COUNT
                && drained.unsynced == 0
                && drained.queueCount == 0
                && drained.duplicateClientIds == 0
                && requestCount == initialUnsynced
                && "ok".equalsIgnoreCase(integrityAfterDrain)
                && cleaned.total == 0
                && cleaned.queueCount == 0
                && "ok".equalsIgnoreCase(integrityAfterVacuum);

        JSONObject result = baseResult("sync_recovery_completed");
        result.put("success", success);
        result.put("before_resume", before.toJson());
        result.put("after_drain", drained.toJson());
        result.put("after_cleanup", cleaned.toJson());
        result.put("integrity_after_drain", integrityAfterDrain);
        result.put("integrity_after_vacuum", integrityAfterVacuum);
        result.put("initial_unsynced", initialUnsynced);
        result.put("punch_http_requests", requestCount);
        result.put("service_trigger_count", triggerCount);
        result.put("base_url", ProcessRecoveryNetworkController.getBaseUrl());
        return result;
    }

    public static JSONObject status(Context context) {
        JSONObject result = new JSONObject();
        try {
            result.put("database_active", ProcessRecoveryDatabaseController.isActive());
            result.put("token_valid", SessionManager.get().isTokenValid());
            result.put("device_registered", SessionManager.get().isDeviceRegistered());
            if (ProcessRecoveryDatabaseController.isActive()) {
                result.put("db", readState(ProcessRecoveryDatabaseController.get(context)).toJson());
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static void cleanup(Context context) {
        stopHeartbeat(context);
        ProcessRecoveryNetworkController.reset();
        try { ProcessRecoveryDatabaseController.cleanup(context); } catch (Throwable ignored) { }
    }

    private static void seedPunches(DatabaseHelper db) {
        long now = System.currentTimeMillis() / 1000L;
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String runId = String.valueOf(System.currentTimeMillis());
        for (int i = 0; i < SYNC_RECORD_COUNT; i++) {
            String suffix = runId + "_" + String.format(Locale.US, "%03d", i);
            PunchRecord record = new PunchRecord();
            record.id = PREFIX + "ID_" + suffix;
            record.clientRecordId = PREFIX + suffix;
            record.empId = PREFIX + "EMP_" + (i % 10);
            record.empName = "V2.7.1 Recovery Employee " + (i % 10);
            record.dept = "Process Recovery";
            record.punchTime = now + i;
            record.punchDate = date;
            record.punchType = Constants.PUNCH_TYPE_SIGN_IN;
            record.shiftName = "V2.7.1 Process Recovery";
            record.lineCode = LINE_CODE;
            record.teamBindingId = 1;
            record.clockIndex = 1;
            record.matchScore = 100.0;
            record.snapImagePath = "";
            record.snapImageMimeType = "";
            record.snapImageWidth = 0;
            record.snapImageHeight = 0;
            record.snapImageSize = 0;
            record.snapCapturedAt = 0;
            record.isSynced = 0;
            if (!db.insertPunchRecord(record)) {
                throw new IllegalStateException("seed_punch_insert_failed_at_" + i);
            }
            db.enqueueSyncItem(record.clientRecordId, Constants.ACTION_PUNCH_PUSH);
        }
    }

    private static DbState waitForBatchProgress(DatabaseHelper db, int queueBefore) throws Exception {
        long deadline = System.currentTimeMillis() + BATCH_TIMEOUT_MS;
        long lastChangeAt = System.currentTimeMillis();
        int lastQueue = queueBefore;
        boolean progressed = false;
        DbState state = readState(db);
        while (System.currentTimeMillis() < deadline) {
            state = readState(db);
            if (state.queueCount != lastQueue) {
                lastQueue = state.queueCount;
                lastChangeAt = System.currentTimeMillis();
            }
            if (state.queueCount < queueBefore) progressed = true;
            if (state.queueCount == 0) return state;
            if (progressed && System.currentTimeMillis() - lastChangeAt >= BATCH_QUIET_MS) return state;
            Thread.sleep(POLL_MS);
        }
        throw new IllegalStateException("sync_batch_no_progress_queue_" + queueBefore);
    }

    private static DbState readState(DatabaseHelper db) {
        SQLiteDatabase sqlite = db.getReadableDatabase();
        DbState state = new DbState();
        state.total = scalarInt(sqlite,
                "SELECT COUNT(*) FROM punch_records WHERE client_record_id LIKE ?", PREFIX + "%");
        state.unsynced = scalarInt(sqlite,
                "SELECT COUNT(*) FROM punch_records WHERE is_synced=0 AND client_record_id LIKE ?", PREFIX + "%");
        state.synced = scalarInt(sqlite,
                "SELECT COUNT(*) FROM punch_records WHERE is_synced=1 AND client_record_id LIKE ?", PREFIX + "%");
        state.queueCount = scalarInt(sqlite,
                "SELECT COUNT(*) FROM sync_queue WHERE action=? AND record_id LIKE ?",
                Constants.ACTION_PUNCH_PUSH, PREFIX + "%");
        state.retrySum = scalarInt(sqlite,
                "SELECT COALESCE(SUM(retry_count),0) FROM sync_queue WHERE action=? AND record_id LIKE ?",
                Constants.ACTION_PUNCH_PUSH, PREFIX + "%");
        state.maxRetry = scalarInt(sqlite,
                "SELECT COALESCE(MAX(retry_count),0) FROM sync_queue WHERE action=? AND record_id LIKE ?",
                Constants.ACTION_PUNCH_PUSH, PREFIX + "%");
        state.duplicateClientIds = scalarInt(sqlite,
                "SELECT COUNT(*) FROM (SELECT client_record_id FROM punch_records WHERE client_record_id LIKE ? GROUP BY client_record_id HAVING COUNT(*)>1)",
                PREFIX + "%");
        return state;
    }

    private static int scalarInt(SQLiteDatabase db, String sql, String... args) {
        Cursor c = db.rawQuery(sql, args);
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    private static String integrityCheck(DatabaseHelper db) {
        Cursor c = db.getReadableDatabase().rawQuery("PRAGMA integrity_check", null);
        try { return c.moveToFirst() ? c.getString(0) : "missing"; }
        finally { c.close(); }
    }

    private static JSONObject baseResult(String phase) throws Exception {
        JSONObject result = new JSONObject();
        result.put("version", "V2.7.1");
        result.put("phase", phase);
        result.put("count", SYNC_RECORD_COUNT);
        result.put("database", ProcessRecoveryDatabaseController.TEST_DB_NAME);
        result.put("production_database_touched", false);
        return result;
    }

    private static void requireSessionReady() {
        if (!SessionManager.get().isTokenValid()) throw new IllegalStateException("token_not_valid");
        if (!SessionManager.get().isDeviceRegistered()) throw new IllegalStateException("device_not_registered");
    }

    private static void stopHeartbeat(Context context) {
        try { HeartbeatManager.get(context.getApplicationContext()).stop(); } catch (Throwable ignored) { }
    }

    private static final class DbState {
        int total;
        int unsynced;
        int synced;
        int queueCount;
        int retrySum;
        int maxRetry;
        int duplicateClientIds;

        JSONObject toJson() throws Exception {
            JSONObject result = new JSONObject();
            result.put("total", total);
            result.put("unsynced", unsynced);
            result.put("synced", synced);
            result.put("queue_count", queueCount);
            result.put("retry_sum", retrySum);
            result.put("max_retry", maxRetry);
            result.put("duplicate_client_ids", duplicateClientIds);
            return result;
        }
    }
}
