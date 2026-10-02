package com.ae2addon.gui;

/** Retry budget for one incomplete list transfer. Used on the client thread. */
final class ChunkRetry {
    private static final long TIMEOUT_MS = 4000L;
    private static final int MAX_RETRIES = 3;
    private long lastProgress;
    private int retries;

    void start() {
        retries = 0;
        lastProgress = System.currentTimeMillis();
    }

    void progress() { lastProgress = System.currentTimeMillis(); }

    void manualRefresh() { start(); }

    boolean retryDue(boolean pending) {
        if (!pending || retries >= MAX_RETRIES || System.currentTimeMillis() - lastProgress < TIMEOUT_MS)
            return false;
        retries++;
        lastProgress = System.currentTimeMillis();
        return true;
    }

    boolean exhausted(boolean pending) {
        return pending && retries >= MAX_RETRIES && System.currentTimeMillis() - lastProgress >= TIMEOUT_MS;
    }
}
