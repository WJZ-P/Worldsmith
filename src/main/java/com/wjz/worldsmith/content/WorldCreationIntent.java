package com.wjz.worldsmith.content;

import java.util.Objects;
import java.lang.ref.WeakReference;

/** One creation request, bound once to its exact screen; late work never acquires a new owner. */
public final class WorldCreationIntent {
    public record Ticket(long requestId, long attempt) {}

    private final long requestId;
    private final String packId;
    private final String displayName;
    private final boolean explicit;
    private WeakReference<Object> owner;
    private long attempt;
    private volatile boolean cancelled;

    public WorldCreationIntent(long requestId, String packId, String displayName, boolean explicit) {
        if (requestId < 1 || packId == null || !packId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Creation requires a request id and an immutable bundle id");
        this.requestId = requestId;
        this.packId = packId;
        this.displayName = Objects.requireNonNull(displayName);
        this.explicit = explicit;
    }

    public long requestId() { return requestId; }
    public String packId() { return packId; }
    public String displayName() { return displayName; }
    public boolean explicit() { return explicit; }
    public boolean cancelled() { return cancelled; }

    public void bind(Object screen) {
        Objects.requireNonNull(screen);
        if (cancelled || (owner != null && owner.get() != screen))
            throw new IllegalStateException("A creation request belongs to exactly one screen");
        owner = new WeakReference<>(screen);
    }

    public Ticket beginAttempt(Object screen) {
        if (cancelled || owner == null || owner.get() != screen)
            throw new IllegalStateException("This screen no longer owns the creation request");
        return new Ticket(requestId, ++attempt);
    }

    public boolean owns(Object screen, Ticket ticket) {
        return !cancelled && owner != null && owner.get() == screen && screen != null && ticket != null
            && ticket.requestId == requestId && ticket.attempt == attempt;
    }

    /** Authoring may suggest another pack only while there is no explicit player selection. */
    public boolean acceptsPublication(String requestedPackId) {
        return !cancelled && (!explicit || packId.equals(requestedPackId));
    }

    public void cancel(Object screen) {
        if (owner == null || owner.get() != screen || screen == null) throw new IllegalStateException("Another screen owns this creation request");
        cancelled = true;
        ++attempt;
    }
}
