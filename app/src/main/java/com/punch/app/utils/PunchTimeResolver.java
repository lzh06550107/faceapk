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
                null,
                null,
                TimeZone.getDefault()
        );
    }

    static long resolveAllowedPunchTimeSeconds(String optionLabel,
                                               long nowMillis,
                                               int windowMinutes,
                                               TimeZone timeZone) {
        return resolveAllowedPunchTimeSeconds(optionLabel, nowMillis, windowMinutes, null, null, timeZone);
    }

    public static long resolveAllowedPunchTimeSeconds(String optionLabel,
                                                      long nowMillis,
                                                      int windowMinutes,
                                                      List<String> optionLabels,
                                                      List<String> overtimeSignOutOptions) {
        return resolveAllowedPunchTimeSeconds(
                optionLabel,
                nowMillis,
                windowMinutes,
                optionLabels,
                overtimeSignOutOptions,
                TimeZone.getDefault()
        );
    }

    static long resolveAllowedPunchTimeSeconds(String optionLabel,
                                               long nowMillis,
                                               int windowMinutes,
                                               List<String> optionLabels,
                                               List<String> overtimeSignOutOptions,
                                               TimeZone timeZone) {
        long allowedTargetMillis = findAllowedTargetMillis(
                optionLabel,
                optionLabels,
                nowMillis,
                windowMinutes,
                overtimeSignOutOptions,
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
                null,
                null,
                TimeZone.getDefault()
        );
    }

    static boolean isWithinAllowedPunchTime(String optionLabel,
                                            long nowMillis,
                                            int windowMinutes,
                                            TimeZone timeZone) {
        return isWithinAllowedPunchTime(optionLabel, nowMillis, windowMinutes, null, null, timeZone);
    }

    public static boolean isWithinAllowedPunchTime(String optionLabel,
                                                   long nowMillis,
                                                   int windowMinutes,
                                                   List<String> optionLabels,
                                                   List<String> overtimeSignOutOptions) {
        return isWithinAllowedPunchTime(
                optionLabel,
                nowMillis,
                windowMinutes,
                optionLabels,
                overtimeSignOutOptions,
                TimeZone.getDefault()
        );
    }

    static boolean isWithinAllowedPunchTime(String optionLabel,
                                            long nowMillis,
                                            int windowMinutes,
                                            List<String> optionLabels,
                                            List<String> overtimeSignOutOptions,
                                            TimeZone timeZone) {
        if (!isScheduledPunchOption(optionLabel)) {
            return false;
        }
        return findAllowedTargetMillis(
                optionLabel,
                optionLabels,
                nowMillis,
                windowMinutes,
                overtimeSignOutOptions,
                timeZone
        ) >= 0;
    }

    private static long findAllowedTargetMillis(String optionLabel,
                                                List<String> optionLabels,
                                                long nowMillis,
                                                int windowMinutes,
                                                List<String> overtimeSignOutOptions,
                                                TimeZone timeZone) {
        PunchOption option = parsePunchOption(optionLabel);
        if (option == null) {
            return -1L;
        }

        Calendar now = Calendar.getInstance(timeZone);
        now.setTimeInMillis(nowMillis);
        Calendar currentDayStart = (Calendar) now.clone();
        currentDayStart.set(Calendar.SECOND, 0);
        currentDayStart.set(Calendar.MILLISECOND, 0);
        currentDayStart.set(Calendar.HOUR_OF_DAY, 0);
        currentDayStart.set(Calendar.MINUTE, 0);

        int safeWindow = Math.max(0, windowMinutes);
        long dayStartMillis = currentDayStart.getTimeInMillis();
        for (int offset = -1; offset <= 1; offset++) {
            int targetMinute = option.targetMinute(offset);
            PunchWindow allowedWindow = buildAllowedPunchWindow(
                    option,
                    offset,
                    safeWindow,
                    optionLabels,
                    overtimeSignOutOptions
            );
            long targetMillis = dayStartMillis + targetMinute * 60_000L;
            long startMillis = dayStartMillis + allowedWindow.startMinute * 60_000L;
            long endMillis = dayStartMillis + allowedWindow.endMinute * 60_000L + 59_999L;
            if (nowMillis >= startMillis && nowMillis <= endMillis) {
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
                null,
                TimeZone.getDefault()
        );
    }

    static List<Integer> findAllowedPunchOptionIndexes(List<String> optionLabels,
                                                       long nowMillis,
                                                       int windowMinutes,
                                                       TimeZone timeZone) {
        return findAllowedPunchOptionIndexes(optionLabels, nowMillis, windowMinutes, null, timeZone);
    }

    public static List<Integer> findAllowedPunchOptionIndexes(List<String> optionLabels,
                                                              long nowMillis,
                                                              int windowMinutes,
                                                              List<String> overtimeSignOutOptions) {
        return findAllowedPunchOptionIndexes(
                optionLabels,
                nowMillis,
                windowMinutes,
                overtimeSignOutOptions,
                TimeZone.getDefault()
        );
    }

    static List<Integer> findAllowedPunchOptionIndexes(List<String> optionLabels,
                                                       long nowMillis,
                                                       int windowMinutes,
                                                       List<String> overtimeSignOutOptions,
                                                       TimeZone timeZone) {
        List<Integer> indexes = new ArrayList<>();
        if (optionLabels == null || optionLabels.isEmpty()) {
            return indexes;
        }
        for (int i = 0; i < optionLabels.size(); i++) {
            if (isWithinAllowedPunchTime(
                    optionLabels.get(i),
                    nowMillis,
                    windowMinutes,
                    optionLabels,
                    overtimeSignOutOptions,
                    timeZone
            )) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    public static WindowValidationResult validatePunchTimeWindows(List<String> timeRanges,
                                                                  int windowMinutes) {
        return validatePunchTimeWindows(timeRanges, windowMinutes, null);
    }

    public static WindowValidationResult validatePunchTimeWindows(List<String> timeRanges,
                                                                  int windowMinutes,
                                                                  List<String> overtimeSignOutOptions) {
        if (timeRanges == null || timeRanges.isEmpty()) {
            return WindowValidationResult.ok();
        }
        List<PunchWindow> windows = buildPunchWindows(timeRanges, windowMinutes, overtimeSignOutOptions);
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

    private static List<PunchWindow> buildPunchWindows(List<String> timeRanges,
                                                       int windowMinutes,
                                                       List<String> overtimeSignOutOptions) {
        List<PunchWindow> windows = new ArrayList<>();
        int safeWindow = Math.max(0, windowMinutes);
        List<String> optionLabels = buildPunchOptionLabels(timeRanges);
        for (String timeRange : timeRanges) {
            if (timeRange == null || timeRange.trim().isEmpty()) {
                continue;
            }
            String rangeLabel = timeRange.trim();
            PunchOption signInOption = parsePunchOption(rangeLabel + " \u4e0a\u73ed");
            PunchOption signOutOption = parsePunchOption(rangeLabel + " \u4e0b\u73ed");
            if (signInOption == null || signOutOption == null) {
                continue;
            }
            for (int day = 0; day < 3; day++) {
                windows.add(buildAllowedPunchWindow(
                        signInOption,
                        day,
                        safeWindow,
                        optionLabels,
                        overtimeSignOutOptions
                ));
                windows.add(buildAllowedPunchWindow(
                        signOutOption,
                        day,
                        safeWindow,
                        optionLabels,
                        overtimeSignOutOptions
                ));
            }
        }
        return windows;
    }

    private static List<String> buildPunchOptionLabels(List<String> timeRanges) {
        List<String> labels = new ArrayList<>();
        if (timeRanges == null) {
            return labels;
        }
        for (String timeRange : timeRanges) {
            if (timeRange == null || timeRange.trim().isEmpty()) {
                continue;
            }
            String rangeLabel = timeRange.trim();
            labels.add(rangeLabel + " \u4e0a\u73ed");
            labels.add(rangeLabel + " \u4e0b\u73ed");
        }
        return labels;
    }

    private static PunchWindow buildAllowedPunchWindow(PunchOption option,
                                                       int dayOffset,
                                                       int windowMinutes,
                                                       List<String> optionLabels,
                                                       List<String> overtimeSignOutOptions) {
        int targetMinute = option.targetMinute(dayOffset);
        int startMinute = targetMinute - windowMinutes;
        int endMinute = targetMinute + windowMinutes;
        if (option.signOut && isOvertimeSignOutEnabled(option.label, overtimeSignOutOptions)) {
            int nextSignInStart = findNextSignInWindowStartMinute(optionLabels, targetMinute, windowMinutes);
            if (nextSignInStart > Integer.MIN_VALUE) {
                endMinute = Math.max(startMinute, nextSignInStart - 1);
            }
        }
        return new PunchWindow(
                option.label,
                dayOffset,
                option.signIn ? 0 : 1,
                startMinute,
                endMinute
        );
    }

    private static int findNextSignInWindowStartMinute(List<String> optionLabels,
                                                       int afterTargetMinute,
                                                       int windowMinutes) {
        int nextStart = Integer.MAX_VALUE;
        if (optionLabels == null || optionLabels.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        for (String label : optionLabels) {
            PunchOption option = parsePunchOption(label);
            if (option == null || !option.signIn) {
                continue;
            }
            for (int dayOffset = -1; dayOffset <= 4; dayOffset++) {
                int signInWindowStart = option.targetMinute(dayOffset) - windowMinutes;
                if (signInWindowStart > afterTargetMinute && signInWindowStart < nextStart) {
                    nextStart = signInWindowStart;
                }
            }
        }
        return nextStart == Integer.MAX_VALUE ? Integer.MIN_VALUE : nextStart;
    }

    private static boolean isOvertimeSignOutEnabled(String optionLabel, List<String> overtimeSignOutOptions) {
        if (optionLabel == null || overtimeSignOutOptions == null || overtimeSignOutOptions.isEmpty()) {
            return false;
        }
        return overtimeSignOutOptions.contains(optionLabel.trim());
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

    private static PunchOption parsePunchOption(String optionLabel) {
        if (optionLabel == null || optionLabel.trim().isEmpty()) {
            return null;
        }
        String label = optionLabel.trim();
        String[] parts = label.split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        String[] range = parts[0].split("-");
        if (range.length != 2) {
            return null;
        }
        int startMinutes = parseMinutes(range[0]);
        int endMinutes = parseMinutes(range[1]);
        if (startMinutes < 0 || endMinutes < 0) {
            return null;
        }
        boolean signIn = parts[1].contains("\u4e0a\u73ed");
        boolean signOut = parts[1].contains("\u4e0b\u73ed");
        if (!signIn && !signOut) {
            return null;
        }
        return new PunchOption(label, startMinutes, endMinutes, signIn, signOut);
    }

    private static final class PunchOption {
        final String label;
        final int startMinutes;
        final int endMinutes;
        final boolean signIn;
        final boolean signOut;
        final boolean overnight;

        private PunchOption(String label, int startMinutes, int endMinutes, boolean signIn, boolean signOut) {
            this.label = label;
            this.startMinutes = startMinutes;
            this.endMinutes = endMinutes;
            this.signIn = signIn;
            this.signOut = signOut;
            this.overnight = endMinutes <= startMinutes;
        }

        int targetMinute(int dayOffset) {
            if (signIn) {
                return dayOffset * 1440 + startMinutes;
            }
            return (dayOffset + (overnight ? 1 : 0)) * 1440 + endMinutes;
        }
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
