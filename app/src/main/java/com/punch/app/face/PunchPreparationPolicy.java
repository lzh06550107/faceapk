package com.punch.app.face;

public final class PunchPreparationPolicy {
    private PunchPreparationPolicy() {
    }

    public enum Action {
        SYNC_EMPLOYEES,
        REUSE_FACE_LIBRARY,
        REBUILD_FACE_LIBRARY
    }

    public static Action decide(int activeEmployeeCount, boolean runtimeLibraryReusable) {
        if (activeEmployeeCount <= 0) {
            return Action.SYNC_EMPLOYEES;
        }
        return runtimeLibraryReusable
                ? Action.REUSE_FACE_LIBRARY
                : Action.REBUILD_FACE_LIBRARY;
    }
}
