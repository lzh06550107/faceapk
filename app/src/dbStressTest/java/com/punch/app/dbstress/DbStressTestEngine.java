package com.punch.app.dbstress;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.punch.app.db.DatabaseHelper;
import com.punch.app.db.DbStressDatabaseController;
import com.punch.app.model.PunchRecord;
import com.punch.app.network.DbStressNetworkController;
import com.punch.app.network.DbStressServer;
import com.punch.app.service.HeartbeatManager;
import com.punch.app.service.SyncService;
import com.punch.app.service.SyncTrigger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Test-only V2.6 SQLite/offline-queue capacity and recovery driver. */
public final class DbStressTestEngine {
    public static final String PREFIX = "DB_V26_";
    private static final String LINE_CODE = "V26_TEST";
    private static final String SHIFT_NAME = "V2.6 DB Stress";
    private static final long POLL_MS = 100L;
    private static final long BATCH_QUIET_MS = 400L;
    private static final long BATCH_TIMEOUT_MS = 30_000L;

    private DbStressTestEngine() { }

    public static boolean isSupportedCount(int count) {
        return count == 1000 || count == 5000 || count == 10000;
    }

    public static JSONObject prepare(Context context, int count) throws Exception {
        requireCount(count);
        requireSessionReady();
        stopHeartbeat(context);
        DbStressNetworkController.reset();
        DatabaseHelper db = DbStressDatabaseController.prepareFresh(context);

        long seedStarted = System.nanoTime();
        List<Long> insertLatencies = seedPunches(db, count);
        long seedMs = nanosToMillis(System.nanoTime() - seedStarted);
        DbState state = readState(db);
        String integrity = integrityCheck(db);

        JSONObject result = baseResult("prepared", count);
        result.put("success", state.total == count
                && state.unsynced == count
                && state.synced == 0
                && state.queueCount == count
                && state.duplicateClientIds == 0
                && state.retrySum == 0
                && "ok".equalsIgnoreCase(integrity));
        result.put("db", state.toJson());
        result.put("integrity", integrity);
        result.put("db_bytes_after_seed", databaseBytes(context));
        result.put("seed_total_ms", seedMs);
        result.put("seed_rows_per_sec", rowsPerSecond(count, seedMs));
        result.put("insert_latency_ms", stats(insertLatencies));
        result.put("query_latency_ms", measureQueries(db, count));
        return result;
    }

    public static JSONObject verifyRestart(Context context, int count) throws Exception {
        requireCount(count);
        requireSessionReady();
        stopHeartbeat(context);
        DatabaseHelper db = DbStressDatabaseController.get(context);
        DbState state = readState(db);
        String integrity = integrityCheck(db);

        JSONObject result = baseResult("restart_verified", count);
        result.put("success", state.total == count
                && state.unsynced == count
                && state.synced == 0
                && state.queueCount == count
                && state.duplicateClientIds == 0
                && state.retrySum == 0
                && "ok".equalsIgnoreCase(integrity));
        result.put("db", state.toJson());
        result.put("integrity", integrity);
        result.put("db_bytes_after_restart", databaseBytes(context));
        result.put("query_latency_ms", measureQueries(db, count));
        return result;
    }

    public static JSONObject drainAndCleanup(Context context, int count) throws Exception {
        requireCount(count);
        requireSessionReady();
        DatabaseHelper db = DbStressDatabaseController.get(context);
        DbState before = readState(db);
        if (before.unsynced != count || before.queueCount != count) {
            throw new IllegalStateException("restart_state_not_ready_for_drain");
        }

        DbStressServer server = DbStressNetworkController.startLoopback();
        long started = System.nanoTime();
        int triggerCount = 0;
        int maxRetryObserved = before.maxRetry;
        DbState current = before;
        while (current.queueCount > 0) {
            int queueBefore = current.queueCount;
            SyncService.triggerSync(context, SyncTrigger.MANUAL);
            triggerCount++;
            current = waitForBatchProgress(db, queueBefore);
            maxRetryObserved = Math.max(maxRetryObserved, current.maxRetry);
            stopHeartbeat(context);
        }
        long drainMs = nanosToMillis(System.nanoTime() - started);
        stopHeartbeat(context);

        DbState drained = readState(db);
        String integrityAfterDrain = integrityCheck(db);
        int requestCount = server.getPunchRequestCount();
        long dbBytesBeforeVacuum = databaseBytes(context);

        SQLiteDatabase sqlite = db.getWritableDatabase();
        sqlite.delete("sync_queue", "record_id LIKE ?", new String[]{PREFIX + "%"});
        sqlite.delete("punch_records", "client_record_id LIKE ?", new String[]{PREFIX + "%"});
        DbState cleaned = readState(db);
        sqlite.execSQL("VACUUM");
        long dbBytesAfterVacuum = databaseBytes(context);
        String integrityAfterVacuum = integrityCheck(db);

        JSONObject result = baseResult("completed", count);
        result.put("drain_initial_db", before.toJson());
        result.put("drain_final_db", drained.toJson());
        result.put("cleanup_db", cleaned.toJson());
        result.put("integrity_after_drain", integrityAfterDrain);
        result.put("integrity_after_vacuum", integrityAfterVacuum);
        result.put("drain_total_ms", drainMs);
        result.put("drain_rows_per_sec", rowsPerSecond(count, drainMs));
        result.put("service_trigger_count", triggerCount);
        result.put("production_batch_size", Constants.PUNCH_BATCH_SIZE);
        result.put("punch_http_requests", requestCount);
        result.put("unexpected_retry", maxRetryObserved);
        result.put("db_bytes_before_vacuum", dbBytesBeforeVacuum);
        result.put("db_bytes_after_vacuum", dbBytesAfterVacuum);
        result.put("base_url", DbStressNetworkController.getBaseUrl());

        boolean success = drained.total == count
                && drained.synced == count
                && drained.unsynced == 0
                && drained.queueCount == 0
                && maxRetryObserved == 0
                && requestCount == count
                && "ok".equalsIgnoreCase(integrityAfterDrain)
                && cleaned.total == 0
                && cleaned.queueCount == 0
                && "ok".equalsIgnoreCase(integrityAfterVacuum);
        result.put("success", success);
        return result;
    }

    public static JSONObject status(Context context) {
        JSONObject result = new JSONObject();
        try {
            result.put("database_active", DbStressDatabaseController.isActive());
            result.put("token_valid", SessionManager.get().isTokenValid());
            result.put("device_registered", SessionManager.get().isDeviceRegistered());
            if (DbStressDatabaseController.isActive()) {
                result.put("db", readState(DbStressDatabaseController.get(context)).toJson());
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static void cleanup(Context context) {
        stopHeartbeat(context);
        DbStressNetworkController.reset();
        try { DbStressDatabaseController.cleanup(context); } catch (Throwable ignored) { }
    }

    private static List<Long> seedPunches(DatabaseHelper db, int count) throws Exception {
        List<Long> latencies = new ArrayList<>(count);
        long now = System.currentTimeMillis() / 1000L;
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String runId = String.valueOf(System.currentTimeMillis());
        for (int i = 0; i < count; i++) {
            long rowStarted = System.nanoTime();
            String suffix = runId + "_" + String.format(Locale.US, "%05d", i);
            PunchRecord record = new PunchRecord();
            record.id = PREFIX + "ID_" + suffix;
            record.clientRecordId = PREFIX + suffix;
            record.empId = PREFIX + "EMP_" + (i % 100);
            record.empName = "V2.6 Stress Employee " + (i % 100);
            record.dept = "DB Stress";
            record.punchTime = now + i;
            record.punchDate = date;
            record.punchType = Constants.PUNCH_TYPE_SIGN_IN;
            record.shiftName = SHIFT_NAME;
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
            latencies.add(nanosToMillisCeil(System.nanoTime() - rowStarted));
        }
        return latencies;
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
            if (state.queueCount < queueBefore) {
                progressed = true;
            }
            if (state.queueCount == 0) return state;
            if (progressed && System.currentTimeMillis() - lastChangeAt >= BATCH_QUIET_MS) {
                return state;
            }
            Thread.sleep(POLL_MS);
        }
        throw new IllegalStateException("sync_batch_no_progress_queue_" + queueBefore);
    }

    private static JSONObject measureQueries(DatabaseHelper db, int count) throws Exception {
        SQLiteDatabase sqlite = db.getReadableDatabase();
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int offset = Math.max(0, count / 2 - 25);
        List<Long> pending = new ArrayList<>();
        List<Long> page = new ArrayList<>();
        List<Long> dateQuery = new ArrayList<>();
        List<Long> lineQuery = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            pending.add(timeQuery(sqlite,
                    "SELECT COUNT(*) FROM punch_records WHERE is_synced=0 AND client_record_id LIKE ?",
                    new String[]{PREFIX + "%"}));
            page.add(timeQuery(sqlite,
                    "SELECT client_record_id FROM punch_records WHERE client_record_id LIKE ? ORDER BY punch_time DESC LIMIT 50 OFFSET ?",
                    new String[]{PREFIX + "%", String.valueOf(offset)}));
            dateQuery.add(timeQuery(sqlite,
                    "SELECT COUNT(*) FROM punch_records WHERE punch_date=? AND client_record_id LIKE ?",
                    new String[]{date, PREFIX + "%"}));
            lineQuery.add(timeQuery(sqlite,
                    "SELECT COUNT(*) FROM punch_records WHERE punch_date=? AND line_code=? AND team_binding_id=? AND clock_index=? AND client_record_id LIKE ?",
                    new String[]{date, LINE_CODE, "1", "1", PREFIX + "%"}));
        }
        JSONObject result = new JSONObject();
        result.put("pending_count", stats(pending));
        result.put("page_50_midpoint", stats(page));
        result.put("date_count", stats(dateQuery));
        result.put("line_clock_count", stats(lineQuery));
        return result;
    }

    private static long timeQuery(SQLiteDatabase db, String sql, String[] args) {
        long started = System.nanoTime();
        Cursor cursor = db.rawQuery(sql, args);
        try {
            while (cursor.moveToNext()) { /* materialize rows */ }
        } finally {
            cursor.close();
        }
        return nanosToMillisCeil(System.nanoTime() - started);
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

    private static JSONObject stats(List<Long> values) throws Exception {
        JSONObject result = new JSONObject();
        if (values == null || values.isEmpty()) {
            result.put("samples", 0);
            result.put("p50", 0L);
            result.put("p95", 0L);
            result.put("p99", 0L);
            result.put("max", 0L);
            return result;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        result.put("samples", sorted.size());
        result.put("p50", percentile(sorted, 0.50));
        result.put("p95", percentile(sorted, 0.95));
        result.put("p99", percentile(sorted, 0.99));
        result.put("max", sorted.get(sorted.size() - 1).longValue());
        return result;
    }

    private static long percentile(List<Long> sorted, double fraction) {
        int index = (int) Math.ceil(sorted.size() * fraction) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index).longValue();
    }

    private static long databaseBytes(Context context) {
        return context.getDatabasePath(DbStressDatabaseController.TEST_DB_NAME).length();
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static long nanosToMillisCeil(long nanos) {
        return Math.max(1L, (nanos + 999_999L) / 1_000_000L);
    }

    private static double rowsPerSecond(int rows, long millis) {
        if (millis <= 0) return rows;
        return (rows * 1000.0) / millis;
    }

    private static JSONObject baseResult(String phase, int count) throws Exception {
        JSONObject result = new JSONObject();
        result.put("version", "V2.6");
        result.put("phase", phase);
        result.put("count", count);
        result.put("database", DbStressDatabaseController.TEST_DB_NAME);
        result.put("production_database_touched", false);
        return result;
    }

    private static void requireCount(int count) {
        if (!isSupportedCount(count)) {
            throw new IllegalArgumentException("unsupported_count_use_1000_5000_10000");
        }
    }

    private static void requireSessionReady() {
        if (!SessionManager.get().isTokenValid()) {
            throw new IllegalStateException("token_not_valid");
        }
        if (!SessionManager.get().isDeviceRegistered()) {
            throw new IllegalStateException("device_not_registered");
        }
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
