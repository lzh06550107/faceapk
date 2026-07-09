package com.punch.app.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
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

    private ArrayAdapter<String> distanceModeAdapter;
    private ArrayAdapter<Integer> recognitionTimeoutAdapter;
    private String selectedDistanceModeValue = DISTANCE_VALUES[1];
    private int selectedRecognitionTimeoutValue = TIMEOUT_OPTIONS[0];

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

        setupConfigDropdowns();
        setupThresholdListeners();

        btnCopyFingerprint.setOnClickListener(v -> copyBaiduFingerprint());
        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnKioskMode.setOnClickListener(v -> toggleKioskMode());
        btnViewLogs.setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), InteractionLogActivity.class)));
        btnViewLocalEmployees.setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), LocalEmployeeDebugActivity.class)));

        renderConfig();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderConfig();
    }

    private void setupConfigDropdowns() {
        distanceModeAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                DISTANCE_LABELS
        );
        distanceModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
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

        recognitionTimeoutAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                TIMEOUT_OPTIONS
        );
        recognitionTimeoutAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
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
        if (!isAdded() || requireActivity().isFinishing()) {
            return;
        }
        if (SessionManager.get().isKioskEnabled()) {
            KioskManager.exitAndDisable(requireActivity());
            Toast.makeText(requireContext(), "已退出 Kiosk 模式", Toast.LENGTH_SHORT).show();
        } else {
            KioskManager.enableAndEnter(requireActivity());
            if (!KioskManager.isLockTaskPermitted(requireContext())) {
                Toast.makeText(requireContext(), "Kiosk 开关已启用，但当前设备未授予锁定权限", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(requireContext(), "已启用 Kiosk 模式", Toast.LENGTH_SHORT).show();
            }
        }
        updateKioskButtonState();
    }

    private void updateKioskButtonState() {
        if (SessionManager.get().isKioskEnabled()) {
            btnKioskMode.setText("退出 Kiosk 模式");
        } else {
            btnKioskMode.setText("启用 Kiosk 模式");
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
