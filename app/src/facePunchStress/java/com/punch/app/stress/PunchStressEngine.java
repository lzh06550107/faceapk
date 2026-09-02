package com.punch.app.stress;

import android.content.Context;

import com.punch.app.db.DatabaseHelper;
import com.punch.app.model.PunchRecord;
import com.punch.app.service.PunchPersistence;
import com.punch.app.utils.Constants;
import com.punch.app.utils.PunchSnapshotHelper;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UlidGenerator;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class PunchStressEngine {
    public static final String STRESS_PREFIX = "STRESS_V23_";
    public static final String STRESS_EMP_ID = "STRESS_EMP_V23";
    private static final String STRESS_EMP_NAME = "V2.3 Stress Employee";
    private static final String STRESS_DEPT = "Stress";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private PunchStressEngine() {}

    public static StressResult run(Context context, int count) {
        StressResult result = new StressResult("Punch", count);
        result.employeeId = STRESS_EMP_ID;
        result.fixtureSource = "synthetic_employee";
        byte[] nv21 = createGrayNv21(WIDTH, HEIGHT);
        SessionManager session = SessionManager.get();
        String lineCode = session.getLineCode();
        if (lineCode == null || lineCode.trim().isEmpty()) lineCode = "STRESS";
        int teamBindingId = session.getTeamBindingId();
        for (int i = 0; i < count; i++) {
            long started = System.nanoTime();
            String clientId = STRESS_PREFIX + System.currentTimeMillis() + "_" + i + "_" + UlidGenerator.generate();
            PunchSnapshotHelper.Snapshot snapshot = PunchSnapshotHelper.capture(
                    context, clientId, nv21, WIDTH, HEIGHT, 0, 0);
            if (snapshot == null) {
                result.add((System.nanoTime() - started) / 1_000_000L, false, "snapshot_failed");
                continue;
            }
            PunchRecord record = new PunchRecord();
            record.id = UlidGenerator.generate();
            record.clientRecordId = clientId;
            record.empId = STRESS_EMP_ID;
            record.empName = STRESS_EMP_NAME;
            record.dept = STRESS_DEPT;
            record.punchTime = System.currentTimeMillis() / 1000L;
            record.punchDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            record.punchType = Constants.PUNCH_TYPE_SIGN_IN;
            record.shiftName = "V2.3 Stress";
            record.lineCode = lineCode;
            record.teamBindingId = teamBindingId;
            record.clockIndex = 0;
            record.matchScore = 100.0;
            record.snapImagePath = snapshot.path;
            record.snapImageMimeType = snapshot.mimeType;
            record.snapImageWidth = snapshot.width;
            record.snapImageHeight = snapshot.height;
            record.snapImageSize = snapshot.sizeBytes;
            record.snapCapturedAt = snapshot.capturedAtSeconds;
            record.isSynced = 1;
            boolean inserted = PunchPersistence.persist(
                    context, record, Constants.ACTION_PUNCH_STRESS_NO_UPLOAD);
            if (!inserted) PunchSnapshotHelper.deleteSnapshot(snapshot.path);
            result.add((System.nanoTime() - started) / 1_000_000L, inserted, inserted ? "inserted" : "insert_failed");
        }
        DatabaseHelper db = DatabaseHelper.get(context);
        result.punchRecordCount = db.countPunchRecordsByClientPrefix(STRESS_PREFIX);
        result.queueCount = db.countSyncQueueByAction(Constants.ACTION_PUNCH_STRESS_NO_UPLOAD);
        File dir = new File(context.getFilesDir(), "punch_snapshots");
        File[] files = dir.listFiles((d, name) -> name != null && name.startsWith(STRESS_PREFIX));
        if (files != null) {
            result.snapshotCount = files.length;
            for (File file : files) result.snapshotBytes += file.length();
        }
        return result;
    }

    public static void cleanup(Context context) {
        DatabaseHelper db = DatabaseHelper.get(context);
        db.deleteSyncQueueByAction(Constants.ACTION_PUNCH_STRESS_NO_UPLOAD);
        db.deletePunchRecordsByClientPrefix(STRESS_PREFIX);
        File dir = new File(context.getFilesDir(), "punch_snapshots");
        File[] files = dir.listFiles((d, name) -> name != null && name.startsWith(STRESS_PREFIX));
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
    }

    private static byte[] createGrayNv21(int width, int height) {
        int ySize = width * height;
        byte[] data = new byte[ySize + ySize / 2];
        for (int i = 0; i < ySize; i++) data[i] = (byte) 128;
        for (int i = ySize; i < data.length; i++) data[i] = (byte) 128;
        return data;
    }
}
