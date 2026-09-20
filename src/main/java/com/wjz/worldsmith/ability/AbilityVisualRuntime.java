package com.wjz.worldsmith.ability;

import com.mojang.math.Transformation;
import com.wjz.worldsmith.content.WorldRewardItems;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.ability.AbilityValues;
import com.wjz.worldsmith.core.ability.visual.AbilityClip;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Typed services dispatched by the shared runtime; every mutable object belongs to its originating invocation. */
public final class AbilityVisualRuntime {
    private static final AtomicLong ORDER = new AtomicLong();
    private static final Map<ServerLevel, Map<UUID, List<Playback>>> CLIPS = new ConcurrentHashMap<>();
    private static SimpleParticleType particle;
    private static boolean registered;
    private AbilityVisualRuntime() {}
    private record Playback(WorldAbilityRuntime.Context context, CreatureEntity actor, int handle, long order, long start, long until, AbilityClip clip) {}

    public static synchronized void register() {
        if (registered) return;
        AbilityFxEntity.register(); OwnedItemDisplay.register(); AbilityAnimationProtocol.register();
        particle = Registry.register(BuiltInRegistries.PARTICLE_TYPE, Identifier.fromNamespaceAndPath("worldsmith", "ability_particle"), FabricParticleTypes.simple(false));
        EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
            if (entity instanceof CreatureEntity actor && actor.level() instanceof ServerLevel level) {
                var latest = latest(level, actor.getUUID());
                if (latest != null) send(player, packet(latest.context(), actor, latest));
            }
        });
        registered = true;
    }
    public static SimpleParticleType particleType() { return java.util.Objects.requireNonNull(particle, "Ability visual runtime is not registered"); }

    /** Resource publication hook: a single static particle type receives the current pack's verified PNG sprites. */
    public static Map<String, byte[]> clientResources(WorldsmithPack pack) {
        var resources = new LinkedHashMap<String, byte[]>();
        var assets = pack.getAssets(); var ids = assets.keySet().stream().sorted().toList();
        var textures = new ArrayList<String>(); textures.add("\"minecraft:generic_0\"");
        for (String hash : ids) {
            if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Particle sprites need verified content asset ids");
            resources.put("assets/worldsmith/textures/particle/ability/" + hash + ".png", assets.get(hash));
            textures.add("\"worldsmith:ability/" + hash + "\"");
        }
        resources.put("assets/worldsmith/particles/ability_particle.json", ("{\"textures\":[" + String.join(",", textures) + "]}").getBytes(StandardCharsets.UTF_8));
        return Map.copyOf(resources);
    }

    public static AbilityValue particles(WorldAbilityRuntime.Context context, List<AbilityValue> args) {
        String texture = text(args.get(0));
        if (!context.snapshot().assetIds().contains(texture)) throw new IllegalArgumentException("Particle texture does not belong to the immutable world assets: " + texture);
        Vec3 position = context.point(args.get(1)), velocity = vector(args.get(2));
        if (velocity.lengthSqr() > 4) throw new IllegalArgumentException("Particle velocity exceeds two blocks per tick");
        int count = integer(args.get(3), 1, 64), ticks = integer(args.get(4), 1, 200), color = integer(args.get(6), 0, 0xFFFFFF);
        float scale = (float)number(args.get(5), .05, 4);
        context.reserveResource();
        var entity = new AbilityFxEntity(AbilityFxEntity.type(), context.level());
        entity.setPos(position);
        entity.initialize(context.invocation(), new AbilityVisualData(AbilityVisualData.Kind.PARTICLES, context.snapshot().scope(),
            Math.addExact(context.level().getGameTime(), ticks), texture, context.origin(), velocity, List.of(), count, scale, color));
        identityTransform(entity);
        return context.ownEntity(entity, ticks);
    }

    public static AbilityValue path(WorldAbilityRuntime.Context context, List<AbilityValue> args) {
        if (!(args.get(0) instanceof AbilityValue.ListValue values) || values.getValues().size() < 2 || values.getValues().size() > 64)
            throw new IllegalArgumentException("A visual path needs 2..64 world-space vectors");
        var points = values.getValues().stream().map(context::point).toList();
        boolean hasLength = false;
        for (int i = 1; i < points.size(); i++) hasLength |= points.get(i).distanceToSqr(points.get(i - 1)) > 1e-8;
        if (!hasLength) throw new IllegalArgumentException("A visual path needs a nonzero segment");
        float width = (float)number(args.get(1), .01, 2); int color = integer(args.get(2), 0, 0xFFFFFF), ticks = integer(args.get(3), 1, 200);
        Vec3 origin = points.getFirst(); context.reserveResource();
        var entity = new AbilityFxEntity(AbilityFxEntity.type(), context.level()); entity.setPos(origin);
        entity.initialize(context.invocation(), new AbilityVisualData(AbilityVisualData.Kind.PATH, context.snapshot().scope(),
            Math.addExact(context.level().getGameTime(), ticks), "", context.origin(), Vec3.ZERO, points.stream().map(p -> p.subtract(origin)).toList(), 0, width, color));
        identityTransform(entity);
        return context.ownEntity(entity, ticks);
    }

    public static AbilityValue item(WorldAbilityRuntime.Context context, List<AbilityValue> args) {
        var stack = WorldRewardItems.stack(text(args.get(0)), 1, context.snapshot().blocks(), context.snapshot().items());
        Vec3 position = context.point(args.get(1)), rotation = rotation(args.get(2)), scale = scale(args.get(3));
        int ticks = integer(args.get(4), 1, 200); context.reserveResource();
        var entity = new OwnedItemDisplay(OwnedItemDisplay.type(), context.level());
        entity.initialize(context.snapshot().scope(), context.invocation()); entity.setItemStack(stack); entity.setItemTransform(ItemDisplayContext.FIXED);
        entity.setPos(position); entity.setTransformation(transformation(rotation, scale));
        entity.setWidth(8); entity.setHeight(8);
        return context.ownEntity(entity, ticks);
    }

    public static AbilityValue transform(WorldAbilityRuntime.Context context, List<AbilityValue> args) {
        Entity entity = context.resolve(args.get(0));
        if (!(entity instanceof AbilityFxEntity || entity instanceof OwnedItemDisplay) || !context.ownsEntity(entity)) return AbilityValues.bool(false);
        Vec3 position = context.point(args.get(1)), rotation = rotation(args.get(2)), scale = scale(args.get(3));
        int ticks = integer(args.get(4), 0, 59);
        var transform = transformation(rotation, scale);
        if (entity instanceof AbilityFxEntity fx && fx.visual().kind() == AbilityVisualData.Kind.PATH) {
            for (var point : fx.visual().points()) {
                var moved = transform.getMatrix().transformPosition(new Vector3f((float)point.x, (float)point.y, (float)point.z));
                context.point(AbilityValues.vector(position.x + moved.x, position.y + moved.y, position.z + moved.z));
            }
        }
        var display = (Display) entity;
        display.setTransformationInterpolationDuration(ticks); display.setTransformationInterpolationDelay(0); display.setPosRotInterpolationDuration(ticks);
        display.setTransformation(transform);
        // Send interpolation metadata before a later positional update, so its very first move
        // uses the requested duration rather than the previous/default interpolation length.
        var dirty = display.getEntityData().packDirty();
        if (dirty != null) context.level().getChunkSource().sendToTrackingPlayersAndSelf(display, new ClientboundSetEntityDataPacket(display.getId(), dirty));
        display.setPos(position);
        return AbilityValues.bool(true);
    }

    public static AbilityValue remove(WorldAbilityRuntime.Context context, List<AbilityValue> args) {
        Entity entity = context.resolve(args.getFirst());
        return AbilityValues.bool((entity instanceof AbilityFxEntity || entity instanceof OwnedItemDisplay) && context.ownsEntity(entity) && context.retireEntity(entity));
    }

    public static AbilityValue animationPlay(WorldAbilityRuntime.Context context, List<AbilityValue> args) {
        if (!(context.actor() instanceof CreatureEntity actor)) throw new IllegalArgumentException("Bone clips require a Worldsmith creature actor");
        var clip = AbilityClip.parse(args.get(0), integer(args.get(1), 1, AbilityClip.MAX_DURATION), integer(args.get(2), 0, 40));
        clip.validateFor(actor.definition()); context.reserveResource();
        Playback[] holder = new Playback[1];
        int handle = context.lease(clip.durationTicks(), () -> { if (holder[0] != null) retireClip(holder[0]); });
        long now = context.level().getGameTime();
        var playback = new Playback(context, actor, handle, ORDER.incrementAndGet(), now, Math.addExact(now, clip.durationTicks()), clip);
        holder[0] = playback;
        CLIPS.computeIfAbsent(context.level(), unused -> new LinkedHashMap<>()).computeIfAbsent(actor.getUUID(), unused -> new ArrayList<>()).add(playback);
        broadcast(context, actor, playback);
        return AbilityValues.number(handle);
    }

    public static AbilityValue animationStop(WorldAbilityRuntime.Context context, List<AbilityValue> args) {
        int handle = integer(args.getFirst(), 1, Integer.MAX_VALUE);
        var actors = CLIPS.get(context.level());
        var playbacks = actors == null ? null : actors.get(context.actor().getUUID());
        if (playbacks == null || playbacks.stream().noneMatch(p -> p.handle() == handle && p.context().invocation().equals(context.invocation())))
            return AbilityValues.bool(false);
        return AbilityValues.bool(context.releaseLease(handle));
    }

    private static void retireClip(Playback playback) {
        var actors = CLIPS.get(playback.context().level()); if (actors == null) return;
        var list = actors.get(playback.actor().getUUID()); if (list == null || !list.remove(playback)) return;
        if (list.isEmpty()) actors.remove(playback.actor().getUUID());
        if (actors.isEmpty()) CLIPS.remove(playback.context().level(), actors);
        broadcast(playback.context(), playback.actor(), latest(playback.context().level(), playback.actor().getUUID()));
    }
    private static Playback latest(ServerLevel level, UUID actor) {
        var actors = CLIPS.get(level); var list = actors == null ? null : actors.get(actor);
        if (list == null) return null;
        return list.stream().filter(p -> p.until() > level.getGameTime()).max(java.util.Comparator.comparingLong(Playback::order)).orElse(null);
    }
    private static AbilityAnimationProtocol.Snapshot packet(WorldAbilityRuntime.Context context, CreatureEntity actor, Playback playback) {
        return new AbilityAnimationProtocol.Snapshot(context.snapshot().scope(), context.level().dimension().identifier().toString(), actor.getId(), actor.getUUID(),
            ORDER.incrementAndGet(), playback == null ? context.level().getGameTime() : playback.start(), playback == null ? null : playback.clip());
    }
    private static void broadcast(WorldAbilityRuntime.Context context, CreatureEntity actor, Playback playback) {
        var packet = packet(context, actor, playback);
        for (var player : PlayerLookup.tracking(actor)) send(player, packet);
    }
    private static void send(ServerPlayer player, AbilityAnimationProtocol.Snapshot payload) { ServerPlayNetworking.send(player, payload); }
    private static void identityTransform(Display entity) {
        entity.setTransformation(Transformation.IDENTITY); entity.setWidth(96); entity.setHeight(96);
    }
    private static Transformation transformation(Vec3 rotation, Vec3 scale) {
        float degrees = (float)(Math.PI / 180);
        return new Transformation(new Vector3f(), new Quaternionf().rotationXYZ((float)rotation.x * degrees, (float)rotation.y * degrees, (float)rotation.z * degrees),
            new Vector3f((float)scale.x, (float)scale.y, (float)scale.z), new Quaternionf());
    }
    private static Vec3 rotation(AbilityValue value) { var v = vector(value); if (Math.max(Math.abs(v.x), Math.max(Math.abs(v.y), Math.abs(v.z))) > 720) throw new IllegalArgumentException("Visual rotation exceeds 720 degrees"); return v; }
    private static Vec3 scale(AbilityValue value) { var v = vector(value); if (v.x < .05 || v.y < .05 || v.z < .05 || v.x > 4 || v.y > 4 || v.z > 4) throw new IllegalArgumentException("Visual scale must be 0.05..4"); return v; }
    private static Vec3 vector(AbilityValue value) { if (!(value instanceof AbilityValue.VectorValue v)) throw new IllegalArgumentException("Expected a visual vector"); return new Vec3(v.getX(), v.getY(), v.getZ()); }
    private static String text(AbilityValue value) { if (!(value instanceof AbilityValue.TextValue text)) throw new IllegalArgumentException("Expected visual text"); return text.getValue(); }
    private static double number(AbilityValue value, double min, double max) {
        if (!(value instanceof AbilityValue.NumberValue n) || !Double.isFinite(n.getValue()) || n.getValue() < min || n.getValue() > max)
            throw new IllegalArgumentException("Visual number must be " + min + ".." + max);
        return n.getValue();
    }
    private static int integer(AbilityValue value, int min, int max) { double number = number(value, min, max); if (number != Math.rint(number)) throw new IllegalArgumentException("Visual argument must be an integer"); return (int)number; }
}
