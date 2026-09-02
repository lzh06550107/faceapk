package com.punch.app.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DevicePolicyApplyGateTest {

    @Test
    public void onlyOnePolicyApplicationRunsAndSuccessIsRemembered() {
        DevicePolicyApplyGate gate = new DevicePolicyApplyGate();

        assertTrue(gate.tryBegin());
        assertFalse(gate.tryBegin());

        gate.finish(true);
        assertFalse(gate.tryBegin());
    }

    @Test
    public void failedOrInvalidatedApplicationCanRunAgain() {
        DevicePolicyApplyGate gate = new DevicePolicyApplyGate();

        assertTrue(gate.tryBegin());
        gate.finish(false);
        assertTrue(gate.tryBegin());
        gate.finish(true);

        gate.invalidate();
        assertTrue(gate.tryBegin());
    }
}
