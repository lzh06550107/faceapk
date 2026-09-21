package com.punch.app.face;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PunchPreparationPolicyTest {
    @Test
    public void noLocalEmployeesRequiresEmployeeSync() {
        assertEquals(
                PunchPreparationPolicy.Action.SYNC_EMPLOYEES,
                PunchPreparationPolicy.decide(0, false));
    }

    @Test
    public void reusableRuntimeLibrarySkipsRebuild() {
        assertEquals(
                PunchPreparationPolicy.Action.REUSE_FACE_LIBRARY,
                PunchPreparationPolicy.decide(20, true));
    }

    @Test
    public void coldRuntimeLibraryRequiresRebuild() {
        assertEquals(
                PunchPreparationPolicy.Action.REBUILD_FACE_LIBRARY,
                PunchPreparationPolicy.decide(20, false));
    }

    @Test
    public void firstPreparationAttemptStartsImmediately() {
        org.junit.Assert.assertTrue(
                PunchPreparationPolicy.shouldStartRetry(0L, 1_000L, 10_000L));
    }

    @Test
    public void repeatedPreparationAttemptIsThrottled() {
        org.junit.Assert.assertFalse(
                PunchPreparationPolicy.shouldStartRetry(1_000L, 5_000L, 10_000L));
        org.junit.Assert.assertTrue(
                PunchPreparationPolicy.shouldStartRetry(1_000L, 11_000L, 10_000L));
    }

    @Test
    public void clockRollbackDoesNotBlockRecovery() {
        org.junit.Assert.assertTrue(
                PunchPreparationPolicy.shouldStartRetry(20_000L, 10_000L, 10_000L));
    }
}
