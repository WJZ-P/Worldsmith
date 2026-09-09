package com.wjz.worldsmith.client.content;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.WorldContentLifecycle;
import com.wjz.worldsmith.client.content.creature.CreatureRenderer;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;

/** Reopening a local save restores its embedded assets, never a remembered authoring draft. */
public final class WorldContentClientLifecycle {
    private static long generation;
    private static CompletableFuture<Void> pending=CompletableFuture.completedFuture(null);
    private WorldContentClientLifecycle() {}
    /** Starting a local world supersedes queued join/disconnect work, including work not yet inside the resource layer. */
    public static CompletableFuture<Void> beforeWorldLoad() {
        ++generation;
        return pending.handle((ignored,error)->null).thenCompose(ignored -> WorldContentClientRuntime.whenIdle());
    }
    public static void initialize() {
        CreatureRenderer.register();
        ClientPlayConnectionEvents.INIT.register((handler,client) -> {
            if (!client.isLocalServer()) clear(client);
        });
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client) -> {
            long ticket=++generation;
            var server=client.getSingleplayerServer();
            var content=server==null ? java.util.Optional.<com.wjz.worldsmith.content.WorldContentRuntime.Prepared>empty()
                : WorldContentLifecycle.prepared(server);
            pending=pending.handle((ignored,error)->null).thenCompose(ignored -> WorldContentClientRuntime.whenIdle()).thenComposeAsync(ignored -> {
                if(ticket!=generation)return CompletableFuture.completedFuture(null);
                if(content.isEmpty())return clearActive();
                if(content.get().scope().equals(WorldContentClientRuntime.activeScope()))return CompletableFuture.completedFuture(null);
                var prepared=WorldContentClientRuntime.prepare(content.get());
                return prepared.activate().thenComposeAsync(v -> ticket==generation
                    ? CompletableFuture.completedFuture(null) : prepared.rollback(),client);
            },client).exceptionally(error -> {
                Worldsmith.LOGGER.error("Worldsmith local-world content activation failed",error);
                if(ticket==generation) handler.getConnection().disconnect(Component.literal("Worldsmith content resources failed to load; see the game log. The saved bundle was preserved."));
                return null;
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client) -> clear(client));
    }
    private static void clear(net.minecraft.client.Minecraft client) {
        long ticket=++generation;
        pending=pending.handle((ignored,error)->null).thenCompose(ignored -> WorldContentClientRuntime.whenIdle()).thenComposeAsync(ignored -> ticket==generation ? clearActive() : CompletableFuture.completedFuture(null),client)
            .exceptionally(error -> {Worldsmith.LOGGER.error("Worldsmith scoped resource cleanup failed",error);return null;});
    }
    private static CompletableFuture<Void> clearActive() {
        String scope=WorldContentClientRuntime.activeScope();
        return scope==null ? CompletableFuture.completedFuture(null) : WorldContentClientRuntime.clear(scope);
    }
}
