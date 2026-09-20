package com.wjz.worldsmith.gametest;

import com.mojang.authlib.GameProfile;
import com.wjz.worldsmith.ability.AbilityActorControl;
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.interaction.WorldMechanicSavedData;
import com.wjz.worldsmith.content.story.*;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.examples.ImmersiveVillageExample;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Real native navigation, a marker-owned resident, live dialogue, and script-controlled ownership handoffs. */
public final class AbilityActorControlGameTests {
    @GameTest(environment = "worldsmith_mechanics_smoke:ability_control", maxTicks = 2200, skyAccess = true, padding = 35)
    public void observationMovementAndDialogueShareExplicitOwnership(GameTestHelper helper) {
        var run = new Run(helper);
        try { run.begin(); } catch (Throwable failure) { run.close(); throw failure; }
        helper.onEachTick(() -> { try { run.tick(); } catch (Throwable failure) { run.close(); throw failure; } });
    }

    private record Step(String name, Runnable start, BooleanSupplier poll, int timeout) {}

    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final WorldsmithPack pack = fixture();
        final List<Step> steps = new ArrayList<>();
        final List<Marker> markers = new ArrayList<>();
        final Map<ChunkPos, Boolean> forced = new LinkedHashMap<>();
        WorldContentRuntime.Prepared prepared;
        StorySavedData priorStory;
        WorldMechanicSavedData priorMechanics;
        ServerPlayer player;
        CreatureEntity actor;
        BlockPos base, home;
        CompoundTag saved;
        UUID oldActor, ambientInvocation, emergencyInvocation;
        net.minecraft.world.level.pathfinder.Path emergencyPath;
        Vec3 conversationPosition;
        long initialTime, initialClock, stepAt, stageAt;
        int index = -1, phase;
        boolean bound, closed, captured;

        Run(GameTestHelper helper) { this.helper = helper; level = helper.getLevel(); }

        void begin() {
            check(WorldContentRuntime.boundLevelCount() == 0, "Control fixture overlapped another bound world");
            var names = new LinkedHashMap<String, String>();
            pack.getBiomes().getBiomes().forEach(b -> names.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
            prepared = WorldContentRuntime.prepare(pack, names);
            priorStory = level.getServer().overworld().getDataStorage().get(StorySavedData.TYPE);
            priorMechanics = level.getDataStorage().get(WorldMechanicSavedData.TYPE);
            initialTime = level.getGameTime(); initialClock = level.getOverworldClockTime(); captured = true;
            level.getServer().overworld().getDataStorage().set(StorySavedData.TYPE, new StorySavedData(StorySavedData.State.empty(pack.getComputedId())));
            level.getDataStorage().set(WorldMechanicSavedData.TYPE, new WorldMechanicSavedData());
            WorldContentRuntime.bindLevel(level, prepared); bound = true;
            base = helper.absolutePos(new BlockPos(8, 3, 8)); home = base.offset(0, 0, 2);
            for (int x = (base.getX() - 24) >> 4; x <= (base.getX() + 24) >> 4; x++)
                for (int z = (base.getZ() - 24) >> 4; z <= (base.getZ() + 24) >> 4; z++) {
                    var chunk = new ChunkPos(x, z);
                    forced.put(chunk, level.getChunkSource().getForceLoadedChunks().contains(chunk.pack()));
                    level.setChunkForced(x, z, true); level.getChunk(x, z);
                }
            for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int y = 0; y <= 5; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            clock(12000);
            marker(base, false); marker(home, true);
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "control-player"), false);
            player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL); player.setNoGravity(true); player.setInvulnerable(true);
            stand(base.offset(-8, 0, -8));
            define(); next();
        }

        void marker(BlockPos position, boolean character) {
            var entity = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse("minecraft:marker")).orElseThrow()
                .create(level, EntitySpawnReason.TRIGGERED);
            check(entity instanceof Marker, "Native marker host missing");
            var marker = (Marker)entity;
            marker.snapTo(position.getX() + .5, position.getY(), position.getZ() + .5, 0, 0);
            marker.addTag("worldsmith.story"); marker.addTag("worldsmith.scope." + pack.getComputedId());
            marker.addTag("worldsmith.place." + ImmersiveVillageExample.VILLAGE);
            if (character) marker.addTag("worldsmith.character." + ImmersiveVillageExample.KEEPER);
            check(level.addFreshEntity(marker), "Native story marker was rejected"); markers.add(marker);
        }

        void define() {
            add("real markers produce one stable resident and actual home", () -> {}, () -> {
                var residents = residents(); if (residents.size() != 1) return false;
                actor = residents.getFirst();
                check(WorldStoryRuntime.characterKey(actor) != null && home.equals(actor.abilityHome()), "Resident identity/home was invented");
                return true;
            }, 120);
            add("pure observer runs while the resident physically walks its daytime routine", () -> {
                start("control_observe"); clock(1000);
            }, () -> {
                check(!AbilityActorControl.hasControl(actor), "A pure observer acquired movement");
                check(WorldAbilityRuntime.isActive(actor, "control_observe"), "Observer ended before routine movement was proved");
                return number("control_observe", "pulses") > 10 && actor.position().distanceToSqr(Vec3.atBottomCenterOf(home.offset(5, 0, 2))) < 2;
            }, 240);
            add("unclaimed motion primitives leave the native routine untouched", () -> start("control_unclaimed"), () -> {
                if (!bool("control_unclaimed", "checked")) return false;
                check(!bool("control_unclaimed", "stopped") && !bool("control_unclaimed", "faced") && !bool("control_unclaimed", "navigated"), "Unclaimed script stole native control");
                check(!AbilityActorControl.hasControl(actor), "Unclaimed motion implicitly created a token");
                return true;
            }, 40);
            add("first owner wins ties and a higher priority claim retires only the old route", () -> {
                WorldAbilityRuntime.cancelOwner(actor); start("control_ambient");
            }, () -> {
                if (phase == 0) {
                    if (!bool("control_ambient", "moved")) return false;
                    ambientInvocation = WorldAbilityRuntime.invocation(actor, "control_ambient");
                    check(AbilityActorControl.hasControl(actor), "Accepted native path has no live control");
                    start("control_equal"); phase = 1; return false;
                }
                if (phase == 1) {
                    if (!bool("control_equal", "checked")) return false;
                    check(number("control_equal", "token") == 0, "Equal priority preempted the first controller");
                    check(AbilityActorControl.held(actor, ambientInvocation, (int)number("control_ambient", "token")), "Rejected claimant retired the first token");
                    start("control_emergency"); phase = 2; return false;
                }
                if (!bool("control_emergency", "moved")) return false;
                emergencyInvocation = WorldAbilityRuntime.invocation(actor, "control_emergency");
                check(!AbilityActorControl.held(actor, ambientInvocation, (int)number("control_ambient", "token")), "Preempted token remained valid");
                emergencyPath = actor.getNavigation().getPath();
                check(emergencyPath != null, "Emergency controller did not install a native path");
                WorldAbilityRuntime.cancelInvocation(level, ambientInvocation);
                check(actor.getNavigation().getPath() == emergencyPath, "Old invocation cleanup erased the emergency route");
                check(AbilityActorControl.held(actor, emergencyInvocation, (int)number("control_emergency", "token")), "Old invocation cleanup erased the new claim");
                return true;
            }, 100);
            add("lease expiry stops ownership without cancelling the observing invocation", () -> {
                WorldAbilityRuntime.cancelOwner(actor); start("control_expire");
            }, () -> {
                if (!bool("control_expire", "checked")) return false;
                check(!bool("control_expire", "held") && !bool("control_expire", "movedAfter"), "Expired token retained motion privileges");
                check(!AbilityActorControl.hasControl(actor), "Expired control stayed published");
                check(WorldAbilityRuntime.isActive(actor, "control_expire"), "Expiry cancelled the entire invocation");
                return true;
            }, 60);
            add("opening a real conversation interrupts ordinary work and blocks ordinary reacquisition", () -> {
                WorldAbilityRuntime.cancelOwner(actor); start("control_ambient");
            }, () -> {
                if (phase == 0) {
                    if (!AbilityActorControl.hasControl(actor)) return false;
                    near(); check(WorldStoryRuntime.openDialogue(player, actor), "Player could not interrupt ordinary work");
                    check(WorldStoryRuntime.journalSnapshot(player).dialogue() != null, "Conversation never reached authoritative state");
                    check(!AbilityActorControl.hasControl(actor), "Opening dialogue retained the ambient lease");
                    check(WorldAbilityRuntime.isActive(actor, "control_ambient"), "Conversation cancelled non-control program state");
                    conversationPosition = actor.position(); start("control_equal"); stageAt = level.getGameTime(); phase = 1;
                    return false;
                }
                if (level.getGameTime() - stageAt < 12 || !bool("control_equal", "checked")) return false;
                check(number("control_equal", "token") == 0, "Ordinary work reacquired control during conversation");
                check(actor.position().distanceToSqr(conversationPosition) < .25, "Talking resident kept following the retired work path");
                return true;
            }, 90);
            add("emergency control closes the live dialogue before movement and prevents immediate reopening", () -> {
                start("control_emergency");
            }, () -> {
                if (!AbilityActorControl.hasControl(actor) || !bool("control_emergency", "moved")) return false;
                check(WorldStoryRuntime.journalSnapshot(player).dialogue() == null, "Emergency left a stale conversation open");
                near(); check(!WorldStoryRuntime.openDialogue(player, actor), "A new dialogue stole emergency control");
                return true;
            }, 50);
            add("NoAI revokes control and native reactivation resumes the resident routine", () -> {
                actor.setNoAi(true);
            }, () -> {
                if (phase == 0) {
                    if (WorldAbilityRuntime.hasActiveProgram(actor)) return false;
                    check(!AbilityActorControl.hasControl(actor), "NoAI left a control lease alive");
                    actor.setNoAi(false); clock(12000); phase = 1;
                }
                return actor.position().distanceToSqr(Vec3.atBottomCenterOf(home)) < 2;
            }, 260);
            add("unload retires paths and claims; native reload keeps state but resumes no continuation", () -> {
                start("control_ambient");
            }, () -> {
                if (phase == 0) {
                    if (!AbilityActorControl.hasControl(actor)) return false;
                    oldActor = actor.getUUID();
                    var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                    actor.saveWithoutId(output); saved = output.buildResult();
                    actor.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                    check(!AbilityActorControl.hasControl(actor), "Native unload retained an actor claim");
                    check(!WorldAbilityRuntime.hasActiveProgram(actor), "Native unload retained an invocation");
                    stageAt = level.getGameTime(); phase = 1; return false;
                }
                if (phase == 1) {
                    if (level.getGameTime() - stageAt < 3) return false;
                    var reloaded = actor.getType().create(level, EntitySpawnReason.LOAD);
                    check(reloaded instanceof CreatureEntity, "Saved creature type changed"); actor = (CreatureEntity)reloaded;
                    actor.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
                    check(level.addFreshEntity(actor), "Same-UUID native resident reload failed"); phase = 2; return false;
                }
                if (WorldStoryRuntime.characterKey(actor) == null) return false;
                check(oldActor.equals(actor.getUUID()) && residents().size() == 1, "Reload duplicated a marker-owned resident");
                check(!AbilityActorControl.hasControl(actor) && !WorldAbilityRuntime.hasActiveProgram(actor), "Reload resumed a control token or instruction pointer");
                check(number("control_observe", "pulses") > 10, "Reload lost persistent actor observation state");
                return actor.position().distanceToSqr(Vec3.atBottomCenterOf(home)) < 2;
            }, 260);
            add("a real lethal hit retires the final claim and invocation", () -> start("control_emergency"), () -> {
                if (phase == 0) {
                    if (!AbilityActorControl.hasControl(actor)) return false;
                    check(actor.hurtServer(level, level.damageSources().generic(), 1000), "Lethal server damage was rejected"); phase = 1;
                }
                check(!AbilityActorControl.hasControl(actor) && !WorldAbilityRuntime.hasActiveProgram(actor), "Death retained movement ownership");
                return actor.isDeadOrDying();
            }, 40);
        }

        void add(String name, Runnable start, BooleanSupplier poll, int timeout) { steps.add(new Step(name, start, poll, timeout)); }
        void next() {
            if (++index >= steps.size()) {
                System.out.println("[AbilityControl] PASS " + steps.size() + " native observer/routine, control, dialogue and lifecycle scenarios");
                close(); helper.succeed(); return;
            }
            phase = 0; stepAt = level.getGameTime(); steps.get(index).start.run();
        }
        void tick() {
            if (closed) return;
            var step = steps.get(index);
            check(level.getGameTime() - stepAt <= step.timeout, "Control scenario timed out: " + step.name);
            if (step.poll.getAsBoolean()) { System.out.println("[AbilityControl] PASS " + step.name); next(); }
        }
        void start(String program) {
            check(WorldAbilityRuntime.start(level, actor, program, actor.position(), null, 1, false), "Program admission failed: " + program);
        }
        List<CreatureEntity> residents() {
            return level.getEntitiesOfClass(CreatureEntity.class, new AABB(base).inflate(24)).stream()
                .filter(e -> e.bundleHash().equals(pack.getComputedId()) && !e.isRemoved() && e.isAlive()).toList();
        }
        void stand(BlockPos position) {
            player.snapTo(position.getX() + .5, position.getY(), position.getZ() + .5, 0, 0);
            player.setDeltaMovement(Vec3.ZERO); level.getChunkSource().move(player);
        }
        void near() { stand(actor.blockPosition().offset(2, 0, 0)); }
        void clock(long value) { level.getServer().clockManager().setTotalTicks(level.registryAccess().get(WorldClocks.OVERWORLD).orElseThrow(), value); }
        double number(String program, String key) { var value = WorldAbilityRuntime.state(actor, program).get(key); return value instanceof AbilityValue.NumberValue n ? n.getValue() : 0; }
        boolean bool(String program, String key) { var value = WorldAbilityRuntime.state(actor, program).get(key); return value instanceof AbilityValue.BoolValue b && b.getValue(); }

        void close() {
            if (closed) return; closed = true;
            try {
                if (actor != null) actor.discard();
                markers.forEach(Entity::discard);
                if (player != null) { WorldAbilityRuntime.cancelOwner(player); level.getServer().getPlayerList().remove(player); player.discard(); }
                if (bound) { WorldContentRuntime.unbindLevel(level); bound = false; }
                if (captured) {
                    level.getServer().overworld().getDataStorage().set(StorySavedData.TYPE, priorStory == null ? new StorySavedData(StorySavedData.State.empty(pack.getComputedId())) : priorStory);
                    level.getDataStorage().set(WorldMechanicSavedData.TYPE, priorMechanics == null ? new WorldMechanicSavedData() : priorMechanics);
                    clock(initialClock + Math.max(0, level.getGameTime() - initialTime));
                }
            } finally { forced.forEach((chunk, was) -> level.setChunkForced(chunk.x(), chunk.z(), was)); }
        }
    }

    private static WorldsmithPack fixture() {
        var base = ImmersiveVillageExample.create();
        var programs = new ArrayList<>(base.getAbilities().getPrograms());
        programs.add(new AbilityProgramDefinition("control_observe", "Observe without steering", """
            on start {
                state.pulses = 0;
                while (state.pulses < 500) {
                    state.environment = entity.environment(self);
                    state.pulses = state.pulses + 1;
                    wait 1;
                }
            }
            """, 700, 32768));
        programs.add(new AbilityProgramDefinition("control_unclaimed", "Motion requires ownership", """
            on start {
                state.stopped = motion.stop(self);
                state.faced = motion.face(self, entity.home(self));
                state.navigated = motion.navigate(self, entity.home(self), 0.7);
                state.checked = true;
            }
            """, 30, 512));
        programs.add(new AbilityProgramDefinition("control_ambient", "Ordinary authored work", """
            on start {
                state.moved = false;
                state.token = control.claim(20, 180);
                if (state.token != 0) {
                    state.moved = motion.navigate(self, vector.add(entity.home(self), vec(-6, 0, 0)), 0.7);
                }
                wait 150;
            }
            """, 220, 1024));
        programs.add(new AbilityProgramDefinition("control_equal", "Equal-priority contender", """
            on start { state.checked = false; state.token = control.claim(20, 40); state.checked = true; }
            """, 80, 512));
        programs.add(new AbilityProgramDefinition("control_emergency", "Urgent authored movement", """
            on start {
                state.moved = false;
                state.token = control.claim(80, 180);
                if (state.token != 0) {
                    state.moved = motion.navigate(self, vector.add(entity.home(self), vec(0, 0, -6)), 0.9);
                }
                wait 150;
            }
            """, 220, 1024));
        programs.add(new AbilityProgramDefinition("control_expire", "Lease expires independently", """
            on start {
                state.checked = false;
                let token = control.claim(20, 8);
                motion.navigate(self, vector.add(entity.home(self), vec(-8, 0, 0)), 0.7);
                wait 16;
                state.held = control.held(token);
                state.movedAfter = motion.navigate(self, entity.home(self), 0.7);
                state.checked = true;
                wait 20;
            }
            """, 80, 1024));
        return WorldContentBundleIO.create("Actor control verification", "Native control ownership and observation", base.getTerrain(), base.getBiomes(),
            base.getFeatures(), base.getStructures(), base.getTheme(), base.getBlocks(), base.getCreatures(), base.getAssets(), base.getItems(),
            base.getQuests(), base.getManifest().getRepresentativeContent(), base.getMechanics(), new AbilityLibrary(1, programs), base.getStory());
    }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
