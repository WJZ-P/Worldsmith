package com.wjz.worldsmith.gametest;

import com.mojang.authlib.GameProfile;
import com.wjz.worldsmith.ability.AbilityFxEntity;
import com.wjz.worldsmith.ability.AbilityProjectile;
import com.wjz.worldsmith.ability.OwnedItemDisplay;
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.creature.HostileCreatureEntity;
import com.wjz.worldsmith.content.interaction.WorldMechanicSavedData;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.ability.AbilityValues;
import com.wjz.worldsmith.core.examples.AbilityVisualExample;
import com.wjz.worldsmith.core.examples.MechanicDiscoveryExample;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Actual native visual entities, source control and collision callbacks in a separate content environment. */
public final class AbilityVisualGameTests {
    @GameTest(environment = "worldsmith_mechanics_smoke:visuals", maxTicks = 600, skyAccess = true, padding = 24)
    public void ownedVisualsAndSteeredProjectilesExecuteSource(GameTestHelper helper) {
        var run = new Run(helper);
        try { run.begin(); } catch (Throwable failure) { run.close(); throw failure; }
        helper.onEachTick(() -> { try { run.tick(); } catch (Throwable failure) { run.close(); throw failure; } });
    }

    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final Map<ChunkPos, Boolean> forced = new LinkedHashMap<>();
        WorldMechanicSavedData previousLedger;
        CreatureEntity actor;
        ServerPlayer observer;
        BlockPos base;
        long started = -1;
        int phase;
        boolean bound, closed;
        UUID intruder;
        Entity path, display, particles, shot;
        Run(GameTestHelper helper) { this.helper = helper; level = helper.getLevel(); }
        void begin() {
            check(WorldContentRuntime.boundLevelCount() == 0, "Visual test overlapped another content environment");
            var pack = AbilityVisualExample.create();
            var names = new LinkedHashMap<String, String>();
            pack.getBiomes().getBiomes().forEach(b -> names.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
            var prepared = WorldContentRuntime.prepare(pack, names);
            previousLedger = level.getDataStorage().get(WorldMechanicSavedData.TYPE);
            level.getDataStorage().set(WorldMechanicSavedData.TYPE, new WorldMechanicSavedData());
            WorldContentRuntime.bindLevel(level, prepared); bound = true;
            base = helper.absolutePos(new BlockPos(8, 3, 8));
            for (int x = (base.getX() - 8) >> 4; x <= (base.getX() + 8) >> 4; x++) for (int z = (base.getZ() - 8) >> 4; z <= (base.getZ() + 8) >> 4; z++) {
                var chunk = new ChunkPos(x, z); boolean old = level.getChunkSource().getForceLoadedChunks().contains(chunk.pack());
                forced.put(chunk, old); if (!old) level.setChunkForced(x, z, true);
                level.getChunk(x, z);
            }
            for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int y = 0; y <= 8; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            for (int x = -3; x <= 3; x++) for (int y = 0; y <= 4; y++) level.setBlock(base.offset(x, y, 6), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            // Match a real connected player's simulation path. Forced chunks alone do not
            // keep the GameTest level's entity scheduler active when the level has no player.
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "visual-observer"), false);
            observer = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            level.getServer().getPlayerList().placeNewPlayer(connection, observer, cookie);
            observer.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            observer.setGameMode(GameType.CREATIVE); observer.setNoGravity(true);
            standObserver();
            actor = new HostileCreatureEntity(CreatureRuntime.hostileType(), level);
            actor.snapTo(base.getX() + .5, base.getY(), base.getZ() + .5, 0, 0);
            actor.initialize(pack.getComputedId(), MechanicDiscoveryExample.GUARDIAN, 717);
            actor.setNoGravity(true); check(level.addFreshEntity(actor), "Visual actor insertion failed");
        }
        void tick() {
            if (closed) return;
            observer.connection.tick();
            // EmbeddedChannel never sends movement/teleport acknowledgements. The listener's login
            // bookkeeping is ticked explicitly above; restore this fixture's actual position and update
            // the native simulation tracker every tick, as the gameplay/runtime fixtures already do.
            standObserver();
            if (started < 0) {
                if (!forced.keySet().stream().allMatch(chunk -> level.isPositionEntityTicking(chunk.getMiddleBlockPosition(base.getY())))) {
                    check(helper.getTick() < 200, "Visual arena never became entity-ticking: " + readiness()); return;
                }
                System.out.println("[AbilityVisual] Native arena ready: " + readiness());
                start(AbilityVisualExample.SHOWCASE); started = level.getGameTime();
            }
            check(level.getGameTime() - started < 260, "Visual scenario timed out at phase " + phase + ": " + WorldAbilityRuntime.state(actor, AbilityVisualExample.SHOWCASE));
            for (String program : new String[]{AbilityVisualExample.SHOWCASE, AbilityVisualExample.INTRUDER, AbilityVisualExample.STEERING, AbilityVisualExample.RETIRE})
                check(WorldAbilityRuntime.lastFailure(actor, program) == null, "Visual source failed: " + program + ": " + WorldAbilityRuntime.lastFailure(actor, program));
            if (phase == 0) {
                if (!flag(AbilityVisualExample.SHOWCASE, "created")) return;
                path = handle(AbilityVisualExample.SHOWCASE, "path"); display = handle(AbilityVisualExample.SHOWCASE, "item"); particles = handle(AbilityVisualExample.SHOWCASE, "particles");
                check(path instanceof AbilityFxEntity && particles instanceof AbilityFxEntity && display instanceof OwnedItemDisplay, "Source did not create the real visual hosts");
                check(!path.getType().canSerialize() && !display.getType().canSerialize() && !particles.getType().canSerialize(), "Transient visual entities are saveable");
                check(CustomItemRuntime.snapshot(level).isCanonical(((OwnedItemDisplay) display).getItemStack()), "Item display is not the canonical world item");
                start(AbilityVisualExample.INTRUDER); intruder = WorldAbilityRuntime.invocation(actor, AbilityVisualExample.INTRUDER);
                signal("visual", Map.of("item", value(AbilityVisualExample.SHOWCASE, "item"), "clip", value(AbilityVisualExample.SHOWCASE, "clip")));
                phase = 1;
            } else if (phase == 1) {
                if (!flag(AbilityVisualExample.INTRUDER, "visual_checked")) return;
                check(!flag(AbilityVisualExample.INTRUDER, "foreign_remove") && !flag(AbilityVisualExample.INTRUDER, "foreign_transform")
                    && !flag(AbilityVisualExample.INTRUDER, "foreign_clip_stop"), "Another invocation controlled a visual/clip handle");
                check(display.isAlive() && path.isAlive(), "Foreign controls removed the owning invocation's objects");
                System.out.println("[AbilityVisual] Native paths/asset particles/canonical display and cross-invocation ownership passed"); phase = 2;
            } else if (phase == 2) {
                if (!flag(AbilityVisualExample.SHOWCASE, "transformed")) return;
                check(Math.abs(display.getY() - (base.getY() + 2)) < .001 && ((OwnedItemDisplay) display).getPosRotInterpolationDuration() == 10,
                    "Visual transform did not update native display position/interpolation");
                start(AbilityVisualExample.STEERING); phase = 3;
            } else if (phase == 3) {
                if (value(AbilityVisualExample.STEERING, "projectile") == null) return;
                shot = handle(AbilityVisualExample.STEERING, "projectile");
                check(shot instanceof AbilityProjectile, "Projectile source did not publish an actual projectile");
                signal("projectile", Map.of("projectile", value(AbilityVisualExample.STEERING, "projectile"))); phase = 4;
            } else if (phase == 4) {
                if (!flag(AbilityVisualExample.INTRUDER, "projectile_checked") || !flag(AbilityVisualExample.STEERING, "controlled")) return;
                check(!flag(AbilityVisualExample.INTRUDER, "foreign_velocity") && !flag(AbilityVisualExample.INTRUDER, "foreign_steer")
                    && !flag(AbilityVisualExample.INTRUDER, "foreign_retire"), "Another invocation hijacked the projectile");
                check(flag(AbilityVisualExample.STEERING, "velocity_changed") && flag(AbilityVisualExample.STEERING, "steered"), "Owner projectile controls were rejected");
                phase = 5;
            } else if (phase == 5) {
                if (!flag(AbilityVisualExample.STEERING, "hit")) return;
                check(value(AbilityVisualExample.STEERING, "normal").equals(AbilityValues.vector(0, 0, -1)), "Block impact did not expose its actual north-face normal");
                check(value(AbilityVisualExample.STEERING, "normal_kind").equals(AbilityValues.text("block_face")), "Exact block normal was not labelled");
                check(value(AbilityVisualExample.STEERING, "block_id").equals(AbilityValues.text("minecraft:stone")), "Impact lost its actual block context");
                check(value(AbilityVisualExample.STEERING, "hit_projectile").equals(value(AbilityVisualExample.STEERING, "projectile")), "Impact lost its owned projectile identity");
                check(value(AbilityVisualExample.STEERING, "incoming") instanceof AbilityValue.VectorValue v && v.getZ() > .3, "Impact lost controlled incoming velocity");
                start(AbilityVisualExample.RETIRE); phase = 6;
                System.out.println("[AbilityVisual] Native projectile steering/velocity/ownership and exact block impact data passed");
            } else if (phase == 6) {
                if (!flag(AbilityVisualExample.RETIRE, "done")) return;
                check(flag(AbilityVisualExample.RETIRE, "retired") && !flag(AbilityVisualExample.RETIRE, "retired_twice"), "Retirement was not an owned exactly-once operation");
                var id = (AbilityValue.EntityValue) value(AbilityVisualExample.RETIRE, "projectile");
                check(level.getEntity(UUID.fromString(id.getId())) == null, "Retired projectile stayed in the native level"); phase = 7;
            } else if (phase == 7) {
                if (!flag(AbilityVisualExample.SHOWCASE, "cleared")) return;
                check(flag(AbilityVisualExample.SHOWCASE, "path_removed") && flag(AbilityVisualExample.SHOWCASE, "clip_stopped"), "Explicit visual/animation cleanup did not succeed");
                check(path.isRemoved() && display.isAlive() && particles.isAlive(), "Clearing one resource removed an unrelated resource"); phase = 8;
            } else if (phase == 8) {
                if (!flag(AbilityVisualExample.SHOWCASE, "done") || WorldAbilityRuntime.activeCount(level) != 0) return;
                check(display.isRemoved() && particles.isRemoved(), "Native visual leases did not expire");
                check(level.getEntitiesOfClass(AbilityFxEntity.class, new AABB(base).inflate(16)).isEmpty()
                    && level.getEntitiesOfClass(OwnedItemDisplay.class, new AABB(base).inflate(16)).isEmpty()
                    && level.getEntitiesOfClass(AbilityProjectile.class, new AABB(base).inflate(16)).isEmpty(), "An owned visual/projectile entity leaked");
                System.out.println("[AbilityVisual] Display transform, explicit clip stop, independent removal and world-time cleanup passed");
                close(); helper.succeed();
            }
        }
        void standObserver() {
            observer.snapTo(base.getX() + .5, base.getY(), base.getZ() - 3.5, 0, 0);
            observer.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            level.getChunkSource().move(observer);
        }
        String readiness() {
            return "observer=" + observer.position() + ", registered=" + (level.getServer().getPlayerList().getPlayer(observer.getUUID()) == observer)
                + ", actor=" + actor.position() + ", chunks=" + forced.keySet().stream().map(chunk -> chunk
                    + "[loaded=" + (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null)
                    + ",entityTicking=" + level.isPositionEntityTicking(chunk.getMiddleBlockPosition(base.getY())) + "]").toList();
        }
        void start(String program) { check(WorldAbilityRuntime.start(level, actor, program, actor.position(), null, 20, false), "Could not start source " + program); }
        void signal(String tag, Map<String, AbilityValue> data) {
            check(intruder != null && WorldAbilityRuntime.emitInvocation(level, intruder, "signal", Map.of("event_tag", AbilityValues.text(tag), "event_data", AbilityValues.map(data))), "Source event was not queued");
        }
        AbilityValue value(String program, String key) { return WorldAbilityRuntime.state(actor, program).get(key); }
        boolean flag(String program, String key) { var value = value(program, key); return value instanceof AbilityValue.BoolValue b && b.getValue(); }
        Entity handle(String program, String key) {
            var handle = value(program, key); check(handle instanceof AbilityValue.EntityValue, "Source returned no entity handle for " + key);
            return level.getEntity(UUID.fromString(((AbilityValue.EntityValue) handle).getId()));
        }
        void close() {
            if (closed) return; closed = true;
            try {
                if (actor != null) { WorldAbilityRuntime.cancelOwner(actor); actor.discard(); }
                if (observer != null) { WorldAbilityRuntime.cancelOwner(observer); level.getServer().getPlayerList().remove(observer); observer.discard(); }
                if (bound) { WorldContentRuntime.unbindLevel(level); level.getDataStorage().set(WorldMechanicSavedData.TYPE, previousLedger == null ? new WorldMechanicSavedData() : previousLedger); }
            } finally { forced.forEach((chunk, old) -> level.setChunkForced(chunk.x(), chunk.z(), old)); }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
