package com.punch.app.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import com.punch.app.network.ApiService;
import com.punch.app.service.SyncService;
import com.punch.app.service.SyncTrigger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class WifiReconnectCoordinator {
    private static final String TAG = "WifiReconnect";
    private static final long CONNECT_SUBMITTED_VERIFY_DELAY_MS = 5_000L;
    private static final long WIFI_ENABLING_RETRY_DELAY_MS = 2_000L;

    private static WifiReconnectCoordinator instance;

    private final Deps deps;
    private final Scheduler scheduler;

    private Cancellable pendingTask;
    private boolean started;
    private boolean observedOffline;
    private boolean connectionAttemptPending;
    private boolean manualWifiConfigurationInProgress;
    private int wifiFailureCount;
    private int backendFailureCount;
    private long wifiStateGeneration;

    WifiReconnectCoordinator(Deps deps, Scheduler scheduler) {
        this.deps = deps;
        this.scheduler = scheduler;
    }

    public static synchronized WifiReconnectCoordinator get(Context context) {
        if (instance == null) {
            Context appContext = context.getApplicationContext();
            if (appContext == null) {
                appContext = context;
            }
            instance = new WifiReconnectCoordinator(
                    new AndroidDeps(appContext),
                    new AndroidScheduler()
            );
        }
        return instance;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        deps.registerNetworkListener(new NetworkListener() {
            @Override
            public void onWifiAvailable() {
                handleWifiAvailable();
            }

            @Override
            public void onWifiLost() {
                handleWifiLost();
            }
        });
        scheduleVerification(0L);
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        cancelPendingTask();
        deps.unregisterNetworkListener();
    }

    public synchronized void requestReconnect(String reason) {
        if (!started || manualWifiConfigurationInProgress) {
            return;
        }
        deps.log("Reconnect requested: " + safeString(reason));
        scheduleVerification(0L);
    }

    public synchronized void beginManualWifiConfiguration(String reason) {
        if (manualWifiConfigurationInProgress) {
            return;
        }
        manualWifiConfigurationInProgress = true;
        wifiStateGeneration++;
        connectionAttemptPending = false;
        backendFailureCount = 0;
        cancelPendingTask();
        deps.log("Manual wifi configuration started: " + safeString(reason));
    }

    public synchronized void finishManualWifiConfiguration(String reason) {
        if (!manualWifiConfigurationInProgress) {
            return;
        }
        manualWifiConfigurationInProgress = false;
        wifiStateGeneration++;
        connectionAttemptPending = false;
        backendFailureCount = 0;
        deps.log("Manual wifi configuration finished: " + safeString(reason));
        scheduleVerification(0L);
    }

    private synchronized void handleWifiAvailable() {
        if (manualWifiConfigurationInProgress) {
            return;
        }
        scheduleVerification(2_000L);
    }

    private synchronized void scheduleVerification(long delayMillis) {
        schedule(this::verifyConnection, delayMillis);
    }

    private synchronized void scheduleReconnect(long delayMillis) {
        schedule(this::attemptReconnect, delayMillis);
    }

    private synchronized void schedule(Runnable runnable, long delayMillis) {
        if (!started || manualWifiConfigurationInProgress) {
            return;
        }
        cancelPendingTask();
        pendingTask = scheduler.schedule(runnable, Math.max(0L, delayMillis));
    }

    private synchronized void cancelPendingTask() {
        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }
    }

    private void verifyConnection() {
        long verificationGeneration;
        synchronized (this) {
            if (!started || manualWifiConfigurationInProgress) {
                return;
            }
            verificationGeneration = wifiStateGeneration;
        }
        if (!deps.isConnectedToSavedWifi()) {
            handleDisconnectedVerification(verificationGeneration);
            return;
        }
        boolean backendAvailable = deps.isBackendAvailable();
        synchronized (this) {
            if (!started
                    || manualWifiConfigurationInProgress
                    || verificationGeneration != wifiStateGeneration) {
                return;
            }
            if (!deps.isConnectedToSavedWifi()) {
                handleDisconnectedVerification(verificationGeneration);
                return;
            }
            connectionAttemptPending = false;
            wifiFailureCount = 0;
            if (!backendAvailable) {
                backendFailureCount++;
                scheduleVerification(retryDelayMillis(backendFailureCount));
                return;
            }
            backendFailureCount = 0;
            boolean shouldTriggerSync = observedOffline;
            observedOffline = false;
            if (shouldTriggerSync && deps.hasValidToken()) {
                deps.triggerNetworkRestoredSync();
            }
        }
    }

    private synchronized void handleWifiLost() {
        wifiStateGeneration++;
        observedOffline = true;
        connectionAttemptPending = false;
        backendFailureCount = 0;
        if (manualWifiConfigurationInProgress) {
            return;
        }
        scheduleReconnect(0L);
    }

    private synchronized void handleDisconnectedVerification(long verificationGeneration) {
        if (!started
                || manualWifiConfigurationInProgress
                || verificationGeneration != wifiStateGeneration) {
            return;
        }
        observedOffline = true;
        backendFailureCount = 0;
        if (connectionAttemptPending) {
            connectionAttemptPending = false;
            wifiFailureCount++;
            scheduleReconnect(retryDelayMillis(wifiFailureCount));
            return;
        }
        scheduleReconnect(0L);
    }

    private void attemptReconnect() {
        if (!isAutoReconnectActive()) {
            return;
        }
        WifiAutoReconnectManager.AttemptResult result =
                deps.attemptSavedWifiConnection();
        handleReconnectResult(result);
    }

    private synchronized void handleReconnectResult(
            WifiAutoReconnectManager.AttemptResult result
    ) {
        if (!started || manualWifiConfigurationInProgress) {
            return;
        }
        switch (result) {
            case ALREADY_CONNECTED:
                connectionAttemptPending = false;
                scheduleVerification(2_000L);
                return;
            case WIFI_ENABLING:
                scheduleReconnect(WIFI_ENABLING_RETRY_DELAY_MS);
                return;
            case SUBMITTED:
                connectionAttemptPending = true;
                scheduleVerification(CONNECT_SUBMITTED_VERIFY_DELAY_MS);
                return;
            case WIFI_SERVICE_UNAVAILABLE:
            case FAILED:
                wifiFailureCount++;
                scheduleReconnect(retryDelayMillis(wifiFailureCount));
                return;
            default:
                connectionAttemptPending = false;
                deps.log("Reconnect paused: result=" + result.name());
        }
    }

    private synchronized boolean isStarted() {
        return started;
    }

    private synchronized boolean isAutoReconnectActive() {
        return started && !manualWifiConfigurationInProgress;
    }

    private static long retryDelayMillis(int failureCount) {
        if (failureCount <= 1) {
            return 5_000L;
        }
        if (failureCount == 2) {
            return 15_000L;
        }
        if (failureCount == 3) {
            return 30_000L;
        }
        return 60_000L;
    }

    private static String safeString(String value) {
        return value == null ? "" : value.trim();
    }

    interface Deps {
        void registerNetworkListener(NetworkListener listener);

        void unregisterNetworkListener();

        boolean isConnectedToSavedWifi();

        WifiAutoReconnectManager.AttemptResult attemptSavedWifiConnection();

        boolean isBackendAvailable();

        boolean hasValidToken();

        void triggerNetworkRestoredSync();

        void log(String message);
    }

    interface NetworkListener {
        void onWifiAvailable();

        void onWifiLost();
    }

    interface Scheduler {
        Cancellable schedule(Runnable runnable, long delayMillis);
    }

    interface Cancellable {
        void cancel();
    }

    private static final class AndroidScheduler implements Scheduler {
        private final ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor();

        @Override
        public Cancellable schedule(Runnable runnable, long delayMillis) {
            ScheduledFuture<?> future = executor.schedule(
                    runnable,
                    Math.max(0L, delayMillis),
                    TimeUnit.MILLISECONDS
            );
            return () -> future.cancel(false);
        }
    }

    private static final class AndroidDeps implements Deps {
        private final Context appContext;
        private final ConnectivityManager connectivityManager;
        private ConnectivityManager.NetworkCallback networkCallback;

        private AndroidDeps(Context appContext) {
            this.appContext = appContext;
            this.connectivityManager = (ConnectivityManager) appContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
        }

        @Override
        public void registerNetworkListener(NetworkListener listener) {
            if (connectivityManager == null || networkCallback != null) {
                return;
            }
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    listener.onWifiAvailable();
                }

                @Override
                public void onLost(Network network) {
                    listener.onWifiLost();
                }
            };
            try {
                connectivityManager.registerNetworkCallback(request, networkCallback);
            } catch (RuntimeException e) {
                AppLogger.e(TAG, "Register wifi callback failed", e);
                networkCallback = null;
            }
        }

        @Override
        public void unregisterNetworkListener() {
            if (connectivityManager == null || networkCallback == null) {
                return;
            }
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException e) {
                AppLogger.w(TAG, "Unregister wifi callback failed: " + e.getMessage());
            } finally {
                networkCallback = null;
            }
        }

        @Override
        public boolean isConnectedToSavedWifi() {
            return WifiAutoReconnectManager.isConnectedToSavedWifi(appContext);
        }

        @Override
        public WifiAutoReconnectManager.AttemptResult attemptSavedWifiConnection() {
            return WifiAutoReconnectManager.attemptSavedWifiConnection(appContext);
        }

        @Override
        public boolean isBackendAvailable() {
            return ApiService.isBackendAvailable();
        }

        @Override
        public boolean hasValidToken() {
            return SessionManager.get().isTokenValid();
        }

        @Override
        public void triggerNetworkRestoredSync() {
            SyncService.triggerSync(appContext, SyncTrigger.NETWORK_RESTORED);
        }

        @Override
        public void log(String message) {
            AppLogger.i(TAG, message);
        }
    }
}
