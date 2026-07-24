package com.punch.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
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

    @Test
    public void isWithinAllowedPunchTime_shouldAcceptNormalShiftSignInWindow() {
        long nowMillis = millisAt(2026, Calendar.JULY, 1, 5, 40);

        assertTrue(PunchTimeResolver.isWithinAllowedPunchTime(
                "06:00-11:00 \u4e0a\u73ed",
                nowMillis,
                20,
                SHANGHAI
        ));
    }

    @Test
    public void isWithinAllowedPunchTime_shouldRejectOutsideNormalShiftSignOutWindow() {
        long nowMillis = millisAt(2026, Calendar.JULY, 1, 10, 39);

        assertFalse(PunchTimeResolver.isWithinAllowedPunchTime(
                "06:00-11:00 \u4e0b\u73ed",
                nowMillis,
                20,
                SHANGHAI
        ));
    }

    @Test
    public void isWithinAllowedPunchTime_shouldAcceptOvernightShiftSignOutWindow() {
        long nowMillis = millisAt(2026, Calendar.JULY, 2, 3, 45);

        assertTrue(PunchTimeResolver.isWithinAllowedPunchTime(
                "20:00-04:00 \u4e0b\u73ed",
                nowMillis,
                20,
                SHANGHAI
        ));
    }

    @Test
    public void isWithinAllowedPunchTime_shouldAcceptOvernightShiftSignInWindowBeforeMidnight() {
        long nowMillis = millisAt(2026, Calendar.JULY, 1, 23, 30);

        assertTrue(PunchTimeResolver.isWithinAllowedPunchTime(
                "23:50-03:20 \u4e0a\u73ed",
                nowMillis,
                20,
                SHANGHAI
        ));
    }

    @Test
    public void isWithinAllowedPunchTime_shouldAcceptOvernightShiftSignInWindowAfterMidnightBoundary() {
        long nowMillis = millisAt(2026, Calendar.JULY, 2, 0, 10);

        assertTrue(PunchTimeResolver.isWithinAllowedPunchTime(
                "23:50-03:20 \u4e0a\u73ed",
                nowMillis,
                20,
                SHANGHAI
        ));
    }

    @Test
    public void isWithinAllowedPunchTime_shouldRejectOvernightShiftSignInAfterWindow() {
        long nowMillis = millisAt(2026, Calendar.JULY, 2, 0, 11);

        assertFalse(PunchTimeResolver.isWithinAllowedPunchTime(
                "23:50-03:20 \u4e0a\u73ed",
                nowMillis,
                20,
                SHANGHAI
        ));
    }

    @Test
    public void isWithinAllowedPunchTime_shouldAcceptOvernightShiftSignOutWindowBoundaries() {
        assertTrue(PunchTimeResolver.isWithinAllowedPunchTime(
                "23:50-03:20 \u4e0b\u73ed",
                millisAt(2026, Calendar.JULY, 2, 3, 0),
                20,
                SHANGHAI
        ));
        assertTrue(PunchTimeResolver.isWithinAllowedPunchTime(
                "23:50-03:20 \u4e0b\u73ed",
                millisAt(2026, Calendar.JULY, 2, 3, 40),
                20,
                SHANGHAI
        ));
    }

    @Test
    public void findAllowedPunchOptionIndex_shouldSelectOvernightSignInAfterMidnight() {
        int index = PunchTimeResolver.findAllowedPunchOptionIndex(
                Arrays.asList("23:50-03:20 \u4e0a\u73ed", "23:50-03:20 \u4e0b\u73ed", "\u81ea\u7531\u6253\u5361"),
                millisAt(2026, Calendar.JULY, 2, 0, 5),
                20,
                SHANGHAI
        );

        assertEquals(0, index);
    }

    @Test
    public void resolveAllowedPunchTimeSeconds_shouldUseMatchedOvernightSignInTargetAfterMidnight() {
        long resolved = PunchTimeResolver.resolveAllowedPunchTimeSeconds(
                "23:50-03:20 \u4e0a\u73ed",
                millisAt(2026, Calendar.JULY, 2, 0, 10),
                20,
                SHANGHAI
        );

        assertEquals(millisAt(2026, Calendar.JULY, 1, 23, 50) / 1000L, resolved);
    }

    @Test
    public void resolveAllowedPunchTimeSeconds_shouldUseMatchedOvernightSignOutTargetAfterEndBoundary() {
        long resolved = PunchTimeResolver.resolveAllowedPunchTimeSeconds(
                "23:50-03:20 \u4e0b\u73ed",
                millisAt(2026, Calendar.JULY, 2, 3, 40),
                20,
                SHANGHAI
        );

        assertEquals(millisAt(2026, Calendar.JULY, 2, 3, 20) / 1000L, resolved);
    }

    @Test
    public void isWithinAllowedPunchTime_shouldAcceptEveningShiftSignInWindowBoundaries() {
        assertTrue(PunchTimeResolver.isWithinAllowedPunchTime(
                "18:20-23:50 \u4e0a\u73ed",
                millisAt(2026, Calendar.JULY, 1, 18, 0),
                20,
                SHANGHAI
        ));
        assertTrue(PunchTimeResolver.isWithinAllowedPunchTime(
                "18:20-23:50 \u4e0a\u73ed",
                millisAt(2026, Calendar.JULY, 1, 18, 40),
                20,
                SHANGHAI
        ));
    }

    @Test
    public void isWithinAllowedPunchTime_shouldRejectEveningShiftSignInAfterWindow() {
        assertFalse(PunchTimeResolver.isWithinAllowedPunchTime(
                "18:20-23:50 \u4e0a\u73ed",
                millisAt(2026, Calendar.JULY, 1, 18, 41),
                20,
                SHANGHAI
        ));
    }

    @Test
    public void isWithinAllowedPunchTime_shouldAcceptEveningShiftSignOutWindowAcrossMidnight() {
        assertTrue(PunchTimeResolver.isWithinAllowedPunchTime(
                "18:20-23:50 \u4e0b\u73ed",
                millisAt(2026, Calendar.JULY, 1, 23, 30),
                20,
                SHANGHAI
        ));
        assertTrue(PunchTimeResolver.isWithinAllowedPunchTime(
                "18:20-23:50 \u4e0b\u73ed",
                millisAt(2026, Calendar.JULY, 2, 0, 10),
                20,
                SHANGHAI
        ));
    }

    @Test
    public void isWithinAllowedPunchTime_shouldRejectEveningShiftSignOutAfterMidnightWindow() {
        assertFalse(PunchTimeResolver.isWithinAllowedPunchTime(
                "18:20-23:50 \u4e0b\u73ed",
                millisAt(2026, Calendar.JULY, 2, 0, 11),
                20,
                SHANGHAI
        ));
    }

    @Test
    public void resolveAllowedPunchTimeSeconds_shouldUseMatchedEveningSignOutTargetAfterMidnight() {
        long resolved = PunchTimeResolver.resolveAllowedPunchTimeSeconds(
                "18:20-23:50 \u4e0b\u73ed",
                millisAt(2026, Calendar.JULY, 2, 0, 10),
                20,
                SHANGHAI
        );

        assertEquals(millisAt(2026, Calendar.JULY, 1, 23, 50) / 1000L, resolved);
    }

    @Test
    public void validatePunchTimeWindows_shouldRejectSameShiftSignInAndSignOutOverlap() {
        PunchTimeResolver.WindowValidationResult result =
                PunchTimeResolver.validatePunchTimeWindows(
                        Collections.singletonList("08:00-09:00"),
                        40
                );

        assertFalse(result.valid);
    }

    @Test
    public void validatePunchTimeWindows_shouldRejectAdjacentShiftOverlap() {
        PunchTimeResolver.WindowValidationResult result =
                PunchTimeResolver.validatePunchTimeWindows(
                        Arrays.asList("06:00-11:00", "11:30-16:00"),
                        20
                );

        assertFalse(result.valid);
    }

    @Test
    public void validatePunchTimeWindows_shouldRejectOvernightAdjacentShiftOverlap() {
        PunchTimeResolver.WindowValidationResult result =
                PunchTimeResolver.validatePunchTimeWindows(
                        Arrays.asList("18:20-23:50", "23:50-03:20"),
                        20
                );

        assertFalse(result.valid);
    }

    @Test
    public void validatePunchTimeWindows_shouldAcceptSeparatedShifts() {
        PunchTimeResolver.WindowValidationResult result =
                PunchTimeResolver.validatePunchTimeWindows(
                        Arrays.asList("06:00-11:00", "12:00-17:00", "23:50-03:20"),
                        20
                );

        assertTrue(result.valid);
    }

    @Test
    public void findAllowedPunchOptionIndexes_shouldReturnAllMatchesWhenWindowsOverlap() {
        List<Integer> indexes = PunchTimeResolver.findAllowedPunchOptionIndexes(
                Arrays.asList("18:20-23:50 \u4e0b\u73ed", "23:50-03:20 \u4e0a\u73ed", "\u81ea\u7531\u6253\u5361"),
                millisAt(2026, Calendar.JULY, 1, 23, 40),
                20,
                SHANGHAI
        );

        assertEquals(Arrays.asList(0, 1), indexes);
        assertEquals(-1, PunchTimeResolver.findAllowedPunchOptionIndex(
                Arrays.asList("18:20-23:50 \u4e0b\u73ed", "23:50-03:20 \u4e0a\u73ed", "\u81ea\u7531\u6253\u5361"),
                millisAt(2026, Calendar.JULY, 1, 23, 40),
                20,
                SHANGHAI
        ));
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
