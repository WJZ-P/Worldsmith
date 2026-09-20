package com.wjz.worldsmith.client.ability;

import com.wjz.worldsmith.ability.AbilityAnimationProtocol;
import com.wjz.worldsmith.ability.AbilityFxEntity;
import com.wjz.worldsmith.ability.OwnedItemDisplay;
import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.core.ability.visual.AbilityClip;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Small scoped render caches; client state can neither start programs nor affect gameplay. */
public final class AbilityVisualClient {
    private static final Map<UUID, Entry> CLIPS = new HashMap<>();
    private static final LinkedHashMap<UUID, Long> REVISIONS = new LinkedHashMap<>();
    private static ClientLevel level;
    private static String scope;
    private static boolean initialized;
    private AbilityVisualClient() {}
    private static final class Entry {
        final AbilityAnimationProtocol.Snapshot packet;
        boolean validated;
        Entry(AbilityAnimationProtocol.Snapshot packet) { this.packet = packet; }
    }
    public record AnimationFrame(AbilityClip clip, double elapsed) {}
    public static void initialize() {
        if (initialized) return;
        AbilityAnimationProtocol.register(); AbilityParticles.initialize();
        EntityRendererRegistry.register(AbilityFxEntity.type(), AbilityFxRenderer::new);
        EntityRendererRegistry.register(OwnedItemDisplay.type(), AbilityItemDisplayRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(AbilityAnimationProtocol.Snapshot.TYPE, (packet, context) -> receive(context.client(), packet));
        ClientTickEvents.END_CLIENT_TICK.register(AbilityVisualClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> { if (client.getConnection() == null || client.getConnection() == handler) reset(); }));
        initialized = true;
    }
    private static void receive(Minecraft client, AbilityAnimationProtocol.Snapshot packet) {
        synchronize(client);
        if (client.level == null || !packet.scope().equals(scope) || !packet.dimension().equals(client.level.dimension().identifier().toString())) return;
        if (packet.revision() <= REVISIONS.getOrDefault(packet.actorUuid(), 0L)) return;
        REVISIONS.put(packet.actorUuid(), packet.revision());
        while (REVISIONS.size() > 256) REVISIONS.remove(REVISIONS.keySet().iterator().next());
        if (packet.clip() == null) { CLIPS.remove(packet.actorUuid()); return; }
        if (packet.startedAt() > Long.MAX_VALUE - packet.clip().durationTicks() || packet.startedAt() + packet.clip().durationTicks() <= client.level.getGameTime()) return;
        if (CLIPS.size() >= 128 && !CLIPS.containsKey(packet.actorUuid())) return;
        CLIPS.put(packet.actorUuid(), new Entry(packet));
    }
    public static AnimationFrame animation(CreatureEntity entity, float partialTicks) {
        var entry = CLIPS.get(entity.getUUID());
        if (entry == null || entity.level() != level || entry.packet.actorId() != entity.getId() || !entry.packet.scope().equals(entity.bundleHash())) return null;
        double elapsed = entity.level().getGameTime() + partialTicks - entry.packet.startedAt();
        if (elapsed < 0 || elapsed >= entry.packet.clip().durationTicks()) return null;
        if (!entry.validated) {
            if (entity.definition() == null) return null;
            try { entry.packet.clip().validateFor(entity.definition()); entry.validated = true; }
            catch (IllegalArgumentException invalid) { CLIPS.remove(entity.getUUID()); return null; }
        }
        return new AnimationFrame(entry.packet.clip(), elapsed);
    }
    public static int activeClips() { return CLIPS.size(); }
    public static int activeParticles() { return AbilityParticles.activeCount(); }
    private static void tick(Minecraft client) {
        synchronize(client);
        if (level == null) return;
        CLIPS.entrySet().removeIf(entry -> entry.getValue().packet.startedAt() + entry.getValue().packet.clip().durationTicks() <= level.getGameTime());
        AbilityParticles.prune(client);
    }
    private static void synchronize(Minecraft client) {
        String active = WorldContentClientRuntime.activeScope();
        if (level != client.level || !java.util.Objects.equals(scope, active)) { reset(); level = client.level; scope = active; }
    }
    private static void reset() { CLIPS.clear(); REVISIONS.clear(); AbilityParticles.clear(); level = null; scope = null; }
}
