package com.wjz.worldsmith.content.quest.server;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.quest.GeneratedQuestAdvancements;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;

/** One-way projection. No advancement event is allowed to complete or reward a quest. */
public final class QuestAdvancementBridge {
    private static final int MAX_ATTEMPTS = 3;
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Failure> FAILURES = new ConcurrentHashMap<>();
    private static boolean initialized;
    private QuestAdvancementBridge() {}

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> request(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> request(newPlayer));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) server.execute(() -> server.getPlayerList().getPlayers().forEach(QuestAdvancementBridge::request));
        });
        ServerTickEvents.END_SERVER_TICK.register(QuestAdvancementBridge::drain);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clear(handler.player.getUUID(), server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PENDING.forEach((id, pending) -> { if (pending.server == server) PENDING.remove(id, pending); });
            FAILURES.forEach((id, failure) -> { if (failure.server == server) FAILURES.remove(id, failure); });
        });
    }

    /** Coalesce state changes until the end of the server tick, outside any inventory transaction. */
    public static void request(ServerPlayer player) {
        if (!initialized) return;
        MinecraftServer server = player.level().getServer();
        if (!server.isSameThread()) { server.execute(() -> request(player)); return; }
        var world = QuestRuntime.snapshot(player.level());
        if (world != null && world.questCount() > 0) PENDING.put(player.getUUID(), new Pending(server, server.getTickCount(), 0));
    }

    private static void drain(MinecraftServer server) {
        int tick = server.getTickCount();
        for (var entry : PENDING.entrySet()) {
            Pending pending = entry.getValue(); UUID id = entry.getKey();
            if (pending.server != server || tick - pending.afterTick < 0 || !PENDING.remove(id, pending)) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) { clear(id, server); continue; }
            try {
                synchronize(player);
                Failure failure = FAILURES.get(id); if (failure != null && failure.server == server) FAILURES.remove(id, failure);
            } catch (RuntimeException error) {
                String message = String.valueOf(error.getMessage());
                Failure previous = FAILURES.put(id, new Failure(server, message));
                if (previous == null || previous.server != server || !previous.message.equals(message))
                    Worldsmith.LOGGER.warn("Quest advancement display could not synchronize for {}; quest inventory/state remain committed", id, error);
                if (pending.attempt + 1 < MAX_ATTEMPTS)
                    PENDING.putIfAbsent(id, new Pending(server, tick + 40 * (pending.attempt + 1), pending.attempt + 1));
            }
        }
    }

    private static void synchronize(ServerPlayer player) {
        var projection = QuestRuntime.advancementProjection(player);
        if (projection == null) return;
        var manager = player.level().getServer().getAdvancements();
        // Resolve the complete tree first. A missing/overridden definition must not partially grant it.
        AdvancementHolder root = requireMirror(manager.get(GeneratedQuestAdvancements.rootId(projection.scope())),
                GeneratedQuestAdvancements.rootId(projection.scope()), List.of(GeneratedQuestAdvancements.ENTERED_WORLD));
        Map<String, AdvancementHolder> tasks = new LinkedHashMap<>();
        for (String task : projection.tasks().keySet()) {
            Identifier id = GeneratedQuestAdvancements.taskId(projection.scope(), task);
            tasks.put(task, requireMirror(manager.get(id), id,
                    List.of(GeneratedQuestAdvancements.OBJECTIVES_MET, GeneratedQuestAdvancements.CLAIMED)));
        }
        PlayerAdvancements advancements = player.getAdvancements();
        advancements.award(root, GeneratedQuestAdvancements.ENTERED_WORLD);
        projection.tasks().forEach((id, state) -> {
            var holder = tasks.get(id);
            // Remove unearned completion before restoring partial objective progress.
            if (!state.claimed()) advancements.revoke(holder, GeneratedQuestAdvancements.CLAIMED);
            set(advancements, holder, GeneratedQuestAdvancements.OBJECTIVES_MET, state.objectivesMet());
            if (state.claimed()) advancements.award(holder, GeneratedQuestAdvancements.CLAIMED);
        });
        // Vanilla owns persistence, visibility and packet delivery; do not flush/suppress other mods' toasts.
    }

    private static AdvancementHolder requireMirror(AdvancementHolder holder, Identifier id, List<String> criteria) {
        if (holder == null) throw new IllegalStateException("Missing generated quest advancement " + id + "; export this world with the current native compiler");
        var value = holder.value();
        if (!value.rewards().equals(AdvancementRewards.EMPTY)
                || !value.criteria().keySet().equals(java.util.Set.copyOf(criteria))
                || !value.requirements().equals(AdvancementRequirements.allOf(criteria)))
            throw new IllegalStateException("Generated quest advancement was replaced by incompatible criteria or rewards: " + id);
        return holder;
    }
    private static void set(PlayerAdvancements player, AdvancementHolder holder, String criterion, boolean done) {
        if (done) player.award(holder, criterion); else player.revoke(holder, criterion);
    }
    private static void clear(UUID id, MinecraftServer server) {
        Pending pending = PENDING.get(id); if (pending != null && pending.server == server) PENDING.remove(id, pending);
        Failure failure = FAILURES.get(id); if (failure != null && failure.server == server) FAILURES.remove(id, failure);
    }
    private record Pending(MinecraftServer server, int afterTick, int attempt) {}
    private record Failure(MinecraftServer server, String message) {}
}
