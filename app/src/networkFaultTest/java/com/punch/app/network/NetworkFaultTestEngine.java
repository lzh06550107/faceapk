package com.punch.app.network;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.punch.app.db.DatabaseHelper;
import com.punch.app.db.NetworkFaultDatabaseController;
import com.punch.app.model.PunchRecord;
import com.punch.app.service.HeartbeatManager;
import com.punch.app.service.SyncService;
import com.punch.app.service.SyncTrigger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UlidGenerator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Test-only V2.5 driver. It runs the production SyncService/SyncCoordinator against an isolated DB. */
public final class NetworkFaultTestEngine {
    public static final String PREFIX = "NET_V25_";
    private static final String EMP_ID = "STRESS_EMP_V25";
    private static final String EMP_NAME = "V2.5 Network Test Employee";
    private static final String DEPT = "Stress";
    private static final long WAIT_TIMEOUT_MS = 10_000L;
    private static final long POLL_MS = 100L;

    private NetworkFaultTestEngine() { }

    public static JSONObject run(Context context, String requestedScenario) throws Exception {
        String scenario = normalizeScenario(requestedScenario);
        if ("AllSafe".equals(scenario)) {
            JSONArray results = new JSONArray();
            for (String name : new String[]{
                    "DisconnectRecovery", "TimeoutRecovery", "Http500Retry",
                    "Http401Recovery", "RetryLimitManualRecovery"}) {
                results.put(runOneSafeScenario(context, name));
            }
            JSONObject suite = new JSONObject();
            suite.put("version", "V2.5");
            suite.put("scenario", "AllSafe");
            suite.put("success", allSuccessful(results));
            suite.put("results", results);
            return suite;
        }
        return runOneSafeScenario(context, scenario);
    }

    public static JSONObject prepareDeviceOffline(Context context) throws Exception {
        requireSessionReady();
        stopHeartbeat(context);
        DatabaseHelper db = NetworkFaultDatabaseController.prepareFresh(context);
        NetworkFaultController.useUnreachable();
        seedPunches(db, "DeviceOfflineRecovery", 1);
        DbState state = readState(db);
        JSONObject result = baseResult("DeviceOfflineRecovery", "prepared");
        result.put("success", state.unsynced == 1 && state.queueCount == 1);
        result.put("db", state.toJson());
        result.put("base_url", NetworkFaultController.getActiveBaseUrl());
        return result;
    }

    public static JSONObject executeDeviceOfflineFailure(Context context) throws Exception {
        requirePreparedDatabase();
        DatabaseHelper db = NetworkFaultDatabaseController.get(context);
        SyncService.triggerSync(context, SyncTrigger.AFTER_PUNCH);
        boolean retained = waitFor(db, state -> state.unsynced == 1
                && state.queueCount == 1 && state.retrySum >= 1, WAIT_TIMEOUT_MS);
        stopHeartbeat(context);
        DbState state = readState(db);
        JSONObject result = baseResult("DeviceOfflineRecovery", "offline_retained");
        result.put("success", retained);
        result.put("db", state.toJson());
        result.put("base_url", NetworkFaultController.getActiveBaseUrl());
        return result;
    }

    public static JSONObject recoverDeviceOffline(Context context) throws Exception {
        requirePreparedDatabase();
        DatabaseHelper db = NetworkFaultDatabaseController.get(context);
        NetworkFaultServer server = NetworkFaultController.useLocal(NetworkFaultServer.Mode.SUCCESS);
        server.resetHistory();
        SyncService.triggerSync(context, SyncTrigger.MANUAL);
        boolean recovered = waitFor(db, state -> state.synced == 1 && state.queueCount == 0, WAIT_TIMEOUT_MS);
        stopHeartbeat(context);
        DbState state = readState(db);
        JSONObject result = baseResult("DeviceOfflineRecovery", "completed");
        result.put("success", recovered);
        result.put("db", state.toJson());
        addRequestDiagnostics(result, server);
        result.put("base_url", NetworkFaultController.getActiveBaseUrl());
        return result;
    }

    public static JSONObject status(Context context) {
        JSONObject result = new JSONObject();
        try {
            result.put("database_prepared", NetworkFaultDatabaseController.isPrepared());
            result.put("base_url", NetworkFaultController.getActiveBaseUrl());
            result.put("token_valid", SessionManager.get().isTokenValid());
            result.put("device_registered", SessionManager.get().isDeviceRegistered());
            if (NetworkFaultDatabaseController.isPrepared()) {
                result.put("db", readState(NetworkFaultDatabaseController.get(context)).toJson());
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static String requestJsonLines() {
        NetworkFaultServer server = NetworkFaultController.getServer();
        if (server == null) return "";
        StringBuilder out = new StringBuilder();
        List<NetworkFaultServer.RequestRecord> requests = server.getRequestsSnapshot();
        for (NetworkFaultServer.RequestRecord request : requests) {
            if (out.length() > 0) out.append('\n');
            out.append(request.toJson().toString());
        }
        return out.toString();
    }

    public static void cleanup(Context context) {
        stopHeartbeat(context);
        // Keep ApiClient pinned to loopback/unreachable for the lifetime of the temporary APK.
        // The original APK installation kills this process and clears static test hooks.
        try {
            if (NetworkFaultDatabaseController.isPrepared()) {
                NetworkFaultDatabaseController.cleanup(context);
            }
        } catch (Throwable ignored) { }
    }

    private static JSONObject runOneSafeScenario(Context context, String scenario) throws Exception {
        requireSessionReady();
        stopHeartbeat(context);
        DatabaseHelper db = NetworkFaultDatabaseController.prepareFresh(context);
        NetworkFaultServer.Mode initialMode = modeForScenario(scenario);
        NetworkFaultServer server = NetworkFaultController.useLocal(initialMode);
        server.resetHistory();

        int recordCount = "Http500Retry".equals(scenario) ? 3 : 1;
        seedPunches(db, scenario, recordCount);
        JSONObject result = baseResult(scenario, "running");
        DbState seeded = readState(db);
        result.put("seeded_db", seeded.toJson());

        boolean faultObserved;
        boolean retryLimitRespected = true;
        if ("RetryLimitManualRecovery".equals(scenario)) {
            faultObserved = driveToRetryLimit(context, db, server);
            int requestsAtLimit = server.getPunchRequestCount();
            SyncService.triggerSync(context, SyncTrigger.AFTER_PUNCH);
            Thread.sleep(1_200L);
            retryLimitRespected = server.getPunchRequestCount() == requestsAtLimit;
        } else {
            SyncService.triggerSync(context, SyncTrigger.AFTER_PUNCH);
            int expectedRetrySum = recordCount;
            faultObserved = waitFor(db, state -> state.unsynced == recordCount
                    && state.queueCount == recordCount && state.retrySum >= expectedRetrySum,
                    WAIT_TIMEOUT_MS);
        }

        DbState faultState = readState(db);
        int punchRequestsBeforeRecovery = server.getPunchRequestCount();
        int refreshRequestsBeforeRecovery = server.getRefreshRequestCount();
        server.setMode(NetworkFaultServer.Mode.SUCCESS);
        SyncService.triggerSync(context, SyncTrigger.MANUAL);
        boolean recovered = waitFor(db, state -> state.synced == recordCount && state.queueCount == 0,
                WAIT_TIMEOUT_MS);
        stopHeartbeat(context);
        DbState finalState = readState(db);

        result.put("fault_observed", faultObserved);
        result.put("retry_limit_respected", retryLimitRespected);
        result.put("fault_db", faultState.toJson());
        result.put("final_db", finalState.toJson());
        result.put("punch_requests_before_recovery", punchRequestsBeforeRecovery);
        result.put("refresh_requests_before_recovery", refreshRequestsBeforeRecovery);
        result.put("http_401_auto_refresh_observed",
                "Http401Recovery".equals(scenario) && refreshRequestsBeforeRecovery > 0);
        addRequestDiagnostics(result, server);
        result.put("success", faultObserved && retryLimitRespected && recovered
                && finalState.synced == recordCount && finalState.queueCount == 0);
        result.put("phase", "completed");
        return result;
    }

    private static boolean driveToRetryLimit(Context context, DatabaseHelper db, NetworkFaultServer server)
            throws Exception {
        for (int expected = 1; expected <= Constants.SYNC_MAX_RETRY; expected++) {
            SyncService.triggerSync(context, SyncTrigger.AFTER_PUNCH);
            final int target = expected;
            if (!waitFor(db, state -> state.unsynced == 1 && state.queueCount == 1
                    && state.maxRetry >= target, WAIT_TIMEOUT_MS)) {
                return false;
            }
        }
        return server.getPunchRequestCount() >= Constants.SYNC_MAX_RETRY;
    }

    private static void seedPunches(DatabaseHelper db, String scenario, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            PunchRecord record = new PunchRecord();
            long now = System.currentTimeMillis() / 1000L;
            String suffix = scenario + "_" + i + "_" + UlidGenerator.generate();
            record.id = PREFIX + "ID_" + suffix;
            record.clientRecordId = PREFIX + suffix;
            record.empId = EMP_ID;
            record.empName = EMP_NAME;
            record.dept = DEPT;
            record.punchTime = now;
            record.punchDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            record.punchType = Constants.PUNCH_TYPE_SIGN_IN;
            record.shiftName = "V2.5 Network Fault";
            record.lineCode = "V25_TEST";
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
                throw new IllegalStateException("seed_punch_insert_failed");
            }
            db.enqueueSyncItem(record.clientRecordId, Constants.ACTION_PUNCH_PUSH);
        }
    }

    private static DbState readState(DatabaseHelper db) {
        SQLiteDatabase sqlite = db.getReadableDatabase();
        String like = PREFIX + "%";
        int unsynced = scalar(sqlite,
                "SELECT COUNT(*) FROM punch_records WHERE client_record_id LIKE ? AND is_synced=0",
                new String[]{like});
        int synced = scalar(sqlite,
                "SELECT COUNT(*) FROM punch_records WHERE client_record_id LIKE ? AND is_synced=1",
                new String[]{like});
        int queueCount = scalar(sqlite,
                "SELECT COUNT(*) FROM sync_queue WHERE action=? AND record_id LIKE ?",
                new String[]{Constants.ACTION_PUNCH_PUSH, like});
        int retrySum = scalar(sqlite,
                "SELECT COALESCE(SUM(retry_count),0) FROM sync_queue WHERE action=? AND record_id LIKE ?",
                new String[]{Constants.ACTION_PUNCH_PUSH, like});
        int maxRetry = scalar(sqlite,
                "SELECT COALESCE(MAX(retry_count),0) FROM sync_queue WHERE action=? AND record_id LIKE ?",
                new String[]{Constants.ACTION_PUNCH_PUSH, like});
        return new DbState(unsynced, synced, queueCount, retrySum, maxRetry);
    }

    private static int scalar(SQLiteDatabase db, String sql, String[] args) {
        Cursor cursor = db.rawQuery(sql, args);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    private static boolean waitFor(DatabaseHelper db, StatePredicate predicate, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (predicate.matches(readState(db))) return true;
            Thread.sleep(POLL_MS);
        }
        return predicate.matches(readState(db));
    }

    private static void requireSessionReady() {
        if (!SessionManager.get().isTokenValid()) {
            throw new IllegalStateException("token_missing_or_expired");
        }
        if (!SessionManager.get().isDeviceRegistered()) {
            throw new IllegalStateException("device_not_registered");
        }
    }

    private static void requirePreparedDatabase() {
        if (!NetworkFaultDatabaseController.isPrepared()) {
            throw new IllegalStateException("network_fault_database_not_prepared");
        }
    }

    private static void stopHeartbeat(Context context) {
        try { HeartbeatManager.get(context.getApplicationContext()).stop(); }
        catch (Throwable ignored) { }
    }

    private static NetworkFaultServer.Mode modeForScenario(String scenario) {
        switch (scenario) {
            case "DisconnectRecovery": return NetworkFaultServer.Mode.DROP;
            case "TimeoutRecovery": return NetworkFaultServer.Mode.TIMEOUT;
            case "Http500Retry": return NetworkFaultServer.Mode.HTTP_500;
            case "Http401Recovery": return NetworkFaultServer.Mode.HTTP_401;
            case "RetryLimitManualRecovery": return NetworkFaultServer.Mode.HTTP_500;
            default: throw new IllegalArgumentException("unsupported_scenario:" + scenario);
        }
    }

    private static String normalizeScenario(String scenario) {
        String safe = scenario == null ? "AllSafe" : scenario.trim();
        if (safe.isEmpty()) safe = "AllSafe";
        for (String supported : new String[]{"AllSafe", "DisconnectRecovery", "TimeoutRecovery",
                "Http500Retry", "Http401Recovery", "RetryLimitManualRecovery"}) {
            if (supported.equalsIgnoreCase(safe)) return supported;
        }
        throw new IllegalArgumentException("unsupported_scenario:" + safe);
    }

    private static JSONObject baseResult(String scenario, String phase) throws Exception {
        JSONObject result = new JSONObject();
        result.put("version", "V2.5");
        result.put("scenario", scenario);
        result.put("phase", phase);
        result.put("prefix", PREFIX);
        result.put("database", NetworkFaultDatabaseController.TEST_DB_NAME);
        result.put("production_database_touched", false);
        return result;
    }

    private static void addRequestDiagnostics(JSONObject result, NetworkFaultServer server) throws Exception {
        result.put("punch_request_count", server == null ? 0 : server.getPunchRequestCount());
        result.put("refresh_request_count", server == null ? 0 : server.getRefreshRequestCount());
        JSONArray requests = new JSONArray();
        if (server != null) {
            for (NetworkFaultServer.RequestRecord request : server.getRequestsSnapshot()) {
                requests.put(request.toJson());
            }
        }
        result.put("requests", requests);
    }

    private static boolean allSuccessful(JSONArray results) {
        for (int i = 0; i < results.length(); i++) {
            JSONObject row = results.optJSONObject(i);
            if (row == null || !row.optBoolean("success", false)) return false;
        }
        return true;
    }

    private interface StatePredicate { boolean matches(DbState state); }

    private static final class DbState {
        final int unsynced;
        final int synced;
        final int queueCount;
        final int retrySum;
        final int maxRetry;

        DbState(int unsynced, int synced, int queueCount, int retrySum, int maxRetry) {
            this.unsynced = unsynced;
            this.synced = synced;
            this.queueCount = queueCount;
            this.retrySum = retrySum;
            this.maxRetry = maxRetry;
        }

        JSONObject toJson() throws Exception {
            JSONObject result = new JSONObject();
            result.put("unsynced", unsynced);
            result.put("synced", synced);
            result.put("queue_count", queueCount);
            result.put("retry_sum", retrySum);
            result.put("max_retry", maxRetry);
            return result;
        }
    }
}
