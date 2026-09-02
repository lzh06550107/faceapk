package com.punch.app.face;

final class FaceSdkOperationGuard {
    private static final FaceSdkOperationGuard SHARED = new FaceSdkOperationGuard();

    private final Object lock = new Object();

    private FaceSdkOperationGuard() {
    }

    static FaceSdkOperationGuard shared() {
        return SHARED;
    }

    <T> T call(Operation<T> operation) {
        synchronized (lock) {
            return operation.run();
        }
    }

    interface Operation<T> {
        T run();
    }
}
