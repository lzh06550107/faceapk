package com.punch.app.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SyncTriggerTest {
    @Test
    public void onlyManualSyncResetsLimitedRetries() {
        assertFalse(SyncTrigger.HEARTBEAT.shouldResetLimitedPunchRetries());
        assertFalse(SyncTrigger.APP_START.shouldResetLimitedPunchRetries());
        assertFalse(SyncTrigger.NETWORK_RESTORED.shouldResetLimitedPunchRetries());
        assertFalse(SyncTrigger.AFTER_PUNCH.shouldResetLimitedPunchRetries());
        assertTrue(SyncTrigger.MANUAL.shouldResetLimitedPunchRetries());
    }

    @Test
    public void automaticTriggersResetOnlyRetriesExpiredByANewDay() {
        assertTrue(SyncTrigger.HEARTBEAT.shouldResetExpiredPunchRetries());
        assertTrue(SyncTrigger.APP_START.shouldResetExpiredPunchRetries());
        assertTrue(SyncTrigger.NETWORK_RESTORED.shouldResetExpiredPunchRetries());
        assertTrue(SyncTrigger.AFTER_PUNCH.shouldResetExpiredPunchRetries());
        assertFalse(SyncTrigger.MANUAL.shouldResetExpiredPunchRetries());
    }
}
