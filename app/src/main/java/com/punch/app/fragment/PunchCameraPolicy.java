package com.punch.app.fragment;

final class PunchCameraPolicy {
    private PunchCameraPolicy() {
    }

    static boolean shouldEnablePunchOnVisibleEntry(boolean cameraFaceSoakAutoEnable) {
        return cameraFaceSoakAutoEnable;
    }

    static boolean shouldUseCamera(
            boolean punchActive,
            boolean punchEnabled,
            boolean textureAvailable,
            boolean fragmentHidden
    ) {
        return punchActive && punchEnabled && textureAvailable && !fragmentHidden;
    }
}
