package com.wjz.worldsmith.content;

import com.wjz.worldsmith.content.creature.CreatureRuntime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.server.MinecraftServer;

/** Save-owned content is restored before each dimension starts ticking; config drafts are never consulted. */
public final class WorldContentLifecycle {
    private static final Map<MinecraftServer,Optional<WorldContentRuntime.Prepared>> SERVERS = new ConcurrentHashMap<>();
    private WorldContentLifecycle() {}

    public static void initialize() {
        WorldsmithCustomBlocks.initialize();
        CreatureRuntime.register();
        net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(net.minecraft.server.packs.PackType.SERVER_DATA)
            .registerReloadListener(com.wjz.worldsmith.Worldsmith.id("content_guard"),
                new net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener<Optional<WorldContentRuntime.EmbeddedWorld>>() {
                    @Override public Optional<WorldContentRuntime.EmbeddedWorld> prepare(net.minecraft.server.packs.resources.PreparableReloadListener.SharedState state) {
                        var next=WorldContentRuntime.loadSelected(state.resourceManager());
                        for (var previous : java.util.List.copyOf(SERVERS.values())) {
                            if (!previous.map(WorldContentRuntime.Prepared::scope).equals(next.map(w -> w.pack().getManifest().getId())))
                                throw new IllegalStateException("Close the running world before replacing its immutable content bundle");
                            if (next.isPresent() && !previous.orElseThrow().blockBindings().equals(next.get().blockBindings()))
                                throw new IllegalStateException("Native block slots changed during reload; save migration is required");
                        }
                        return next;
                    }
                    @Override public void apply(Optional<WorldContentRuntime.EmbeddedWorld> ignored,net.minecraft.server.packs.resources.PreparableReloadListener.SharedState state) {}
                });
        ServerLevelEvents.LOAD.register((server,level) -> {
            var content=SERVERS.computeIfAbsent(server,WorldContentLifecycle::read);
            content.ifPresent(prepared -> WorldContentRuntime.bindLevel(level,prepared));
        });
        ServerLevelEvents.UNLOAD.register((server,level) -> WorldContentRuntime.unbindLevel(level));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            WorldContentRuntime.clearServer(server); SERVERS.remove(server);
        });
    }
    private static Optional<WorldContentRuntime.Prepared> read(MinecraftServer server) {
        var embedded=WorldContentRuntime.loadSelected(server.getResourceManager());
        if (embedded.isPresent() && server.isDedicatedServer())
            throw new IllegalStateException("Generated world content currently requires a local integrated server; remote content negotiation is not installed");
        return embedded.map(WorldContentRuntime::prepare);
    }
    public static Optional<WorldContentRuntime.Prepared> prepared(MinecraftServer server) {
        return SERVERS.getOrDefault(server,Optional.empty());
    }
}
