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
import com.punch.app.db.DatabaseHelper;
import com.punch.app.model.Employee;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.SessionManager;

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
        if (FaceSDKManager.getInstance().getFaceDetectPerson() != null) {
            FaceSDKManager.getInstance().getFaceDetectPerson().loadConfig(buildSdkConfig());
        }
    }

    public void rebuildFaceLibrary(Context context) {
        executor.execute(() -> {
            if (!initialized) {
                return;
            }

            FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
            if (faceSearch == null) {
                return;
            }

            faceSearch.featureClear();
            empToIntId.clear();
            intToEmpId.clear();

            List<Employee> employees = DatabaseHelper.get(context).getAllActiveEmployees();
            int count = 0;
            for (Employee emp : employees) {
                if (emp.faceRegistered == 1 && emp.localFaceId != null && emp.faceImageUrl != null) {
                    String imagePath = FaceFileManager.getFaceImagePath(context, emp.id);
                    byte[] feature = extractFeatureFromFile(imagePath);
                    if (feature != null) {
                        int intId = toIntId(emp.id);
                        faceSearch.pushPersonById(intId, feature);
                        empToIntId.put(emp.id, intId);
                        intToEmpId.put(intId, emp.id);
                        count++;
                    }
                }
            }
            Log.i(TAG, "rebuildFaceLibrary: " + count + " faces loaded");
        });
    }

    public RegisterResult registerFace(Context context, String empId, String imageFilePath) {
        if (!initialized) {
            return RegisterResult.fail(ERROR_FACE_SDK_NOT_READY);
        }

        FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
        if (faceSearch == null) {
            return RegisterResult.fail(ERROR_FACE_SEARCH_NOT_READY);
        }

        byte[] feature = extractFeatureFromFile(imageFilePath);
        if (feature == null) {
            return RegisterResult.fail(ERROR_INVALID_FACE_IMAGE);
        }

        int intId = toIntId(empId);
        faceSearch.pushPersonById(intId, feature);
        empToIntId.put(empId, intId);
        intToEmpId.put(intId, empId);

        String localFaceId = "FACE_" + empId;
        AppLogger.i(TAG, "Face registered: empId=" + empId + " intId=" + intId);
        return RegisterResult.ok(localFaceId);
    }

    public void removeFace(String empId) {
        Integer intId = empToIntId.remove(empId);
        if (intId != null) {
            intToEmpId.remove(intId);
            FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
            if (faceSearch != null) {
                faceSearch.delPersonById(intId);
            }
        }
    }

    public RecognizeResult recognizeFromBitmap(Bitmap bmp) {
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
                return RecognizeResult.fail(ERROR_LIVENESS_CHECK_FAILED);
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
        float configuredThreshold = SessionManager.get().getMatchThreshold();
        float effectiveThreshold = Math.max(configuredThreshold, SAFE_MATCH_THRESHOLD_FLOOR);
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


    private byte[] extractFeatureFromFile(String imagePath) {
        Bitmap bmp = BitmapFactory.decodeFile(imagePath);
        if (bmp == null) {
            return null;
        }

        BDFaceImageInstance inst = new BDFaceImageInstance(bmp);
        bmp.recycle();
        try {
            FaceInfo[] faceInfos = FaceSDKManager.getInstance()
                    .getFaceDetectPerson()
                    .detect(BDFaceSDKCommon.DetectType.DETECT_VIS, inst);
            if (faceInfos == null || faceInfos.length == 0) {
                return null;
            }

            byte[] feature = new byte[512];
            float size = FaceSDKManager.getInstance().getFacePersonFeature()
                    .feature(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,
                            inst, faceInfos[0].landmarks, feature);
            return size > 0 ? feature : null;
        } finally {
            inst.destory();
        }
    }

    private int toIntId(String empId) {
        try {
            return Integer.parseInt(empId);
        } catch (NumberFormatException e) {
            int hash = empId.hashCode();
            return hash == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(hash);
        }
    }

    public boolean isInitialized() {
        return initialized;
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

        private RecognizeResult(boolean matched, String empId, float score, String errorMsg, RectF faceBounds) {
            this.matched = matched;
            this.empId = empId;
            this.score = score;
            this.errorMsg = errorMsg;
            this.faceBounds = faceBounds;
        }

        public static RecognizeResult ok(String empId, float score) {
            return ok(empId, score, null);
        }

        public static RecognizeResult ok(String empId, float score, RectF faceBounds) {
            return new RecognizeResult(true, empId, score, null, faceBounds);
        }

        public static RecognizeResult fail(String errorMsg) {
            return new RecognizeResult(false, null, 0, errorMsg, null);
        }
    }

    public interface InitCallback {
        void onSuccess();

        void onError(int code, String msg);
    }
}
