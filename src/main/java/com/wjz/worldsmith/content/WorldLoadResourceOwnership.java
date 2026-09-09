package com.wjz.worldsmith.content;

import java.util.Objects;

/** The pre-server loading barrier owns only these two already-open handles, never their directories. */
public final class WorldLoadResourceOwnership {
    private final AutoCloseable stem;
    private final AutoCloseable storage;
    private boolean transferred;
    private boolean closed;

    public WorldLoadResourceOwnership(AutoCloseable stem, AutoCloseable storage) {
        this.stem = Objects.requireNonNull(stem);
        this.storage = Objects.requireNonNull(storage);
    }

    /** Once vanilla is entered it may already have started a server; the barrier must not close its handles. */
    public synchronized void transferToVanilla() {
        if (closed || transferred) throw new IllegalStateException("World load handles have already been consumed");
        transferred = true;
    }

    /** Both handles are attempted exactly once even if close fails. No path delete or data rewrite occurs. */
    public synchronized void abort(Throwable cause) {
        Objects.requireNonNull(cause);
        if (transferred || closed) return;
        closed = true;
        close(stem, cause);
        close(storage, cause);
    }

    private static void close(AutoCloseable handle, Throwable cause) {
        try { handle.close(); }
        catch (Throwable closing) { if (closing != cause) cause.addSuppressed(closing); }
    }
}
