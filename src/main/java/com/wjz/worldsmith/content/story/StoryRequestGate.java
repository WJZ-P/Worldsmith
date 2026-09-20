package com.wjz.worldsmith.content.story;

/** Constant-space request budget, applied before even rejected requests may build a story snapshot. */
final class StoryRequestGate {
    static final int LIMIT = 12;
    static final long WINDOW_TICKS = 20;
    private int admitted, lastRequest;
    private long windowStart;
    private boolean started;

    boolean allowRequest(long gameTime) {
        if (gameTime < 0) return false;
        if (!started) { started = true; windowStart = gameTime; }
        else if (gameTime < windowStart) {
            // A clock rebase does not refund an already spent budget or erase nonce history.
            windowStart = gameTime;
        } else if (gameTime - windowStart >= WINDOW_TICKS) {
            windowStart = gameTime; admitted = 0;
        }
        if (admitted >= LIMIT) return false; // Saturate: hostile repeated packets cannot overflow the counter.
        admitted++; return true;
    }

    /** Invoke only for an admitted request in the correct world, preserving the existing scope/nonce semantics. */
    boolean advanceNonce(int requestId) {
        if (requestId < 1 || requestId <= lastRequest) return false;
        lastRequest = requestId; return true;
    }
}
