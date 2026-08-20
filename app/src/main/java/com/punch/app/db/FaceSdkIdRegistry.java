package com.punch.app.db;

/** Allocates stable, collision-free positive integer IDs for the face SDK. */
public final class FaceSdkIdRegistry {
    interface Store {
        Integer find(String employeeId);

        int maxId();

        boolean insert(String employeeId, int sdkId);
    }

    private static final int MAX_INSERT_ATTEMPTS = 8;
    private final Store store;

    FaceSdkIdRegistry(Store store) {
        if (store == null) {
            throw new IllegalArgumentException("store == null");
        }
        this.store = store;
    }

    public synchronized int getOrCreate(String employeeId) {
        if (employeeId == null || employeeId.trim().isEmpty()) {
            throw new IllegalArgumentException("employeeId is blank");
        }
        Integer existing = store.find(employeeId);
        if (existing != null && existing > 0) {
            return existing;
        }
        for (int attempt = 0; attempt < MAX_INSERT_ATTEMPTS; attempt++) {
            int maxId = store.maxId();
            if (maxId == Integer.MAX_VALUE) {
                throw new IllegalStateException("Face SDK ID space exhausted");
            }
            int candidate = Math.max(1, maxId + 1);
            if (store.insert(employeeId, candidate)) {
                return candidate;
            }
            existing = store.find(employeeId);
            if (existing != null && existing > 0) {
                return existing;
            }
        }
        throw new IllegalStateException("Unable to allocate Face SDK ID");
    }
}
