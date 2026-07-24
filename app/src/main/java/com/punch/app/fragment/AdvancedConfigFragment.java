package com.punch.app.fragment;

import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.Camera;
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
import com.punch.app.network.dto.DeviceDto;
import com.punch.app.receiver.KioskDeviceAdminReceiver;
import com.punch.app.utils.Constants;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.PunchTimeResolver;
import com.punch.app.utils.SessionManager;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class AdvancedConfigFragment extends Fragment {
    private static final String[] DISTANCE_LABELS = {"近距离", "标准", "远距离"};
    private static final String[] DISTANCE_VALUES = {
            Constants.DISTANCE_MODE_NEAR,
            Constants.DISTANCE_MODE_STANDARD,
            Constants.DISTANCE_MODE_FAR
    };
    private static final Integer[] TIMEOUT_OPTIONS = {3, 5, 8, 10};
    private static final int PUNCH_INTERVAL_STEP_MINUTES = 1;
    private static final int PUNCH_INTERVAL_MIN_MINUTES = 0;
    private static final int PUNCH_INTERVAL_MAX_MINUTES = 240;
    private static final long CLEAR_DEVICE_OWNER_POLL_INTERVAL_MS = 1000L;
    private static final long CLEAR_DEVICE_OWNER_TIMEOUT_MS = 20000L;
    private EditText etBaseUrl;
    private EditText etCompanyId;
    private EditText etPunchIntervalMinutes;
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
    private MaterialButton btnViewLocalConfig;
    private MaterialButton btnViewLocalEmployees;
    private MaterialButton btnClearDeviceOwner;
    private MaterialButton btnPunchIntervalMinus;
    private MaterialButton btnPunchIntervalPlus;

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
        etPunchIntervalMinutes = view.findViewById(R.id.et_punch_interval_minutes);
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
        btnViewLocalConfig = view.findViewById(R.id.btn_view_local_config);
        btnViewLocalEmployees = view.findViewById(R.id.btn_view_local_employees);
        btnClearDeviceOwner = view.findViewById(R.id.btn_clear_device_owner);
        btnPunchIntervalMinus = view.findViewById(R.id.btn_punch_interval_minus);
        btnPunchIntervalPlus = view.findViewById(R.id.btn_punch_interval_plus);

        setupConfigDropdowns();
        setupThresholdListeners();

        btnCopyFingerprint.setOnClickListener(v -> copyBaiduFingerprint());
        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnKioskMode.setOnClickListener(v -> toggleKioskMode());
        btnViewLogs.setOnClickListener(v -> startActivity(new Intent(requireContext(), InteractionLogActivity.class)));
        btnViewLocalConfig.setOnClickListener(v -> showLocalConfigDialog());
        btnViewLocalEmployees.setOnClickListener(v -> startActivity(new Intent(requireContext(), LocalEmployeeDebugActivity.class)));
        btnClearDeviceOwner.setOnClickListener(v -> confirmClearDeviceOwner());
        btnPunchIntervalMinus.setOnClickListener(v -> adjustPunchInterval(-PUNCH_INTERVAL_STEP_MINUTES));
        btnPunchIntervalPlus.setOnClickListener(v -> adjustPunchInterval(PUNCH_INTERVAL_STEP_MINUTES));
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
        etPunchIntervalMinutes.setText(String.valueOf(SessionManager.get().getPunchTimeWindowMinutes()));
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

        SessionManager session = SessionManager.get();
        int punchIntervalMinutes = parsePunchIntervalMinutes();
        if (punchIntervalMinutes < 0) {
            Toast.makeText(requireContext(), "打卡间隔必须为 0-240 分钟", Toast.LENGTH_SHORT).show();
            etPunchIntervalMinutes.requestFocus();
            return;
        }
        PunchTimeResolver.WindowValidationResult windowValidation =
                PunchTimeResolver.validatePunchTimeWindows(
                        session.getCurrentTeamTimeRanges(),
                        punchIntervalMinutes
                );
        if (!windowValidation.valid) {
            Toast.makeText(requireContext(), windowValidation.buildMessage(), Toast.LENGTH_LONG).show();
            etPunchIntervalMinutes.requestFocus();
            return;
        }
        boolean serverChanged = !baseUrl.equals(session.getBaseUrl()) || companyId != session.getCompanyId();
        session.saveBaseUrl(baseUrl);
        session.saveCompanyId(companyId);
        if (serverChanged) {
            session.clearServerBoundState();
        }
        session.saveMatchThreshold(seekMatchThreshold.getProgress() / 100f);
        session.saveFaceThreshold(seekFaceThreshold.getProgress() / 100f);
        session.saveRecognitionDistanceMode(selectedDistanceModeValue);
        session.saveLivenessCheck(switchLiveness.isChecked());
        session.saveLivenessThreshold(seekLivenessThreshold.getProgress() / 100f);
        session.saveMaskDetectEnabled(switchMaskDetect.isChecked());
        session.saveRecognitionTimeoutSeconds(selectedRecognitionTimeoutValue);
        session.savePunchTimeWindowMinutes(punchIntervalMinutes);

        if (FaceManager.get().isInitialized()) {
            FaceManager.get().refreshRuntimeConfig();
        }

        renderConfig();
        Toast.makeText(requireContext(), "高级配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void showLocalConfigDialog() {
        if (!isAdded()) {
            return;
        }
        String configText = buildLocalConfigText();
        new AlertDialog.Builder(requireContext())
                .setTitle("\u672c\u5730\u914d\u7f6e")
                .setMessage(configText)
                .setNegativeButton("\u5173\u95ed", null)
                .setPositiveButton("\u590d\u5236", (dialog, which) -> copyText("local_config", configText))
                .show();
    }

    private String buildLocalConfigText() {
        SessionManager session = SessionManager.get();
        StringBuilder builder = new StringBuilder();

        appendSection(builder, "\u7cfb\u7edf\u4e0e\u6388\u6743");
        appendLine(builder, "Base URL", session.getBaseUrl());
        appendLine(builder, "Company ID", String.valueOf(session.getCompanyId()));
        appendLine(builder, "\u9996\u6b21\u914d\u7f6e\u5b8c\u6210", formatBoolean(session.isSetupCompleted()));
        appendLine(builder, "\u8bbe\u5907 ID", emptyFallback(session.getDeviceId()));
        appendLine(builder, "\u8bbe\u5907\u5df2\u6ce8\u518c", formatBoolean(session.isDeviceRegistered()));
        appendLine(builder, "\u8bbe\u5907\u914d\u7f6e\u5df2\u521d\u59cb\u5316", formatBoolean(session.isDeviceConfigInitialized()));
        appendLine(builder, "\u6388\u6743\u6307\u7eb9", emptyFallback(BaiduDeviceFingerprint.get(requireContext())));
        appendLine(builder, "\u6388\u6743\u65b9\u5f0f", formatActivationMode(session.getActivationMode()));
        appendLine(builder, "\u6fc0\u6d3b\u72b6\u6001", formatActivationStatus(session.getActivationStatus()));
        appendLine(builder, "\u6fc0\u6d3b\u65f6\u95f4", formatEpochSeconds(session.getLastActivationTime()));

        appendSection(builder, "\u767b\u5f55\u4e0e\u540c\u6b65");
        appendLine(builder, "\u8d26\u53f7", session.getAccount());
        appendLine(builder, "\u767b\u5f55\u5bc6\u7801", session.getPassword());
        appendLine(builder, "Token", session.getToken());
        appendLine(builder, "Token \u6709\u6548", formatBoolean(session.isTokenValid()));
        appendLine(builder, "Token \u8fc7\u671f\u65f6\u95f4", formatEpochSeconds(session.getTokenExpireAt()));
        appendLine(builder, "\u6700\u540e\u5fc3\u8df3\u65f6\u95f4", formatEpochSeconds(session.getLastHeartbeatTime()));
        appendLine(builder, "\u6700\u540e\u670d\u52a1\u5668\u65f6\u95f4", formatEpochSeconds(session.getLastServerTime()));

        appendSection(builder, "\u4e1a\u52a1\u7ed1\u5b9a");
        appendLine(builder, "\u7ebf\u4f53", buildLineBindingText(session));
        appendLine(builder, "\u73ed\u7ec4", buildTeamBindingText(session));
        appendLine(builder, "\u73ed\u6b21\u65f6\u95f4", joinStrings(session.getCurrentTeamTimeRanges()));
        appendLine(builder, "\u6253\u5361\u4eba\u6570\u4e0a\u9650", String.valueOf(session.getCheckCount()));
        appendLine(builder, "\u6253\u5361\u95f4\u9694", session.getPunchTimeWindowMinutes() + "\u5206\u949f");
        appendLine(builder, "\u53ef\u9009\u7ebf\u4f53", buildLineOptionsText(session.getLineBindingOptions()));
        appendLine(builder, "\u53ef\u9009\u73ed\u7ec4", buildTeamOptionsText(session.getTeamBindingOptions()));

        appendSection(builder, "\u8bc6\u522b\u53c2\u6570");
        appendLine(builder, "\u4eba\u8138\u6bd4\u5bf9\u9608\u503c", formatFloat(session.getMatchThreshold()));
        appendLine(builder, "\u4eba\u8138\u68c0\u6d4b\u9608\u503c", formatFloat(session.getFaceThreshold()));
        appendLine(builder, "\u6d3b\u4f53\u68c0\u6d4b", formatBoolean(session.isLivenessCheck()));
        appendLine(builder, "\u6d3b\u4f53\u68c0\u6d4b\u9608\u503c", formatFloat(session.getLivenessThreshold()));
        appendLine(builder, "\u53e3\u7f69\u68c0\u6d4b", formatBoolean(session.isMaskDetectEnabled()));
        appendLine(builder, "\u8bc6\u522b\u8ddd\u79bb", formatDistanceMode(session.getRecognitionDistanceMode()));
        appendLine(builder, "\u8bc6\u522b\u8d85\u65f6", session.getRecognitionTimeoutSeconds() + "s");
        appendLine(builder, "\u6700\u5c0f\u4eba\u8138\u5c3a\u5bf8", String.valueOf(session.getMinFaceSizeForRecognitionDistance()));

        appendSection(builder, "\u8bbe\u5907\u9009\u9879");
        appendLine(builder, "\u6444\u50cf\u5934", formatCameraFacing(session.getCameraFacing(-1)));
        appendLine(builder, "\u58f0\u97f3", formatBoolean(session.isSoundEnabled()));
        appendLine(builder, "Kiosk", formatBoolean(session.isKioskEnabled()));
        appendLine(builder, "\u4e0a\u6b21 Wi-Fi SSID", session.getLastWifiSsid());
        appendLine(builder, "\u4e0a\u6b21 Wi-Fi \u5bc6\u7801", session.getLastWifiPassword());

        appendSection(builder, "\u66f4\u65b0");
        appendLine(builder, "\u9700\u8981\u66f4\u65b0", formatBoolean(session.isUpdateNeeded()));
        appendLine(builder, "APK URL", session.getUpdateApkUrl());
        appendLine(builder, "\u5f53\u524d\u7248\u672c", session.getUpdateCurrentVersion());
        appendLine(builder, "\u76ee\u6807\u7248\u672c", session.getUpdateTargetVersion());
        appendLine(builder, "\u7248\u672c\u540d", session.getUpdateVersionName());
        appendLine(builder, "\u5b89\u88c5\u72b6\u6001", session.getUpdateInstallStatus());
        appendLine(builder, "\u5b89\u88c5\u6d88\u606f", session.getUpdateInstallMessage());

        return builder.toString();
    }

    private void copyText(String label, String text) {
        ClipboardManager clipboardManager =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            Toast.makeText(requireContext(), "\u526a\u8d34\u677f\u670d\u52a1\u4e0d\u53ef\u7528", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text == null ? "" : text));
        Toast.makeText(requireContext(), "\u672c\u5730\u914d\u7f6e\u5df2\u590d\u5236", Toast.LENGTH_SHORT).show();
    }

    private void appendSection(StringBuilder builder, String title) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append("[").append(title).append("]").append('\n');
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        builder.append(label).append(": ").append(emptyFallback(value)).append('\n');
    }

    private String buildLineBindingText(SessionManager session) {
        String code = session.getLineCode();
        String name = session.getLineName();
        if (code.isEmpty() && name.isEmpty()) {
            return "-";
        }
        if (name.isEmpty()) {
            return code;
        }
        if (code.isEmpty()) {
            return name;
        }
        return name + " (" + code + ")";
    }

    private String buildTeamBindingText(SessionManager session) {
        int id = session.getTeamBindingId();
        String name = session.getTeamBindingName();
        if (id <= 0 && name.isEmpty()) {
            return "-";
        }
        if (name.isEmpty()) {
            return String.valueOf(id);
        }
        if (id <= 0) {
            return name;
        }
        return name + " (" + id + ")";
    }

    private String buildLineOptionsText(List<DeviceDto.LineOptionData> options) {
        if (options == null || options.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (DeviceDto.LineOptionData option : options) {
            if (option == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            String name = option.name == null ? "" : option.name.trim();
            String code = option.code == null ? "" : option.code.trim();
            if (!name.isEmpty() && !code.isEmpty()) {
                builder.append(name).append(" (").append(code).append(")");
            } else {
                builder.append(!name.isEmpty() ? name : code);
            }
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private String buildTeamOptionsText(List<DeviceDto.TeamOptionData> options) {
        if (options == null || options.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (DeviceDto.TeamOptionData option : options) {
            if (option == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            String name = option.name == null ? "" : option.name.trim();
            builder.append(name.isEmpty() ? String.valueOf(option.id) : name + " (" + option.id + ")");
            String ranges = joinStrings(option.timeRanges);
            if (!"-".equals(ranges)) {
                builder.append(" ").append(ranges);
            }
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private String joinStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value.trim());
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private String formatBoolean(boolean value) {
        return value ? "\u662f" : "\u5426";
    }

    private String formatFloat(float value) {
        return String.format(Locale.getDefault(), "%.2f", value);
    }

    private String formatDistanceMode(String mode) {
        if (Constants.DISTANCE_MODE_NEAR.equals(mode)) {
            return "\u8fd1\u8ddd\u79bb";
        }
        if (Constants.DISTANCE_MODE_FAR.equals(mode)) {
            return "\u8fdc\u8ddd\u79bb";
        }
        return "\u6807\u51c6";
    }

    private String formatCameraFacing(int cameraFacing) {
        if (cameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return "\u524d\u7f6e";
        }
        if (cameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK) {
            return "\u540e\u7f6e";
        }
        return "\u672a\u77e5";
    }

    private String formatEpochSeconds(long seconds) {
        if (seconds <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new java.util.Date(seconds * 1000L));
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

    private void adjustPunchInterval(int deltaMinutes) {
        int current = parsePunchIntervalMinutes();
        if (current < 0) {
            current = SessionManager.get().getPunchTimeWindowMinutes();
        }
        int adjusted = Math.max(
                PUNCH_INTERVAL_MIN_MINUTES,
                Math.min(PUNCH_INTERVAL_MAX_MINUTES, current + deltaMinutes)
        );
        etPunchIntervalMinutes.setText(String.valueOf(adjusted));
        etPunchIntervalMinutes.setSelection(etPunchIntervalMinutes.getText().length());
    }

    private int parsePunchIntervalMinutes() {
        String value = etPunchIntervalMinutes.getText() == null
                ? ""
                : etPunchIntervalMinutes.getText().toString().trim();
        if (value.isEmpty()) {
            return -1;
        }
        try {
            int minutes = Integer.parseInt(value);
            if (minutes < PUNCH_INTERVAL_MIN_MINUTES || minutes > PUNCH_INTERVAL_MAX_MINUTES) {
                return -1;
            }
            return minutes;
        } catch (NumberFormatException ignored) {
            return -1;
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
