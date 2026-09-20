package com.wjz.worldsmith.gametest;

import com.mojang.authlib.GameProfile;
import com.wjz.worldsmith.ability.AbilityActorControl;
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.interaction.WorldMechanicSavedData;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.examples.AbilityRuntimeExample;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real server observations and native creature tick pulses; no direct provider calls or manually stepped VM. */
public final class AbilityPerceptionGameTests {
    @GameTest(environment = "worldsmith_mechanics_smoke:ability_perception", maxTicks = 2200, skyAccess = true, padding = 50)
    public void realEnvironmentScopeAndRepeatedObservationRespectNativeBudgets(GameTestHelper helper) {
        var run = new Run(helper);
        try { run.begin(); } catch (Throwable failure) { run.close(); throw failure; }
        helper.onEachTick(() -> { try { run.tick(); } catch (Throwable failure) { run.close(); throw failure; } });
    }

    private record Step(String name, Runnable start, BooleanSupplier poll, int timeout) {}
    private static final class QuietCreature extends CreatureEntity {
        QuietCreature(ServerLevel level) { super(CreatureRuntime.passiveType(), level); }
        void quiet() { goalSelector.removeAllGoals(goal -> true); targetSelector.removeAllGoals(goal -> true); setTarget(null); }
    }

    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final WorldsmithPack pack = fixture();
        final List<Step> steps = new ArrayList<>();
        final List<QuietCreature> actors = new ArrayList<>();
        final List<QuietCreature> cohort = new ArrayList<>();
        final Map<ChunkPos, Boolean> forced = new LinkedHashMap<>();
        final Map<UUID, Double> firstCounts = new HashMap<>();
        WorldMechanicSavedData priorMechanics;
        ServerPlayer player;
        QuietCreature probe, remote;
        BlockPos base, home, dark, lit, water;
        ChunkPos remoteAnchor, absentChunk;
        Vec3 absentSample;
        long initialTime, initialClock, stepAt, marked;
        int index = -1, phase, peakBackground;
        int clearTime, rainTime, thunderTime;
        boolean wasRaining, wasThundering, captured, bound, closed;
        float rainLevel, thunderLevel;

        Run(GameTestHelper helper) { this.helper = helper; level = helper.getLevel(); }

        void begin() {
            check(WorldContentRuntime.boundLevelCount() == 0, "Perception fixture overlaps another content binding");
            var names = new LinkedHashMap<String, String>();
            pack.getBiomes().getBiomes().forEach(b -> names.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
            priorMechanics = level.getDataStorage().get(WorldMechanicSavedData.TYPE);
            level.getDataStorage().set(WorldMechanicSavedData.TYPE, new WorldMechanicSavedData());
            WorldContentRuntime.bindLevel(level, WorldContentRuntime.prepare(pack, names)); bound = true;
            initialTime = level.getGameTime(); initialClock = level.getOverworldClockTime();
            var weather = level.getWeatherData();
            clearTime = weather.getClearWeatherTime(); rainTime = weather.getRainTime(); thunderTime = weather.getThunderTime();
            wasRaining = weather.isRaining(); wasThundering = weather.isThundering();
            rainLevel = level.getRainLevel(1); thunderLevel = level.getThunderLevel(1); captured = true;
            clock(3 * 24000L + 6000);
            weather.setClearWeatherTime(0); weather.setRainTime(6000); weather.setThunderTime(6000);
            weather.setRaining(true); weather.setThundering(false); level.setRainLevel(1); level.setThunderLevel(0);
            base = helper.absolutePos(new BlockPos(8, 3, 8)); home = base.offset(6, 0, -8);
            dark = base.offset(-8, 0, -8); lit = base.offset(-8, 0, 2); water = base.offset(6, 0, -2);
            for (int x = (base.getX() - 24) >> 4; x <= (base.getX() + 40) >> 4; x++)
                for (int z = (base.getZ() - 24) >> 4; z <= (base.getZ() + 32) >> 4; z++) force(new ChunkPos(x, z));
            for (int x = -14; x <= 32; x++) for (int z = -14; z <= 26; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int y = 0; y <= 5; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            room(dark, false); room(lit, true);
            level.setBlock(water, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(home.offset(4, 0, 0), Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            probe = spawn("sense_probe", home, new UUID(0x7180, 1));
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "perception-player"), false);
            player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL); player.setNoGravity(true); player.setInvulnerable(true); stand(home.offset(2, 0, 0));
            define(); next();
        }

        void force(ChunkPos chunk) {
            forced.putIfAbsent(chunk, level.getChunkSource().getForceLoadedChunks().contains(chunk.pack()));
            level.setChunkForced(chunk.x(), chunk.z(), true); level.getChunk(chunk.x(), chunk.z());
        }
        void room(BlockPos center, boolean lamp) {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 3; y++)
                if (Math.abs(x) == 2 || Math.abs(z) == 2 || y == 3)
                    level.setBlock(center.offset(x, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            if (lamp) level.setBlock(center.offset(1, 0, 0), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        QuietCreature spawn(String definition, BlockPos at, UUID id) {
            var actor = new QuietCreature(level); actor.setUUID(id);
            actor.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
            actor.initialize(pack.getComputedId(), definition, id.getLeastSignificantBits()); actor.quiet();
            actor.setNoGravity(true); actor.setInvulnerable(true);
            check(level.addFreshEntity(actor), "Native perception actor insertion failed: " + id); actors.add(actor); return actor;
        }
        void define() {
            add("identity, actual home, day clock, rain shelter, lighting and block/fluid metadata are native", () -> {}, () -> {
                if (phase == 0) {
                    if (level.getGameTime() - stepAt < 20 || level.getMaxLocalRawBrightness(lit) <= level.getMaxLocalRawBrightness(dark)) return false;
                    check(level.isRainingAt(home), "Open-sky fixture must really receive native rain");
                    start(probe, "sense_snapshot", player, AbilityValues.none()); phase = 1; return false;
                }
                if (!bool(probe, "sense_snapshot", "done")) return false;
                var own = map(probe, "sense_snapshot", "identity");
                check(text(own, "kind").equals("creature") && text(own, "logicalId").equals("sense_probe"), "Self identity lost its logical creature type");
                check(text(own, "bundleScope").equals(pack.getComputedId()) && text(own, "nativeId").equals(BuiltInRegistries.ENTITY_TYPE.getKey(probe.getType()).toString()), "Identity reported an invented scope/native host");
                var other = map(probe, "sense_snapshot", "other");
                check(text(other, "kind").equals("player") && text(other, "logicalId").isEmpty() && text(other, "bundleScope").isEmpty(), "Player identity borrowed creature metadata");
                check(value(probe, "sense_snapshot", "home").equals(vector(Vec3.atBottomCenterOf(home))) && bool(probe, "sense_snapshot", "otherHasNoHome"), "Home query did not use the actual actor home");
                var environment = map(probe, "sense_snapshot", "environment");
                check(number(environment, "day") == 3 && number(environment, "dayTime") >= 6000 && number(environment, "dayTime") < 6500, "Environment ignored the real overworld clock");
                check(flag(environment, "raining") && flag(environment, "skyVisible") && !flag(environment, "inWater") && !flag(environment, "onFire"), "Exposed actor environment was fabricated");
                var sheltered = map(probe, "sense_snapshot", "dark"); var lighted = map(probe, "sense_snapshot", "lit");
                check(!flag(sheltered, "skyVisible") && !flag(sheltered, "raining") && !flag(lighted, "raining"), "Rain passed through a real solid roof");
                check(number(lighted, "light") > number(sheltered, "light") && number(lighted, "light") <= 15, "World sample missed the native block-light difference");
                check(text(map(probe, "sense_snapshot", "solid"), "blockId").equals("minecraft:iron_block") && flag(map(probe, "sense_snapshot", "solid"), "collision"), "Solid sample lost native registry/collision data");
                check(text(map(probe, "sense_snapshot", "water"), "fluidId").equals("minecraft:water") && !flag(map(probe, "sense_snapshot", "water"), "collision"), "Water sample confused fluid and collision");
                check(bool(probe, "sense_snapshot", "farUnavailable") && !AbilityActorControl.hasControl(probe), "Read-only sampling exceeded scope or acquired control");
                return true;
            }, 100);
            add("live entity environment observes water and fire rather than only static blocks", () -> {
                probe.snapTo(water.getX() + .5, water.getY(), water.getZ() + .5, 0, 0);
            }, () -> {
                if (phase == 0) {
                    if (!probe.isInWater()) return false;
                    start(probe, "sense_environment", null, AbilityValues.none()); phase = 1; return false;
                }
                if (phase == 1) {
                    if (!bool(probe, "sense_environment", "done")) return false;
                    check(flag(map(probe, "sense_environment", "environment"), "inWater"), "Entity water state was not sampled");
                    probe.snapTo(dark.getX() + .5, dark.getY(), dark.getZ() + .5, 0, 0); marked = level.getGameTime(); phase = 2; return false;
                }
                if (phase == 2) {
                    if (probe.isInWater() || level.getGameTime() - marked < 3) return false;
                    probe.setRemainingFireTicks(80); start(probe, "sense_fire", null, AbilityValues.none()); phase = 3; return false;
                }
                if (!bool(probe, "sense_fire", "done")) return false;
                var environment = map(probe, "sense_fire", "environment");
                check(flag(environment, "onFire") && !flag(environment, "inWater") && !flag(environment, "raining"), "Sheltered burning actor environment is inconsistent");
                probe.clearFire(); probe.snapTo(home.getX() + .5, home.getY(), home.getZ() + .5, 0, 0);
                return true;
            }, 80);
            add("entity handles become unavailable outside the invocation scope", () -> start(probe, "sense_scope", player, AbilityValues.none()), () -> {
                if (phase == 0) {
                    if (!bool(probe, "sense_scope", "firstSeen")) return false;
                    stand(base.offset(60, 0, 0)); phase = 1; return false;
                }
                if (!bool(probe, "sense_scope", "done")) return false;
                check(bool(probe, "sense_scope", "lost"), "Out-of-scope target still resolved its identity/environment");
                stand(home.offset(2, 0, 0)); return true;
            }, 40);
            add("motion status distinguishes accepted native navigation from per-invocation control", () -> {
                probe.setNoGravity(false);
            }, () -> {
                if (phase == 0) {
                    if (!probe.onGround()) return false;
                    start(probe, "sense_motion", null, AbilityValues.none()); phase = 1; return false;
                }
                if (phase == 1) {
                    if (!bool(probe, "sense_motion", "accepted")) return false;
                    var status = map(probe, "sense_motion", "status");
                    check(flag(status, "controlled") && flag(status, "navigating") && !flag(status, "done") && flag(status, "pathReachable"), "Accepted native navigation status is wrong");
                    check(number(status, "distance") > 4 && number(status, "distance") < 8 && status.get("target") instanceof AbilityValue.VectorValue, "Path target/distance were invented");
                    start(probe, "sense_motion_observer", null, AbilityValues.none()); phase = 2; return false;
                }
                if (!bool(probe, "sense_motion_observer", "done")) return false;
                var observer = map(probe, "sense_motion_observer", "status");
                check(flag(observer, "navigating") && !flag(observer, "controlled"), "Observer confused another invocation's token with its own");
                WorldAbilityRuntime.cancelOwner(probe); probe.setNoGravity(true); return true;
            }, 60);
            add("loaded-edge sample returns null without loading the absent neighbor", () -> {
                remoteAnchor = new ChunkPos((base.getX() + 4096) >> 4, (base.getZ() + 4096) >> 4); force(remoteAnchor);
            }, () -> {
                if (phase == 0) {
                    if (level.getGameTime() - stepAt < 50) return false;
                    check(findLoadedEdge(), "A distant forced area had no stable loaded/unloaded edge");
                    check(level.getChunkSource().getChunkNow(absentChunk.x(), absentChunk.z()) == null, "Boundary candidate was already loaded");
                    check(!level.getChunkSource().getForceLoadedChunks().contains(absentChunk.pack()), "Boundary candidate was already forced");
                    start(remote, "sense_unloaded", null, vector(absentSample)); phase = 1; return false;
                }
                if (!bool(remote, "sense_unloaded", "done")) return false;
                check(bool(remote, "sense_unloaded", "unavailable"), "Unloaded sample claimed world data");
                check(level.getChunkSource().getChunkNow(absentChunk.x(), absentChunk.z()) == null && !level.getChunkSource().getForceLoadedChunks().contains(absentChunk.pack()), "world.sample loaded or forced its missing neighbor");
                remote.discard(); probe.discard(); return true;
            }, 120);
            add("eighty native 20/40-tick observers restart beyond maxTicks and preserve per-actor memory", () -> spawnCohort(false), () -> {
                check(WorldAbilityRuntime.activeCount(level) <= 48, "Background observation consumed reserved critical slots");
                peakBackground = Math.max(peakBackground, WorldAbilityRuntime.activeCount(level));
                for (var actor : cohort) {
                    check(!AbilityActorControl.hasControl(actor), "Pure periodic observation acquired native movement");
                    check(WorldAbilityRuntime.lastFailure(actor, "sense_pulse") == null, "A short periodic pulse exhausted lifetime or native budget");
                }
                if (phase == 0 && level.getGameTime() - stepAt >= 80) {
                    for (var actor : cohort) firstCounts.put(actor.getUUID(), number(actor, "sense_pulse", "runs")); phase = 1;
                }
                if (level.getGameTime() - stepAt < 260) return false;
                check(cohort.size() == 80 && peakBackground > 0, "Crowd fixture did not actually execute native pulses");
                for (var actor : cohort) {
                    double runs = number(actor, "sense_pulse", "runs");
                    int minimum = actor.creatureId().equals("sense_twenty") ? 10 : 5;
                    check(actor.tickCount >= 240 && runs >= minimum && runs >= firstCounts.getOrDefault(actor.getUUID(), 0.0) + 3,
                        "Periodic actor stalled or reset persistent memory: " + actor.getUUID() + "/" + runs);
                    check(!bool(actor, "sense_pulse", "controlled") && number(actor, "sense_pulse", "completed") >= runs - 1, "Pulse memory did not survive multiple independent completions");
                }
                System.out.println("[AbilityPerception] 80 real actors ran 260+ ticks with maxTicks=10; peak background=" + peakBackground);
                clearCohort(); return true;
            }, 340);
            add("saturated background admission leaves all sixteen global critical slots usable", () -> spawnCohort(true), () -> {
                if (phase == 0) {
                    check(WorldAbilityRuntime.activeCount(level) <= 48, "Background cap exceeded before critical admission");
                    if (WorldAbilityRuntime.activeCount(level) != 48) return false;
                    for (int i = 0; i < 16; i++) start(cohort.get(i), "sense_critical", null, AbilityValues.none());
                    check(WorldAbilityRuntime.activeCount(level) == 64, "Sixteen reserved slots were not all admitted");
                    check(!WorldAbilityRuntime.start(level, cohort.get(16), "sense_critical", cohort.get(16).position(), null, 1, false), "Global hard cap admitted a sixty-fifth invocation");
                    phase = 1; return false;
                }
                for (int i = 0; i < 16; i++) if (!bool(cohort.get(i), "sense_critical", "ran")) return false;
                for (var actor : cohort) check(!AbilityActorControl.hasControl(actor), "Capacity test acquired implicit movement ownership");
                clearCohort(); return true;
            }, 60);
            add("one actor retains its fourth slot for a non-background reaction", () -> {
                probe = spawn("sense_multi", home, new UUID(0x7184, 1));
            }, () -> {
                if (phase == 0) {
                    long background = List.of("sense_slot_a", "sense_slot_b", "sense_slot_c", "sense_slot_d").stream().filter(p -> WorldAbilityRuntime.isActive(probe, p)).count();
                    check(background <= 3, "Periodic bindings occupied the actor's reserved reaction slot");
                    if (background != 3) return false;
                    check(!WorldAbilityRuntime.backgroundSlotAvailable(probe), "Background preflight ignored the actor reserve");
                    start(probe, "sense_critical", null, AbilityValues.none());
                    check(!WorldAbilityRuntime.start(level, probe, "sense_overflow", probe.position(), null, 1, false), "Actor hard cap admitted a fifth invocation");
                    phase = 1; return false;
                }
                return bool(probe, "sense_critical", "ran") && !AbilityActorControl.hasControl(probe);
            }, 50);
        }

        boolean findLoadedEdge() {
            for (int x = remoteAnchor.x() - 6; x <= remoteAnchor.x() + 6; x++) for (int z = remoteAnchor.z() - 6; z <= remoteAnchor.z() + 6; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) continue;
                for (int[] direction : List.of(new int[]{1, 0}, new int[]{-1, 0}, new int[]{0, 1}, new int[]{0, -1})) {
                    int nextX = x + direction[0], nextZ = z + direction[1];
                    if (level.getChunkSource().getChunkNow(nextX, nextZ) != null || level.getChunkSource().getForceLoadedChunks().contains(ChunkPos.pack(nextX, nextZ))) continue;
                    var origin = new BlockPos(x * 16 + 8, base.getY(), z * 16 + 8);
                    absentChunk = new ChunkPos(nextX, nextZ); absentSample = Vec3.atBottomCenterOf(origin.offset(direction[0] * 16, 0, direction[1] * 16));
                    remote = spawn("sense_probe", origin, new UUID(0x7181, 1)); return true;
                }
            }
            return false;
        }
        void spawnCohort(boolean saturated) {
            firstCounts.clear(); peakBackground = 0;
            for (int i = 0; i < 80; i++) {
                String definition = saturated ? "sense_saturated" : i < 40 ? "sense_twenty" : "sense_forty";
                cohort.add(spawn(definition, base.offset((i % 10) * 3, 0, 3 + (i / 10) * 3), new UUID(saturated ? 0x7183 : 0x7182, i + 1)));
            }
        }
        void clearCohort() { cohort.forEach(CreatureEntity::discard); cohort.clear(); check(WorldAbilityRuntime.activeCount(level) == 0, "Retiring the test cohort leaked active invocations"); }
        void start(CreatureEntity actor, String program, ServerPlayer target, AbilityValue args) {
            try (var prepared = WorldAbilityRuntime.prepareStart(level, actor, program, actor.position(), target, 1, false, args)) {
                check(prepared != null, "Native program admission failed: " + program); prepared.commit();
            }
        }
        void add(String name, Runnable start, BooleanSupplier poll, int timeout) { steps.add(new Step(name, start, poll, timeout)); }
        void next() {
            if (++index >= steps.size()) { System.out.println("[AbilityPerception] PASS " + steps.size() + " native scope/environment/crowd scenarios"); close(); helper.succeed(); return; }
            phase = 0; stepAt = level.getGameTime(); steps.get(index).start.run();
        }
        void tick() {
            if (closed) return;
            var step = steps.get(index); check(level.getGameTime() - stepAt <= step.timeout, "Perception scenario timed out: " + step.name);
            if (step.poll.getAsBoolean()) { System.out.println("[AbilityPerception] PASS " + step.name); next(); }
        }
        void stand(BlockPos position) { player.snapTo(position.getX() + .5, position.getY(), position.getZ() + .5, 0, 0); player.setDeltaMovement(Vec3.ZERO); level.getChunkSource().move(player); }
        void clock(long value) { level.getServer().clockManager().setTotalTicks(level.registryAccess().get(WorldClocks.OVERWORLD).orElseThrow(), value); }
        void close() {
            if (closed) return; closed = true;
            try {
                actors.forEach(CreatureEntity::discard);
                if (player != null) { WorldAbilityRuntime.cancelOwner(player); level.getServer().getPlayerList().remove(player); player.discard(); }
                if (bound) { WorldContentRuntime.unbindLevel(level); bound = false; }
                level.getDataStorage().set(WorldMechanicSavedData.TYPE, priorMechanics == null ? new WorldMechanicSavedData() : priorMechanics);
                if (captured) {
                    var weather = level.getWeatherData(); weather.setClearWeatherTime(clearTime); weather.setRainTime(rainTime); weather.setThunderTime(thunderTime);
                    weather.setRaining(wasRaining); weather.setThundering(wasThundering); level.setRainLevel(rainLevel); level.setThunderLevel(thunderLevel);
                    clock(initialClock + Math.max(0, level.getGameTime() - initialTime));
                }
            } finally { forced.forEach((chunk, was) -> level.setChunkForced(chunk.x(), chunk.z(), was)); }
        }
    }

    private static WorldsmithPack fixture() {
        var base = AbilityRuntimeExample.create(); var programs = new ArrayList<>(base.getAbilities().getPrograms());
        programs.add(new AbilityProgramDefinition("sense_snapshot", "Native world snapshot", """
            on start {
                state.identity = entity.identity(self); state.other = entity.identity(target);
                state.home = entity.home(self); state.otherHasNoHome = entity.home(target) == null;
                state.environment = entity.environment(self);
                state.dark = world.sample(vector.add(origin, vec(-14,0,0)));
                state.lit = world.sample(vector.add(origin, vec(-14,0,10)));
                state.water = world.sample(vector.add(origin, vec(0,0,6)));
                state.solid = world.sample(vector.add(origin, vec(4,0,0)));
                state.farUnavailable = world.sample(vector.add(origin, vec(25,0,0))) == null;
                state.done = true;
            }
            """, 20, 2048));
        for (String id : List.of("sense_environment", "sense_fire")) programs.add(new AbilityProgramDefinition(id, id,
            "on start { state.environment = entity.environment(self); state.done = true; }", 20, 256));
        programs.add(new AbilityProgramDefinition("sense_scope", "Live scope boundary", """
            on start {
                state.firstSeen = entity.identity(target) != null; wait 5;
                state.lost = entity.identity(target) == null && entity.environment(target) == null;
                state.done = true;
            }
            """, 20, 512));
        programs.add(new AbilityProgramDefinition("sense_motion", "Native path observation", """
            on start {
                let token = control.claim(20, 40);
                state.accepted = motion.navigate(self, vector.add(origin, vec(6,0,0)), 0.6);
                state.status = motion.status(self); wait 25; control.release(token);
            }
            """, 60, 512));
        programs.add(new AbilityProgramDefinition("sense_motion_observer", "Observe another controller", "on start { state.status = motion.status(self); state.done = true; }", 20, 256));
        programs.add(new AbilityProgramDefinition("sense_unloaded", "Missing chunk read", "on start { state.unavailable = world.sample(args) == null; state.done = true; }", 20, 256));
        programs.add(new AbilityProgramDefinition("sense_pulse", "Short persistent observation pulse", """
            on start {
                if (state.runs == null) { state.runs = 0; }
                state.runs = state.runs + 1;
                state.controlled = map.get(motion.status(self), "controlled");
                state.identity = entity.identity(self);
                wait 3; state.completed = state.runs;
            }
            """, 10, 512));
        for (String id : List.of("sense_slot_a", "sense_slot_b", "sense_slot_c", "sense_slot_d")) programs.add(new AbilityProgramDefinition(id, id,
            "on start { if (state.runs == null) { state.runs = 0; } state.runs = state.runs + 1; wait 8; }", 10, 256));
        programs.add(new AbilityProgramDefinition("sense_critical", "Reserved critical input", "on start { state.ran = true; wait 6; }", 20, 256));
        programs.add(new AbilityProgramDefinition("sense_overflow", "Hard-cap overflow", "on start { state.ran = true; wait 6; }", 20, 256));
        var creatures = new ArrayList<>(base.getCreatures().getCreatures()); var model = creatures.getFirst().getModel();
        for (String id : List.of("sense_probe", "sense_twenty", "sense_forty", "sense_saturated", "sense_multi")) {
            var bindings = new ArrayList<AbilityEventBinding>();
            if (id.equals("sense_multi")) for (String slot : List.of("a", "b", "c", "d")) bindings.add(new AbilityEventBinding("slot_" + slot, "sense_slot_" + slot, List.of("tick"), List.of(), List.of(), 1, 8, 1));
            else if (!id.equals("sense_probe")) bindings.add(new AbilityEventBinding("pulse", "sense_pulse", List.of("tick"), List.of(), List.of(), 1, 8, id.equals("sense_twenty") ? 20 : id.equals("sense_forty") ? 40 : 1));
            creatures.add(new CreatureDefinition(id, id, CreatureCategory.PASSIVE, model, new CreatureAttributes(), new CreatureBehavior(), new CreatureSpawn(),
                "Native perception fixture", List.of(), null, null, null, bindings));
        }
        return WorldContentBundleIO.create("Perception and bounded observation verification", "Native environment and crowded event admission", base.getTerrain(), base.getBiomes(),
            base.getFeatures(), base.getStructures(), base.getTheme(), base.getBlocks(), new CreatureLibrary(6, creatures), base.getAssets(), base.getItems(),
            base.getQuests(), base.getManifest().getRepresentativeContent(), base.getMechanics(), new AbilityLibrary(1, programs));
    }
    private static AbilityValue value(CreatureEntity actor, String program, String key) { return WorldAbilityRuntime.state(actor, program).get(key); }
    private static boolean bool(CreatureEntity actor, String program, String key) { return value(actor, program, key) instanceof AbilityValue.BoolValue b && b.getValue(); }
    private static double number(CreatureEntity actor, String program, String key) { return value(actor, program, key) instanceof AbilityValue.NumberValue n ? n.getValue() : 0; }
    private static Map<String, AbilityValue> map(CreatureEntity actor, String program, String key) {
        var value = value(actor, program, key); check(value instanceof AbilityValue.MapValue, "Missing native map: " + program + "/" + key + "/" + value);
        return ((AbilityValue.MapValue)value).getValues();
    }
    private static boolean flag(Map<String, AbilityValue> map, String key) { return map.get(key) instanceof AbilityValue.BoolValue b && b.getValue(); }
    private static double number(Map<String, AbilityValue> map, String key) { return map.get(key) instanceof AbilityValue.NumberValue n ? n.getValue() : Double.NaN; }
    private static String text(Map<String, AbilityValue> map, String key) { return map.get(key) instanceof AbilityValue.TextValue t ? t.getValue() : "<missing>"; }
    private static AbilityValue vector(Vec3 value) { return AbilityValues.vector(value.x, value.y, value.z); }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
