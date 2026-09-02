package com.punch.app.fragment;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PunchCameraPolicyTest {

    @Test
    public void cameraFaceSoakBuildAutoEnablesPunchWhileReleaseStaysOff() {
        assertTrue(PunchCameraPolicy.shouldEnablePunchOnVisibleEntry(true));
        assertFalse(PunchCameraPolicy.shouldEnablePunchOnVisibleEntry(false));
    }

    @Test
    public void cameraOpensOnlyForAnActiveVisibleEnabledPunchView() {
        assertTrue(PunchCameraPolicy.shouldUseCamera(true, true, true, false));

        assertFalse(PunchCameraPolicy.shouldUseCamera(false, true, true, false));
        assertFalse(PunchCameraPolicy.shouldUseCamera(true, false, true, false));
        assertFalse(PunchCameraPolicy.shouldUseCamera(true, true, false, false));
        assertFalse(PunchCameraPolicy.shouldUseCamera(true, true, true, true));
    }
}
