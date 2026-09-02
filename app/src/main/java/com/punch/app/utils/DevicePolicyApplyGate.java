package com.punch.app.utils;

final class DevicePolicyApplyGate {
    private boolean applying;
    private boolean applied;

    synchronized boolean tryBegin() {
        if (applying || applied) {
            return false;
        }
        applying = true;
        return true;
    }

    synchronized void finish(boolean success) {
        applying = false;
        applied = success;
    }

    synchronized void invalidate() {
        applying = false;
        applied = false;
    }
}
