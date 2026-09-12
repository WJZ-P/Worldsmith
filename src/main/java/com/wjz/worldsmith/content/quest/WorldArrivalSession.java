package com.wjz.worldsmith.content.quest;

/** Once per actual connection; quest-cache resets, respawns and dimension changes do not affect it. */
public final class WorldArrivalSession {
    public static final long DURATION_NANOS = 8_000_000_000L;
    private Object connection;
    private boolean shown;
    private long startedAt;
    private boolean dismissed;

    public void connect(Object next) {
        if (connection == next) return;
        connection = next; shown = false; dismissed = false; startedAt = 0;
    }
    public boolean waiting(Object current) { return connection != null && connection == current && !shown; }
    public boolean start(Object current, long now) {
        if (!waiting(current)) return false;
        shown = true; dismissed = false; startedAt = now; return true;
    }
    public boolean active(long now) { return connection != null && shown && !dismissed && now - startedAt >= 0 && now - startedAt < DURATION_NANOS; }
    public long elapsed(long now) { return Math.max(0, now - startedAt); }
    public void dismiss() { dismissed = true; }
}
