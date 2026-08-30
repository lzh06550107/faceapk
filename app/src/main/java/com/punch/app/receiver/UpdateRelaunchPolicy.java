package com.punch.app.receiver;

import com.punch.app.utils.Constants;

public final class UpdateRelaunchPolicy {
    public static final String PHASE_IDLE = Constants.UPDATE_RELAUNCH_PHASE_IDLE;
    public static final String PHASE_WAITING = Constants.UPDATE_RELAUNCH_PHASE_WAITING;
    public static final String PHASE_LAUNCHING = Constants.UPDATE_RELAUNCH_PHASE_LAUNCHING;
    public static final String PHASE_UI_ACKED = Constants.UPDATE_RELAUNCH_PHASE_UI_ACKED;
    public static final String PHASE_EXHAUSTED = Constants.UPDATE_RELAUNCH_PHASE_EXHAUSTED;

    static final long DEADLINE_MS = 60_000L;
    private static final long LAUNCH_GRACE_MS = 8_000L;
    private static final long[] RETRY_DELAYS_MS = {
            2_000L,
            5_000L,
            10_000L,
            20_000L
    };

    private UpdateRelaunchPolicy() {
    }

    public static Decision decide(State state,
                                  long installedVersionCode,
                                  long nowElapsedRealtime,
                                  boolean activityVisible) {
        if (state == null || !isPendingPhase(state.phase)) {
            return Decision.IGNORE;
        }
        if (state.versionCode <= 0L || state.versionCode != installedVersionCode) {
            return Decision.IGNORE;
        }
        if (state.startedElapsedRealtime <= 0L
                || nowElapsedRealtime < state.startedElapsedRealtime
                || nowElapsedRealtime - state.startedElapsedRealtime >= DEADLINE_MS) {
            return Decision.EXHAUST;
        }
        if (activityVisible) {
            return Decision.WAIT;
        }
        if (PHASE_LAUNCHING.equals(state.phase)
                && state.lastLaunchElapsedRealtime > 0L
                && nowElapsedRealtime - state.lastLaunchElapsedRealtime < LAUNCH_GRACE_MS) {
            return Decision.WAIT;
        }
        if (state.startedElapsedRealtime + DEADLINE_MS - nowElapsedRealtime
                <= LAUNCH_GRACE_MS) {
            return Decision.EXHAUST;
        }
        return Decision.LAUNCH;
    }

    public static int attemptAfterDecision(int attempt, Decision decision) {
        int safeAttempt = Math.max(0, attempt);
        return decision == Decision.LAUNCH ? safeAttempt + 1 : safeAttempt;
    }

    public static long nextDelayMs(int attempt,
                                   long startedElapsedRealtime,
                                   long nowElapsedRealtime) {
        long remaining = Math.max(
                0L,
                startedElapsedRealtime + DEADLINE_MS - nowElapsedRealtime
        );
        if (attempt >= 0 && attempt < RETRY_DELAYS_MS.length) {
            return Math.min(RETRY_DELAYS_MS[attempt], remaining);
        }
        return remaining;
    }

    public static boolean isPendingPhase(String phase) {
        return PHASE_WAITING.equals(phase) || PHASE_LAUNCHING.equals(phase);
    }

    public enum Decision {
        IGNORE,
        WAIT,
        LAUNCH,
        EXHAUST
    }

    public static final class State {
        public final String phase;
        public final long versionCode;
        public final int attempt;
        public final long startedElapsedRealtime;
        public final long lastLaunchElapsedRealtime;

        public State(String phase,
                     long versionCode,
                     int attempt,
                     long startedElapsedRealtime,
                     long lastLaunchElapsedRealtime) {
            this.phase = phase == null ? PHASE_IDLE : phase;
            this.versionCode = versionCode;
            this.attempt = Math.max(0, attempt);
            this.startedElapsedRealtime = startedElapsedRealtime;
            this.lastLaunchElapsedRealtime = lastLaunchElapsedRealtime;
        }
    }
}
