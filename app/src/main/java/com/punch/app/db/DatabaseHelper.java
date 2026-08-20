package com.punch.app.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.punch.app.model.Employee;
import com.punch.app.model.PunchRecord;
import com.punch.app.model.SyncQueueItem;
import com.punch.app.utils.Constants;
import com.punch.app.utils.PunchSnapshotHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class DatabaseHelper extends SQLiteOpenHelper {

    private static DatabaseHelper instance;
    private final Context appContext;
    private final FaceSdkIdRegistry faceSdkIdRegistry;


    public static synchronized DatabaseHelper get(Context ctx) {
        if (instance == null) instance = new DatabaseHelper(ctx.getApplicationContext());
        return instance;
    }


    private DatabaseHelper(Context context) {
        super(context, Constants.DB_NAME, null, Constants.DB_VERSION);
        this.appContext = context.getApplicationContext();
        this.faceSdkIdRegistry = new FaceSdkIdRegistry(new FaceSdkIdRegistry.Store() {
            @Override
            public Integer find(String employeeId) {
                return findFaceSdkId(employeeId);
            }

            @Override
            public int maxId() {
                return findMaxFaceSdkId();
            }

            @Override
            public boolean insert(String employeeId, int sdkId) {
                return insertFaceSdkId(employeeId, sdkId);
            }
        });
    }


    @Override
    public void onCreate(SQLiteDatabase db) {
        // 员工表：保存员工基本信息、人脸图片信息、所属线体以及本地注册状态。
        db.execSQL("CREATE TABLE IF NOT EXISTS employees (" +
                "id TEXT PRIMARY KEY, name TEXT NOT NULL, dept TEXT, " +
                "face_image_url TEXT, face_image_sha256 TEXT, " +
                "face_version INTEGER DEFAULT 0, face_status TEXT DEFAULT 'enabled', " +
                "local_face_id TEXT, face_registered INTEGER DEFAULT 0, " +
                "assigned_line_code TEXT DEFAULT '', assigned_line_name TEXT DEFAULT '', " +
                "status TEXT DEFAULT 'normal', sync_version INTEGER DEFAULT 0, " +
                "is_deleted INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");

        // 打卡记录表：保存本地打卡业务数据，以及是否已同步到服务端的状态。
        createPunchRecordsTable(db);

        // 同步队列表：只记录“待同步动作”和关联记录 ID，真正业务数据仍在各自业务表中。
        // UNIQUE(action, record_id) 防止同一条记录被重复加入相同同步任务。
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_queue (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "record_id TEXT NOT NULL, action TEXT NOT NULL, " +
                "retry_count INTEGER DEFAULT 0, created_at INTEGER NOT NULL, " +
                "last_retry INTEGER, UNIQUE(action, record_id))");

        // 常用索引：优化按线体、打卡日期、同步状态等场景的查询性能。
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_emp_line ON employees(assigned_line_code)");
        createFaceSdkIdsTable(db);
        createPunchRecordIndexes(db);
    }


    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            migrateEmployeesDropAvatarUrl(db);
            db.execSQL("DROP INDEX IF EXISTS idx_transfer_sync");
            db.execSQL("DROP TABLE IF EXISTS transfer_records");
        }
        if (oldVersion < 4) {
            migratePunchRecordsDropTransferColumns(db);
        }
        if (oldVersion < 5) {
            migratePunchRecordsDropIsEarly(db);
        }
        if (oldVersion < 6) {
            migratePunchRecordsSlimColumns(db);
        }
        if (oldVersion < 7) {
            migratePunchRecordsAddTeamBindingAndClockIndex(db);
        }
        if (oldVersion < 8) {
            migratePunchRecordsAddSnapshotColumns(db);
        }
        if (oldVersion < 9) {
            createFaceSdkIdsTable(db);
        }
    }

    public int getOrCreateFaceSdkId(String employeeId) {
        return faceSdkIdRegistry.getOrCreate(employeeId);
    }

    private Integer findFaceSdkId(String employeeId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT sdk_id FROM face_sdk_ids WHERE emp_id=?",
                new String[]{employeeId});
        try {
            return c.moveToFirst() ? c.getInt(0) : null;
        } finally {
            c.close();
        }
    }

    private int findMaxFaceSdkId() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(MAX(sdk_id), 0) FROM face_sdk_ids", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    private boolean insertFaceSdkId(String employeeId, int sdkId) {
        ContentValues values = new ContentValues();
        values.put("emp_id", employeeId);
        values.put("sdk_id", sdkId);
        return getWritableDatabase().insertWithOnConflict(
                "face_sdk_ids", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    private void createFaceSdkIdsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS face_sdk_ids (" +
                "emp_id TEXT PRIMARY KEY, sdk_id INTEGER NOT NULL UNIQUE CHECK(sdk_id > 0))");
    }


    public void upsertEmployee(Employee e) {
        ContentValues v = new ContentValues();
        v.put("id", e.id); v.put("name", e.name); v.put("dept", e.dept);
        v.put("face_image_url", e.faceImageUrl);
        v.put("face_image_sha256", e.faceImageSha256); v.put("face_version", e.faceVersion);
        v.put("face_status", e.faceStatus); v.put("local_face_id", e.localFaceId);
        v.put("face_registered", e.faceRegistered);
        v.put("assigned_line_code", e.assignedLineCode);
        v.put("assigned_line_name", e.assignedLineName);
        v.put("status", e.status); v.put("sync_version", e.syncVersion);
        v.put("is_deleted", e.isDeleted); v.put("updated_at", e.updatedAt);
        getWritableDatabase().insertWithOnConflict("employees", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }


    public void upsertEmployees(List<Employee> list) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Employee e : list) {
                ContentValues v = new ContentValues();
                v.put("id", e.id); v.put("name", e.name); v.put("dept", e.dept);
                v.put("face_image_url", e.faceImageUrl);
                v.put("face_image_sha256", e.faceImageSha256); v.put("face_version", e.faceVersion);
                v.put("face_status", e.faceStatus); v.put("local_face_id", e.localFaceId);
                v.put("face_registered", e.faceRegistered);
                v.put("assigned_line_code", e.assignedLineCode);
                v.put("assigned_line_name", e.assignedLineName);
                v.put("status", e.status); v.put("sync_version", e.syncVersion);
                v.put("is_deleted", e.isDeleted); v.put("updated_at", e.updatedAt);
                db.insertWithOnConflict("employees", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }


    public Employee getEmployee(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM employees WHERE id=?", new String[]{id});
        try { return c.moveToFirst() ? mapEmployee(c) : null; }
        finally { c.close(); }
    }


    public List<Employee> getAllActiveEmployees() {
        return queryEmployees("is_deleted=0 AND face_status='enabled'", null);
    }

    public List<Employee> getAllEmployeesForDebug() {
        List<Employee> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM employees ORDER BY updated_at DESC, id ASC",
                null);
        try {
            while (c.moveToNext()) {
                list.add(mapEmployee(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    public int getActiveEmployeeCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM employees WHERE is_deleted=0 AND face_status='enabled'",
                null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }


    public Map<String, String> getAvailableLines() {
        Map<String, String> lines = new LinkedHashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT assigned_line_code, assigned_line_name FROM employees " +
                        "WHERE is_deleted=0 AND assigned_line_code IS NOT NULL AND assigned_line_code<>'' " +
                        "ORDER BY assigned_line_code ASC",
                null);
        try {
            while (c.moveToNext()) {
                String lineCode = c.getString(c.getColumnIndexOrThrow("assigned_line_code"));
                String lineName = c.getString(c.getColumnIndexOrThrow("assigned_line_name"));
                lines.put(lineCode, lineName == null ? "" : lineName);
            }
        } finally {
            c.close();
        }
        return lines;
    }


    public List<Employee> getEmployeesByLine(String lineCode) {
        return queryEmployees("assigned_line_code=? AND is_deleted=0", new String[]{lineCode});
    }


    public List<Employee> getUnregisteredFaces() {
        return queryEmployees(
                "face_registered=0 AND is_deleted=0 AND face_status='enabled' AND face_image_url IS NOT NULL",
                null);
    }


    public void updateFaceRegistration(String empId, String localFaceId, boolean registered) {
        ContentValues v = new ContentValues();
        v.put("local_face_id", localFaceId);
        v.put("face_registered", registered ? 1 : 0);
        getWritableDatabase().update("employees", v, "id=?", new String[]{empId});
    }


    public void updateEmployeeLineAssignment(String empId, String lineCode, String lineName) {
        ContentValues v = new ContentValues();
        v.put("assigned_line_code", lineCode);
        v.put("assigned_line_name", lineName);
        getWritableDatabase().update("employees", v, "id=?", new String[]{empId});
    }


    public void markEmployeesDeleted(List<String> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues(); v.put("is_deleted", 1);
            for (String id : ids) db.update("employees", v, "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void markEmployeeDeleted(String id, long updatedAt) {
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        ContentValues v = new ContentValues();
        v.put("is_deleted", 1);
        if (updatedAt > 0) {
            v.put("updated_at", updatedAt);
        }
        getWritableDatabase().update("employees", v, "id=?", new String[]{id});
    }

    public void removeEmployee(String id) {
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        getWritableDatabase().delete("employees", "id=?", new String[]{id});
    }

    public void clearAllEmployees() {
        getWritableDatabase().delete("employees", null, null);
    }


    private List<Employee> queryEmployees(String where, String[] args) {
        List<Employee> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM employees" + (where != null ? " WHERE " + where : ""), args);
        try { while (c.moveToNext()) list.add(mapEmployee(c)); }
        finally { c.close(); }
        return list;
    }


    private Employee mapEmployee(Cursor c) {
        Employee e = new Employee();
        e.id = c.getString(c.getColumnIndexOrThrow("id"));
        e.name = c.getString(c.getColumnIndexOrThrow("name"));
        e.dept = c.getString(c.getColumnIndexOrThrow("dept"));
        e.faceImageUrl = c.getString(c.getColumnIndexOrThrow("face_image_url"));
        e.faceImageSha256 = c.getString(c.getColumnIndexOrThrow("face_image_sha256"));
        e.faceVersion = c.getInt(c.getColumnIndexOrThrow("face_version"));
        e.faceStatus = c.getString(c.getColumnIndexOrThrow("face_status"));
        e.localFaceId = c.getString(c.getColumnIndexOrThrow("local_face_id"));
        e.faceRegistered = c.getInt(c.getColumnIndexOrThrow("face_registered"));
        e.assignedLineCode = c.getString(c.getColumnIndexOrThrow("assigned_line_code"));
        e.assignedLineName = c.getString(c.getColumnIndexOrThrow("assigned_line_name"));
        e.status = c.getString(c.getColumnIndexOrThrow("status"));
        e.syncVersion = c.getInt(c.getColumnIndexOrThrow("sync_version"));
        e.isDeleted = c.getInt(c.getColumnIndexOrThrow("is_deleted"));
        e.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return e;
    }


    public boolean insertPunchRecord(PunchRecord r) {
        ContentValues v = new ContentValues();
        v.put("id", r.id); v.put("client_record_id", r.clientRecordId);
        v.put("emp_id", r.empId); v.put("emp_name", r.empName); v.put("dept", r.dept);
        v.put("punch_time", r.punchTime); v.put("punch_date", r.punchDate);
        v.put("punch_type", r.punchType);
        v.put("shift_name", r.shiftName); v.put("line_code", r.lineCode);
        v.put("team_binding_id", r.teamBindingId); v.put("clock_index", r.clockIndex);
        v.put("match_score", r.matchScore);
        v.put("snap_image_path", r.snapImagePath);
        v.put("snap_image_mime_type", r.snapImageMimeType);
        v.put("snap_image_width", r.snapImageWidth);
        v.put("snap_image_height", r.snapImageHeight);
        v.put("snap_image_size", r.snapImageSize);
        v.put("snap_captured_at", r.snapCapturedAt);
        v.put("is_synced", r.isSynced);
        long result = getWritableDatabase().insertWithOnConflict("punch_records", null, v,
                SQLiteDatabase.CONFLICT_IGNORE);
        return result != -1;
    }


    public List<PunchRecord> getPunchRecordsByDate(String date, String lineCode) {
        List<PunchRecord> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM punch_records WHERE punch_date=? AND line_code=? ORDER BY punch_time DESC",
                new String[]{date, lineCode});
        try { while (c.moveToNext()) list.add(mapPunch(c)); }
        finally { c.close(); }
        return list;
    }


    public List<PunchRecord> getUnsyncedPunchRecords() {
        List<PunchRecord> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM punch_records WHERE is_synced=0 ORDER BY punch_time ASC", null);
        try { while (c.moveToNext()) list.add(mapPunch(c)); }
        finally { c.close(); }
        return list;
    }

    public PunchRecord getUnsyncedPunchRecord(String clientRecordId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM punch_records WHERE client_record_id=? AND is_synced=0",
                new String[]{clientRecordId});
        try {
            return c.moveToFirst() ? mapPunch(c) : null;
        } finally {
            c.close();
        }
    }


    public void markPunchSynced(String id) {
        ContentValues v = new ContentValues();
        v.put("is_synced", 1);
        getWritableDatabase().update("punch_records", v, "id=?", new String[]{id});
    }


    private PunchRecord mapPunch(Cursor c) {
        PunchRecord r = new PunchRecord();
        r.id = c.getString(c.getColumnIndexOrThrow("id"));
        r.clientRecordId = c.getString(c.getColumnIndexOrThrow("client_record_id"));
        r.empId = c.getString(c.getColumnIndexOrThrow("emp_id"));
        r.empName = c.getString(c.getColumnIndexOrThrow("emp_name"));
        r.dept = c.getString(c.getColumnIndexOrThrow("dept"));
        r.punchTime = c.getLong(c.getColumnIndexOrThrow("punch_time"));
        r.punchDate = c.getString(c.getColumnIndexOrThrow("punch_date"));
        r.punchType = c.getString(c.getColumnIndexOrThrow("punch_type"));
        r.shiftName = c.getString(c.getColumnIndexOrThrow("shift_name"));
        r.lineCode = c.getString(c.getColumnIndexOrThrow("line_code"));
        r.teamBindingId = c.getInt(c.getColumnIndexOrThrow("team_binding_id"));
        r.clockIndex = c.getInt(c.getColumnIndexOrThrow("clock_index"));
        r.matchScore = c.getDouble(c.getColumnIndexOrThrow("match_score"));
        r.snapImagePath = c.getString(c.getColumnIndexOrThrow("snap_image_path"));
        r.snapImageMimeType = c.getString(c.getColumnIndexOrThrow("snap_image_mime_type"));
        r.snapImageWidth = c.getInt(c.getColumnIndexOrThrow("snap_image_width"));
        r.snapImageHeight = c.getInt(c.getColumnIndexOrThrow("snap_image_height"));
        r.snapImageSize = c.getLong(c.getColumnIndexOrThrow("snap_image_size"));
        r.snapCapturedAt = c.getLong(c.getColumnIndexOrThrow("snap_captured_at"));
        r.isSynced = c.getInt(c.getColumnIndexOrThrow("is_synced"));
        return r;
    }


    public void enqueueSyncItem(String recordId, String action) {
        ContentValues v = new ContentValues();
        v.put("record_id", recordId); v.put("action", action);
        v.put("retry_count", 0); v.put("created_at", System.currentTimeMillis() / 1000);
        getWritableDatabase().insertWithOnConflict("sync_queue", null, v,
                SQLiteDatabase.CONFLICT_IGNORE);
    }


    public List<SyncQueueItem> getSyncQueue(String action) {
        return querySyncQueue(action, null, 0);
    }

    public List<SyncQueueItem> getRetryableSyncQueue(String action, int maxRetries, int limit) {
        return querySyncQueue(action, maxRetries, limit);
    }

    private List<SyncQueueItem> querySyncQueue(String action,
                                               Integer maxRetries,
                                               int limit) {
        List<SyncQueueItem> list = new ArrayList<>();
        List<String> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM sync_queue");
        if (action != null || maxRetries != null) {
            sql.append(" WHERE ");
            if (action != null) {
                sql.append("action=?");
                args.add(action);
            }
            if (maxRetries != null) {
                if (action != null) {
                    sql.append(" AND ");
                }
                sql.append("retry_count<?");
                args.add(String.valueOf(maxRetries));
            }
        }
        sql.append(" ORDER BY created_at ASC, id ASC");
        if (limit > 0) {
            sql.append(" LIMIT ?");
            args.add(String.valueOf(limit));
        }
        Cursor c = getReadableDatabase().rawQuery(
                sql.toString(), args.isEmpty() ? null : args.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                SyncQueueItem item = new SyncQueueItem();
                item.id = c.getInt(c.getColumnIndexOrThrow("id"));
                item.recordId = c.getString(c.getColumnIndexOrThrow("record_id"));
                item.action = c.getString(c.getColumnIndexOrThrow("action"));
                item.retryCount = c.getInt(c.getColumnIndexOrThrow("retry_count"));
                item.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
                item.lastRetry = c.getLong(c.getColumnIndexOrThrow("last_retry"));
                list.add(item);
            }
        } finally { c.close(); }
        return list;
    }


    public void removeSyncQueueItem(int id) {
        getWritableDatabase().delete("sync_queue", "id=?", new String[]{String.valueOf(id)});
    }


    public void incrementSyncRetry(int id) {
        getWritableDatabase().execSQL(
                "UPDATE sync_queue SET retry_count=retry_count+1, last_retry=? WHERE id=?",
                new Object[]{System.currentTimeMillis() / 1000, id});
    }


    public int repairPunchSyncQueue() {
        int missingCount = countMissingPunchSyncQueueItems();
        if (missingCount <= 0) {
            return 0;
        }
        getWritableDatabase().execSQL(
                "INSERT OR IGNORE INTO sync_queue (record_id, action, retry_count, created_at, last_retry) " +
                        "SELECT client_record_id, ?, 0, ?, NULL FROM punch_records " +
                        "WHERE is_synced=0 AND client_record_id IS NOT NULL AND TRIM(client_record_id)<>''",
                new Object[]{Constants.ACTION_PUNCH_PUSH, System.currentTimeMillis() / 1000});
        return missingCount;
    }


    public int resetLimitedPunchSyncRetries() {
        int resetCount = countLimitedPunchSyncQueueItems();
        if (resetCount <= 0) {
            return 0;
        }
        getWritableDatabase().execSQL(
                "UPDATE sync_queue SET retry_count=0, last_retry=NULL " +
                        "WHERE action=? AND retry_count>=? AND EXISTS (" +
                        "SELECT 1 FROM punch_records p " +
                        "WHERE p.client_record_id=sync_queue.record_id AND p.is_synced=0)",
                new Object[]{Constants.ACTION_PUNCH_PUSH, Constants.SYNC_MAX_RETRY});
        return resetCount;
    }

    public int resetExpiredPunchSyncRetries(long localDayStartSeconds) {
        if (localDayStartSeconds <= 0) {
            return 0;
        }
        ContentValues values = new ContentValues();
        values.put("retry_count", 0);
        values.putNull("last_retry");
        return getWritableDatabase().update(
                "sync_queue",
                values,
                "action=? AND retry_count>=? " +
                        "AND (last_retry IS NULL OR last_retry<?) " +
                        "AND EXISTS (SELECT 1 FROM punch_records p " +
                        "WHERE p.client_record_id=sync_queue.record_id AND p.is_synced=0)",
                new String[]{
                        Constants.ACTION_PUNCH_PUSH,
                        String.valueOf(Constants.SYNC_MAX_RETRY),
                        String.valueOf(localDayStartSeconds)
                });
    }


    public int getPendingCount() {
        return getUnsyncedPunchCount() + getOtherPendingQueueCount();
    }

    private int getUnsyncedPunchCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM punch_records WHERE is_synced=0", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    private int getOtherPendingQueueCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM sync_queue WHERE action<>?",
                new String[]{Constants.ACTION_PUNCH_PUSH});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    private int countMissingPunchSyncQueueItems() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM punch_records p " +
                        "WHERE p.is_synced=0 " +
                        "AND p.client_record_id IS NOT NULL AND TRIM(p.client_record_id)<>'' " +
                        "AND NOT EXISTS (" +
                        "SELECT 1 FROM sync_queue q " +
                        "WHERE q.action=? AND q.record_id=p.client_record_id)",
                new String[]{Constants.ACTION_PUNCH_PUSH});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    private int countLimitedPunchSyncQueueItems() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM sync_queue q " +
                        "WHERE q.action=? AND q.retry_count>=? AND EXISTS (" +
                        "SELECT 1 FROM punch_records p " +
                        "WHERE p.client_record_id=q.record_id AND p.is_synced=0)",
                new String[]{Constants.ACTION_PUNCH_PUSH, String.valueOf(Constants.SYNC_MAX_RETRY)});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }


    public List<String> getSignedEmpIds(String date, String lineCode, int teamBindingId, int clockIndex) {
        List<String> ids = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT emp_id FROM punch_records " +
                        "WHERE punch_date=? AND line_code=? AND team_binding_id=? AND clock_index=?",
                new String[]{date, lineCode, String.valueOf(teamBindingId), String.valueOf(clockIndex)});
        try { while (c.moveToNext()) ids.add(c.getString(0)); }
        finally { c.close(); }
        return ids;
    }

    private void createPunchRecordsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS punch_records (" +
                "id TEXT PRIMARY KEY, client_record_id TEXT NOT NULL UNIQUE, " +
                "emp_id TEXT NOT NULL, emp_name TEXT NOT NULL, dept TEXT, " +
                "punch_time INTEGER NOT NULL, punch_date TEXT NOT NULL, " +
                "punch_type TEXT NOT NULL, shift_name TEXT, line_code TEXT NOT NULL, " +
                "team_binding_id INTEGER NOT NULL DEFAULT 0, " +
                "clock_index INTEGER NOT NULL DEFAULT 0, " +
                "match_score REAL DEFAULT 0, " +
                "snap_image_path TEXT, snap_image_mime_type TEXT DEFAULT 'image/jpeg', " +
                "snap_image_width INTEGER DEFAULT 0, snap_image_height INTEGER DEFAULT 0, " +
                "snap_image_size INTEGER DEFAULT 0, snap_captured_at INTEGER DEFAULT 0, " +
                "is_synced INTEGER DEFAULT 0)");
    }

    private void createPunchRecordIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_date ON punch_records(punch_date)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_sync ON punch_records(is_synced)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_limit " +
                "ON punch_records(punch_date, line_code, team_binding_id, clock_index, emp_id)");
    }


    public void clearLocalBusinessData() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("sync_queue", null, null);
            db.delete("punch_records", null, null);
            db.delete("employees", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        PunchSnapshotHelper.clearSnapshots(appContext);
    }

    private void migrateEmployeesDropAvatarUrl(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE employees RENAME TO employees_legacy_v2");
        db.execSQL("CREATE TABLE IF NOT EXISTS employees (" +
                "id TEXT PRIMARY KEY, name TEXT NOT NULL, dept TEXT, " +
                "face_image_url TEXT, face_image_sha256 TEXT, " +
                "face_version INTEGER DEFAULT 0, face_status TEXT DEFAULT 'enabled', " +
                "local_face_id TEXT, face_registered INTEGER DEFAULT 0, " +
                "assigned_line_code TEXT DEFAULT '', assigned_line_name TEXT DEFAULT '', " +
                "status TEXT DEFAULT 'normal', sync_version INTEGER DEFAULT 0, " +
                "is_deleted INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");
        db.execSQL("INSERT INTO employees (" +
                "id, name, dept, face_image_url, face_image_sha256, face_version, face_status, " +
                "local_face_id, face_registered, assigned_line_code, assigned_line_name, " +
                "status, sync_version, is_deleted, updated_at) " +
                "SELECT id, name, dept, face_image_url, face_image_sha256, face_version, face_status, " +
                "local_face_id, face_registered, assigned_line_code, assigned_line_name, " +
                "status, sync_version, is_deleted, updated_at " +
                "FROM employees_legacy_v2");
        db.execSQL("DROP TABLE employees_legacy_v2");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_emp_line ON employees(assigned_line_code)");
    }

    private void migratePunchRecordsDropTransferColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE punch_records RENAME TO punch_records_legacy_v3");
        db.execSQL("CREATE TABLE IF NOT EXISTS punch_records (" +
                "id TEXT PRIMARY KEY, client_record_id TEXT NOT NULL UNIQUE, " +
                "emp_id TEXT NOT NULL, emp_name TEXT NOT NULL, dept TEXT, " +
                "punch_time INTEGER NOT NULL, punch_date TEXT NOT NULL, " +
                "punch_index INTEGER NOT NULL, punch_type TEXT NOT NULL, " +
                "shift_name TEXT, line_code TEXT NOT NULL, line_name TEXT NOT NULL, " +
                "device_id TEXT NOT NULL, status TEXT DEFAULT 'normal', " +
                "is_early INTEGER DEFAULT 0, is_synced INTEGER DEFAULT 0, " +
                "sync_time INTEGER, server_id TEXT)");
        db.execSQL("INSERT INTO punch_records (" +
                "id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_index, punch_type, shift_name, line_code, line_name, device_id, " +
                "status, is_early, is_synced, sync_time, server_id) " +
                "SELECT id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_index, punch_type, shift_name, line_code, line_name, device_id, " +
                "status, is_early, is_synced, sync_time, server_id " +
                "FROM punch_records_legacy_v3");
        db.execSQL("DROP TABLE punch_records_legacy_v3");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_date ON punch_records(punch_date)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_sync ON punch_records(is_synced)");
    }

    private void migratePunchRecordsDropIsEarly(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE punch_records RENAME TO punch_records_legacy_v4");
        db.execSQL("CREATE TABLE IF NOT EXISTS punch_records (" +
                "id TEXT PRIMARY KEY, client_record_id TEXT NOT NULL UNIQUE, " +
                "emp_id TEXT NOT NULL, emp_name TEXT NOT NULL, dept TEXT, " +
                "punch_time INTEGER NOT NULL, punch_date TEXT NOT NULL, " +
                "punch_index INTEGER NOT NULL, punch_type TEXT NOT NULL, " +
                "shift_name TEXT, line_code TEXT NOT NULL, line_name TEXT NOT NULL, " +
                "device_id TEXT NOT NULL, status TEXT DEFAULT 'normal', " +
                "is_synced INTEGER DEFAULT 0, sync_time INTEGER, server_id TEXT)");
        db.execSQL("INSERT INTO punch_records (" +
                "id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_index, punch_type, shift_name, line_code, line_name, device_id, " +
                "status, is_synced, sync_time, server_id) " +
                "SELECT id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_index, punch_type, shift_name, line_code, line_name, device_id, " +
                "status, is_synced, sync_time, server_id " +
                "FROM punch_records_legacy_v4");
        db.execSQL("DROP TABLE punch_records_legacy_v4");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_date ON punch_records(punch_date)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_sync ON punch_records(is_synced)");
    }

    private void migratePunchRecordsSlimColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE punch_records RENAME TO punch_records_legacy_v5");
        createPunchRecordsTable(db);
        db.execSQL("INSERT INTO punch_records (" +
                "id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_type, shift_name, line_code, is_synced) " +
                "SELECT id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_type, shift_name, line_code, is_synced " +
                "FROM punch_records_legacy_v5");
        db.execSQL("DROP TABLE punch_records_legacy_v5");
        createPunchRecordIndexes(db);
    }

    private void migratePunchRecordsAddTeamBindingAndClockIndex(SQLiteDatabase db) {
        db.execSQL("DROP INDEX IF EXISTS idx_punch_date");
        db.execSQL("DROP INDEX IF EXISTS idx_punch_sync");
        db.execSQL("DROP INDEX IF EXISTS idx_punch_limit");
        addColumnIfMissing(db, "punch_records", "team_binding_id", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "punch_records", "clock_index", "INTEGER NOT NULL DEFAULT 0");
        createPunchRecordIndexes(db);
        ensureSyncQueueTable(db);
        repairPunchSyncQueue(db);
    }

    private void migratePunchRecordsAddSnapshotColumns(SQLiteDatabase db) {
        addColumnIfMissing(db, "punch_records", "match_score", "REAL DEFAULT 0");
        addColumnIfMissing(db, "punch_records", "snap_image_path", "TEXT");
        addColumnIfMissing(db, "punch_records", "snap_image_mime_type", "TEXT DEFAULT 'image/jpeg'");
        addColumnIfMissing(db, "punch_records", "snap_image_width", "INTEGER DEFAULT 0");
        addColumnIfMissing(db, "punch_records", "snap_image_height", "INTEGER DEFAULT 0");
        addColumnIfMissing(db, "punch_records", "snap_image_size", "INTEGER DEFAULT 0");
        addColumnIfMissing(db, "punch_records", "snap_captured_at", "INTEGER DEFAULT 0");
    }

    private void repairPunchSyncQueue(SQLiteDatabase db) {
        db.execSQL(
                "INSERT OR IGNORE INTO sync_queue (record_id, action, retry_count, created_at, last_retry) " +
                        "SELECT client_record_id, ?, 0, ?, NULL FROM punch_records " +
                        "WHERE is_synced=0 AND client_record_id IS NOT NULL AND TRIM(client_record_id)<>''",
                new Object[]{Constants.ACTION_PUNCH_PUSH, System.currentTimeMillis() / 1000});
    }

    private void ensureSyncQueueTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_queue (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "record_id TEXT NOT NULL, action TEXT NOT NULL, " +
                "retry_count INTEGER DEFAULT 0, created_at INTEGER NOT NULL DEFAULT 0, " +
                "last_retry INTEGER, UNIQUE(action, record_id))");
        addColumnIfMissing(db, "sync_queue", "retry_count", "INTEGER DEFAULT 0");
        addColumnIfMissing(db, "sync_queue", "created_at", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "sync_queue", "last_retry", "INTEGER");
    }

    private void addColumnIfMissing(SQLiteDatabase db, String table, String column, String definition) {
        if (!hasColumn(db, table, column)) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            while (c.moveToNext()) {
                String existing = c.getString(c.getColumnIndexOrThrow("name"));
                if (column.equalsIgnoreCase(existing)) {
                    return true;
                }
            }
            return false;
        } finally {
            c.close();
        }
    }
}
