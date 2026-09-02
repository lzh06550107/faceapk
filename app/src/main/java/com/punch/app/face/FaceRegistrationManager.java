package com.punch.app.face;

import android.content.Context;
import android.util.Log;

import com.punch.app.db.DatabaseHelper;
import com.punch.app.model.Employee;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FaceRegistrationManager {
    private static final String TAG = "FaceRegManager";
    private static final String FAIL_MSG_FACE_IMAGE_DOWNLOAD_FAILED = "人脸图片下载失败";
    private static final String FAIL_MSG_FACE_IMAGE_INVALID = "人脸图片不合格";
    private static final String FAIL_MSG_FACE_IMAGE_URL_EMPTY = "人脸图片URL为空";

    private static FaceRegistrationManager instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(
            ParallelDownloadBatchRunner.DEFAULT_DOWNLOAD_PARALLELISM);

    public static FaceRegistrationManager get() {
        if (instance == null) {
            instance = new FaceRegistrationManager();
        }
        return instance;
    }

    public void registerPending(Context ctx, Callback callback) {
        executor.execute(() -> {
            List<RegistrationResult> results = registerEmployeesInternal(
                    ctx,
                    DatabaseHelper.get(ctx).getUnregisteredFaces(),
                    true
            );
            int ok = countSucceeded(results);
            int fail = countFailed(results);
            Log.i(TAG, "registerPending done: ok=" + ok + " fail=" + fail);
            if (callback != null) {
                callback.onDone(ok, fail);
            }
        });
    }

    public void registerEmployees(Context ctx,
                                  List<Employee> employees,
                                  DetailedCallback callback) {
        executor.execute(() -> {
            List<RegistrationResult> results = registerEmployeesInternal(ctx, employees, true);
            if (callback != null) {
                callback.onDone(results);
            }
        });
    }

    public void validateEmployeesForRebuild(Context ctx,
                                            List<Employee> employees,
                                            DetailedCallback callback) {
        executor.execute(() -> {
            List<RegistrationResult> results = registerEmployeesInternal(ctx, employees, false);
            if (callback != null) {
                callback.onDone(results);
            }
        });
    }

    public void refreshEmployee(Context ctx, Employee emp, Callback callback) {
        executor.execute(() -> {
            FaceManager.get().removeFace(emp.id);
            DatabaseHelper.get(ctx).deleteFaceFeature(emp.id);
            DatabaseHelper.get(ctx).updateFaceRegistration(emp.id, null, false);
            RegistrationResult result = registerSingle(ctx, emp, true);
            boolean success = result.success;
            if (callback != null) {
                callback.onDone(success ? 1 : 0, success ? 0 : 1);
            }
        });
    }

    private List<RegistrationResult> registerEmployeesInternal(Context ctx,
                                                              List<Employee> employees,
                                                              boolean addToRuntimeLibrary) {
        List<Employee> validEmployees = new ArrayList<>();
        if (employees == null) {
            return new ArrayList<>();
        }
        for (Employee emp : employees) {
            if (emp == null || emp.id == null || emp.id.trim().isEmpty()) {
                continue;
            }
            validEmployees.add(emp);
        }
        if (validEmployees.isEmpty()) {
            return new ArrayList<>();
        }

        long startedAt = System.currentTimeMillis();
        Log.i(TAG, "Face batch begin: employees=" + validEmployees.size()
                + " downloadParallelism=" + ParallelDownloadBatchRunner.DEFAULT_DOWNLOAD_PARALLELISM
                + " faceSdkSerial=true");
        long[] batchMetrics = new long[2];
        List<RegistrationResult> results = ParallelDownloadBatchRunner.run(
                validEmployees,
                downloadExecutor,
                emp -> downloadFaceImage(ctx, emp),
                (emp, downloadResult) -> registerDownloadedFace(
                        ctx, emp, downloadResult, addToRuntimeLibrary),
                (emp, error) -> {
                    Log.e(TAG, "Face image preparation crashed: empId=" + emp.id, error);
                    return RegistrationResult.fail(emp.id, FAIL_MSG_FACE_IMAGE_DOWNLOAD_FAILED);
                },
                (downloadWaitMs, processMs) -> {
                    batchMetrics[0] = downloadWaitMs;
                    batchMetrics[1] = processMs;
                }
        );
        Log.i(TAG, "Face batch end: employees=" + validEmployees.size()
                + " elapsedMs=" + (System.currentTimeMillis() - startedAt)
                + " download_wait_ms=" + batchMetrics[0]
                + " face_process_ms=" + batchMetrics[1]);
        return results;
    }

    private RegistrationResult registerSingle(Context ctx, Employee emp, boolean addToRuntimeLibrary) {
        FaceFileManager.DownloadResult downloadResult = downloadFaceImage(ctx, emp);
        return registerDownloadedFace(ctx, emp, downloadResult, addToRuntimeLibrary);
    }

    private FaceFileManager.DownloadResult downloadFaceImage(Context ctx, Employee emp) {
        if (emp.faceImageUrl == null || emp.faceImageUrl.trim().isEmpty()) {
            Log.w(TAG, "Face image url is empty: empId=" + emp.id);
            return FaceFileManager.DownloadResult.fail(FAIL_MSG_FACE_IMAGE_URL_EMPTY);
        }
        return FaceFileManager.downloadAndVerify(
                ctx,
                emp.id,
                emp.faceImageUrl,
                emp.faceImageSha256
        );
    }

    private RegistrationResult registerDownloadedFace(Context ctx,
                                                      Employee emp,
                                                      FaceFileManager.DownloadResult downloadResult,
                                                      boolean addToRuntimeLibrary) {
        if (downloadResult == null || !downloadResult.success) {
            String failMsg = downloadResult == null
                    || downloadResult.failMsg == null
                    || downloadResult.failMsg.trim().isEmpty()
                    ? FAIL_MSG_FACE_IMAGE_DOWNLOAD_FAILED
                    : downloadResult.failMsg.trim();
            Log.w(TAG, "Download/verify failed: empId=" + emp.id + " reason=" + failMsg);
            return RegistrationResult.fail(emp.id, failMsg);
        }

        FaceManager.RegisterResult result = addToRuntimeLibrary
                ? FaceManager.get().registerFace(ctx, emp, downloadResult.path)
                : FaceManager.get().validateFaceImage(ctx, emp, downloadResult.path);
        if (result.success) {
            DatabaseHelper.get(ctx).updateFaceRegistration(emp.id, result.localFaceId, true);
            return RegistrationResult.ok(emp.id);
        }

        Log.w(TAG, "SDK register failed " + emp.id + ": " + result.errorMsg);
        String failMsg = result.errorMsg != null && !result.errorMsg.trim().isEmpty()
                ? result.errorMsg.trim()
                : FAIL_MSG_FACE_IMAGE_INVALID;
        return RegistrationResult.fail(emp.id, failMsg);
    }

    private int countSucceeded(List<RegistrationResult> results) {
        int count = 0;
        for (RegistrationResult result : results) {
            if (result != null && result.success) {
                count++;
            }
        }
        return count;
    }

    private int countFailed(List<RegistrationResult> results) {
        int count = 0;
        for (RegistrationResult result : results) {
            if (result != null && !result.success) {
                count++;
            }
        }
        return count;
    }

    public interface Callback {
        void onDone(int succeeded, int failed);
    }

    public interface DetailedCallback {
        void onDone(List<RegistrationResult> results);
    }

    public static final class RegistrationResult {
        public final String empId;
        public final boolean success;
        public final String failMsg;

        private RegistrationResult(String empId, boolean success, String failMsg) {
            this.empId = empId;
            this.success = success;
            this.failMsg = failMsg == null ? "" : failMsg;
        }

        public static RegistrationResult ok(String empId) {
            return new RegistrationResult(empId, true, "");
        }

        public static RegistrationResult fail(String empId, String failMsg) {
            return new RegistrationResult(empId, false, failMsg);
        }
    }
}
