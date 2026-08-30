package com.punch.app.receiver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UpdateRelaunchPolicyTest {
    private static final long VERSION_CODE = 112L;
    private static final long STARTED_AT = 1_000L;

    @Test
    public void acknowledgedGenerationNeverLaunchesAgain() {
        assertEquals(
                UpdateRelaunchPolicy.Decision.IGNORE,
                decide(UpdateRelaunchPolicy.PHASE_UI_ACKED, VERSION_CODE, VERSION_CODE,
                        1, STARTED_AT, 2_000L, 30_000L, false)
        );
    }

    @Test
    public void retryForDifferentInstalledVersionIsIgnored() {
        assertEquals(
                UpdateRelaunchPolicy.Decision.IGNORE,
                decide(UpdateRelaunchPolicy.PHASE_WAITING, VERSION_CODE, VERSION_CODE + 1,
                        0, STARTED_AT, 0L, 2_000L, false)
        );
    }

    @Test
    public void visibleActivitySuppressesAnotherLaunch() {
        assertEquals(
                UpdateRelaunchPolicy.Decision.WAIT,
                decide(UpdateRelaunchPolicy.PHASE_WAITING, VERSION_CODE, VERSION_CODE,
                        0, STARTED_AT, 0L, 2_000L, true)
        );
    }

    @Test
    public void launchGraceSuppressesSlowStartupRetry() {
        assertEquals(
                UpdateRelaunchPolicy.Decision.WAIT,
                decide(UpdateRelaunchPolicy.PHASE_LAUNCHING, VERSION_CODE, VERSION_CODE,
                        1, STARTED_AT, 2_000L, 9_999L, false)
        );
    }

    @Test
    public void missingUiAfterGraceAllowsRecoveryLaunch() {
        assertEquals(
                UpdateRelaunchPolicy.Decision.LAUNCH,
                decide(UpdateRelaunchPolicy.PHASE_LAUNCHING, VERSION_CODE, VERSION_CODE,
                        2, STARTED_AT, 2_000L, 10_000L, false)
        );
    }

    @Test
    public void deadlineExhaustsUnacknowledgedGeneration() {
        assertEquals(
                UpdateRelaunchPolicy.Decision.EXHAUST,
                decide(UpdateRelaunchPolicy.PHASE_WAITING, VERSION_CODE, VERSION_CODE,
                        4, STARTED_AT, 40_000L, 61_000L, false)
        );
    }

    @Test
    public void retryDelaysBackOffAndThenWaitForDeadline() {
        assertEquals(2_000L, UpdateRelaunchPolicy.nextDelayMs(0, STARTED_AT, STARTED_AT));
        assertEquals(5_000L, UpdateRelaunchPolicy.nextDelayMs(1, STARTED_AT, 3_000L));
        assertEquals(10_000L, UpdateRelaunchPolicy.nextDelayMs(2, STARTED_AT, 8_000L));
        assertEquals(20_000L, UpdateRelaunchPolicy.nextDelayMs(3, STARTED_AT, 18_000L));
        assertEquals(23_000L, UpdateRelaunchPolicy.nextDelayMs(4, STARTED_AT, 38_000L));
    }

    @Test
    public void graceWaitsDoNotConsumeLaunchAttemptsAndEveryLaunchGetsRecoveryCheck() {
        long now = STARTED_AT;
        int attempt = 0;
        long lastLaunchAt = 0L;
        String phase = UpdateRelaunchPolicy.PHASE_WAITING;
        List<Long> launchTimes = new ArrayList<>();

        while (true) {
            UpdateRelaunchPolicy.Decision decision = decide(
                    phase,
                    VERSION_CODE,
                    VERSION_CODE,
                    attempt,
                    STARTED_AT,
                    lastLaunchAt,
                    now,
                    false
            );
            if (decision == UpdateRelaunchPolicy.Decision.EXHAUST) {
                break;
            }

            int previousAttempt = attempt;
            long delayMs = UpdateRelaunchPolicy.nextDelayMs(attempt, STARTED_AT, now);
            attempt = UpdateRelaunchPolicy.attemptAfterDecision(attempt, decision);
            if (decision == UpdateRelaunchPolicy.Decision.LAUNCH) {
                launchTimes.add(now);
                lastLaunchAt = now;
                phase = UpdateRelaunchPolicy.PHASE_LAUNCHING;
                assertEquals(previousAttempt + 1, attempt);
            } else {
                assertEquals(previousAttempt, attempt);
            }
            now += delayMs;
        }

        assertEquals(Arrays.asList(1_000L, 13_000L, 28_000L, 38_000L), launchTimes);
        assertEquals(58_000L, now);
    }

    private static UpdateRelaunchPolicy.Decision decide(String phase,
                                                         long generationVersion,
                                                         long installedVersion,
                                                         int attempt,
                                                         long startedAt,
                                                         long lastLaunchAt,
                                                         long now,
                                                         boolean activityVisible) {
        return UpdateRelaunchPolicy.decide(
                new UpdateRelaunchPolicy.State(
                        phase,
                        generationVersion,
                        attempt,
                        startedAt,
                        lastLaunchAt
                ),
                installedVersion,
                now,
                activityVisible
        );
    }
}
