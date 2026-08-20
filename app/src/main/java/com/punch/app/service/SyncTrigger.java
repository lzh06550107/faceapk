package com.punch.app.service;

public enum SyncTrigger {
    HEARTBEAT,
    APP_START,
    NETWORK_RESTORED,
    MANUAL,
    AFTER_PUNCH;

    public boolean shouldResetLimitedPunchRetries() {
        return this == MANUAL;
    }

    public boolean shouldResetExpiredPunchRetries() {
        return this != MANUAL;
    }
}
