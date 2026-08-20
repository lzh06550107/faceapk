package com.punch.app.service;

import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PunchSyncPolicyTest {
    @Test
    public void retryLimitAndBatchBoundsAreEnforced() {
        assertTrue(PunchSyncPolicy.canAttempt(2, 3));
        assertFalse(PunchSyncPolicy.canAttempt(3, 3));
        assertTrue(PunchSyncPolicy.canContinueBatch(49, 29_999, 50, 30_000));
        assertFalse(PunchSyncPolicy.canContinueBatch(50, 1, 50, 30_000));
        assertFalse(PunchSyncPolicy.canContinueBatch(1, 30_000, 50, 30_000));
    }

    @Test
    public void transportFailureStopsCurrentBatch() {
        assertTrue(PunchSyncPolicy.shouldStopAfterFailure(-1));
        assertFalse(PunchSyncPolicy.shouldStopAfterFailure(400));
        assertFalse(PunchSyncPolicy.shouldStopAfterFailure(500));
    }

    @Test
    public void dailyRetryWindowStartsAtLocalMidnight() {
        long afternoonInShanghaiMillis = 1_787_211_000_000L;

        long startSeconds = PunchSyncPolicy.startOfLocalDayEpochSeconds(
                afternoonInShanghaiMillis,
                TimeZone.getTimeZone("Asia/Shanghai"));

        assertEquals(1_787_155_200L, startSeconds);
    }
}
