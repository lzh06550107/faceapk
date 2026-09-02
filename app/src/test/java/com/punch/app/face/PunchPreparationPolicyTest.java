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
}
