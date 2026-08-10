package com.punch.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.punch.app.activation.ActivationManager;
import com.punch.app.activity.KioskHomeActivity;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.face.FaceManager;
import com.punch.app.network.InteractionLogStore;
import com.punch.app.service.HeartbeatManager;
import com.punch.app.service.SyncCoordinator;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UpdateManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PunchApplication extends Application {
    private static final String TAG = "PunchApplication";
    private static final long FACE_SDK_READY_TIMEOUT_MS = 120_000L;
    private static final long KIOSK_RESTORE_DELAY_MS = 250L;
    private static final long KIOSK_FOREGROUND_WATCHDOG_INTERVAL_MS = 150L;
    private static final int MAX_STATUS_HISTORY = 5;

    public static final int STATUS_LEVEL_INFO = 0;
    public static final int STATUS_LEVEL_PROGRESS = 1;
    public static final int STATUS_LEVEL_SUCCESS = 2;
    public static final int STATUS_LEVEL_ERROR = 3;

    private static PunchApplication instance;
    private static volatile boolean uiTestModeEnabled;

    private volatile boolean faceSdkInitializing;
    private volatile boolean punchDataPreparing;
    private volatile boolean punchDataReady;
    private volatile String punchDataStatus = "正在准备打卡数据...";
    private volatile int punchDataStatusLevel = STATUS_LEVEL_PROGRESS;
    private volatile boolean punchStatusAttention;
    private volatile int resumedNonHomeActivityCount;
    private volatile long lastNonHomeActivityVisibleAt;
    private volatile Class<? extends Activity> lastNonHomeActivityClass;
    private volatile boolean kioskForegroundWatchdogRunning;

    private final ExecutorService appExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object statusLock = new Object();
    private final Deque<PunchStatusEntry> recentStatusEntries = new ArrayDeque<>();
    private final List<PunchStatusListener> statusListeners = new CopyOnWriteArrayList<>();

    public static PunchApplication get() {
        return instance;
    }

    public static void setUiTestModeForTest(boolean enabled) {
        uiTestModeEnabled = enabled;
    }

    public static boolean isUiTestModeEnabled() {
        return uiTestModeEnabled;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        if (uiTestModeEnabled) {
            SessionManager.get().init(this);
            return;
        }

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
                if (activity instanceof KioskHomeActivity) {
                    return;
                }
                KioskManager.enterIfPossible(activity);
                startKioskForegroundWatchdog();
                resumedNonHomeActivityCount += 1;
                lastNonHomeActivityVisibleAt = System.currentTimeMillis();
                lastNonHomeActivityClass = activity.getClass();
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (activity instanceof KioskHomeActivity) {
                    return;
                }
                resumedNonHomeActivityCount = Math.max(0, resumedNonHomeActivityCount - 1);
                lastNonHomeActivityVisibleAt = System.currentTimeMillis();
                scheduleKioskTaskRestore();
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });

        SessionManager.get().init(this);
        if (KioskManager.isDeviceOwner(this)) {
            SessionManager.get().saveKioskEnabled(true);
            KioskManager.ensureOwnerKioskPolicies(this);
            startKioskForegroundWatchdog();
        }
        InteractionLogStore.init(this); // 日志在应用启动就初始化
        DatabaseHelper.get(this);
        ActivationManager.get().ensureDeviceRegistered(this);

        if (SessionManager.get().isTokenValid()) {
            initFaceSDK();
            preparePunchRecognitionData();
            startSyncService(); // 心跳/同步只在 token 有效时启动
            UpdateManager.startBackgroundUpdateIfEligible(this, "app_start");
        }
    }

    public boolean wasNonHomeActivityRecentlyVisible(long windowMs) {
        if (resumedNonHomeActivityCount > 0) {
            return true;
        }
        long lastVisibleAt = lastNonHomeActivityVisibleAt;
        return lastVisibleAt > 0L && System.currentTimeMillis() - lastVisibleAt <= windowMs;
    }

    public Class<? extends Activity> getLastNonHomeActivityClass() {
        return lastNonHomeActivityClass;
    }

    private void scheduleKioskTaskRestore() {
        mainHandler.postDelayed(() -> {
            if (!SessionManager.get().isKioskEnabled() || resumedNonHomeActivityCount > 0) {
                return;
            }
            KioskManager.bringExistingAppTaskToFront(this, -1);
        }, KIOSK_RESTORE_DELAY_MS);
    }

    private void startKioskForegroundWatchdog() {
        if (kioskForegroundWatchdogRunning) {
            return;
        }
        kioskForegroundWatchdogRunning = true;
        mainHandler.post(kioskForegroundWatchdogRunnable);
    }

    private final Runnable kioskForegroundWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!SessionManager.get().isKioskEnabled() || !KioskManager.isDeviceOwner(PunchApplication.this)) {
                kioskForegroundWatchdogRunning = false;
                return;
            }
            if (resumedNonHomeActivityCount <= 0) {
                KioskManager.bringExistingAppTaskToFrontQuietly(PunchApplication.this, -1);
            }
            mainHandler.postDelayed(this, KIOSK_FOREGROUND_WATCHDOG_INTERVAL_MS);
        }
    };

    public void initFaceSDK() {
        if (!SessionManager.get().isTokenValid()) {
            return;
        }
        if (faceSdkInitializing || FaceManager.get().isInitialized()) {
            return;
        }
        faceSdkInitializing = true;

        ActivationManager.get().prepareActivation(this, (ready, failureMessage) -> {
            if (!ready) {
                faceSdkInitializing = false;
                markPunchRecognitionFailed(
                        failureMessage == null || failureMessage.trim().isEmpty()
                                ? "人脸引擎初始化失败"
                                : failureMessage.trim()
                );
                AppLogger.w(TAG, "Activation preparation failed: " + failureMessage);
                return;
            }
            FaceManager.get().init(PunchApplication.this, "idl-license.face-android", new FaceManager.InitCallback() {
                @Override
                public void onSuccess() {
                    faceSdkInitializing = false;
                    reportStatusEvent("人脸引擎初始化完成", STATUS_LEVEL_SUCCESS);
                    AppLogger.i(TAG, "Face SDK ready");
                }

                @Override
                public void onError(int code, String msg) {
                    faceSdkInitializing = false;
                    markPunchRecognitionFailed("人脸引擎初始化失败");
                    AppLogger.e(TAG, "Face SDK init error: " + msg);
                }
            });
        });
    }

    public void preparePunchRecognitionData() {
        if (!SessionManager.get().isTokenValid()) {
            return;
        }
        if (punchDataPreparing || punchDataReady) {
            return;
        }
        beginPunchDataPreparation("正在准备打卡数据...");
        appExecutor.execute(this::runPunchPreparation);
    }

    public boolean isPunchRecognitionReady() {
        return punchDataReady;
    }

    public boolean isPunchDataPreparing() {
        return punchDataPreparing;
    }

    public String getPunchDataStatus() {
        return punchDataStatus;
    }

    public int getPunchDataStatusLevel() {
        return punchDataStatusLevel;
    }

    public void resetPunchRecognitionState() {
        punchDataPreparing = false;
        punchDataReady = false;
        punchDataStatus = "正在准备打卡数据...";
        punchDataStatusLevel = STATUS_LEVEL_PROGRESS;
    }

    public void resetPunchStatusTimeline(String status, int level) {
        synchronized (statusLock) {
            recentStatusEntries.clear();
            punchStatusAttention = false;
        }
        pushStatus(status, level, true, false);
    }

    public void addPunchStatusListener(PunchStatusListener listener) {
        if (listener == null) {
            return;
        }
        statusListeners.add(listener);
        notifyListener(listener, getPunchStatusSnapshot());
    }

    public void removePunchStatusListener(PunchStatusListener listener) {
        if (listener == null) {
            return;
        }
        statusListeners.remove(listener);
    }

    public PunchStatusSnapshot getPunchStatusSnapshot() {
        synchronized (statusLock) {
            return new PunchStatusSnapshot(
                    punchDataStatus,
                    punchDataStatusLevel,
                    punchStatusAttention,
                    new ArrayList<>(recentStatusEntries)
            );
        }
    }

    public void clearPunchStatusAttention() {
        synchronized (statusLock) {
            punchStatusAttention = false;
        }
    }

    public void reportStatusEvent(String status, int level) {
        pushStatus(status, level, false, level == STATUS_LEVEL_ERROR);
    }

    public void setCurrentPunchStatus(String status, int level) {
        pushStatus(status, level, true, level == STATUS_LEVEL_ERROR);
    }

    public void beginPunchDataPreparation(String status) {
        punchDataPreparing = true;
        punchDataReady = false;
        pushStatus(status, STATUS_LEVEL_PROGRESS, true, false);
    }

    public void updatePunchDataPreparationStatus(String status) {
        punchDataReady = false;
        pushStatus(status, STATUS_LEVEL_PROGRESS, true, false);
    }

    public void markPunchRecognitionReady(String status) {
        punchDataPreparing = false;
        punchDataReady = true;
        pushStatus(status, STATUS_LEVEL_SUCCESS, true, false);
    }

    public void markPunchRecognitionFailed(String status) {
        punchDataPreparing = false;
        punchDataReady = false;
        pushStatus(status, STATUS_LEVEL_ERROR, true, true);
    }

    public void startSyncService() {
        if (!SessionManager.get().isTokenValid()) {
            return;
        }
        try {
            HeartbeatManager.get(this).start();
        } catch (Exception e) {
            Log.e(TAG, "startSyncService error", e);
        }
    }

    private void runPunchPreparation() {
        beginPunchDataPreparation("正在初始化人脸引擎...");
        initFaceSDK();
        if (!waitForFaceSdkReady()) {
            markPunchRecognitionFailed("人脸引擎初始化失败");
            return;
        }

        if (DatabaseHelper.get(this).getActiveEmployeeCount() <= 0) {
            if (!SyncCoordinator.get().syncEmployeesForPreparation(this)) {
                markPunchRecognitionFailed("员工同步失败，等待重试");
                return;
            }
        } else {
            beginPunchDataPreparation("正在重建人脸库...");
            if (!SyncCoordinator.get().rebuildLocalFaceLibrary(this)) {
                markPunchRecognitionFailed("人脸库重建失败，请稍后重试");
                return;
            }
        }
    }

    private boolean waitForFaceSdkReady() {
        long deadline = System.currentTimeMillis() + FACE_SDK_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (FaceManager.get().isInitialized()) {
                return true;
            }
            if (!faceSdkInitializing) {
                return false;
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void pushStatus(String status, int level, boolean keepAsCurrent, boolean requestAttention) {
        String message = status == null ? "" : status.trim();
        if (message.isEmpty()) {
            return;
        }
        synchronized (statusLock) {
            if (keepAsCurrent) {
                punchDataStatus = message;
                punchDataStatusLevel = level;
            }
            if (requestAttention) {
                punchStatusAttention = true;
            }
            addHistoryLocked(new PunchStatusEntry(System.currentTimeMillis(), message, level));
        }
        notifyStatusChanged();
    }

    private void addHistoryLocked(PunchStatusEntry entry) {
        PunchStatusEntry latest = recentStatusEntries.peekFirst();
        if (latest != null
                && latest.level == entry.level
                && latest.message.equals(entry.message)) {
            return;
        }
        recentStatusEntries.addFirst(entry);
        while (recentStatusEntries.size() > MAX_STATUS_HISTORY) {
            recentStatusEntries.removeLast();
        }
    }

    private void notifyStatusChanged() {
        PunchStatusSnapshot snapshot = getPunchStatusSnapshot();
        for (PunchStatusListener listener : statusListeners) {
            notifyListener(listener, snapshot);
        }
    }

    private void notifyListener(PunchStatusListener listener, PunchStatusSnapshot snapshot) {
        mainHandler.post(() -> listener.onPunchStatusChanged(snapshot));
    }

    public interface PunchStatusListener {
        void onPunchStatusChanged(PunchStatusSnapshot snapshot);
    }

    public static final class PunchStatusSnapshot {
        public final String currentStatus;
        public final int currentLevel;
        public final boolean shouldExpandHistory;
        public final List<PunchStatusEntry> recentEntries;

        private PunchStatusSnapshot(String currentStatus,
                                    int currentLevel,
                                    boolean shouldExpandHistory,
                                    List<PunchStatusEntry> recentEntries) {
            this.currentStatus = currentStatus;
            this.currentLevel = currentLevel;
            this.shouldExpandHistory = shouldExpandHistory;
            this.recentEntries = recentEntries;
        }
    }

    public static final class PunchStatusEntry {
        public final long timeMillis;
        public final String message;
        public final int level;

        private PunchStatusEntry(long timeMillis, String message, int level) {
            this.timeMillis = timeMillis;
            this.message = message;
            this.level = level;
        }
    }
}
