package com.punch.app.utils;

import java.util.Calendar;
import java.util.TimeZone;

public final class PunchTimeResolver {
    private PunchTimeResolver() {
    }

    public static long resolveScheduledPunchTimeSeconds(String optionLabel, long nowMillis) {
        return resolveScheduledPunchTimeSeconds(
                optionLabel,
                nowMillis,
                TimeZone.getDefault()
        );
    }

    static long resolveScheduledPunchTimeSeconds(String optionLabel, long nowMillis, TimeZone timeZone) {
        if (optionLabel == null || optionLabel.trim().isEmpty()) {
            return nowMillis / 1000L;
        }

        String[] parts = optionLabel.trim().split("\\s+");
        if (parts.length < 2) {
            return nowMillis / 1000L;
        }

        String[] range = parts[0].split("-");
        if (range.length != 2) {
            return nowMillis / 1000L;
        }

        int startMinutes = parseMinutes(range[0]);
        int endMinutes = parseMinutes(range[1]);
        if (startMinutes < 0 || endMinutes < 0) {
            return nowMillis / 1000L;
        }

        boolean signIn = parts[1].contains("\u4e0a\u73ed");
        boolean overnight = endMinutes <= startMinutes;
        Calendar now = Calendar.getInstance(timeZone);
        now.setTimeInMillis(nowMillis);
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        Calendar shiftStartDay = (Calendar) now.clone();
        shiftStartDay.set(Calendar.SECOND, 0);
        shiftStartDay.set(Calendar.MILLISECOND, 0);
        shiftStartDay.set(Calendar.HOUR_OF_DAY, 0);
        shiftStartDay.set(Calendar.MINUTE, 0);

        if (overnight) {
            if (signIn) {
                if (nowMinutes < endMinutes) {
                    shiftStartDay.add(Calendar.DAY_OF_MONTH, -1);
                }
            } else if (nowMinutes < endMinutes) {
                shiftStartDay.add(Calendar.DAY_OF_MONTH, -1);
            }
        }

        Calendar target = (Calendar) shiftStartDay.clone();
        if (signIn) {
            target.set(Calendar.HOUR_OF_DAY, startMinutes / 60);
            target.set(Calendar.MINUTE, startMinutes % 60);
        } else {
            if (overnight) {
                target.add(Calendar.DAY_OF_MONTH, 1);
            }
            target.set(Calendar.HOUR_OF_DAY, endMinutes / 60);
            target.set(Calendar.MINUTE, endMinutes % 60);
        }

        return target.getTimeInMillis() / 1000L;
    }

    static int parseMinutes(String hhmm) {
        String[] parts = hhmm.split(":");
        if (parts.length != 2) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return -1;
            }
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
