package com.wjz.worldsmith.client.content;

import com.wjz.worldsmith.content.WorldContentRuntime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/**
 * Local integrated-world publication: resources first, then block mapping and creature definitions.
 * Never join these futures on the Minecraft thread. Remote asset negotiation is not implemented.
 */
public final class WorldContentClientRuntime {
    private static Prepared active;
    private static boolean changing;
    private static CompletableFuture<Void> inFlight = CompletableFuture.completedFuture(null);
    private WorldContentClientRuntime() {}

    /** This stage is pure: it validates/copies/hashes resources without activating native content. */
    public static synchronized Prepared prepare(WorldContentRuntime.Prepared content) {
        Objects.requireNonNull(content);
        return new Prepared(content, active, WorldContentResources.prepare(content.scope(), content.clientResources()));
    }

    public static synchronized String activeScope() { return active == null ? null : active.content.scope(); }

    /** Shared barrier for creation-screen publication, connection cleanup and saved-world restoration. */
    public static synchronized CompletableFuture<Void> whenIdle() { return inFlight; }

    private static void track(CompletableFuture<Void> result) {
        changing = true;
        // Cleanup waits for completion, not success; the original caller still receives the failure.
        inFlight = result.handle((ignored, failure) -> null);
    }

    public static void requireLocalWorld() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null && !client.isLocalServer())
            throw new IllegalStateException("Worldsmith custom content currently supports local integrated worlds only; remote servers require an asset/binding handshake that is not installed");
    }

    /** Called on disconnect/creation cancellation; server leases retain block bindings until that server stops. */
    public static CompletableFuture<Void> clear(String expectedScope) {
        var result = new CompletableFuture<Void>();
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            Prepared current;
            WorldContentRuntime.ClientTransition transition;
            synchronized (WorldContentClientRuntime.class) {
                if (changing) { fail(result, new IllegalStateException("Await the current content resource transition before clearing")); return; }
                current = active;
                if (current == null) {
                    if (WorldContentResources.activeScope() != null) fail(result, new IllegalStateException("An unmanaged world resource pack owns the client"));
                    else result.complete(null);
                    return;
                }
                if (!current.content.scope().equals(expectedScope)) { fail(result, new IllegalStateException("Client clear belongs to another world")); return; }
                try { transition = WorldContentRuntime.beginClientTransition(null, current.lease); }
                catch (RuntimeException failure) { fail(result, failure); return; }
                track(result);
            }
            WorldContentResources.clear(expectedScope).whenCompleteAsync((ignored, failure) -> {
                if (failure != null) {
                    cancel(transition, failure);
                    synchronized (WorldContentClientRuntime.class) { changing = false; }
                    fail(result, failure); return;
                }
                try {
                    transition.commit();
                    synchronized (WorldContentClientRuntime.class) { active = null; current.rolledBack = true; changing = false; }
                    result.complete(null);
                } catch (Throwable publicationFailure) {
                    // Reservation prevents ordinary ownership races; restore verified resources on a lower-layer failure.
                    WorldContentResources.prepare(current.content.scope(), current.content.clientResources()).activate().whenCompleteAsync((restore, restoreFailure) -> {
                        if (restoreFailure != null) publicationFailure.addSuppressed(restoreFailure);
                        cancel(transition, publicationFailure);
                        synchronized (WorldContentClientRuntime.class) { changing = false; }
                        fail(result, publicationFailure);
                    }, client);
                }
            }, client);
        });
        return result;
    }

    public static final class Prepared {
        private final WorldContentRuntime.Prepared content;
        private final Prepared previous;
        private final WorldContentResources.Prepared resources;
        private WorldContentRuntime.ClientLease lease;
        private boolean consumed;
        private boolean rolledBack;
        private Prepared(WorldContentRuntime.Prepared content, Prepared previous, WorldContentResources.Prepared resources) {
            this.content = content; this.previous = previous; this.resources = resources;
        }
        public String scope() { return content.scope(); }
        public String contentHash() { return resources.contentHash(); }
        public Map<String, String> resourceHashes() { return resources.hashes(); }
        public WorldContentRuntime.Prepared content() { return content; }

        public CompletableFuture<Void> activate() {
            var result = new CompletableFuture<Void>();
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                WorldContentRuntime.ClientTransition transition;
                synchronized (WorldContentClientRuntime.class) {
                    if (consumed || changing || active != previous) { fail(result, new IllegalStateException("Stale, consumed or overlapping client content preparation")); return; }
                    try {
                        requireLocalWorld();
                        transition = WorldContentRuntime.beginClientTransition(content, previous == null ? null : previous.lease);
                    } catch (RuntimeException failure) { fail(result, failure); return; }
                    consumed = true; track(result);
                }
                resources.activate().whenCompleteAsync((ignored, failure) -> {
                    if (failure != null) {
                        cancel(transition, failure);
                        synchronized (WorldContentClientRuntime.class) { changing = false; }
                        fail(result, failure); return;
                    }
                    try {
                        var published = transition.commit();
                        synchronized (WorldContentClientRuntime.class) { lease = published; active = this; changing = false; }
                        result.complete(null);
                    } catch (Throwable publicationFailure) {
                        resources.rollback().whenCompleteAsync((restored, restoreFailure) -> {
                            if (restoreFailure != null) publicationFailure.addSuppressed(restoreFailure);
                            cancel(transition, publicationFailure);
                            synchronized (WorldContentClientRuntime.class) { changing = false; }
                            fail(result, publicationFailure);
                        }, client);
                    }
                }, client);
            });
            return result;
        }

        /** Restores the previous complete client world; rejects rollback across an active server's ownership. */
        public CompletableFuture<Void> rollback() {
            var result = new CompletableFuture<Void>();
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                WorldContentRuntime.ClientTransition transition;
                synchronized (WorldContentClientRuntime.class) {
                    if (rolledBack) { result.complete(null); return; }
                    if (changing) { fail(result, new IllegalStateException("Await client publication before rollback")); return; }
                    if (!consumed) { consumed = true; rolledBack = true; result.complete(null); return; }
                    if (active != this) {
                        if (lease == null) { rolledBack = true; result.complete(null); }
                        else fail(result, new IllegalStateException("A later publication owns the client; stale rollback rejected"));
                        return;
                    }
                    try { transition = WorldContentRuntime.beginClientTransition(previous == null ? null : previous.content, lease); }
                    catch (RuntimeException failure) { fail(result, failure); return; }
                    track(result);
                }
                // Stage fresh restoration rather than reusing an old resource epoch after nested rollbacks.
                CompletableFuture<Void> restore = previous == null ? WorldContentResources.clear(content.scope())
                    : WorldContentResources.prepare(previous.content.scope(), previous.content.clientResources()).activate();
                restore.whenCompleteAsync((ignored, failure) -> {
                    if (failure != null) {
                        cancel(transition, failure);
                        synchronized (WorldContentClientRuntime.class) { changing = false; }
                        fail(result, failure); return;
                    }
                    try {
                        var restoredLease = transition.commit();
                        synchronized (WorldContentClientRuntime.class) {
                            if (previous != null) previous.lease = restoredLease;
                            active = previous; rolledBack = true; changing = false;
                        }
                        result.complete(null);
                    } catch (Throwable publicationFailure) {
                        WorldContentResources.prepare(content.scope(), content.clientResources()).activate().whenCompleteAsync((restored, restoreFailure) -> {
                            if (restoreFailure != null) publicationFailure.addSuppressed(restoreFailure);
                            cancel(transition, publicationFailure);
                            synchronized (WorldContentClientRuntime.class) { changing = false; }
                            fail(result, publicationFailure);
                        }, client);
                    }
                }, client);
            });
            return result;
        }
    }

    private static void cancel(WorldContentRuntime.ClientTransition transition, Throwable failure) {
        try { transition.cancel(); } catch (Throwable cancellation) { failure.addSuppressed(cancellation); }
    }
    private static void fail(CompletableFuture<Void> result, Throwable failure) { result.completeExceptionally(failure); }
}
