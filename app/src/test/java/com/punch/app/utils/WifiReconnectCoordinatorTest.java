package com.punch.app.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class WifiReconnectCoordinatorTest {

    @Test
    public void wifiLossSchedulesImmediateReconnectThenDelayedVerification() {
        FakeDeps deps = new FakeDeps();
        FakeScheduler scheduler = new FakeScheduler();
        WifiReconnectCoordinator coordinator = new WifiReconnectCoordinator(deps, scheduler);

        coordinator.start();
        scheduler.runNext();

        deps.connectedToSavedWifi = false;
        deps.listener.onWifiLost();

        assertEquals(0L, scheduler.nextDelayMillis());

        scheduler.runNext();

        assertEquals(1, deps.reconnectAttempts);
        assertEquals(5_000L, scheduler.nextDelayMillis());
    }

    @Test
    public void connectedWifiWithUnavailableBackendUsesIncreasingProbeDelays() {
        FakeDeps deps = new FakeDeps();
        deps.connectedToSavedWifi = true;
        deps.backendAvailable = false;
        FakeScheduler scheduler = new FakeScheduler();
        WifiReconnectCoordinator coordinator = new WifiReconnectCoordinator(deps, scheduler);

        coordinator.start();
        scheduler.runNext();

        assertEquals(0, deps.reconnectAttempts);
        assertEquals(5_000L, scheduler.nextDelayMillis());

        scheduler.runNext();

        assertEquals(0, deps.reconnectAttempts);
        assertEquals(15_000L, scheduler.nextDelayMillis());
    }

    @Test
    public void restoredWifiTriggersSyncOnlyOnceAfterObservedLoss() {
        FakeDeps deps = new FakeDeps();
        FakeScheduler scheduler = new FakeScheduler();
        WifiReconnectCoordinator coordinator = new WifiReconnectCoordinator(deps, scheduler);

        coordinator.start();
        scheduler.runNext();
        assertEquals(0, deps.syncTriggers);

        deps.connectedToSavedWifi = false;
        deps.listener.onWifiLost();
        deps.connectedToSavedWifi = true;
        deps.listener.onWifiAvailable();
        scheduler.runNext();

        assertEquals(1, deps.syncTriggers);

        deps.listener.onWifiAvailable();
        scheduler.runNext();

        assertEquals(1, deps.syncTriggers);
    }

    @Test
    public void submittedConnectionThatStaysOfflineUsesIncreasingRetryDelays() {
        FakeDeps deps = new FakeDeps();
        deps.connectedToSavedWifi = false;
        FakeScheduler scheduler = new FakeScheduler();
        WifiReconnectCoordinator coordinator = new WifiReconnectCoordinator(deps, scheduler);

        coordinator.start();
        scheduler.runNext();
        scheduler.runNext();
        assertEquals(5_000L, scheduler.nextDelayMillis());

        scheduler.runNext();
        assertEquals(5_000L, scheduler.nextDelayMillis());

        scheduler.runNext();
        assertEquals(5_000L, scheduler.nextDelayMillis());
        scheduler.runNext();
        assertEquals(15_000L, scheduler.nextDelayMillis());
    }

    @Test
    public void staleBackendProbeCannotConsumeANewerWifiLoss() {
        FakeDeps deps = new FakeDeps();
        FakeScheduler scheduler = new FakeScheduler();
        WifiReconnectCoordinator coordinator = new WifiReconnectCoordinator(deps, scheduler);

        coordinator.start();
        scheduler.runNext();
        deps.listener.onWifiLost();
        deps.connectedToSavedWifi = true;
        deps.listener.onWifiAvailable();
        deps.backendProbeHook = deps.listener::onWifiLost;

        scheduler.runNext();

        assertEquals(0, deps.syncTriggers);

        deps.backendProbeHook = null;
        deps.connectedToSavedWifi = true;
        deps.listener.onWifiAvailable();
        scheduler.runNext();

        assertEquals(1, deps.syncTriggers);
    }

    @Test
    public void coldStartOfflineTriggersSyncAfterWifiRecovers() {
        FakeDeps deps = new FakeDeps();
        deps.connectedToSavedWifi = false;
        FakeScheduler scheduler = new FakeScheduler();
        WifiReconnectCoordinator coordinator = new WifiReconnectCoordinator(deps, scheduler);

        coordinator.start();
        scheduler.runNext();
        scheduler.runNext();
        deps.connectedToSavedWifi = true;
        scheduler.runNext();

        assertEquals(1, deps.syncTriggers);
    }

    @Test
    public void manualWifiConfigurationSuppressesSavedReconnectUntilFinished() {
        FakeDeps deps = new FakeDeps();
        FakeScheduler scheduler = new FakeScheduler();
        WifiReconnectCoordinator coordinator = new WifiReconnectCoordinator(deps, scheduler);

        coordinator.start();
        scheduler.runNext();

        coordinator.beginManualWifiConfiguration("settings");
        deps.connectedToSavedWifi = false;
        deps.listener.onWifiLost();
        deps.listener.onWifiAvailable();
        coordinator.requestReconnect("splash");

        assertEquals(-1L, scheduler.nextDelayMillis());
        assertEquals(0, deps.reconnectAttempts);

        deps.connectedToSavedWifi = true;
        coordinator.finishManualWifiConfiguration("connected");
        assertEquals(0L, scheduler.nextDelayMillis());

        scheduler.runNext();

        assertEquals(0, deps.reconnectAttempts);
        assertEquals(1, deps.syncTriggers);
    }

    private static final class FakeDeps implements WifiReconnectCoordinator.Deps {
        WifiReconnectCoordinator.NetworkListener listener;
        boolean connectedToSavedWifi = true;
        boolean backendAvailable = true;
        WifiAutoReconnectManager.AttemptResult attemptResult =
                WifiAutoReconnectManager.AttemptResult.SUBMITTED;
        int reconnectAttempts;
        int syncTriggers;
        Runnable backendProbeHook;

        @Override
        public void registerNetworkListener(WifiReconnectCoordinator.NetworkListener listener) {
            this.listener = listener;
        }

        @Override
        public void unregisterNetworkListener() {
            listener = null;
        }

        @Override
        public boolean isConnectedToSavedWifi() {
            return connectedToSavedWifi;
        }

        @Override
        public WifiAutoReconnectManager.AttemptResult attemptSavedWifiConnection() {
            reconnectAttempts++;
            return attemptResult;
        }

        @Override
        public boolean isBackendAvailable() {
            if (backendProbeHook != null) {
                backendProbeHook.run();
            }
            return backendAvailable;
        }

        @Override
        public boolean hasValidToken() {
            return true;
        }

        @Override
        public void triggerNetworkRestoredSync() {
            syncTriggers++;
        }

        @Override
        public void log(String message) {
        }
    }

    private static final class FakeScheduler implements WifiReconnectCoordinator.Scheduler {
        private final List<FakeTask> tasks = new ArrayList<>();

        @Override
        public WifiReconnectCoordinator.Cancellable schedule(Runnable runnable, long delayMillis) {
            FakeTask task = new FakeTask(runnable, delayMillis);
            tasks.add(task);
            return task;
        }

        long nextDelayMillis() {
            for (FakeTask task : tasks) {
                if (!task.cancelled && !task.completed) {
                    return task.delayMillis;
                }
            }
            return -1L;
        }

        void runNext() {
            for (FakeTask task : tasks) {
                if (!task.cancelled && !task.completed) {
                    task.completed = true;
                    task.runnable.run();
                    return;
                }
            }
            throw new AssertionError("No scheduled task");
        }
    }

    private static final class FakeTask implements WifiReconnectCoordinator.Cancellable {
        final Runnable runnable;
        final long delayMillis;
        boolean cancelled;
        boolean completed;

        FakeTask(Runnable runnable, long delayMillis) {
            this.runnable = runnable;
            this.delayMillis = delayMillis;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
