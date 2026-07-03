package com.punch.app.fragment;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.punch.app.PunchApplication;
import com.punch.app.activity.MainActivity;
import com.punch.app.R;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.face.FaceManager;
import com.punch.app.model.Employee;
import com.punch.app.model.PunchRecord;
import com.punch.app.model.SyncQueueItem;
import com.punch.app.network.ApiResult;
import com.punch.app.network.ApiService;
import com.punch.app.network.dto.PunchDto;
import com.punch.app.service.SyncService;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.PunchTimeResolver;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UlidGenerator;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Punch screen fragment.
 * <p>Handles camera preview, face recognition, punch persistence, audio feedback, and result display.</p>
 */

public class PunchFragment extends Fragment implements TextureView.SurfaceTextureListener {

    private static final String TAG = "PunchFragment";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;

    // Views
    private View layoutHeader;
    private View layoutControls;
    private View layoutCameraContainer;
    private View layoutCameraLoading;
    private View layoutPunchStatusPanel;
    private TextureView textureView;
    private TextView tvLine, tvTeam, tvStatus, tvResult, tvResultIcon, btnSwitchCamera, btnSound, btnPunchToggle, btnFullscreen, tvCameraLoading;
    private TextView tvPunchStatusLevel;
    private TextView tvPunchStatusCurrent;
    private TextView tvPunchStatusToggle;
    private TextView tvPunchStatusHint;
    private Spinner spinnerShift;
    private Switch switchSpecialTime;
    private LinearLayout layoutResult;
    private LinearLayout layoutPunchStatusHistory;

    // Camera
    private Camera camera;
    private int cameraId = -1;
    private int cameraFacing = Camera.CameraInfo.CAMERA_FACING_FRONT;
    private int frameRotation = 0;
    private int frameMirror = 0;
    private int previewWidth = 0;
    private int previewHeight = 0;
    private boolean openingCamera = false;

    // State
    private String punchType = Constants.PUNCH_TYPE_SIGN_IN;
    private boolean soundEnabled = true;
    private boolean punchEnabled = false;
    private boolean specialTimeEnabled = false;
    private boolean previewFullscreen = false;
    private boolean recognizing = false;
    private long recognitionAttemptStartedAt = 0;
    private long lastRecognitionTimeoutAt = 0;
    private long lastFrameTime = 0;
    private static final long FRAME_INTERVAL_MS = 600;
    private static final long RESULT_DISPLAY_MS = 3000;
    private static final long RECOGNITION_TIMEOUT_FEEDBACK_COOLDOWN_MS = 1500;
    private static final long CAMERA_RELEASE_DELAY_MS = 1800;
    private static final long STATUS_PANEL_AUTO_HIDE_DELAY_MS = 3500;
    private static final long STATUS_PANEL_FADE_DURATION_MS = 500;
    private static final int FREE_PUNCH_OPTION_VALUE = 0;
    private static final String FREE_PUNCH_OPTION_LABEL = "\u81ea\u7531\u6253\u5361";
    private static final String PUNCH_TYPE_FREE = "free";
    private final List<String> punchOptions = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable delayedCameraRelease = this::releaseCameraNow;
    private MediaPlayer feedbackPlayer;
    private boolean punchActive = false;
    private boolean waitingFirstPreviewFrame = false;
    private boolean statusHistoryExpanded = false;
    private ArrayAdapter<String> punchOptionAdapter;
    private final PunchApplication.PunchStatusListener punchStatusListener = this::renderPunchStatusSnapshot;
    private final Runnable hideStatusPanelRunnable = this::fadeOutStatusPanel;

    private volatile boolean frameProcessing = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_punch, container, false);
    }

    @Override
    
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        layoutHeader = view.findViewById(R.id.layout_punch_header);
        layoutControls = view.findViewById(R.id.layout_punch_controls);
        layoutCameraContainer = view.findViewById(R.id.layout_camera_container);
        layoutCameraLoading = view.findViewById(R.id.layout_camera_loading);
        layoutPunchStatusPanel = view.findViewById(R.id.layout_punch_status_panel);
        textureView = view.findViewById(R.id.texture_view);
        tvLine = view.findViewById(R.id.tv_line);
        tvTeam = view.findViewById(R.id.tv_team);
        tvStatus = view.findViewById(R.id.tv_status);
        tvResult = view.findViewById(R.id.tv_result);
        tvResultIcon = view.findViewById(R.id.tv_result_icon);
        btnSwitchCamera = view.findViewById(R.id.btn_switch_camera);
        btnSound = view.findViewById(R.id.btn_sound);
        btnPunchToggle = view.findViewById(R.id.btn_punch_toggle);
        btnFullscreen = view.findViewById(R.id.btn_fullscreen);
        tvCameraLoading = view.findViewById(R.id.tv_camera_loading);
        tvPunchStatusLevel = view.findViewById(R.id.tv_punch_status_level);
        tvPunchStatusCurrent = view.findViewById(R.id.tv_punch_status_current);
        tvPunchStatusToggle = view.findViewById(R.id.tv_punch_status_toggle);
        tvPunchStatusHint = view.findViewById(R.id.tv_punch_status_hint);
        spinnerShift = view.findViewById(R.id.spinner_shift);
        switchSpecialTime = view.findViewById(R.id.switch_special_time);
        layoutResult = view.findViewById(R.id.layout_result);
        layoutPunchStatusHistory = view.findViewById(R.id.layout_punch_status_history);
        cameraFacing = resolveInitialCameraFacing();
        soundEnabled = SessionManager.get().isSoundEnabled();

        refreshBindingHeader();

                punchOptionAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                punchOptions
        );
        spinnerShift.setAdapter(punchOptionAdapter);
        spinnerShift.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                updatePunchTypeFromSelection();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        switchSpecialTime.setOnCheckedChangeListener((buttonView, isChecked) -> specialTimeEnabled = isChecked);

        btnSwitchCamera.setOnClickListener(v -> toggleCameraFacing());
        btnSwitchCamera.setVisibility(hasMultipleCameras() ? View.VISIBLE : View.GONE);
        btnSound.setOnClickListener(v -> toggleSoundEnabled());
        btnPunchToggle.setOnClickListener(v -> setPunchEnabled(!punchEnabled));
        btnFullscreen.setOnClickListener(v -> setPreviewFullscreen(!previewFullscreen));
        View statusSummary = view.findViewById(R.id.layout_punch_status_summary);
        statusSummary.setOnClickListener(v -> setStatusHistoryExpanded(!statusHistoryExpanded));
        tvPunchStatusToggle.setOnClickListener(v -> setStatusHistoryExpanded(!statusHistoryExpanded));
        layoutCameraContainer.setOnClickListener(v -> revealStatusPanel());
        updateSoundButtonLabel();
        updatePunchToggleLabel();
        updateSwitchCameraLabel();
        updateFullscreenButtonLabel();
        rebuildPunchOptions();
        updatePunchTypeFromSelection();
        initAudioFeedback();
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            renderPunchStatusSnapshot(app.getPunchStatusSnapshot());
        }

        textureView.setSurfaceTextureListener(this);
        textureView.addOnLayoutChangeListener((v, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updatePreviewTransform();
            }
        });
    }


    @Override
    
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int w, int h) {
        ensureCameraReady();
    }

    @Override
    public void onStart() {
        super.onStart();
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.addPunchStatusListener(punchStatusListener);
        }
    }

    @Override
    public void onStop() {
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.removePunchStatusListener(punchStatusListener);
        }
        super.onStop();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {
        updatePreviewTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) {
        releaseCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {
        if (waitingFirstPreviewFrame) {
            waitingFirstPreviewFrame = false;
            hideCameraLoading();
        }
    }

    
    private void toggleCameraFacing() {
        if (openingCamera) {
            return;
        }

        int targetFacing = cameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT
                ? Camera.CameraInfo.CAMERA_FACING_BACK
                : Camera.CameraInfo.CAMERA_FACING_FRONT;
        int targetCameraId = findCameraId(targetFacing);
        if (targetCameraId < 0) {
            setStatus(targetFacing == Camera.CameraInfo.CAMERA_FACING_FRONT
                    ? "\u8bbe\u5907\u4e0d\u652f\u6301\u524d\u7f6e\u76f8\u673a"
                    : "\u8bbe\u5907\u4e0d\u652f\u6301\u540e\u7f6e\u76f8\u673a");
            playFailFeedback();
            return;
        }

        cameraFacing = targetFacing;
        cameraId = targetCameraId;
        SessionManager.get().saveCameraFacing(cameraFacing);
        updateSwitchCameraLabel();
        releaseCamera();
        ensureCameraReady();
    }

    private void updateSwitchCameraLabel() {
        if (btnSwitchCamera == null) {
            return;
        }
        btnSwitchCamera.setText(cameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT
                ? "\u5207\u540e\u7f6e"
                : "\u5207\u524d\u7f6e");
    }

    private void toggleSoundEnabled() {
        soundEnabled = !soundEnabled;
        SessionManager.get().saveSoundEnabled(soundEnabled);
        updateSoundButtonLabel();
    }

    private void updateSoundButtonLabel() {
        if (btnSound == null) {
            return;
        }
        btnSound.setText(soundEnabled
                ? "\u58f0\u97f3\uff1a\u5f00"
                : "\u58f0\u97f3\uff1a\u5173");
    }

    private void setPunchEnabled(boolean enabled) {
        punchEnabled = enabled;
        if (!enabled) {
            recognizing = false;
            resetRecognitionAttempt();
            if (layoutResult != null) {
                layoutResult.setVisibility(View.GONE);
            }
        }
        updatePunchToggleLabel();
        updateIdleStatus();
    }

    private void updatePunchToggleLabel() {
        if (btnPunchToggle == null) {
            return;
        }
        btnPunchToggle.setText(punchEnabled
                ? "\u6253\u5361\uff1a\u5f00"
                : "\u6253\u5361\uff1a\u5173");
    }

    private void updateIdleStatus() {
        if (!isAdded() || tvStatus == null) {
            return;
        }
        if (punchEnabled) {
            setStatus("\u8bf7\u5c06\u9762\u90e8\u5bf9\u51c6\u8bc6\u522b\u6846");
        } else {
            setStatus("\u6253\u5361\u5df2\u5173\u95ed\uff0c\u70b9\u51fb\u201c\u6253\u5361\uff1a\u5173\u201d\u5f00\u542f");
        }
    }

    
    private void setPreviewFullscreen(boolean fullscreen) {
        previewFullscreen = fullscreen;
        if (layoutHeader != null) {
            layoutHeader.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        }
        if (layoutControls != null) {
            layoutControls.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        }
        if (layoutCameraContainer != null) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) layoutCameraContainer.getLayoutParams();
            params.height = 0;
            params.weight = 1f;
            layoutCameraContainer.setLayoutParams(params);
        }
        updateFullscreenButtonLabel();
        if (textureView != null) {
            textureView.post(this::updatePreviewTransform);
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setPunchFullscreen(fullscreen);
        }
    }

    private void updateFullscreenButtonLabel() {
        if (btnFullscreen == null) {
            return;
        }
        btnFullscreen.setText(previewFullscreen
                ? "\u9000\u51fa\u5168\u5c4f"
                : "\u5168\u5c4f");
    }

    private boolean hasMultipleCameras() {
        return Camera.getNumberOfCameras() > 1;
    }

    private int resolveInitialCameraFacing() {
        int savedFacing = SessionManager.get().getCameraFacing(Camera.CameraInfo.CAMERA_FACING_FRONT);
        if (findCameraId(savedFacing) >= 0) {
            return savedFacing;
        }
        if (findCameraId(Camera.CameraInfo.CAMERA_FACING_FRONT) >= 0) {
            return Camera.CameraInfo.CAMERA_FACING_FRONT;
        }
        if (findCameraId(Camera.CameraInfo.CAMERA_FACING_BACK) >= 0) {
            return Camera.CameraInfo.CAMERA_FACING_BACK;
        }
        return Camera.CameraInfo.CAMERA_FACING_FRONT;
    }

    private int findCameraId(int facing) {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == facing) {
                return i;
            }
        }
        return -1;
    }

    private int getDisplayDegrees() {
        int rotation = requireActivity().getWindowManager().getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    private int getCameraDisplayOrientation(int targetCameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(targetCameraId, info);
        int degrees = getDisplayDegrees();
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            int result = (info.orientation + degrees) % 360;
            return (360 - result) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    
    private void initAudioFeedback() {
        releaseAudioFeedback();
    }

    
    private void playPunchFeedback(PunchRecord record) {
        if (!soundEnabled || !isAdded()) {
            return;
        }
        playRawSound(R.raw.punch_success);
    }

    
    private void playFailFeedback() {
        if (!soundEnabled || !isAdded()) {
            return;
        }
        playRawSound(R.raw.punch_fail);
    }

    /** Plays fixed feedback for forbidden punch scenarios. */
    private void playForbiddenFeedback() {
        if (!soundEnabled || !isAdded()) {
            return;
        }
        playRawSound(R.raw.punch_forbidden);
    }

    private void playRawSound(int resId) {
        releaseAudioFeedback();
        feedbackPlayer = MediaPlayer.create(requireContext().getApplicationContext(), resId);
        if (feedbackPlayer == null) {
            return;
        }
        feedbackPlayer.setOnCompletionListener(mp -> {
            mp.release();
            if (feedbackPlayer == mp) {
                feedbackPlayer = null;
            }
        });
        feedbackPlayer.setOnErrorListener((mp, what, extra) -> {
            mp.release();
            if (feedbackPlayer == mp) {
                feedbackPlayer = null;
            }
            return true;
        });
        feedbackPlayer.start();
    }

    private void releaseAudioFeedback() {
        if (feedbackPlayer != null) {
            try {
                if (feedbackPlayer.isPlaying()) {
                    feedbackPlayer.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            feedbackPlayer.release();
            feedbackPlayer = null;
        }
    }

    
    private void beginRecognitionAttempt() {
        if (recognitionAttemptStartedAt == 0) {
            recognitionAttemptStartedAt = System.currentTimeMillis();
        }
    }

    
    private void resetRecognitionAttempt() {
        recognitionAttemptStartedAt = 0;
    }

    
    private long getRecognitionTimeoutMs() {
        return SessionManager.get().getRecognitionTimeoutSeconds() * 1000L;
    }

    
    private void showTransientFailureResult(String message) {
        recognizing = true;
        setStatus(message);
        playFailFeedback();

        applyResultIconStyle(false);
        tvResult.setText(message);
        layoutResult.setVisibility(View.VISIBLE);

        uiHandler.postDelayed(() -> {
            layoutResult.setVisibility(View.GONE);
            recognizing = false;
            resetRecognitionAttempt();
            updateIdleStatus();
        }, RESULT_DISPLAY_MS);
    }



    
    private void updatePreviewTransform() {
        if (textureView == null || previewWidth <= 0 || previewHeight <= 0) {
            return;
        }
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        boolean rotated = frameRotation == 90 || frameRotation == 270;
        float bufferWidth = rotated ? previewHeight : previewWidth;
        float bufferHeight = rotated ? previewWidth : previewHeight;
        float scale = Math.max(viewWidth / bufferWidth, viewHeight / bufferHeight);

        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0f, 0f, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0f, 0f, bufferWidth, bufferHeight);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        matrix.postScale(scale, scale, centerX, centerY);
        textureView.setTransform(matrix);
    }

    @SuppressWarnings("deprecation")
    
    private void openCamera(SurfaceTexture surface) {
        if (!isAdded() || surface == null || camera != null || openingCamera) {
            return;
        }
        try {
            openingCamera = true;
            Camera.CameraInfo info = new Camera.CameraInfo();
            cameraId = findCameraId(cameraFacing);
            if (cameraId < 0) {
                waitingFirstPreviewFrame = false;
                hideCameraLoading();
                setStatus("\u672a\u627e\u5230\u53ef\u7528\u76f8\u673a");
                playFailFeedback();
                return;
            }
            camera = Camera.open(cameraId);
            Camera.Parameters params = camera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);
            Camera.Size previewSize = choosePreviewSize(params);
            if (previewSize != null) {
                previewWidth = previewSize.width;
                previewHeight = previewSize.height;
                params.setPreviewSize(previewSize.width, previewSize.height);
                surface.setDefaultBufferSize(previewSize.width, previewSize.height);
            }
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            camera.setParameters(params);

            Camera.getCameraInfo(cameraId, info);
            frameMirror = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? 1 : 0;
            frameRotation = getCameraDisplayOrientation(cameraId);
            camera.setDisplayOrientation(frameRotation);
            camera.setPreviewTexture(surface);
            textureView.post(this::updatePreviewTransform);

            camera.setPreviewCallback((data, cam) -> {
                if (!punchActive) {
                    return;
                }
                if (!punchEnabled || recognizing) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (frameProcessing || (now - lastFrameTime) < FRAME_INTERVAL_MS) return;
                frameProcessing = true;
                lastFrameTime = now;

                Camera.Parameters p = cam.getParameters();
                int w = p.getPreviewSize().width;
                int h = p.getPreviewSize().height;
                byte[] frameCopy = data.clone();

                executor.execute(() -> {
                    try {
                        processFrame(frameCopy, w, h, frameRotation, frameMirror);
                    } finally {
                        frameProcessing = false;
                    }
                });
            });

            camera.startPreview();
            updateIdleStatus();
        } catch (IOException | RuntimeException e) {
            waitingFirstPreviewFrame = false;
            hideCameraLoading();
            Log.e(TAG, "openCamera error", e);
            releaseCamera();
            setStatus("\u76f8\u673a\u542f\u52a8\u5931\u8d25: " + e.getMessage());
            playFailFeedback();
        } finally {
            openingCamera = false;
        }
    }

    
    private void releaseCamera() {
        cancelDelayedCameraRelease();
        releaseCameraNow();
    }

    private void releaseCameraNow() {
        waitingFirstPreviewFrame = false;
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
            } catch (Exception ignored) {
            }
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
        openingCamera = false;
    }

    private void scheduleCameraRelease() {
        cancelDelayedCameraRelease();
        uiHandler.postDelayed(delayedCameraRelease, CAMERA_RELEASE_DELAY_MS);
    }

    private void cancelDelayedCameraRelease() {
        uiHandler.removeCallbacks(delayedCameraRelease);
    }

    
    private void ensureCameraReady() {
        cancelDelayedCameraRelease();
        if (!isAdded() || textureView == null || !textureView.isAvailable() || isHidden()) {
            return;
        }
        if (!hasCameraPermission()) {
            waitingFirstPreviewFrame = false;
            hideCameraLoading();
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            setStatus("\u8bf7\u5141\u8bb8\u76f8\u673a\u6743\u9650");
            return;
        }
        waitingFirstPreviewFrame = true;
        showCameraLoading("\u76f8\u673a\u51c6\u5907\u4e2d...");
        openCamera(textureView.getSurfaceTexture());
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private Camera.Size choosePreviewSize(Camera.Parameters params) {
        List<Camera.Size> sizes = params.getSupportedPreviewSizes();
        if (sizes == null || sizes.isEmpty()) {
            return params.getPreviewSize();
        }

        Camera.Size fallback = sizes.get(0);
        Camera.Size preferred = null;
        for (Camera.Size size : sizes) {
            if (size.width == 640 && size.height == 480) {
                return size;
            }
            if (size.width == 720 && size.height == 480) {
                preferred = size;
            } else if (preferred == null && size.width <= 1280 && size.height <= 720) {
                preferred = size;
            }
        }
        return preferred != null ? preferred : fallback;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensureCameraReady();
            } else {
                waitingFirstPreviewFrame = false;
                hideCameraLoading();
                setStatus("\u76f8\u673a\u6743\u9650\u88ab\u62d2\u7edd");
                playFailFeedback();
            }
        }
    }

    @Override
    
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            punchActive = false;
            setPunchEnabled(false);
            waitingFirstPreviewFrame = false;
            hideCameraLoading();
            resetRecognitionAttempt();
            setScreenOnLocked(false);
            if (previewFullscreen) {
                setPreviewFullscreen(false);
            }
            scheduleCameraRelease();
        } else {
            punchActive = true;
            setPunchEnabled(false);
            setScreenOnLocked(true);
            if (tvLine != null) {
                refreshBindingHeader();
            }
            rebuildPunchOptions();
            ensureCameraReady();
        }
    }


    
    private void processFrame(byte[] nv21, int width, int height, int angle, int mirror) {
        if (recognizing || !punchEnabled) return;
        PunchApplication app = PunchApplication.get();
        if (app != null && !app.isPunchRecognitionReady()) {
            app.preparePunchRecognitionData();
            uiHandler.post(() -> setStatus(app.getPunchDataStatus()));
            return;
        }
        if (!FaceManager.get().isInitialized()) {
            if (app != null) {
                app.preparePunchRecognitionData();
            }
            uiHandler.post(() -> setStatus("\u4eba\u8138\u5f15\u64ce\u521d\u59cb\u5316\u4e2d..."));
            return;
        }

        long startedAt = System.currentTimeMillis();
        FaceManager.RecognizeResult result =
                FaceManager.get().recognizeFromNv21(nv21, width, height, angle, mirror);
        long durationMs = System.currentTimeMillis() - startedAt;

        if (!result.matched) {
            if (FaceManager.ERROR_NO_FACE_DETECTED.equals(result.errorMsg)) {
                resetRecognitionAttempt();
                return;
            }

            beginRecognitionAttempt();
            long now = System.currentTimeMillis();
            if (now - recognitionAttemptStartedAt >= getRecognitionTimeoutMs()
                    && now - lastRecognitionTimeoutAt >= RECOGNITION_TIMEOUT_FEEDBACK_COOLDOWN_MS) {
                lastRecognitionTimeoutAt = now;
                uiHandler.post(() -> showTransientFailureResult("\u8bc6\u522b\u8d85\u65f6\n\u8bf7\u91cd\u8bd5"));
                return;
            }

            if (!FaceManager.ERROR_NO_FACE_DETECTED.equals(result.errorMsg)) {
                uiHandler.post(() -> setStatus(result.errorMsg != null ? result.errorMsg : "\u6b63\u5728\u8bc6\u522b..."));
            }
            return;
        }

        resetRecognitionAttempt();

        Employee emp = DatabaseHelper.get(requireContext()).getEmployee(result.empId);
        if (emp == null) {
            uiHandler.post(() -> {
                setStatus("\u672a\u627e\u5230\u5458\u5de5\u6570\u636e");
                playFailFeedback();
            });
            return;
        }

        uiHandler.post(() -> doPunch(emp, result.score, durationMs));
    }

    
    private void updatePunchTypeFromSelection() {
        if (isSelectedFreePunch()) {
            punchType = PUNCH_TYPE_FREE;
        } else {
            punchType = isSelectedSignIn()
                    ? Constants.PUNCH_TYPE_SIGN_IN
                    : Constants.PUNCH_TYPE_SIGN_OUT;
        }
    }

    
    private String getSelectedPunchOptionLabel() {
        Object selected = spinnerShift != null ? spinnerShift.getSelectedItem() : null;
        return selected != null ? selected.toString() : FREE_PUNCH_OPTION_LABEL;
    }

    
    private boolean isSelectedSignIn() {
        return getSelectedPunchOptionLabel().endsWith("\u4e0a\u73ed");
    }

    private boolean isSelectedFreePunch() {
        return spinnerShift != null && spinnerShift.getSelectedItemPosition() == FREE_PUNCH_OPTION_VALUE;
    }

    
    private long resolvePunchTimeSeconds() {
        if (!specialTimeEnabled) {
            return System.currentTimeMillis() / 1000;
        }
        return resolveScheduledPunchTimeSeconds(getSelectedPunchOptionLabel());
    }

    
    private long resolveScheduledPunchTimeSeconds(String optionLabel) {
        return PunchTimeResolver.resolveScheduledPunchTimeSeconds(
                optionLabel,
                System.currentTimeMillis()
        );
    }

    
    private String buildPunchDate(long punchTimeSeconds) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(new Date(punchTimeSeconds * 1000));
    }

    /** Leave and rest statuses are both forbidden for punching. */
    private boolean isForbiddenStatus(String status) {
        return Constants.STATUS_LEAVE.equals(status) || Constants.STATUS_REST.equals(status);
    }

    
    private String getStatusLabel(String status) {
        if (Constants.STATUS_LEAVE.equals(status)) {
            return "\u8bf7\u5047";
        }
        if (Constants.STATUS_REST.equals(status)) {
            return "\u4f11\u606f";
        }
        return "\u6b63\u5e38";
    }

    
    private void showForbiddenPunchDialog(Employee emp, String status) {
        uiHandler.post(() -> {
            String statusLabel = getStatusLabel(status);
            setStatus("\u7981\u6b62\u6253\u5361\uff1a" + statusLabel);
            playForbiddenFeedback();

            applyResultIconStyle(false);
            tvResult.setText(String.format(
                    Locale.getDefault(),
                    "%s\n\u7981\u6b62\u6253\u5361\n%s",
                    emp.name, statusLabel));
            layoutResult.setVisibility(View.VISIBLE);

            uiHandler.postDelayed(() -> {
                layoutResult.setVisibility(View.GONE);
                recognizing = false;
                resetRecognitionAttempt();
                updateIdleStatus();
            }, RESULT_DISPLAY_MS);
        });
    }

    private void doPunch(Employee emp, float matchScore, long durationMs) {
        recognizing = true;

        String deviceId = SessionManager.get().getDeviceId();
        String lineCode = SessionManager.get().getLineCode();
        String status = emp.status != null ? emp.status : Constants.STATUS_NORMAL;
        String shiftLabel = getSelectedPunchOptionLabel();
        long punchTime = resolvePunchTimeSeconds();
        String punchDate = buildPunchDate(punchTime);

        if (isForbiddenStatus(status)) {
            showForbiddenPunchDialog(emp, status);
            return;
        }

        PunchRecord record = new PunchRecord();
        record.id = UlidGenerator.generate();
        record.clientRecordId = "P" + deviceId + "_" + UlidGenerator.generate();
        record.empId = emp.id;
        record.empName = emp.name;
        record.dept = emp.dept;
        record.punchTime = punchTime;
        record.punchDate = punchDate;
        record.punchType = punchType;
        record.shiftName = shiftLabel;
        record.lineCode = lineCode;
        record.isSynced = 0;

        savePunchAndSync(record);
    }

    
    private void savePunchAndSync(PunchRecord record) {
        boolean inserted = DatabaseHelper.get(requireContext()).insertPunchRecord(record);
        if (!inserted) {
            uiHandler.post(() -> {
                recognizing = false;
                resetRecognitionAttempt();
                setStatus("\u6253\u5361\u8bb0\u5f55\u5df2\u5b58\u5728");
                playFailFeedback();
            });
            return;
        }
        DatabaseHelper.get(requireContext()).enqueueSyncItem(
                record.clientRecordId, Constants.ACTION_PUNCH_PUSH);

        executor.execute(() -> {
            boolean synced = false;
            boolean online = ApiService.isBackendAvailable();
            if (online) {
                ApiResult<PunchDto.PunchPushData> result = ApiService.pushPunch(record);
                if (result.success) {
                    DatabaseHelper.get(requireContext()).markPunchSynced(record.id);
                    DatabaseHelper.get(requireContext()).removeSyncQueueItem(
                            getQueueId(Constants.ACTION_PUNCH_PUSH, record.clientRecordId));
                    synced = true;
                }
            }
            final boolean finalSynced = synced;
            uiHandler.post(() -> showPunchResult(record, finalSynced));
        });
    }

    
    private void showPunchResult(PunchRecord record, boolean synced) {
        String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(record.punchTime * 1000));
        String typeStr;
        if (PUNCH_TYPE_FREE.equals(record.punchType)) {
            typeStr = FREE_PUNCH_OPTION_LABEL;
        } else {
            typeStr = Constants.PUNCH_TYPE_SIGN_IN.equals(record.punchType)
                    ? "\u4e0a\u73ed\u6253\u5361"
                    : "\u4e0b\u73ed\u6253\u5361";
        }
        String syncStr = synced ? "\u5df2\u540c\u6b65" : "\u5df2\u79bb\u7ebf\u4fdd\u5b58";

        applyResultIconStyle(true);
        tvResult.setText(record.empName + "\n" + typeStr + " " + timeStr + "\n" + syncStr);
        layoutResult.setVisibility(View.VISIBLE);
        playPunchFeedback(record);

        uiHandler.postDelayed(() -> {
            recognizing = false;
            resetRecognitionAttempt();
            layoutResult.setVisibility(View.GONE);
            updateIdleStatus();
        }, RESULT_DISPLAY_MS);

        SyncService.triggerSync(requireContext());
    }

    private void renderPunchStatusSnapshot(PunchApplication.PunchStatusSnapshot snapshot) {
        if (!isAdded() || snapshot == null || tvPunchStatusCurrent == null) {
            return;
        }
        tvPunchStatusCurrent.setText(snapshot.currentStatus);
        bindStatusLevelChip(snapshot.currentLevel);
        if (snapshot.shouldExpandHistory && !statusHistoryExpanded) {
            statusHistoryExpanded = true;
            PunchApplication app = PunchApplication.get();
            if (app != null) {
                app.clearPunchStatusAttention();
            }
        }
        renderStatusHistory(snapshot.recentEntries);
        updateStatusHistoryVisibility();
        showStatusPanel(true);
    }

    private void bindStatusLevelChip(int level) {
        int fillColor;
        String label;
        switch (level) {
            case PunchApplication.STATUS_LEVEL_SUCCESS:
                fillColor = ContextCompat.getColor(requireContext(), R.color.green);
                label = "已就绪";
                break;
            case PunchApplication.STATUS_LEVEL_ERROR:
                fillColor = ContextCompat.getColor(requireContext(), R.color.red);
                label = "异常";
                break;
            case PunchApplication.STATUS_LEVEL_INFO:
                fillColor = ContextCompat.getColor(requireContext(), R.color.primary);
                label = "提示";
                break;
            case PunchApplication.STATUS_LEVEL_PROGRESS:
            default:
                fillColor = ContextCompat.getColor(requireContext(), R.color.orange);
                label = "进行中";
                break;
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(12));
        tvPunchStatusLevel.setBackground(background);
        tvPunchStatusLevel.setText(label);
        tvPunchStatusHint.setText(level == PunchApplication.STATUS_LEVEL_ERROR
                ? "存在异常，已展开最近状态"
                : "点击摄像头区域可再次查看状态");
    }

    private void renderStatusHistory(List<PunchApplication.PunchStatusEntry> entries) {
        if (layoutPunchStatusHistory == null) {
            return;
        }
        layoutPunchStatusHistory.removeAllViews();
        if (entries == null || entries.isEmpty()) {
            addStatusHistoryRow("--:--:--", "等待新的状态更新", PunchApplication.STATUS_LEVEL_INFO);
            return;
        }
        for (PunchApplication.PunchStatusEntry entry : entries) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(entry.timeMillis));
            addStatusHistoryRow(time, entry.message, entry.level);
        }
    }

    private void addStatusHistoryRow(String time, String message, int level) {
        if (layoutPunchStatusHistory == null) {
            return;
        }
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dpInt(4), 0, dpInt(4));

        TextView timeView = new TextView(requireContext());
        timeView.setText(time);
        timeView.setTextColor(0xFFD7E0EA);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(dpInt(52), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(timeView, timeParams);

        View dot = new View(requireContext());
        GradientDrawable dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(resolveStatusLevelColor(level));
        dot.setBackground(dotDrawable);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dpInt(8), dpInt(8));
        dotParams.topMargin = dpInt(4);
        dotParams.rightMargin = dpInt(8);
        row.addView(dot, dotParams);

        TextView messageView = new TextView(requireContext());
        messageView.setText(message);
        messageView.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        messageView.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(messageView, messageParams);

        layoutPunchStatusHistory.addView(row);
    }

    private void setStatusHistoryExpanded(boolean expanded) {
        statusHistoryExpanded = expanded;
        if (expanded) {
            PunchApplication app = PunchApplication.get();
            if (app != null) {
                app.clearPunchStatusAttention();
            }
        }
        updateStatusHistoryVisibility();
        showStatusPanel(true);
    }

    private void updateStatusHistoryVisibility() {
        if (layoutPunchStatusHistory == null || tvPunchStatusToggle == null || tvPunchStatusHint == null) {
            return;
        }
        layoutPunchStatusHistory.setVisibility(statusHistoryExpanded ? View.VISIBLE : View.GONE);
        tvPunchStatusHint.setVisibility(statusHistoryExpanded ? View.VISIBLE : View.GONE);
        tvPunchStatusToggle.setText(statusHistoryExpanded ? "收起" : "详情");
    }

    private void revealStatusPanel() {
        showStatusPanel(false);
    }

    private void showStatusPanel(boolean fromStatusUpdate) {
        if (layoutPunchStatusPanel == null) {
            return;
        }
        uiHandler.removeCallbacks(hideStatusPanelRunnable);
        layoutPunchStatusPanel.animate().cancel();
        if (layoutPunchStatusPanel.getVisibility() != View.VISIBLE) {
            layoutPunchStatusPanel.setVisibility(View.VISIBLE);
            layoutPunchStatusPanel.setAlpha(0f);
            layoutPunchStatusPanel.animate()
                    .alpha(1f)
                    .setDuration(220L)
                    .start();
        } else if (layoutPunchStatusPanel.getAlpha() < 1f) {
            layoutPunchStatusPanel.animate()
                    .alpha(1f)
                    .setDuration(180L)
                    .start();
        }
        if (!statusHistoryExpanded) {
            long delay = fromStatusUpdate ? STATUS_PANEL_AUTO_HIDE_DELAY_MS : STATUS_PANEL_AUTO_HIDE_DELAY_MS + 1200L;
            uiHandler.postDelayed(hideStatusPanelRunnable, delay);
        }
    }

    private void fadeOutStatusPanel() {
        if (layoutPunchStatusPanel == null || statusHistoryExpanded || !isAdded()) {
            return;
        }
        layoutPunchStatusPanel.animate()
                .alpha(0f)
                .setDuration(STATUS_PANEL_FADE_DURATION_MS)
                .withEndAction(() -> {
                    if (layoutPunchStatusPanel != null && !statusHistoryExpanded) {
                        layoutPunchStatusPanel.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private int resolveStatusLevelColor(int level) {
        switch (level) {
            case PunchApplication.STATUS_LEVEL_SUCCESS:
                return ContextCompat.getColor(requireContext(), R.color.green);
            case PunchApplication.STATUS_LEVEL_ERROR:
                return ContextCompat.getColor(requireContext(), R.color.red);
            case PunchApplication.STATUS_LEVEL_INFO:
                return ContextCompat.getColor(requireContext(), R.color.primary);
            case PunchApplication.STATUS_LEVEL_PROGRESS:
            default:
                return ContextCompat.getColor(requireContext(), R.color.orange);
        }
    }

    private float dp(int value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                requireContext().getResources().getDisplayMetrics()
        );
    }

    private int dpInt(int value) {
        return Math.round(dp(value));
    }

    
    private void setStatus(String msg) {
        if (isAdded()) tvStatus.setText(msg);
    }

    private void showCameraLoading(String message) {
        if (layoutCameraLoading != null) {
            layoutCameraLoading.setVisibility(View.VISIBLE);
        }
        if (tvCameraLoading != null) {
            tvCameraLoading.setText(message);
        }
    }

    private void hideCameraLoading() {
        if (layoutCameraLoading != null) {
            layoutCameraLoading.setVisibility(View.GONE);
        }
    }

    
    private void applyResultIconStyle(boolean success) {
        if (tvResultIcon == null) {
            return;
        }
        tvResultIcon.setText(success ? "\u2713" : "!");
        tvResultIcon.setBackgroundResource(success
                ? R.drawable.bg_result_icon_success
                : R.drawable.bg_result_icon_error);
        tvResultIcon.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
    }

    
    private void setScreenOnLocked(boolean keepScreenOn) {
        if (!isAdded()) {
            return;
        }
        Window window = requireActivity().getWindow();
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }


    private int getQueueId(String action, String recordId) {
        List<SyncQueueItem> queue = DatabaseHelper.get(requireContext()).getSyncQueue(action);
        for (SyncQueueItem item : queue) {
            if (recordId.equals(item.recordId)) return item.id;
        }
        return -1;
    }

    
    @Override
    public void onResume() {
        super.onResume();
        punchActive = !isHidden();
        setPunchEnabled(false);
        setScreenOnLocked(true);
        refreshBindingHeader();
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            renderPunchStatusSnapshot(app.getPunchStatusSnapshot());
        }
        rebuildPunchOptions();
        ensureCameraReady();
    }

    @Override
    public void onPause() {
        punchActive = false;
        setPunchEnabled(false);
        waitingFirstPreviewFrame = false;
        hideCameraLoading();
        uiHandler.removeCallbacks(hideStatusPanelRunnable);
        setScreenOnLocked(false);
        super.onPause();
        if (previewFullscreen) {
            setPreviewFullscreen(false);
        }
        releaseCamera();
    }

    private void refreshBindingHeader() {
        if (tvLine != null) {
            String lineName = SessionManager.get().getLineName();
            if (lineName == null || lineName.trim().isEmpty()) {
                lineName = SessionManager.get().getLineCode();
            }
            if (lineName == null || lineName.trim().isEmpty()) {
                lineName = "未绑定线体";
            }
            tvLine.setText(lineName.trim());
        }
        if (tvTeam != null) {
            String teamName = SessionManager.get().getTeamBindingName();
            if (teamName == null || teamName.trim().isEmpty()) {
                tvTeam.setVisibility(View.GONE);
            } else {
                tvTeam.setVisibility(View.VISIBLE);
                tvTeam.setText(teamName.trim());
            }
        }
    }

    @Override
    public void onDestroyView() {
        setScreenOnLocked(false);
        waitingFirstPreviewFrame = false;
        hideCameraLoading();
        uiHandler.removeCallbacks(hideStatusPanelRunnable);
        if (previewFullscreen) {
            setPreviewFullscreen(false);
        }
        super.onDestroyView();
        releaseAudioFeedback();
    }

    private void rebuildPunchOptions() {
        if (spinnerShift == null || punchOptionAdapter == null) {
            return;
        }
        String currentSelection = getSelectedPunchOptionLabel();
        punchOptions.clear();
        punchOptions.add(FREE_PUNCH_OPTION_LABEL);
        for (String timeRange : SessionManager.get().getCurrentTeamTimeRanges()) {
            if (timeRange == null) {
                continue;
            }
            String range = timeRange.trim();
            if (range.isEmpty()) {
                continue;
            }
            punchOptions.add(range + " \u4e0a\u73ed");
            punchOptions.add(range + " \u4e0b\u73ed");
        }
        punchOptionAdapter.notifyDataSetChanged();
        int selectedIndex = punchOptions.indexOf(currentSelection);
        if (selectedIndex < 0) {
            selectedIndex = FREE_PUNCH_OPTION_VALUE;
        }
        spinnerShift.setSelection(selectedIndex);
        updatePunchTypeFromSelection();
    }
}




