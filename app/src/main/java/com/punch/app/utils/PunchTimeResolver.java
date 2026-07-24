package com.punch.app.utils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
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

    public static long resolveAllowedPunchTimeSeconds(String optionLabel,
                                                      long nowMillis,
                                                      int windowMinutes) {
        return resolveAllowedPunchTimeSeconds(
                optionLabel,
                nowMillis,
                windowMinutes,
                TimeZone.getDefault()
        );
    }

    static long resolveAllowedPunchTimeSeconds(String optionLabel,
                                               long nowMillis,
                                               int windowMinutes,
                                               TimeZone timeZone) {
        long allowedTargetMillis = findAllowedTargetMillis(
                optionLabel,
                nowMillis,
                windowMinutes,
                timeZone
        );
        if (allowedTargetMillis >= 0) {
            return allowedTargetMillis / 1000L;
        }
        return resolveScheduledPunchTimeSeconds(optionLabel, nowMillis, timeZone);
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

    public static boolean isWithinAllowedPunchTime(String optionLabel,
                                                   long nowMillis,
                                                   int windowMinutes) {
        return isWithinAllowedPunchTime(
                optionLabel,
                nowMillis,
                windowMinutes,
                TimeZone.getDefault()
        );
    }

    static boolean isWithinAllowedPunchTime(String optionLabel,
                                            long nowMillis,
                                            int windowMinutes,
                                            TimeZone timeZone) {
        if (!isScheduledPunchOption(optionLabel)) {
            return false;
        }
        return findAllowedTargetMillis(optionLabel, nowMillis, windowMinutes, timeZone) >= 0;
    }

    private static long findAllowedTargetMillis(String optionLabel,
                                                long nowMillis,
                                                int windowMinutes,
                                                TimeZone timeZone) {
        if (!isScheduledPunchOption(optionLabel)) {
            return -1L;
        }
        String[] parts = optionLabel.trim().split("\\s+");
        String[] range = parts[0].split("-");
        int startMinutes = parseMinutes(range[0]);
        int endMinutes = parseMinutes(range[1]);
        boolean signIn = parts[1].contains("\u4e0a\u73ed");
        boolean overnight = endMinutes <= startMinutes;

        Calendar now = Calendar.getInstance(timeZone);
        now.setTimeInMillis(nowMillis);
        Calendar currentDayStart = (Calendar) now.clone();
        currentDayStart.set(Calendar.SECOND, 0);
        currentDayStart.set(Calendar.MILLISECOND, 0);
        currentDayStart.set(Calendar.HOUR_OF_DAY, 0);
        currentDayStart.set(Calendar.MINUTE, 0);

        long windowMillis = Math.max(0, windowMinutes) * 60_000L;
        for (int offset = -1; offset <= 1; offset++) {
            Calendar target = (Calendar) currentDayStart.clone();
            target.add(Calendar.DAY_OF_MONTH, offset);
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
            long targetMillis = target.getTimeInMillis();
            if (nowMillis >= targetMillis - windowMillis
                    && nowMillis <= targetMillis + windowMillis) {
                return targetMillis;
            }
        }
        return -1L;
    }

    public static int findAllowedPunchOptionIndex(List<String> optionLabels,
                                                  long nowMillis,
                                                  int windowMinutes) {
        List<Integer> indexes = findAllowedPunchOptionIndexes(optionLabels, nowMillis, windowMinutes);
        return indexes.size() == 1 ? indexes.get(0) : -1;
    }

    public static List<Integer> findAllowedPunchOptionIndexes(List<String> optionLabels,
                                                              long nowMillis,
                                                              int windowMinutes) {
        return findAllowedPunchOptionIndexes(
                optionLabels,
                nowMillis,
                windowMinutes,
                TimeZone.getDefault()
        );
    }

    static List<Integer> findAllowedPunchOptionIndexes(List<String> optionLabels,
                                                       long nowMillis,
                                                       int windowMinutes,
                                                       TimeZone timeZone) {
        List<Integer> indexes = new ArrayList<>();
        if (optionLabels == null || optionLabels.isEmpty()) {
            return indexes;
        }
        for (int i = 0; i < optionLabels.size(); i++) {
            if (isWithinAllowedPunchTime(optionLabels.get(i), nowMillis, windowMinutes, timeZone)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    public static WindowValidationResult validatePunchTimeWindows(List<String> timeRanges,
                                                                  int windowMinutes) {
        if (timeRanges == null || timeRanges.isEmpty()) {
            return WindowValidationResult.ok();
        }
        List<PunchWindow> windows = buildPunchWindows(timeRanges, windowMinutes);
        for (int i = 0; i < windows.size(); i++) {
            PunchWindow left = windows.get(i);
            for (int j = i + 1; j < windows.size(); j++) {
                PunchWindow right = windows.get(j);
                if (left.sameDailyWindow(right)) {
                    continue;
                }
                if (left.overlaps(right)) {
                    return WindowValidationResult.conflict(left.label, right.label);
                }
            }
        }
        return WindowValidationResult.ok();
    }

    private static List<PunchWindow> buildPunchWindows(List<String> timeRanges, int windowMinutes) {
        List<PunchWindow> windows = new ArrayList<>();
        int safeWindow = Math.max(0, windowMinutes);
        for (String timeRange : timeRanges) {
            if (timeRange == null || timeRange.trim().isEmpty()) {
                continue;
            }
            String rangeLabel = timeRange.trim();
            String[] range = rangeLabel.split("-");
            if (range.length != 2) {
                continue;
            }
            int startMinutes = parseMinutes(range[0]);
            int endMinutes = parseMinutes(range[1]);
            if (startMinutes < 0 || endMinutes < 0) {
                continue;
            }
            boolean overnight = endMinutes <= startMinutes;
            for (int day = 0; day < 3; day++) {
                int signInTarget = day * 1440 + startMinutes;
                windows.add(PunchWindow.of(rangeLabel + " \u4e0a\u73ed", day, 0, signInTarget, safeWindow));

                int signOutTarget = (day + (overnight ? 1 : 0)) * 1440 + endMinutes;
                windows.add(PunchWindow.of(rangeLabel + " \u4e0b\u73ed", day, 1, signOutTarget, safeWindow));
            }
        }
        return windows;
    }

    static int findAllowedPunchOptionIndex(List<String> optionLabels,
                                           long nowMillis,
                                           int windowMinutes,
                                           TimeZone timeZone) {
        List<Integer> indexes = findAllowedPunchOptionIndexes(
                optionLabels,
                nowMillis,
                windowMinutes,
                timeZone
        );
        return indexes.size() == 1 ? indexes.get(0) : -1;
    }

    private static final class PunchWindow {
        final String label;
        final int sourceDay;
        final int type;
        final int startMinute;
        final int endMinute;

        private PunchWindow(String label, int sourceDay, int type, int startMinute, int endMinute) {
            this.label = label;
            this.sourceDay = sourceDay;
            this.type = type;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
        }

        static PunchWindow of(String label, int sourceDay, int type, int targetMinute, int windowMinutes) {
            return new PunchWindow(
                    label,
                    sourceDay,
                    type,
                    targetMinute - windowMinutes,
                    targetMinute + windowMinutes
            );
        }

        boolean overlaps(PunchWindow other) {
            return startMinute <= other.endMinute && other.startMinute <= endMinute;
        }

        boolean sameDailyWindow(PunchWindow other) {
            return type == other.type && label.equals(other.label) && sourceDay != other.sourceDay;
        }
    }

    public static final class WindowValidationResult {
        public final boolean valid;
        public final String firstLabel;
        public final String secondLabel;

        private WindowValidationResult(boolean valid, String firstLabel, String secondLabel) {
            this.valid = valid;
            this.firstLabel = firstLabel == null ? "" : firstLabel;
            this.secondLabel = secondLabel == null ? "" : secondLabel;
        }

        static WindowValidationResult ok() {
            return new WindowValidationResult(true, "", "");
        }

        static WindowValidationResult conflict(String firstLabel, String secondLabel) {
            return new WindowValidationResult(false, firstLabel, secondLabel);
        }

        public String buildMessage() {
            if (valid) {
                return "";
            }
            return "\u6253\u5361\u95f4\u9694\u8fc7\u5927\uff0c\u5bfc\u81f4\u6253\u5361\u65f6\u95f4\u8303\u56f4\u91cd\u53e0\uff1a"
                    + firstLabel + " \u4e0e " + secondLabel;
        }
    }

    private static boolean isScheduledPunchOption(String optionLabel) {
        if (optionLabel == null || optionLabel.trim().isEmpty()) {
            return false;
        }
        String[] parts = optionLabel.trim().split("\\s+");
        if (parts.length < 2) {
            return false;
        }
        String[] range = parts[0].split("-");
        return range.length == 2
                && parseMinutes(range[0]) >= 0
                && parseMinutes(range[1]) >= 0
                && (parts[1].contains("\u4e0a\u73ed") || parts[1].contains("\u4e0b\u73ed"));
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
