package com.punch.app.activation;

import android.content.Context;
import android.util.Log;

import com.punch.app.PunchApplication;
import com.punch.app.network.ApiResult;
import com.punch.app.network.ApiService;
import com.punch.app.network.dto.DeviceDto;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ActivationManager {
    private static final String TAG = "ActivationManager";
    private static final String PREPARE_ERROR_REGISTER_FAILED = "设备注册失败";
    private static final String PREPARE_ERROR_ACTIVATION_CODE_FAILED = "激活码获取失败";

    private static ActivationManager instance;

    public interface PrepareCallback {
        void onComplete(boolean ready, String failureMessage);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ActivationManager() {
    }

    public static ActivationManager get() {
        if (instance == null) {
            instance = new ActivationManager();
        }
        return instance;
    }

    public void prepareActivation(Context context, PrepareCallback callback) {
        executor.execute(() -> {
            Context appContext = context.getApplicationContext();
            boolean ready = false;
            String failureMessage = "";
            try {
                if (!SessionManager.get().isDeviceRegistered() && !registerDevice(appContext)) {
                    failureMessage = PREPARE_ERROR_REGISTER_FAILED;
                } else if (!prepareOnlineActivation(appContext)) {
                    failureMessage = PREPARE_ERROR_ACTIVATION_CODE_FAILED;
                } else {
                    ready = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "prepareActivation error", e);
                failureMessage = e.getMessage() == null || e.getMessage().trim().isEmpty()
                        ? PREPARE_ERROR_ACTIVATION_CODE_FAILED
                        : e.getMessage().trim();
            }
            if (callback != null) {
                callback.onComplete(ready, failureMessage);
            }
        });
    }

    public void ensureDeviceRegistered(Context context) {
        if (SessionManager.get().isDeviceRegistered()) {
            return;
        }

        executor.execute(() -> {
            Context appContext = context.getApplicationContext();
            try {
                registerDevice(appContext);
            } catch (Exception e) {
                Log.e(TAG, "ensureDeviceRegistered error", e);
            }
        });
    }

    public boolean prepareOnlineActivation(Context context) {
        String activationMode = SessionManager.get().getActivationMode();
        if (Constants.ACTIVATION_MODE_OFFLINE_ZIP.equals(activationMode)) {
            return true;
        }

        if (Constants.ACTIVATION_STATUS_SUCCESS.equals(SessionManager.get().getActivationStatus())
                && !SessionManager.get().getActivationCode().isEmpty()) {
            return true;
        }

        String deviceId = SessionManager.get().getDeviceId();
        if (deviceId == null || deviceId.trim().isEmpty()) {
            SessionManager.get().saveActivationStatus(Constants.ACTIVATION_STATUS_FAILED);
            SessionManager.get().saveLastActivationMessage("device_id 缺失，无法获取激活码");
            return false;
        }

        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.reportStatusEvent("正在获取设备激活码...", PunchApplication.STATUS_LEVEL_PROGRESS);
        }

        ApiResult<DeviceDto.DeviceActivateData> result = ApiService.activateDevice(deviceId);
        if (!result.success || result.data == null || result.data.activationCode.isEmpty()) {
            SessionManager.get().saveActivationStatus(Constants.ACTIVATION_STATUS_FAILED);
            SessionManager.get().saveLastActivationMessage(buildActivationFailureMessage(result));
            if (app != null) {
                app.reportStatusEvent("激活码获取失败，稍后重试", PunchApplication.STATUS_LEVEL_ERROR);
            }
            Log.w(TAG, "activateDevice failed: code=" + result.code + " msg=" + result.message);
            return false;
        }

        SessionManager.get().saveActivationCode(result.data.activationCode);
        SessionManager.get().saveActivationMode(Constants.ACTIVATION_MODE_ONLINE);
        SessionManager.get().saveActivationStatus(Constants.ACTIVATION_STATUS_PENDING);
        SessionManager.get().saveLastActivationMessage("激活码已获取，等待引擎激活");
        if (app != null) {
            app.reportStatusEvent("激活码已获取，等待引擎激活", PunchApplication.STATUS_LEVEL_SUCCESS);
        }
        return true;
    }

    public void reportActivationResult(Context context, int sdkCode, String sdkMessage) {
        long now = System.currentTimeMillis() / 1000;
        boolean success = sdkCode == 0;

        SessionManager.get().saveLastActivationCode(sdkCode);
        SessionManager.get().saveLastActivationMessage(safeText(sdkMessage));
        SessionManager.get().saveLastActivationTime(now);
        SessionManager.get().saveActivationStatus(success
                ? Constants.ACTIVATION_STATUS_SUCCESS
                : Constants.ACTIVATION_STATUS_FAILED);

        if (success && !Constants.ACTIVATION_MODE_OFFLINE_ZIP.equals(SessionManager.get().getActivationMode())) {
            SessionManager.get().saveActivationMode(Constants.ACTIVATION_MODE_ONLINE);
        }
    }

    private boolean registerDevice(Context context) {
        ApiResult<DeviceDto.DeviceRegisterData> result = ApiService.registerDevice(context);
        if (!result.success || result.data == null || result.data.deviceId.isEmpty()) {
            Log.w(TAG, "registerDevice failed: code=" + result.code + " msg=" + result.message);
            return false;
        }
        SessionManager.get().saveDeviceId(result.data.deviceId);
        SessionManager.get().saveDeviceRegistered(true);
        return true;
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private String buildActivationFailureMessage(ApiResult<DeviceDto.DeviceActivateData> result) {
        if (result == null) {
            return "激活码接口无响应";
        }
        String message = safeText(result.message).trim();
        if (!message.isEmpty()) {
            return "获取激活码失败: " + message;
        }
        return "获取激活码失败: code=" + result.code;
    }

    public File getOfflineLicenseCacheFile(Context context) {
        return new File(context.getCacheDir(), "License.zip");
    }

    public boolean importOfflineLicensePackage(Context context, InputStream inputStream) {
        File target = getOfflineLicenseCacheFile(context.getApplicationContext());
        try (InputStream in = inputStream;
             FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            out.flush();
            SessionManager.get().saveActivationMode(Constants.ACTIVATION_MODE_OFFLINE_ZIP);
            SessionManager.get().saveActivationStatus(Constants.ACTIVATION_STATUS_PENDING);
            SessionManager.get().saveLastActivationMessage("License.zip 已导入应用私有目录");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "importOfflineLicensePackage error", e);
            SessionManager.get().saveLastActivationMessage("导入 License.zip 失败: " + e.getMessage());
            return false;
        }
    }
}
