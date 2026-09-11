package com.wjz.worldsmith.client;

import com.wjz.worldsmith.core.mcp.GenerationProgressSnapshot;
import com.wjz.worldsmith.core.mcp.PublicationStatus;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

/** Per-tab read cache. Only the worker projects drafts; render ticks read metadata and screen-owned state. */
public final class WorldsmithGenerationProgressPoller implements AutoCloseable {
    private static final long INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(750);
    private static final ExecutorService READS = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "worldsmith-generation-progress"); thread.setDaemon(true); return thread;
    });
    private static final PublicationStatus NOT_SELECTED = new PublicationStatus("NOT_SELECTED", "No bundle is selected in this Create World context", List.of());

    public record Snapshot(long epoch, boolean connected, boolean refreshing, String error,
                           GenerationProgressSnapshot progress, PublicationStatus nativeStatus) {}
    private record Completed(long ticket, long connectionEpoch, WorldsmithMcpService.ProgressRead read, String error) {}

    private final AtomicReference<Completed> completed = new AtomicReference<>();
    private volatile Snapshot snapshot = new Snapshot(-1, false, false, null, null, NOT_SELECTED);
    private volatile long requestSequence;
    private volatile boolean closed;
    private long connectionEpoch = Long.MIN_VALUE;
    private long nextPoll;
    private boolean inFlight;
    private String preferredSessionId;

    public Snapshot snapshot() { return snapshot; }
    public boolean isFollowingActiveSession() { return preferredSessionId == null; }
    public String preferredSessionId() { return preferredSessionId; }

    public void selectSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) throw new IllegalArgumentException("A session id is required");
        select(sessionId);
    }

    public void followActiveSession() { select(null); }

    private void select(String sessionId) {
        if (closed) throw new IllegalStateException("Create a new progress poller after closing this view");
        if (Objects.equals(preferredSessionId, sessionId)) return;
        preferredSessionId = sessionId;
        ++requestSequence; completed.set(null); inFlight = false; nextPoll = 0;
        snapshot = new Snapshot(snapshot.epoch(), snapshot.connected(), false, null, null, NOT_SELECTED);
    }

    /** Call on the render thread for the owning, currently visible Create World tab. No I/O is performed here. */
    public void tick(CreateWorldScreen screen) {
        if (closed) return;
        long now = System.nanoTime();
        var connection = WorldsmithMcpService.progressConnection();
        if (connection.epoch() != connectionEpoch) {
            connectionEpoch = connection.epoch();
            ++requestSequence; completed.set(null); inFlight = false; nextPoll = 0;
            snapshot = new Snapshot(connectionEpoch, connection.connected(), false, null, null, NOT_SELECTED);
        }
        var result = completed.getAndSet(null);
        if (result != null && result.ticket() == requestSequence && result.connectionEpoch() == connectionEpoch) {
            inFlight = false; nextPoll = now + INTERVAL_NANOS;
            if (result.read() != null && result.read().epoch() == connectionEpoch && result.read().connected()) {
                var progress = result.read().snapshot();
                String error = progress != null && preferredSessionId != null && progress.getSelectedSessionId() == null
                    ? "The selected session is no longer active on this bridge" : result.error();
                snapshot = new Snapshot(connectionEpoch, true, false, error, progress, NOT_SELECTED);
            } else if (result.error() != null) {
                snapshot = new Snapshot(connectionEpoch, connection.connected(), false, result.error(), snapshot.progress(), NOT_SELECTED);
            }
        }
        var progress = snapshot.progress();
        String packId = progress == null || progress.getView() == null ? null : progress.getView().getPackId();
        var nativeStatus = connection.connected() ? WorldsmithWorldCreationBridge.progressPublicationStatus(screen, packId) : NOT_SELECTED;
        snapshot = new Snapshot(connectionEpoch, connection.connected(), inFlight, snapshot.error(), progress, nativeStatus);
        if (!connection.connected() || inFlight || now < nextPoll) return;

        final long ticket = ++requestSequence, epoch = connectionEpoch;
        final String selection = preferredSessionId;
        inFlight = true;
        snapshot = new Snapshot(connectionEpoch, true, true, snapshot.error(), progress, nativeStatus);
        READS.execute(() -> {
            if (closed || requestSequence != ticket) return;
            Completed response;
            try { response = new Completed(ticket, epoch, WorldsmithMcpService.readGenerationProgress(selection), null); }
            catch (Exception failure) {
                String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                response = new Completed(ticket, epoch, null, message.substring(0, Math.min(message.length(), 512)));
            }
            if (!closed && requestSequence == ticket) {
                // A late completion must not overwrite a newer selection's already-finished response.
                completed.accumulateAndGet(response, (previous, next) -> previous == null || previous.ticket() < next.ticket() ? next : previous);
            }
        });
    }

    @Override public void close() {
        closed = true; ++requestSequence; completed.set(null); inFlight = false;
        snapshot = new Snapshot(connectionEpoch, false, false, null, null, NOT_SELECTED);
    }
}
