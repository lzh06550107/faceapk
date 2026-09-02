package com.punch.app.service;

import java.util.Calendar;
import java.util.TimeZone;

final class PunchSyncPolicy {
    private PunchSyncPolicy() {
    }

    static boolean canAttempt(int retryCount, int maxRetries) {
        return retryCount < maxRetries;
    }

    static boolean canContinueBatch(int processed,
                                    long elapsedMillis,
                                    int maxBatchSize,
                                    long timeBudgetMillis) {
        return processed < maxBatchSize && elapsedMillis < timeBudgetMillis;
    }

    static boolean shouldStopAfterFailure(int responseCode) {
        return responseCode < 0;
    }

    static boolean shouldConsumeRetry(int responseCode) {
        return responseCode >= 0;
    }

    static long startOfLocalDayEpochSeconds(long nowMillis, TimeZone timeZone) {
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setTimeInMillis(nowMillis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis() / 1000L;
    }
}
