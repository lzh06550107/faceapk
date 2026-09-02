package com.punch.app.camerafacerecovery;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.punch.app.PunchApplication;
import com.punch.app.R;
import com.punch.app.db.CameraFaceRecoveryDatabaseController;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.face.FaceFileManager;
import com.punch.app.face.FaceManager;
import com.punch.app.model.Employee;
import com.punch.app.network.CameraFaceRecoveryNetworkController;
import com.punch.app.utils.SessionManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** V2.7.3 deterministic Camera/Face recovery scenario engine. */
public final class CameraFaceRecoveryEngine {
    public static final String EMP_ID = "RECOVERY_FACE_V273";
    private static final String EMP_NAME = "V2.7.3 Recovery Fixture";
    private static final long FACE_READY_TIMEOUT_MS = 150_000L;
    private static final long POLL_MS = 250L;

    private CameraFaceRecoveryEngine() { }

    public static JSONObject prepare(Context context) throws Exception {
        Context appContext = context.getApplicationContext();
        PunchApplication app = PunchApplication.get();
        if (app == null) throw new IllegalStateException("application_not_ready");
        requireSessionReady();

        waitForFaceSdk();
        waitForPreviousPreparation(app, 15_000L);

        DatabaseHelper db = CameraFaceRecoveryDatabaseController.prepareFresh(appContext);
        try { FaceManager.get().removeFace(EMP_ID); } catch (Throwable ignored) { }

        String imagePath = FaceFileManager.getFaceImagePath(appContext, EMP_ID);
        copyFixture(appContext, new File(imagePath));

        Employee employee = new Employee();
        employee.id = EMP_ID;
        employee.name = EMP_NAME;
        employee.dept = "Camera Face Recovery";
        employee.faceImageUrl = "fixture://v273";
        employee.faceImageSha256 = "";
        employee.faceVersion = 1;
        employee.faceStatus = "enabled";
        employee.localFaceId = "FACE_" + EMP_ID;
        employee.faceRegistered = 1;
        employee.assignedLineCode = "";
        employee.assignedLineName = "";
        employee.status = "normal";
        employee.syncVersion = 1;
        employee.isDeleted = 0;
        employee.updatedAt = System.currentTimeMillis() / 1000L;
        db.upsertEmployee(employee);

        app.resetPunchRecognitionState();
        app.preparePunchRecognitionData();
        waitForPunchReady(app);

        JSONObject health = health(appContext);
        JSONObject recognition = recognize(appContext);
        boolean success = health.optBoolean("success", false)
                && recognition.optBoolean("success", false);

        JSONObject result = new JSONObject();
        result.put("version", "V2.7.3");
        result.put("phase", "prepared");
        result.put("success", success);
        result.put("health", health);
        result.put("recognition", recognition);
        result.put("base_url", CameraFaceRecoveryNetworkController.getBaseUrl());
        return result;
    }

    public static JSONObject health(Context context) {
        Context appContext = context.getApplicationContext();
        JSONObject result = new JSONObject();
        try {
            PunchApplication app = PunchApplication.get();
            DatabaseHelper db = CameraFaceRecoveryDatabaseController.get(appContext);
            Employee employee = db.getEmployee(EMP_ID);
            File image = new File(FaceFileManager.getFaceImagePath(appContext, EMP_ID));
            boolean faceInitialized = FaceManager.get().isInitialized();
            int loadedFaceCount = FaceManager.get().getLoadedFaceCount();
            boolean punchReady = app != null && app.isPunchRecognitionReady();
            boolean punchPreparing = app != null && app.isPunchDataPreparing();
            boolean fixtureEmployee = employee != null
                    && employee.isDeleted == 0
                    && employee.faceRegistered == 1
                    && "enabled".equals(employee.faceStatus);
            boolean fixtureImage = image.isFile() && image.length() > 0L;
            boolean success = CameraFaceRecoveryDatabaseController.isActive()
                    && faceInitialized
                    && loadedFaceCount >= 1
                    && punchReady
                    && fixtureEmployee
                    && fixtureImage;
            result.put("success", success);
            result.put("database_active", CameraFaceRecoveryDatabaseController.isActive());
            result.put("token_valid", SessionManager.get().isTokenValid());
            result.put("device_registered", SessionManager.get().isDeviceRegistered());
            result.put("face_initialized", faceInitialized);
            result.put("loaded_face_count", loadedFaceCount);
            result.put("punch_ready", punchReady);
            result.put("punch_preparing", punchPreparing);
            result.put("punch_status", app == null ? "" : app.getPunchDataStatus());
            result.put("fixture_employee", fixtureEmployee);
            result.put("fixture_image", fixtureImage);
            result.put("fixture_image_path", image.getAbsolutePath());
            result.put("base_url", CameraFaceRecoveryNetworkController.getBaseUrl());
        } catch (Throwable t) {
            try {
                result.put("success", false);
                result.put("error", t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage()));
            } catch (Throwable ignored) { }
        }
        return result;
    }

    public static JSONObject recognize(Context context) throws Exception {
        Context appContext = context.getApplicationContext();
        File image = new File(FaceFileManager.getFaceImagePath(appContext, EMP_ID));
        if (!image.isFile()) throw new IllegalStateException("fixture_image_missing");
        Bitmap bitmap = BitmapFactory.decodeFile(image.getAbsolutePath());
        if (bitmap == null) throw new IllegalStateException("fixture_bitmap_decode_failed");
        try {
            FaceManager.RecognizeResult recognized = FaceManager.get().recognizeFromBitmap(bitmap);
            boolean success = recognized.matched && EMP_ID.equals(recognized.empId);
            JSONObject result = new JSONObject();
            result.put("version", "V2.7.3");
            result.put("success", success);
            result.put("matched", recognized.matched);
            result.put("emp_id", recognized.empId == null ? "" : recognized.empId);
            result.put("score", (double) recognized.score);
            result.put("error", recognized.errorMsg == null ? "" : recognized.errorMsg);
            result.put("debug_detail", recognized.debugDetail == null ? "" : recognized.debugDetail);
            return result;
        } finally {
            bitmap.recycle();
        }
    }

    public static void cleanup(Context context) {
        Context appContext = context.getApplicationContext();
        try { FaceManager.get().removeFace(EMP_ID); } catch (Throwable ignored) { }
        try { FaceFileManager.deleteFaceImage(appContext, EMP_ID); } catch (Throwable ignored) { }
        try {
            DatabaseHelper db = CameraFaceRecoveryDatabaseController.get(appContext);
            SQLiteDatabase sqlite = db.getWritableDatabase();
            sqlite.delete("face_sdk_ids", "emp_id=?", new String[]{EMP_ID});
            sqlite.delete("employees", "id=?", new String[]{EMP_ID});
        } catch (Throwable ignored) { }
        try { CameraFaceRecoveryDatabaseController.cleanup(appContext); } catch (Throwable ignored) { }
        CameraFaceRecoveryNetworkController.reset();
    }

    private static void waitForFaceSdk() throws Exception {
        long deadline = System.currentTimeMillis() + FACE_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (FaceManager.get().isInitialized()) return;
            Thread.sleep(POLL_MS);
        }
        throw new IllegalStateException("face_sdk_not_initialized");
    }

    private static void waitForPreviousPreparation(PunchApplication app, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!app.isPunchDataPreparing()) return;
            Thread.sleep(POLL_MS);
        }
        throw new IllegalStateException("previous_preparation_still_running");
    }

    private static void waitForPunchReady(PunchApplication app) throws Exception {
        long deadline = System.currentTimeMillis() + FACE_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (app.isPunchRecognitionReady()
                    && FaceManager.get().isInitialized()
                    && FaceManager.get().getLoadedFaceCount() >= 1) return;
            if (!app.isPunchDataPreparing() && !app.isPunchRecognitionReady()
                    && app.getPunchDataStatusLevel() == PunchApplication.STATUS_LEVEL_ERROR) {
                throw new IllegalStateException("punch_preparation_failed:" + app.getPunchDataStatus());
            }
            Thread.sleep(POLL_MS);
        }
        throw new IllegalStateException("punch_preparation_timeout");
    }

    private static void copyFixture(Context context, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("fixture_parent_create_failed");
        }
        InputStream in = context.getResources().openRawResource(R.raw.recovery_face_fixture);
        FileOutputStream out = new FileOutputStream(destination, false);
        try {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
        } finally {
            try { in.close(); } finally { out.close(); }
        }
        if (!destination.isFile() || destination.length() <= 0L) {
            throw new IllegalStateException("fixture_copy_failed");
        }
    }

    private static void requireSessionReady() {
        if (!SessionManager.get().isTokenValid()) throw new IllegalStateException("token_not_valid");
        if (!SessionManager.get().isDeviceRegistered()) throw new IllegalStateException("device_not_registered");
    }
}
