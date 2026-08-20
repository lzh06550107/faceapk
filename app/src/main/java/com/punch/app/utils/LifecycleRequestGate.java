package com.punch.app.utils;

/** Invalidates asynchronous work when a Fragment view generation is destroyed. */
public final class LifecycleRequestGate {
    private int generation;
    private boolean open;

    public synchronized int open() {
        generation++;
        open = true;
        return generation;
    }

    public synchronized void close() {
        generation++;
        open = false;
    }

    public synchronized boolean isActive(int token) {
        return open && token == generation;
    }
}
