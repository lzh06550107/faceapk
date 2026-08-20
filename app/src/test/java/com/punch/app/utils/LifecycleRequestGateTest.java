package com.punch.app.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LifecycleRequestGateTest {
    @Test
    public void closingViewInvalidatesItsOutstandingWork() {
        LifecycleRequestGate gate = new LifecycleRequestGate();
        int firstView = gate.open();
        assertTrue(gate.isActive(firstView));

        gate.close();
        assertFalse(gate.isActive(firstView));

        int secondView = gate.open();
        assertTrue(gate.isActive(secondView));
        assertFalse(gate.isActive(firstView));
    }
}
