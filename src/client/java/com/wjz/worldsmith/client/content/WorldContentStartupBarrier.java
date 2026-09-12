package com.wjz.worldsmith.client.content;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.client.WorldsmithWorldCreationBridge;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.WorldLoadResourceOwnership;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.WorldStem;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Loads the selected save's client content before Minecraft starts its integrated server.
 * The WorldStem is already open and owned by this load request; no save data is changed here.
 */
public final class WorldContentStartupBarrier {
    private static volatile Request current;
    private WorldContentStartupBarrier() {}

    /** Creation-cancellation callbacks must not clear a newer save while this barrier owns its activation. */
    public static boolean isLoading() { return current != null; }

    public static void start(Minecraft client, LevelStorageAccess storage, WorldStem stem, boolean newWorld, Runnable resumeVanilla) {
        if (!client.isSameThread()) {
            client.execute(() -> start(client, storage, stem, newWorld, resumeVanilla));
            return;
        }
        Request existing = current;
        if (existing != null) {
            boolean sharedStem = existing.stem == stem || existing.stem.resourceManager() == stem.resourceManager();
            if (existing.storage == storage && sharedStem) return;
            var failure = new IllegalStateException("Another integrated-world load is already waiting for its content resources");
            // Never close a handle shared with the load that is already in flight.
            closeRejected(sharedStem ? null : stem, failure);
            closeRejected(existing.storage == storage ? null : storage, failure);
            Worldsmith.LOGGER.warn("Rejected overlapping world load for {}", storage.getLevelId(), failure);
            SystemToast.add(client.gui.toastManager(), SystemToast.SystemToastId.WORLD_ACCESS_FAILURE,
                Component.literal("Worldsmith world load already in progress"), Component.literal("Wait for the current world load before opening another save."));
            return;
        }

        Request request = new Request(storage, stem, resumeVanilla,
            WorldsmithWorldCreationBridge.consumeStartupExpectation(storage.getLevelId(),newWorld));
        current = request;
        try {
            // This is vanilla doWorldLoad's first operation. Run it once BEFORE activating the new mapping.
            client.disconnectWithProgressScreen();
            client.gui.setScreen(new GenericMessageScreen(Component.literal("Loading Worldsmith world content…")));
            WorldContentClientLifecycle.beforeWorldLoad()
                .thenComposeAsync(ignored -> CompletableFuture.supplyAsync(() ->
                    WorldContentRuntime.loadSelected(stem.resourceManager()).map(WorldContentRuntime::prepare)), client)
                .thenComposeAsync(content -> WorldContentClientRuntime.whenIdle()
                    .thenComposeAsync(ignored -> activate(request, content), client), client)
                .whenCompleteAsync((ignored, failure) -> {
                    if (failure != null) fail(client, request, unwrap(failure));
                    else handoff(client, request);
                }, client);
        } catch (Throwable failure) { fail(client, request, failure); }
    }

    private static CompletableFuture<Void> activate(Request request, Optional<WorldContentRuntime.Prepared> content) {
        if (current != request) return CompletableFuture.failedFuture(new IllegalStateException("This world load no longer owns the startup barrier"));
        if(request.expectedScope!=null && (content.isEmpty() || !request.expectedScope.equals(content.get().scope())))
            return CompletableFuture.failedFuture(new IllegalStateException("The embedded world bundle differs from the player's committed creation selection"));
        if (content.isEmpty()) {
            request.targetScope = null;
            String previous = WorldContentClientRuntime.activeScope();
            return previous == null ? CompletableFuture.completedFuture(null) : WorldContentClientRuntime.clear(previous);
        }
        var prepared = WorldContentClientRuntime.prepare(content.get());
        request.targetScope = prepared.scope();
        request.activation = prepared;
        return prepared.activate();
    }

    private static void handoff(Minecraft client, Request request) {
        if (current != request) { fail(client, request, new IllegalStateException("A later load owns the client startup barrier")); return; }
        if (!Objects.equals(request.targetScope, WorldContentClientRuntime.activeScope())
            || !Objects.equals(request.targetScope, WorldContentRuntime.activeScope())) {
            fail(client, request, new IllegalStateException("World content changed after preparation and before server startup"));
            return;
        }
        request.ownership.transferToVanilla();
        try {
            // The mixin's exact-argument resume token bypasses re-entry and only this duplicate disconnect.
            request.resumeVanilla.run();
        } catch (Throwable nativeFailure) {
            // Ownership was handed to vanilla, which may have started its server. Do not close its resources.
            Worldsmith.LOGGER.error("Integrated server startup failed after Worldsmith content activation", nativeFailure);
            client.delayCrash(CrashReport.forThrowable(nativeFailure, "Starting integrated server after Worldsmith content loading"));
        } finally {
            if (current == request) current = null;
        }
    }

    private static void fail(Minecraft client, Request request, Throwable failure) {
        // A successful activation followed by a failed final guard still needs a transactional client rollback.
        CompletableFuture<Void> restore;
        try { restore = request.activation == null ? CompletableFuture.completedFuture(null) : request.activation.rollback(); }
        catch (Throwable rollbackFailure) { restore = CompletableFuture.failedFuture(rollbackFailure); }
        restore.whenCompleteAsync((ignored, rollbackFailure) -> {
            if (rollbackFailure != null) failure.addSuppressed(unwrap(rollbackFailure));
            request.ownership.abort(failure);
            Worldsmith.LOGGER.error("Worldsmith content preparation failed for saved world {}", request.storage.getLevelId(), failure);
            if (current != request) return;
            current = null;
            String detail = Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
            if (detail.length() > 1000) detail = detail.substring(0, 1000);
            client.gui.setScreen(new AlertScreen(
                () -> client.gui.setScreen(new SelectWorldScreen(new TitleScreen())),
                Component.literal("Worldsmith world content failed to load"),
                Component.literal(detail + "\nThe selected save and its embedded bundle were preserved. See the game log for details.")));
        }, client);
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private static void closeRejected(AutoCloseable handle, Throwable failure) {
        if (handle == null) return;
        try { handle.close(); } catch (Throwable closing) { if (closing != failure) failure.addSuppressed(closing); }
    }

    private static final class Request {
        final LevelStorageAccess storage;
        final WorldStem stem;
        final Runnable resumeVanilla;
        final String expectedScope;
        final WorldLoadResourceOwnership ownership;
        WorldContentClientRuntime.Prepared activation;
        String targetScope;
        Request(LevelStorageAccess storage, WorldStem stem, Runnable resumeVanilla, String expectedScope) {
            this.storage = storage; this.stem = stem; this.resumeVanilla = resumeVanilla;
            this.expectedScope=expectedScope;
            ownership = new WorldLoadResourceOwnership(stem, storage);
        }
    }
}
