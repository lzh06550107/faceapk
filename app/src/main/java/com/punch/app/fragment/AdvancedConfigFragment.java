package com.punch.app.fragment;

import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.punch.app.R;
import com.punch.app.activity.InteractionLogActivity;
import com.punch.app.activity.LocalEmployeeDebugActivity;
import com.punch.app.activation.BaiduDeviceFingerprint;
import com.punch.app.face.FaceManager;
import com.punch.app.network.InteractionLogger;
import com.punch.app.receiver.KioskDeviceAdminReceiver;
import com.punch.app.utils.Constants;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;

import java.text.SimpleDateFormat;
import java.util.Locale;

public class AdvancedConfigFragment extends Fragment {
    private static final String[] DISTANCE_LABELS = {"近距离", "标准", "远距离"};
    private static final String[] DISTANCE_VALUES = {
            Constants.DISTANCE_MODE_NEAR,
            Constants.DISTANCE_MODE_STANDARD,
            Constants.DISTANCE_MODE_FAR
    };
    private static final Integer[] TIMEOUT_OPTIONS = {3, 5, 8, 10};
    private static final long CLEAR_DEVICE_OWNER_POLL_INTERVAL_MS = 1000L;
    private static final long CLEAR_DEVICE_OWNER_TIMEOUT_MS = 20000L;
    private EditText etBaseUrl;
    private EditText etCompanyId;
    private TextView tvBaiduFingerprint;
    private TextView tvActivationMode;
    private TextView tvActivationStatus;
    private TextView tvActivationTime;
    private TextView tvMatchThresholdValue;
    private TextView tvFaceThresholdValue;
    private TextView tvLivenessThresholdValue;
    private MaterialAutoCompleteTextView dropdownDistanceMode;
    private MaterialAutoCompleteTextView dropdownRecognitionTimeout;
    private SeekBar seekMatchThreshold;
    private SeekBar seekFaceThreshold;
    private SeekBar seekLivenessThreshold;
    private Switch switchLiveness;
    private Switch switchMaskDetect;
    private Button btnCopyFingerprint;
    private Button btnSaveConfig;
    private Button btnKioskMode;
    private MaterialButton btnViewLogs;
    private MaterialButton btnViewLocalEmployees;
    private MaterialButton btnClearDeviceOwner;

    private String selectedDistanceModeValue = DISTANCE_VALUES[1];
    private int selectedRecognitionTimeoutValue = TIMEOUT_OPTIONS[0];
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean clearingDeviceOwner = false;
    private long clearDeviceOwnerStartedAt = 0L;
    private Context appContext;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        appContext = context.getApplicationContext();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_advanced_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        appContext = view.getContext().getApplicationContext();

        etBaseUrl = view.findViewById(R.id.et_base_url);
        etCompanyId = view.findViewById(R.id.et_company_id);
        tvBaiduFingerprint = view.findViewById(R.id.tv_baidu_fingerprint);
        tvActivationMode = view.findViewById(R.id.tv_activation_mode);
        tvActivationStatus = view.findViewById(R.id.tv_activation_status);
        tvActivationTime = view.findViewById(R.id.tv_activation_time);
        tvMatchThresholdValue = view.findViewById(R.id.tv_threshold_value);
        tvFaceThresholdValue = view.findViewById(R.id.tv_face_threshold_value);
        tvLivenessThresholdValue = view.findViewById(R.id.tv_liveness_threshold_value);
        dropdownDistanceMode = view.findViewById(R.id.dropdown_distance_mode);
        dropdownRecognitionTimeout = view.findViewById(R.id.dropdown_recognition_timeout);
        seekMatchThreshold = view.findViewById(R.id.seek_match_threshold);
        seekFaceThreshold = view.findViewById(R.id.seek_face_threshold);
        seekLivenessThreshold = view.findViewById(R.id.seek_liveness_threshold);
        switchLiveness = view.findViewById(R.id.switch_liveness);
        switchMaskDetect = view.findViewById(R.id.switch_mask_detect);
        btnCopyFingerprint = view.findViewById(R.id.btn_copy_fingerprint);
        btnSaveConfig = view.findViewById(R.id.btn_save_config);
        btnKioskMode = view.findViewById(R.id.btn_kiosk_mode);
        btnViewLogs = view.findViewById(R.id.btn_view_logs);
        btnViewLocalEmployees = view.findViewById(R.id.btn_view_local_employees);
        btnClearDeviceOwner = view.findViewById(R.id.btn_clear_device_owner);

        setupConfigDropdowns();
        setupThresholdListeners();

        btnCopyFingerprint.setOnClickListener(v -> copyBaiduFingerprint());
        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnKioskMode.setOnClickListener(v -> toggleKioskMode());
        btnViewLogs.setOnClickListener(v -> startActivity(new Intent(requireContext(), InteractionLogActivity.class)));
        btnViewLocalEmployees.setOnClickListener(v -> startActivity(new Intent(requireContext(), LocalEmployeeDebugActivity.class)));
        btnClearDeviceOwner.setOnClickListener(v -> confirmClearDeviceOwner());
        renderConfig();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderConfig();
        if (clearingDeviceOwner) {
            scheduleClearDeviceOwnerStateCheck(0L);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void setupConfigDropdowns() {
        ArrayAdapter<String> distanceModeAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                DISTANCE_LABELS
        );
        dropdownDistanceMode.setThreshold(0);
        dropdownDistanceMode.setAdapter(distanceModeAdapter);
        dropdownDistanceMode.setOnItemClickListener((parent, view, position, id) ->
                selectedDistanceModeValue = DISTANCE_VALUES[position]);
        dropdownDistanceMode.setOnClickListener(v -> dropdownDistanceMode.showDropDown());
        dropdownDistanceMode.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                dropdownDistanceMode.showDropDown();
            }
        });

        ArrayAdapter<Integer> recognitionTimeoutAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                TIMEOUT_OPTIONS
        );
        dropdownRecognitionTimeout.setThreshold(0);
        dropdownRecognitionTimeout.setAdapter(recognitionTimeoutAdapter);
        dropdownRecognitionTimeout.setOnItemClickListener((parent, view, position, id) ->
                selectedRecognitionTimeoutValue = TIMEOUT_OPTIONS[position]);
        dropdownRecognitionTimeout.setOnClickListener(v -> dropdownRecognitionTimeout.showDropDown());
        dropdownRecognitionTimeout.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                dropdownRecognitionTimeout.showDropDown();
            }
        });
    }

    private void setupThresholdListeners() {
        seekMatchThreshold.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvMatchThresholdValue.setText(String.format(Locale.getDefault(), "%d", progress));
            }
        });
        seekFaceThreshold.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvFaceThresholdValue.setText(String.format(Locale.getDefault(), "%d", progress));
            }
        });
        seekLivenessThreshold.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvLivenessThresholdValue.setText(String.format(Locale.getDefault(), "%.2f", progress / 100f));
            }
        });
    }

    private void renderConfig() {
        if (!isAdded()) {
            return;
        }

        etBaseUrl.setText(SessionManager.get().getBaseUrl());
        etCompanyId.setText(String.valueOf(SessionManager.get().getCompanyId()));
        bindBaiduFingerprint();
        tvActivationMode.setText(formatActivationMode(SessionManager.get().getActivationMode()));
        tvActivationStatus.setText(formatActivationStatus(SessionManager.get().getActivationStatus()));
        tvActivationTime.setText(formatActivationTime(SessionManager.get().getLastActivationTime()));

        int matchThreshold = Math.round(SessionManager.get().getMatchThreshold() * 100);
        seekMatchThreshold.setProgress(matchThreshold);
        tvMatchThresholdValue.setText(String.format(Locale.getDefault(), "%d", matchThreshold));

        int faceThreshold = Math.round(SessionManager.get().getFaceThreshold() * 100);
        seekFaceThreshold.setProgress(faceThreshold);
        tvFaceThresholdValue.setText(String.format(Locale.getDefault(), "%d", faceThreshold));

        int livenessThreshold = Math.round(SessionManager.get().getLivenessThreshold() * 100);
        seekLivenessThreshold.setProgress(livenessThreshold);
        tvLivenessThresholdValue.setText(String.format(Locale.getDefault(), "%.2f", livenessThreshold / 100f));

        switchLiveness.setChecked(SessionManager.get().isLivenessCheck());
        switchMaskDetect.setChecked(SessionManager.get().isMaskDetectEnabled());
        selectDistanceMode(SessionManager.get().getRecognitionDistanceMode());
        selectRecognitionTimeout(SessionManager.get().getRecognitionTimeoutSeconds());
        updateKioskButtonState();
        updateClearDeviceOwnerButtonState();
    }

    private void bindBaiduFingerprint() {
        String fingerprint = BaiduDeviceFingerprint.get(requireContext());
        tvBaiduFingerprint.setText(emptyFallback(fingerprint));
    }
    private void copyBaiduFingerprint() {
        String fingerprint = BaiduDeviceFingerprint.get(requireContext());
        if (fingerprint == null || fingerprint.trim().isEmpty()) {
            Toast.makeText(requireContext(), "授权指纹为空", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboardManager =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            Toast.makeText(requireContext(), "剪贴板服务不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboardManager.setPrimaryClip(ClipData.newPlainText("baidu_fingerprint", fingerprint));
        Toast.makeText(requireContext(), "授权指纹已复制", Toast.LENGTH_SHORT).show();
    }

    private void saveConfig() {
        String rawBaseUrl = etBaseUrl.getText() == null ? "" : etBaseUrl.getText().toString().trim();
        String baseUrl = SessionManager.normalizeBaseUrl(rawBaseUrl);
        if (!rawBaseUrl.isEmpty() && !baseUrl.equals(rawBaseUrl.replaceAll("/+$", "").trim())) {
            Toast.makeText(requireContext(), "Base URL 必须是有效的 http/https 地址", Toast.LENGTH_SHORT).show();
            etBaseUrl.requestFocus();
            return;
        }

        int companyId = parseCompanyId();
        if (companyId <= 0) {
            Toast.makeText(requireContext(), "Company ID 必须为正整数", Toast.LENGTH_SHORT).show();
            etCompanyId.requestFocus();
            return;
        }

        SessionManager.get().saveBaseUrl(baseUrl);
        SessionManager.get().saveCompanyId(companyId);
        SessionManager.get().saveMatchThreshold(seekMatchThreshold.getProgress() / 100f);
        SessionManager.get().saveFaceThreshold(seekFaceThreshold.getProgress() / 100f);
        SessionManager.get().saveRecognitionDistanceMode(selectedDistanceModeValue);
        SessionManager.get().saveLivenessCheck(switchLiveness.isChecked());
        SessionManager.get().saveLivenessThreshold(seekLivenessThreshold.getProgress() / 100f);
        SessionManager.get().saveMaskDetectEnabled(switchMaskDetect.isChecked());
        SessionManager.get().saveRecognitionTimeoutSeconds(selectedRecognitionTimeoutValue);

        if (FaceManager.get().isInitialized()) {
            FaceManager.get().refreshRuntimeConfig();
        }

        renderConfig();
        Toast.makeText(requireContext(), "高级配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void toggleKioskMode() {
        if (!isAdded() || requireActivity().isFinishing() || clearingDeviceOwner) {
            return;
        }
        if (SessionManager.get().isKioskEnabled()) {
            if (KioskManager.isDeviceOwner(requireContext())) {
                Toast.makeText(requireContext(), "Device Owner 模式下不能退出 Kiosk，请先解除 Device Owner", Toast.LENGTH_LONG).show();
                KioskManager.enableAndEnter(requireActivity());
                updateKioskButtonState();
                return;
            }
            KioskManager.exitAndDisable(requireActivity());
            Toast.makeText(requireContext(), "已退出 Kiosk 模式", Toast.LENGTH_SHORT).show();
        } else {
            KioskManager.enableAndEnter(requireActivity());
            if (!KioskManager.isLockTaskPermitted(requireContext())) {
                Toast.makeText(requireContext(), "Kiosk 已启用，但当前设备未授予锁定权限", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(requireContext(), "已启用 Kiosk 模式", Toast.LENGTH_SHORT).show();
            }
        }
        updateKioskButtonState();
    }

    private void updateKioskButtonState() {
        if (clearingDeviceOwner) {
            btnKioskMode.setEnabled(false);
            btnKioskMode.setAlpha(0.6f);
            return;
        }
        btnKioskMode.setEnabled(true);
        btnKioskMode.setAlpha(1f);
        btnKioskMode.setText(SessionManager.get().isKioskEnabled() ? "退出 Kiosk 模式" : "启用 Kiosk 模式");
    }

    private void updateClearDeviceOwnerButtonState() {
        if (clearingDeviceOwner) {
            btnClearDeviceOwner.setEnabled(false);
            btnClearDeviceOwner.setAlpha(0.6f);
            btnClearDeviceOwner.setText("正在解除 Device Owner...");
            return;
        }
        boolean isDeviceOwner = KioskManager.isDeviceOwner(requireContext());
        btnClearDeviceOwner.setEnabled(isDeviceOwner);
        btnClearDeviceOwner.setAlpha(isDeviceOwner ? 1f : 0.6f);
        btnClearDeviceOwner.setText(isDeviceOwner ? "解除 Device Owner" : "当前不是 Device Owner");
    }

    private void confirmClearDeviceOwner() {
        if (!KioskManager.isDeviceOwner(requireContext())) {
            Toast.makeText(requireContext(), "当前应用不是 Device Owner", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("解除 Device Owner")
                .setMessage("该操作会取消当前设备上的 Device Owner 身份，Kiosk 和设备管理能力会立即失效。只有系统真正完成移除后，才会自动弹出系统卸载界面。是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> clearDeviceOwner())
                .show();
    }

    private void clearDeviceOwner() {
        Context context = requireContext();
        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "解除 Device Owner 失败",
                    "DevicePolicyManager unavailable"
            );
            Toast.makeText(context, "设备策略服务不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_GENERAL,
                    "开始解除 Device Owner",
                    "package=" + context.getPackageName()
            );
            clearingDeviceOwner = true;
            clearDeviceOwnerStartedAt = System.currentTimeMillis();
            updateKioskButtonState();
            updateClearDeviceOwnerButtonState();
            KioskManager.exitForDeviceOwnerRemoval(requireActivity());
            dpm.clearDeviceOwnerApp(context.getPackageName());
            openSystemUninstallPage(appContext);
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_GENERAL,
                    "解除 Device Owner 请求已提交",
                    "waiting for owner/admin removal"
            );
            scheduleClearDeviceOwnerStateCheck(CLEAR_DEVICE_OWNER_POLL_INTERVAL_MS);
        } catch (SecurityException e) {
            clearingDeviceOwner = false;
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "解除 Device Owner 失败",
                    "SecurityException: " + e.getMessage()
            );
            updateKioskButtonState();
            updateClearDeviceOwnerButtonState();
            Toast.makeText(context, "解除失败：权限不足", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            clearingDeviceOwner = false;
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "解除 Device Owner 失败",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
            );
            updateKioskButtonState();
            updateClearDeviceOwnerButtonState();
            Toast.makeText(context, "解除失败：" + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }
    private void scheduleClearDeviceOwnerStateCheck(long delayMillis) {
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(this::checkClearDeviceOwnerState, delayMillis);
    }
    private void checkClearDeviceOwnerState() {
        Context context = appContext;
        if (context == null) {
            Context currentContext = getContext();
            if (currentContext != null) {
                context = currentContext.getApplicationContext();
                appContext = context;
            }
        }
        if (context == null) {
            return;
        }
        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        boolean ownerCleared = dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName());
        boolean adminCleared = dpm == null || !dpm.isAdminActive(getAdminComponent(context));

        if (ownerCleared) {
            clearingDeviceOwner = false;
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_GENERAL,
                    "瑙ｉ櫎 Device Owner 鎴愬姛",
                    "ownerCleared=" + ownerCleared + ", adminCleared=" + adminCleared
            );
            if (isAdded()) {
                updateKioskButtonState();
                updateClearDeviceOwnerButtonState();
                renderConfig();
            }
            openSystemUninstallPage(context);
            return;
        }

        if (System.currentTimeMillis() - clearDeviceOwnerStartedAt >= CLEAR_DEVICE_OWNER_TIMEOUT_MS) {
            clearingDeviceOwner = false;
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "瑙ｉ櫎 Device Owner 瓒呮椂",
                    "ownerCleared=" + ownerCleared + ", adminCleared=" + adminCleared
            );
            if (isAdded()) {
                updateKioskButtonState();
                updateClearDeviceOwnerButtonState();
                renderConfig();
            }
            Toast.makeText(
                    context,
                    "\u89e3\u9664\u4ecd\u5728\u5904\u7406\u4e2d\uff0c\u5c06\u518d\u6b21\u5c1d\u8bd5\u6253\u5f00\u5378\u8f7d\u9875",
                    Toast.LENGTH_LONG
            ).show();
            openSystemUninstallPage(context);
            return;
        }

        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_GENERAL,
                "缁х画绛夊緟 Device Owner 绉婚櫎",
                "ownerCleared=" + ownerCleared + ", adminCleared=" + adminCleared
        );
        scheduleClearDeviceOwnerStateCheck(CLEAR_DEVICE_OWNER_POLL_INTERVAL_MS);
    }
    private ComponentName getAdminComponent() {
        return getAdminComponent(requireContext());
    }

    private ComponentName getAdminComponent(Context context) {
        return new ComponentName(context, KioskDeviceAdminReceiver.class);
    }

    private void openSystemUninstallPage(Context context) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_GENERAL,
                    "Open uninstall page",
                    "package=" + context.getPackageName()
            );
        } catch (Exception e) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "Open uninstall page failed",
                    e.getClass().getSimpleName() + ": " + safeMessage(e)
            );
            Toast.makeText(
                    context,
                    "\u65e0\u6cd5\u6253\u5f00\u7cfb\u7edf\u5378\u8f7d\u9875\uff0c\u8bf7\u624b\u52a8\u5378\u8f7d",
                    Toast.LENGTH_LONG
            ).show();
        }
    }
    private void selectDistanceMode(String distanceMode) {
        int index = 1;
        if (Constants.DISTANCE_MODE_NEAR.equals(distanceMode)) {
            index = 0;
        } else if (Constants.DISTANCE_MODE_FAR.equals(distanceMode)) {
            index = 2;
        }
        selectedDistanceModeValue = DISTANCE_VALUES[index];
        dropdownDistanceMode.setText(DISTANCE_LABELS[index], false);
    }

    private void selectRecognitionTimeout(int timeoutSeconds) {
        int selected = TIMEOUT_OPTIONS[0];
        for (int option : TIMEOUT_OPTIONS) {
            if (option == timeoutSeconds) {
                selected = option;
                break;
            }
        }
        selectedRecognitionTimeoutValue = selected;
        dropdownRecognitionTimeout.setText(String.valueOf(selected), false);
    }

    private int parseCompanyId() {
        String value = etCompanyId.getText() == null ? "" : etCompanyId.getText().toString().trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
    private String formatActivationMode(String mode) {
        if (Constants.ACTIVATION_MODE_ONLINE.equals(mode)) {
            return "在线激活";
        }
        if (Constants.ACTIVATION_MODE_OFFLINE_ZIP.equals(mode)) {
            return "License.zip";
        }
        return "未知";
    }

    private String formatActivationStatus(String status) {
        if (Constants.ACTIVATION_STATUS_SUCCESS.equals(status)) {
            return "已激活";
        }
        if (Constants.ACTIVATION_STATUS_FAILED.equals(status)) {
            return "激活失败";
        }
        if (Constants.ACTIVATION_STATUS_DISABLED.equals(status)) {
            return "已禁用";
        }
        return "待激活";
    }

    private String formatActivationTime(long activationTimeSeconds) {
        if (activationTimeSeconds <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(activationTimeSeconds * 1000L);
    }

    private String safeMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) {
            return "未知错误";
        }
        return e.getMessage().trim();
    }

    private String emptyFallback(String value) {
        return (value == null || value.trim().isEmpty()) ? "-" : value;
    }

    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
