package com.punch.app.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AvatarLoadPolicyTest {
    @Test
    public void clearingAvatarAfterActivityDestroyedUsesApplicationContext() {
        assertEquals(
                AvatarLoadPolicy.RequestManagerChoice.APPLICATION_CONTEXT,
                AvatarLoadPolicy.chooseRequestManager(true, true));
    }

    @Test
    public void loadingAvatarAfterActivityDestroyedDoesNotStartRequest() {
        assertEquals(
                AvatarLoadPolicy.RequestManagerChoice.SKIP,
                AvatarLoadPolicy.chooseRequestManager(false, true));
    }

    @Test
    public void activeActivityUsesViewLifecycleForImageRequests() {
        assertEquals(
                AvatarLoadPolicy.RequestManagerChoice.VIEW,
                AvatarLoadPolicy.chooseRequestManager(false, false));
        assertEquals(
                AvatarLoadPolicy.RequestManagerChoice.VIEW,
                AvatarLoadPolicy.chooseRequestManager(true, false));
    }
}
