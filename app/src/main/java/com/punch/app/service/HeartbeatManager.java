package com.punch.app.service;

import android.content.Context;

import com.punch.app.network.InteractionLogger;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class HeartbeatManager {
    private static final String TAG = "HeartbeatManager";

    private static HeartbeatManager instance;

    private final Context appContext;
    private ScheduledExecutorService scheduler;
    private boolean started;

    private HeartbeatManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static synchronized HeartbeatManager get(Context context) {
        if (instance == null) {
            instance = new HeartbeatManager(context);
        }
        return instance;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        if (!SessionManager.get().isTokenValid()) {
            AppLogger.d(TAG, "Skip starting heartbeat scheduler: token missing or expired");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(
                () -> SyncCoordinator.get().enqueueHeartbeatCycle(appContext, SyncTrigger.HEARTBEAT),
                Constants.HEARTBEAT_INITIAL_DELAY_MS,
                Constants.HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        started = true;
        AppLogger.d(TAG, "Heartbeat scheduler started");
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_HEARTBEAT,
                "心跳调度已启动",
                "间隔 " + (Constants.HEARTBEAT_INTERVAL_MS / 1000L) + " 秒"
        );
    }

    public void triggerNow() {
        triggerNow(SyncTrigger.AFTER_PUNCH);
    }

    public void triggerNow(boolean forcePunchRetry) {
        triggerNow(forcePunchRetry ? SyncTrigger.MANUAL : SyncTrigger.AFTER_PUNCH);
    }

    public void triggerNow(SyncTrigger trigger) {
        if (!SessionManager.get().isTokenValid()) {
            AppLogger.d(TAG, "Skip triggerNow: token missing or expired");
            return;
        }
        SyncTrigger safeTrigger = trigger != null ? trigger : SyncTrigger.AFTER_PUNCH;
        start();
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_HEARTBEAT,
                "立即触发心跳同步",
                "trigger=" + safeTrigger.name()
        );
        SyncCoordinator.get().enqueueHeartbeatCycle(appContext, safeTrigger);
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        started = false;
        AppLogger.d(TAG, "Heartbeat scheduler stopped");
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_HEARTBEAT,
                "心跳调度已停止",
                "当前不再自动向平台发送心跳"
        );
    }
}
