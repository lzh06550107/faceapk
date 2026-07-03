package com.punch.app.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class PunchTimeResolverTest {
    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");

    @Test
    public void resolveScheduledPunchTimeSeconds_shouldUseSameDayStartForNormalShiftSignIn() {
        long nowMillis = millisAt(2026, Calendar.JULY, 1, 9, 23);

        long resolved = PunchTimeResolver.resolveScheduledPunchTimeSeconds(
                "08:00-17:00 \u4e0a\u73ed",
                nowMillis,
                SHANGHAI
        );

        assertEquals(millisAt(2026, Calendar.JULY, 1, 8, 0) / 1000L, resolved);
    }

    @Test
    public void resolveScheduledPunchTimeSeconds_shouldUseSameDayEndForNormalShiftSignOut() {
        long nowMillis = millisAt(2026, Calendar.JULY, 1, 10, 5);

        long resolved = PunchTimeResolver.resolveScheduledPunchTimeSeconds(
                "08:00-17:00 \u4e0b\u73ed",
                nowMillis,
                SHANGHAI
        );

        assertEquals(millisAt(2026, Calendar.JULY, 1, 17, 0) / 1000L, resolved);
    }

    @Test
    public void resolveScheduledPunchTimeSeconds_shouldUseShiftStartDayForOvernightSignIn() {
        long nowMillis = millisAt(2026, Calendar.JULY, 2, 1, 30);

        long resolved = PunchTimeResolver.resolveScheduledPunchTimeSeconds(
                "20:00-04:00 \u4e0a\u73ed",
                nowMillis,
                SHANGHAI
        );

        assertEquals(millisAt(2026, Calendar.JULY, 1, 20, 0) / 1000L, resolved);
    }

    @Test
    public void resolveScheduledPunchTimeSeconds_shouldUseNextDayEndForOvernightSignOut() {
        long nowMillis = millisAt(2026, Calendar.JULY, 2, 3, 50);

        long resolved = PunchTimeResolver.resolveScheduledPunchTimeSeconds(
                "20:00-04:00 \u4e0b\u73ed",
                nowMillis,
                SHANGHAI
        );

        assertEquals(millisAt(2026, Calendar.JULY, 2, 4, 0) / 1000L, resolved);
    }

    @Test
    public void resolveScheduledPunchTimeSeconds_shouldUseUpcomingShiftForOvernightSignOutDuringDaytime() {
        long nowMillis = millisAt(2026, Calendar.JULY, 2, 10, 0);

        long resolved = PunchTimeResolver.resolveScheduledPunchTimeSeconds(
                "20:00-03:30 \u4e0b\u73ed",
                nowMillis,
                SHANGHAI
        );

        assertEquals(millisAt(2026, Calendar.JULY, 3, 3, 30) / 1000L, resolved);
    }

    private long millisAt(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(SHANGHAI);
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
