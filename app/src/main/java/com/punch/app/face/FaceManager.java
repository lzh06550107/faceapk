package com.punch.app.face;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.util.Log;

import com.baidu.idl.main.facesdk.FaceInfo;
import com.baidu.idl.main.facesdk.FaceLive;
import com.baidu.idl.main.facesdk.FaceMouthMask;
import com.baidu.idl.main.facesdk.FaceSearch;
import com.baidu.idl.main.facesdk.model.BDFaceDetectListConf;
import com.baidu.idl.main.facesdk.model.BDFaceImageInstance;
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon;
import com.baidu.idl.main.facesdk.model.BDFaceSDKConfig;
import com.baidu.idl.main.facesdk.model.Feature;
import com.punch.app.R;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.model.Employee;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.SessionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FaceManager {
    private static final String TAG = "FaceManager";
    private static final float MASK_SCORE_THRESHOLD = 0.5f;
    private static final float SAFE_MATCH_THRESHOLD_FLOOR = 0.75f;
    private static final float SAFE_MATCH_SCORE_GAP = 0.03f;
    public static final int FEATURE_CACHE_SCHEMA_VERSION = 1;
    public static final String ERROR_INVALID_FACE_IMAGE = "\u4eba\u8138\u56fe\u7247\u4e0d\u5408\u683c";
    public static final String ERROR_NO_FACE_DETECTED = "\u672a\u68c0\u6d4b\u5230\u4eba\u8138";
    public static final String ERROR_LIVENESS_MODEL_NOT_READY = "\u6d3b\u4f53\u68c0\u6d4b\u6a21\u578b\u672a\u5c31\u7eea";
    public static final String ERROR_LIVENESS_CHECK_FAILED = "\u6d3b\u4f53\u68c0\u6d4b\u5931\u8d25";
    public static final String ERROR_FACE_SDK_NOT_READY = "\u4eba\u8138\u5f15\u64ce\u672a\u5c31\u7eea";
    public static final String ERROR_FACE_SEARCH_NOT_READY = "\u4eba\u8138\u641c\u7d22\u6a21\u5757\u672a\u5c31\u7eea";
    public static final String ERROR_MASK_DETECTED = "\u68c0\u6d4b\u5230\u53e3\u7f69\uff0c\u8bf7\u6458\u4e0b\u540e\u91cd\u8bd5";
    public static final String ERROR_NO_MATCHING_FACE = "\u672a\u627e\u5230\u5339\u914d\u4eba\u5458";
    public static final String ERROR_MATCH_AMBIGUOUS = "\u8bc6\u522b\u7ed3\u679c\u4e0d\u591f\u660e\u786e\uff0c\u8bf7\u91cd\u8bd5";
    public static final String ERROR_FACE_ID_MAPPING_MISSING = "\u4eba\u8138\u7d22\u5f15\u6620\u5c04\u4e22\u5931";
    public static final String ERROR_FACE_SEARCH_WRITE_FAILED = "\u4eba\u8138\u5e93\u66f4\u65b0\u5931\u8d25";

    private static FaceManager instance;

    public static FaceManager get() {
        if (instance == null) {
            instance = new FaceManager();
        }
        return instance;
    }

    private boolean initialized = false;
    private Context appContext;

    private final Map<String, Integer> empToIntId = new HashMap<>();
    private final Map<Integer, String> intToEmpId = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object faceLibraryLock = new Object();
    private final FaceSdkOperationGuard sdkOperationGuard = FaceSdkOperationGuard.shared();
    private final FaceLibraryRuntimeState runtimeState = new FaceLibraryRuntimeState();
    private volatile int loadedFaceCount;

    public void init(Context context, String licenseFileName, final InitCallback callback) {
        appContext = context.getApplicationContext();
        FaceSDKManager.getInstance().initModel(appContext, buildSdkConfig(), new SdkInitListener() {
            @Override
            public void initStart() {
                Log.i(TAG, "SDK init start");
            }

            @Override
            public void initLicenseSuccess() {
                Log.i(TAG, "SDK license ok");
            }

            @Override
            public void initLicenseFail(int code, String msg) {
                Log.e(TAG, "SDK license failed code=" + code + " msg=" + msg);
                callback.onError(code, "License init failed: " + msg);
            }

            @Override
            public void initModelSuccess() {
                Log.i(TAG, "SDK initModel success");
                FaceSDKManager.initModelSuccess = true;
                initialized = true;
                callback.onSuccess();
            }

            @Override
            public void initModelFail(int code, String msg) {
                Log.e(TAG, "SDK model failed code=" + code + " msg=" + msg);
                callback.onError(code, "Model init failed: " + msg);
            }
        });
    }

    public void refreshRuntimeConfig() {
        if (!initialized) {
            return;
        }
        withSdkOperation("refresh-runtime-config", () -> {
            if (FaceSDKManager.getInstance().getFaceDetectPerson() != null) {
                FaceSDKManager.getInstance().getFaceDetectPerson().loadConfig(buildSdkConfig());
            }
            return null;
        });
    }

    public void rebuildFaceLibrary(Context context) {
        executor.execute(() -> rebuildFaceLibrarySync(context));
    }

    public boolean canReuseRuntimeFaceLibrary(Context context) {
        if (!initialized || loadedFaceCount <= 0) {
            return false;
        }
        Context targetContext = context == null ? appContext : context.getApplicationContext();
        if (targetContext == null) {
            return false;
        }
        List<Employee> employees = DatabaseHelper.get(targetContext).getAllActiveEmployees();
        return runtimeState.canReuse(
                buildRuntimeLibraryScope(),
                FaceLibraryRuntimeState.fingerprint(employees)
        );
    }

    public boolean rebuildFaceLibrarySync(Context context) {
        return withSdkOperation("rebuild-face-library",
                () -> rebuildFaceLibrarySyncExclusive(context));
    }

    private boolean rebuildFaceLibrarySyncExclusive(Context context) {
        if (!initialized) {
            loadedFaceCount = 0;
            return false;
        }

        FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
        if (faceSearch == null) {
            loadedFaceCount = 0;
            return false;
        }

        DatabaseHelper db = DatabaseHelper.get(context);
        List<Employee> employees = db.getAllActiveEmployees();
        AppLogger.i(TAG, "Face rebuild begin: employees=" + employees.size()
                + " thread=" + Thread.currentThread().getName());
        List<FaceLibraryEntry> entries = new ArrayList<>();
        int featureCacheHits = 0;
        int featureExtractions = 0;
        for (Employee emp : employees) {
            if (emp.faceRegistered == 1 && emp.localFaceId != null && emp.faceImageUrl != null) {
                String imagePath = FaceFileManager.getFaceImagePath(context, emp.id);
                AppLogger.i(TAG, "Face rebuild employee begin: empId=" + safeEmpId(emp.id));
                byte[] feature = db.getReusableFaceFeature(
                        emp.id,
                        emp.faceVersion,
                        emp.faceImageSha256,
                        FEATURE_CACHE_SCHEMA_VERSION
                );
                boolean cacheHit = feature != null;
                if (cacheHit) {
                    featureCacheHits++;
                } else {
                    featureExtractions++;
                    feature = extractFeatureFromFile(imagePath, emp.id);
                    if (feature != null) {
                        db.saveFaceFeature(
                                emp.id,
                                emp.faceVersion,
                                emp.faceImageSha256,
                                FEATURE_CACHE_SCHEMA_VERSION,
                                feature
                        );
                    }
                }
                AppLogger.i(TAG, "Face rebuild employee end: empId=" + safeEmpId(emp.id)
                        + " feature=" + (feature == null ? "invalid" : (cacheHit ? "cache" : "extracted")));
                if (feature != null) {
                    try {
                        entries.add(new FaceLibraryEntry(
                                emp.id,
                                db.getOrCreateFaceSdkId(emp.id),
                                feature));
                    } catch (RuntimeException e) {
                        AppLogger.e(TAG, "Face SDK ID allocation failed: empId="
                                + safeEmpId(emp.id) + " error=" + e.getMessage());
                    }
                }
            }
        }

        int loadedCount = 0;
        synchronized (faceLibraryLock) {
            AppLogger.i(TAG, "Face rebuild featureClear begin: candidates=" + entries.size());
            int clearCode = faceSearch.featureClear();
            AppLogger.i(TAG, "Face rebuild featureClear end: code=" + clearCode);
            empToIntId.clear();
            intToEmpId.clear();
            for (FaceLibraryEntry entry : entries) {
                AppLogger.i(TAG, "Face rebuild push begin: empId=" + safeEmpId(entry.empId)
                        + " sdkId=" + entry.sdkId);
                int pushCode = faceSearch.pushPersonById(entry.sdkId, entry.feature);
                AppLogger.i(TAG, "Face rebuild push end: empId=" + safeEmpId(entry.empId)
                        + " sdkId=" + entry.sdkId + " code=" + pushCode);
                if (pushCode != 0) {
                    AppLogger.e(TAG, "Face rebuild push failed: empId=" + safeEmpId(entry.empId)
                            + " sdkId=" + entry.sdkId + " code=" + pushCode);
                    continue;
                }
                empToIntId.put(entry.empId, entry.sdkId);
                intToEmpId.put(entry.sdkId, entry.empId);
                loadedCount++;
            }
            loadedFaceCount = loadedCount;
            Log.i(TAG, "rebuildFaceLibrary: " + loadedCount + " faces loaded");
        }
        updateRuntimeLibraryState(employees, loadedCount, entries.size());
        AppLogger.i(TAG, "Face rebuild end: loaded=" + loadedCount
                + " candidates=" + employees.size()
                + " cacheHits=" + featureCacheHits
                + " extracted=" + featureExtractions);
        return true;
    }

    public RegisterResult registerFace(Context context, String empId, String imageFilePath) {
        return withSdkOperation("register-face:" + safeEmpId(empId),
                () -> registerFaceInternal(
                        context, empId, imageFilePath, true, 0, "", false));
    }

    public RegisterResult registerFace(Context context, Employee employee, String imageFilePath) {
        if (employee == null) {
            return RegisterResult.fail(ERROR_INVALID_FACE_IMAGE);
        }
        return registerFace(
                context, employee.id, imageFilePath, employee.faceVersion, employee.faceImageSha256);
    }

    public RegisterResult registerFace(Context context,
                                       String empId,
                                       String imageFilePath,
                                       int faceVersion,
                                       String imageSha256) {
        return withSdkOperation("register-face:" + safeEmpId(empId),
                () -> registerFaceInternal(
                        context, empId, imageFilePath, true, faceVersion, imageSha256, true));
    }

    public RegisterResult validateFaceImage(Context context, String empId, String imageFilePath) {
        return withSdkOperation("validate-face:" + safeEmpId(empId),
                () -> registerFaceInternal(
                        context, empId, imageFilePath, false, 0, "", false));
    }

    public RegisterResult validateFaceImage(Context context, Employee employee, String imageFilePath) {
        if (employee == null) {
            return RegisterResult.fail(ERROR_INVALID_FACE_IMAGE);
        }
        return validateFaceImage(
                context, employee.id, imageFilePath, employee.faceVersion, employee.faceImageSha256);
    }

    public RegisterResult validateFaceImage(Context context,
                                            String empId,
                                            String imageFilePath,
                                            int faceVersion,
                                            String imageSha256) {
        return withSdkOperation("validate-face:" + safeEmpId(empId),
                () -> registerFaceInternal(
                        context, empId, imageFilePath, false, faceVersion, imageSha256, true));
    }

    private RegisterResult registerFaceInternal(Context context,
                                                  String empId,
                                                  String imageFilePath,
                                                  boolean addToRuntimeLibrary,
                                                  int faceVersion,
                                                  String imageSha256,
                                                  boolean persistFeature) {
        if (!initialized) {
            return RegisterResult.fail(ERROR_FACE_SDK_NOT_READY);
        }

        FaceSearch faceSearch = addToRuntimeLibrary ? FaceSDKManager.getInstance().getFaceSearch() : null;
        if (addToRuntimeLibrary && faceSearch == null) {
            return RegisterResult.fail(ERROR_FACE_SEARCH_NOT_READY);
        }

        byte[] feature = extractFeatureFromFile(imageFilePath, empId);
        if (feature == null) {
            return RegisterResult.fail(ERROR_INVALID_FACE_IMAGE);
        }

        DatabaseHelper db = DatabaseHelper.get(context);
        if (persistFeature) {
            db.saveFaceFeature(
                    empId,
                    faceVersion,
                    imageSha256,
                    FEATURE_CACHE_SCHEMA_VERSION,
                    feature
            );
        }

        if (addToRuntimeLibrary) {
                final int intId;
            try {
                intId = db.getOrCreateFaceSdkId(empId);
            } catch (RuntimeException e) {
                AppLogger.e(TAG, "Face SDK ID allocation failed: empId="
                        + safeEmpId(empId) + " error=" + e.getMessage());
                return RegisterResult.fail(ERROR_FACE_ID_MAPPING_MISSING);
            }
            synchronized (faceLibraryLock) {
                boolean wasLoaded = empToIntId.containsKey(empId);
                if (wasLoaded) {
                    faceSearch.delPersonById(intId);
                }
                int pushCode = faceSearch.pushPersonById(intId, feature);
                if (pushCode != 0) {
                    empToIntId.remove(empId);
                    intToEmpId.remove(intId);
                    if (wasLoaded && loadedFaceCount > 0) {
                        loadedFaceCount--;
                    }
                    AppLogger.e(TAG, "Face register push failed: empId=" + safeEmpId(empId)
                            + " intId=" + intId + " code=" + pushCode);
                    return RegisterResult.fail(ERROR_FACE_SEARCH_WRITE_FAILED);
                }
                empToIntId.put(empId, intId);
                intToEmpId.put(intId, empId);
                if (!wasLoaded) {
                    loadedFaceCount++;
                }
                runtimeState.invalidate();
                AppLogger.i(TAG, "Face registered: empId=" + empId + " intId=" + intId);
            }
        } else {
            AppLogger.i(TAG, "Face image validated and cached: empId=" + empId);
        }

        String localFaceId = "FACE_" + empId;
        return RegisterResult.ok(localFaceId);
    }

    public void removeFace(String empId) {
        withSdkOperation("remove-face:" + safeEmpId(empId), () -> {
            removeFaceExclusive(empId);
            return null;
        });
    }

    private void removeFaceExclusive(String empId) {
        synchronized (faceLibraryLock) {
            Integer intId = empToIntId.remove(empId);
            if (intId != null) {
                intToEmpId.remove(intId);
                FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
                if (faceSearch != null) {
                    int deleteCode = faceSearch.delPersonById(intId);
                    if (deleteCode != 0) {
                        AppLogger.w(TAG, "Face remove failed: empId=" + safeEmpId(empId)
                                + " intId=" + intId + " code=" + deleteCode);
                    }
                }
                if (loadedFaceCount > 0) {
                    loadedFaceCount--;
                }
                runtimeState.invalidate();
            }
        }
    }

    public RecognizeResult recognizeFromBitmap(Bitmap bmp) {
        return withSdkOperation("recognize-bitmap", () -> recognizeFromBitmapExclusive(bmp));
    }

    private RecognizeResult recognizeFromBitmapExclusive(Bitmap bmp) {
        if (!initialized) {
            return RecognizeResult.fail(ERROR_FACE_SDK_NOT_READY);
        }

        FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
        if (faceSearch == null) {
            return RecognizeResult.fail(ERROR_FACE_SEARCH_NOT_READY);
        }

        BDFaceImageInstance inst = new BDFaceImageInstance(bmp);
        try {
            FaceInfo[] faceInfos = FaceSDKManager.getInstance()
                    .getFaceDetectPerson()
                    .detect(BDFaceSDKCommon.DetectType.DETECT_VIS, inst);
            if (faceInfos == null || faceInfos.length == 0) {
                return RecognizeResult.fail(ERROR_NO_FACE_DETECTED);
            }

            RecognizeResult checkResult = runPreChecks(inst, faceInfos[0]);
            if (checkResult != null) {
                return checkResult;
            }

            byte[] feature = new byte[512];
            FaceSDKManager.getInstance().getFacePersonFeature()
                    .feature(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,
                            inst, faceInfos[0].landmarks, feature);
            return doSearch(feature, faceInfos[0]);
        } finally {
            inst.destory();
        }
    }

    public RecognizeResult recognizeFromNv21(byte[] nv21, int width, int height, int angle, int mirror) {
        return withSdkOperation("recognize-nv21",
                () -> recognizeFromNv21Exclusive(nv21, width, height, angle, mirror));
    }

    private RecognizeResult recognizeFromNv21Exclusive(byte[] nv21, int width, int height, int angle, int mirror) {
        if (!initialized) {
            return RecognizeResult.fail(ERROR_FACE_SDK_NOT_READY);
        }

        FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
        if (faceSearch == null) {
            return RecognizeResult.fail(ERROR_FACE_SEARCH_NOT_READY);
        }

        BDFaceImageInstance inst = new BDFaceImageInstance(
                nv21,
                height,
                width,
                BDFaceSDKCommon.BDFaceImageType.BDFACE_IMAGE_TYPE_YUV_NV21,
                angle,
                mirror
        );
        try {
            BDFaceDetectListConf conf = new BDFaceDetectListConf();
            conf.usingDetect = true;
            FaceInfo[] faceInfos = FaceSDKManager.getInstance()
                    .getFaceDetectPerson()
                    .detect(BDFaceSDKCommon.DetectType.DETECT_VIS,
                            BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,
                            inst, null, conf);
            if (faceInfos == null || faceInfos.length == 0) {
                return RecognizeResult.fail(ERROR_NO_FACE_DETECTED);
            }

            RecognizeResult checkResult = runPreChecks(inst, faceInfos[0]);
            if (checkResult != null) {
                return checkResult;
            }

            byte[] feature = new byte[512];
            FaceSDKManager.getInstance().getFacePersonFeature()
                    .feature(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,
                            inst, faceInfos[0].landmarks, feature);
            return doSearch(feature, faceInfos[0]);
        } finally {
            inst.destory();
        }
    }

    private RecognizeResult runPreChecks(BDFaceImageInstance inst, FaceInfo faceInfo) {
        if (appContext != null
                && appContext.getResources().getBoolean(R.bool.face_punch_stress_bypass_prechecks)) {
            return null;
        }
        SessionManager session = SessionManager.get();

        if (session.isLivenessCheck()) {
            FaceLive faceLive = FaceSDKManager.getInstance().getFaceLive();
            if (faceLive == null) {
                return RecognizeResult.fail(ERROR_LIVENESS_MODEL_NOT_READY);
            }
            float threshold = session.getLivenessThreshold();
            float score = faceLive.silentLive(
                    BDFaceSDKCommon.LiveType.BDFACE_SILENT_LIVE_TYPE_RGB,
                    inst,
                    faceInfo.landmarks
            );
            String livenessDetail = buildLivenessDetail(faceInfo, score, threshold);
            AppLogger.d(TAG, "RGB liveness check: " + livenessDetail);
            if (score < threshold) {
                return RecognizeResult.fail(ERROR_LIVENESS_CHECK_FAILED, livenessDetail);
            }
        }

        FaceMouthMask faceMouthMask = FaceSDKManager.getInstance().getFaceMouthMask();
        if (session.isMaskDetectEnabled() && faceMouthMask != null) {
            float[] maskScores = faceMouthMask.checkMask(inst, new FaceInfo[]{faceInfo});
            float maskScore = (maskScores != null && maskScores.length > 0) ? maskScores[0] : 0f;
            if (maskScore >= MASK_SCORE_THRESHOLD) {
                return RecognizeResult.fail(ERROR_MASK_DETECTED);
            }
        }

        return null;
    }

    private RecognizeResult doSearch(byte[] feature, FaceInfo faceInfo) {
        FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
        if (faceSearch == null) {
            return RecognizeResult.fail(ERROR_FACE_SEARCH_NOT_READY);
        }
        float configuredThreshold = SessionManager.get().getMatchThreshold();
        float effectiveThreshold = Math.max(configuredThreshold, SAFE_MATCH_THRESHOLD_FLOOR);
        synchronized (faceLibraryLock) {
            List<? extends Feature> results = faceSearch.search(
                    BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,
                    effectiveThreshold,
                    2,
                    feature
            );
            if (results == null || results.isEmpty()) {
                AppLogger.d(TAG, "Face search miss: configuredThreshold=" + configuredThreshold
                        + ", effectiveThreshold=" + effectiveThreshold);
                return RecognizeResult.fail(ERROR_NO_MATCHING_FACE);
            }

            Feature best = results.get(0);
            float bestScore = best.getScore();
            if (bestScore < effectiveThreshold * 100) {
                AppLogger.d(TAG, "Face search below threshold: bestScore=" + bestScore
                        + ", configuredThreshold=" + configuredThreshold
                        + ", effectiveThreshold=" + effectiveThreshold);
                return RecognizeResult.fail(ERROR_NO_MATCHING_FACE);
            }

            float secondScore = 0f;
            if (results.size() > 1 && results.get(1) != null) {
                secondScore = results.get(1).getScore();
            }
            float scoreGap = bestScore - secondScore;
            if (secondScore > 0f && scoreGap < SAFE_MATCH_SCORE_GAP) {
                AppLogger.w(TAG, "Reject ambiguous face match: bestScore=" + bestScore
                        + ", secondScore=" + secondScore
                        + ", scoreGap=" + scoreGap
                        + ", configuredThreshold=" + configuredThreshold
                        + ", effectiveThreshold=" + effectiveThreshold);
                return RecognizeResult.fail(ERROR_MATCH_AMBIGUOUS);
            }

            String empId = intToEmpId.get(best.getId());
            if (empId == null) {
                return RecognizeResult.fail(ERROR_FACE_ID_MAPPING_MISSING);
            }
            AppLogger.d(TAG, "Face search matched: empId=" + empId
                    + ", bestScore=" + bestScore
                    + ", secondScore=" + secondScore
                    + ", scoreGap=" + scoreGap
                    + ", configuredThreshold=" + configuredThreshold
                    + ", effectiveThreshold=" + effectiveThreshold);
            return RecognizeResult.ok(empId, bestScore, buildFaceBounds(faceInfo));
        }
    }

    private RectF buildFaceBounds(FaceInfo faceInfo) {
        if (faceInfo == null || faceInfo.width <= 0 || faceInfo.height <= 0) {
            return null;
        }
        float halfWidth = faceInfo.width / 2f;
        float halfHeight = faceInfo.height / 2f;
        return new RectF(
                faceInfo.centerX - halfWidth,
                faceInfo.centerY - halfHeight,
                faceInfo.centerX + halfWidth,
                faceInfo.centerY + halfHeight
        );
    }

    private BDFaceSDKConfig buildSdkConfig() {
        BDFaceSDKConfig sdkConfig = new BDFaceSDKConfig();
        sdkConfig.minFaceSize = SessionManager.get().getMinFaceSizeForRecognitionDistance();

        float faceThreshold = SessionManager.get().getFaceThreshold();
        sdkConfig.notRGBFaceThreshold = faceThreshold;
        sdkConfig.notNIRFaceThreshold = faceThreshold;

        sdkConfig.isCropFace = true;
        sdkConfig.isAttribute = false;
        sdkConfig.isBestImage = false;
        return sdkConfig;
    }

    private String buildLivenessDetail(FaceInfo faceInfo, float score, float threshold) {
        if (faceInfo == null) {
            return "score=" + score + ", threshold=" + threshold + ", faceInfo=null";
        }
        return "score=" + score
                + ", threshold=" + threshold
                + ", faceWidth=" + faceInfo.width
                + ", faceHeight=" + faceInfo.height
                + ", yaw=" + faceInfo.yaw
                + ", roll=" + faceInfo.roll
                + ", pitch=" + faceInfo.pitch
                + ", blur=" + faceInfo.bluriness
                + ", illum=" + faceInfo.illum;
    }


    private byte[] extractFeatureFromFile(String imagePath, String empId) {
        Bitmap bmp = BitmapFactory.decodeFile(imagePath);
        if (bmp == null) {
            AppLogger.w(TAG, "Face image decode failed: empId=" + safeEmpId(empId)
                    + " path=" + imagePath);
            return null;
        }

        BDFaceImageInstance inst = new BDFaceImageInstance(bmp);
        bmp.recycle();
        try {
            AppLogger.i(TAG, "Face file detect begin: empId=" + safeEmpId(empId)
                    + " thread=" + Thread.currentThread().getName());
            FaceInfo[] faceInfos = FaceSDKManager.getInstance()
                    .getFaceDetectPerson()
                    .detect(BDFaceSDKCommon.DetectType.DETECT_VIS, inst);
            AppLogger.i(TAG, "Face file detect end: empId=" + safeEmpId(empId)
                    + " faces=" + (faceInfos == null ? -1 : faceInfos.length));
            if (faceInfos == null || faceInfos.length == 0) {
                AppLogger.w(TAG, "No face detected in registered image: empId=" + safeEmpId(empId)
                        + " path=" + imagePath);
                return null;
            }

            byte[] feature = new byte[512];
            AppLogger.i(TAG, "Face file feature begin: empId=" + safeEmpId(empId));
            float size = FaceSDKManager.getInstance().getFacePersonFeature()
                    .feature(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,
                            inst, faceInfos[0].landmarks, feature);
            AppLogger.i(TAG, "Face file feature end: empId=" + safeEmpId(empId)
                    + " size=" + size);
            if (size <= 0) {
                AppLogger.w(TAG, "Face feature extraction failed: empId=" + safeEmpId(empId)
                        + " path=" + imagePath);
                return null;
            }
            return feature;
        } finally {
            inst.destory();
        }
    }

    private <T> T withSdkOperation(String operation, FaceSdkOperationGuard.Operation<T> action) {
        long queuedAtNanos = System.nanoTime();
        return sdkOperationGuard.call(() -> {
            long startedAtNanos = System.nanoTime();
            long waitMs = (startedAtNanos - queuedAtNanos) / 1_000_000L;
            String threadName = Thread.currentThread().getName();
            AppLogger.i(TAG, "Face SDK op begin: op=" + operation
                    + " thread=" + threadName + " waitMs=" + waitMs);
            try {
                return action.run();
            } finally {
                long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
                AppLogger.i(TAG, "Face SDK op end: op=" + operation
                        + " thread=" + threadName + " elapsedMs=" + elapsedMs);
            }
        });
    }

    private String safeEmpId(String empId) {
        return empId == null || empId.trim().isEmpty() ? "-" : empId.trim();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getLoadedFaceCount() {
        return loadedFaceCount;
    }

    private void updateRuntimeLibraryState(List<Employee> employees, int loadedCount, int candidateCount) {
        if (loadedCount > 0 && loadedCount == candidateCount) {
            runtimeState.markReady(
                    buildRuntimeLibraryScope(),
                    FaceLibraryRuntimeState.fingerprint(employees)
            );
            return;
        }
        runtimeState.invalidate();
    }

    private String buildRuntimeLibraryScope() {
        SessionManager session = SessionManager.get();
        return session.getBaseUrl()
                + "|company=" + session.getCompanyId()
                + "|device=" + session.getDeviceId();
    }

    private static final class FaceLibraryEntry {
        final String empId;
        final int sdkId;
        final byte[] feature;

        FaceLibraryEntry(String empId, int sdkId, byte[] feature) {
            this.empId = empId;
            this.sdkId = sdkId;
            this.feature = feature;
        }
    }

    public static class RegisterResult {
        public final boolean success;
        public final String localFaceId;
        public final String errorMsg;

        private RegisterResult(boolean success, String localFaceId, String errorMsg) {
            this.success = success;
            this.localFaceId = localFaceId;
            this.errorMsg = errorMsg;
        }

        public static RegisterResult ok(String localFaceId) {
            return new RegisterResult(true, localFaceId, null);
        }

        public static RegisterResult fail(String errorMsg) {
            return new RegisterResult(false, null, errorMsg);
        }
    }

    public static class RecognizeResult {
        public final boolean matched;
        public final String empId;
        public final float score;
        public final String errorMsg;
        public final RectF faceBounds;
        public final String debugDetail;

        private RecognizeResult(boolean matched,
                                String empId,
                                float score,
                                String errorMsg,
                                RectF faceBounds,
                                String debugDetail) {
            this.matched = matched;
            this.empId = empId;
            this.score = score;
            this.errorMsg = errorMsg;
            this.faceBounds = faceBounds;
            this.debugDetail = debugDetail == null ? "" : debugDetail;
        }

        public static RecognizeResult ok(String empId, float score) {
            return ok(empId, score, null);
        }

        public static RecognizeResult ok(String empId, float score, RectF faceBounds) {
            return new RecognizeResult(true, empId, score, null, faceBounds, "");
        }

        public static RecognizeResult fail(String errorMsg) {
            return fail(errorMsg, "");
        }

        public static RecognizeResult fail(String errorMsg, String debugDetail) {
            return new RecognizeResult(false, null, 0, errorMsg, null, debugDetail);
        }
    }

    public interface InitCallback {
        void onSuccess();

        void onError(int code, String msg);
    }
}
