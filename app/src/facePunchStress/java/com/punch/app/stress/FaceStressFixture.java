package com.punch.app.stress;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.punch.app.R;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.face.FaceFileManager;
import com.punch.app.face.FaceManager;
import com.punch.app.model.Employee;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * Test-only face fixture used by the facePunchStress build.
 *
 * It prefers an already registered local face. If the device has no usable
 * business face data, it falls back to a public-domain portrait bundled only
 * in the facePunchStress source set and registers the reserved STRESS_FACE_V23
 * identity in the runtime FaceSearch library. No employee registration state
 * is changed. close() removes only test-owned runtime/database/cache artifacts.
 */
public final class FaceStressFixture implements AutoCloseable {
    static final String STRESS_EMP_ID = "STRESS_FACE_V23";
    static final String SOURCE_EXISTING_LOCAL_FACE = "existing_local_face";
    static final String SOURCE_BUNDLED_TEST_FACE = "bundled_test_face";
    private static final String CACHE_FILE_NAME = "stress_face_fixture_v23.jpg";

    public final Employee employee;
    public final String imagePath;
    public final String error;
    public final String fixtureSource;
    public final boolean fixtureRegistered;

    private final Context context;
    private final boolean temporaryRuntimeRegistration;
    private final boolean deleteSdkMappingOnClose;
    private final boolean deleteImageOnClose;
    private boolean closed;

    private FaceStressFixture(Context context,
                              Employee employee,
                              String imagePath,
                              String error,
                              String fixtureSource,
                              boolean fixtureRegistered,
                              boolean temporaryRuntimeRegistration,
                              boolean deleteSdkMappingOnClose,
                              boolean deleteImageOnClose) {
        this.context = context;
        this.employee = employee;
        this.imagePath = imagePath;
        this.error = error == null ? "" : error;
        this.fixtureSource = fixtureSource == null ? "" : fixtureSource;
        this.fixtureRegistered = fixtureRegistered;
        this.temporaryRuntimeRegistration = temporaryRuntimeRegistration;
        this.deleteSdkMappingOnClose = deleteSdkMappingOnClose;
        this.deleteImageOnClose = deleteImageOnClose;
    }

    public static FaceStressFixture prepare(Context context) {
        Context app = context.getApplicationContext();
        List<Employee> employees = DatabaseHelper.get(app).getAllActiveEmployees();

        Employee existing = findExistingRegisteredFace(app, employees);
        if (existing != null) {
            return new FaceStressFixture(
                    app,
                    existing,
                    FaceFileManager.getFaceImagePath(app, existing.id),
                    "",
                    SOURCE_EXISTING_LOCAL_FACE,
                    false,
                    false,
                    false,
                    false);
        }

        return prepareBundledFixture(app);
    }

    private static FaceStressFixture prepareBundledFixture(Context app) {
        File imageFile = new File(app.getCacheDir(), CACHE_FILE_NAME);
        try {
            // STRESS_FACE_V23 is reserved for this test variant. Clean stale
            // artifacts left by a previously interrupted stress process.
            try { FaceManager.get().removeFace(STRESS_EMP_ID); } catch (Throwable ignored) {}
            try { deleteFaceSdkMapping(app, STRESS_EMP_ID); } catch (Throwable ignored) {}
            if (imageFile.exists() && !imageFile.delete()) {
                return failure(app, "stress_fixture_cache_cleanup_failed");
            }

            copyBundledFace(app, imageFile);
            if (!imageFile.isFile() || imageFile.length() <= 0L) {
                safeDelete(imageFile);
                return failure(app, "stress_fixture_copy_failed");
            }

            FaceManager.RegisterResult registered = FaceManager.get().registerFace(
                    app,
                    STRESS_EMP_ID,
                    imageFile.getAbsolutePath());
            if (!registered.success) {
                try { deleteFaceSdkMapping(app, STRESS_EMP_ID); } catch (Throwable ignored) {}
                safeDelete(imageFile);
                String reason = registered.errorMsg == null || registered.errorMsg.trim().isEmpty()
                        ? "stress_fixture_register_failed"
                        : "stress_fixture_register_failed:" + registered.errorMsg.trim();
                return failure(app, reason);
            }

            Employee fixtureEmployee = new Employee();
            fixtureEmployee.id = STRESS_EMP_ID;
            fixtureEmployee.name = "Face Stress Fixture";
            return new FaceStressFixture(
                    app,
                    fixtureEmployee,
                    imageFile.getAbsolutePath(),
                    "",
                    SOURCE_BUNDLED_TEST_FACE,
                    true,
                    true,
                    true,
                    true);
        } catch (Throwable t) {
            try { FaceManager.get().removeFace(STRESS_EMP_ID); } catch (Throwable ignored) {}
            try { deleteFaceSdkMapping(app, STRESS_EMP_ID); } catch (Throwable ignored) {}
            safeDelete(imageFile);
            return failure(app, "stress_fixture_prepare_failed:" + t.getClass().getSimpleName());
        }
    }

    private static void copyBundledFace(Context context, File destination) throws Exception {
        InputStream in = context.getResources().openRawResource(R.raw.stress_face_fixture);
        FileOutputStream out = new FileOutputStream(destination, false);
        try {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            try { in.close(); } finally { out.close(); }
        }
    }

    private static Employee findExistingRegisteredFace(Context context, List<Employee> employees) {
        if (employees == null) return null;
        for (Employee employee : employees) {
            if (employee == null || isBlank(employee.id) || employee.faceRegistered != 1) continue;
            String path = FaceFileManager.getFaceImagePath(context, employee.id);
            if (new File(path).isFile()) return employee;
        }
        return null;
    }

    private static void deleteFaceSdkMapping(Context context, String empId) {
        SQLiteDatabase db = DatabaseHelper.get(context).getWritableDatabase();
        db.delete("face_sdk_ids", "emp_id=?", new String[]{empId});
    }

    private static FaceStressFixture failure(Context context, String error) {
        return new FaceStressFixture(context, null, null, error, "", false,
                false, false, false);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void safeDelete(File file) {
        if (file != null && file.exists()) {
            try { file.delete(); } catch (Throwable ignored) {}
        }
    }

    public boolean isReady() {
        return employee != null && imagePath != null && error.isEmpty();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (!temporaryRuntimeRegistration || employee == null) return;

        try { FaceManager.get().removeFace(employee.id); } catch (Throwable ignored) {}
        if (deleteSdkMappingOnClose) {
            try { deleteFaceSdkMapping(context, employee.id); } catch (Throwable ignored) {}
        }
        if (deleteImageOnClose) {
            safeDelete(new File(imagePath));
        }
    }
}
