package com.wjz.worldsmith.client.ability;

import com.wjz.worldsmith.ability.AbilityFxEntity;
import com.wjz.worldsmith.ability.AbilityVisualData;
import com.wjz.worldsmith.ability.OwnedItemDisplay;
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.creature.HostileCreatureEntity;
import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.examples.AbilityVisualExample;
import com.wjz.worldsmith.core.examples.MechanicDiscoveryExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Actual asset atlas, native Display/ribbon rendering and synced custom bone clips; never edited screenshots. */
public final class AbilityVisualClientGameTest implements FabricClientGameTest {
    private record Setup(BlockPos base, int actor) {}
    @Override public void runTest(ClientGameTestContext context) {
        var pack = AbilityVisualExample.create();
        var names = new LinkedHashMap<String, String>();
        pack.getBiomes().getBiomes().forEach(b -> names.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
        var screenshots = new ArrayList<Path>();
        context.getInput().resizeWindow(1120, 700);
        context.runOnClient(client -> {
            client.options.guiScale().set(2); client.options.setCameraType(CameraType.FIRST_PERSON);
            client.options.particles().set(ParticleStatus.ALL);
        });
        try (var world = context.worldBuilder().create()) {
            var prepared = WorldContentRuntime.prepare(pack, names);
            await(context, context.computeOnClient(client -> WorldContentClientRuntime.prepare(prepared).activate()));
            Setup setup = world.getServer().computeOnServer(server -> {
                server.setDifficulty(Difficulty.NORMAL, true);
                var level = server.overworld(); WorldContentRuntime.bindLevel(level, prepared);
                var player = server.getPlayerList().getPlayers().getFirst();
                var base = player.blockPosition().offset(0, 0, 8);
                for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
                    level.setBlock(base.offset(x, -1, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
                    for (int y = 0; y <= 8; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                player.setGameMode(GameType.CREATIVE); player.setNoGravity(true);
                player.teleportTo(base.getX() + 6.5, base.getY() + 4, base.getZ() + 8.5);
                var actor = new HostileCreatureEntity(CreatureRuntime.hostileType(), level);
                actor.snapTo(base.getX() + .5, base.getY(), base.getZ() + .5, 0, 0);
                actor.initialize(pack.getComputedId(), MechanicDiscoveryExample.GUARDIAN, 117);
                actor.setNoGravity(true); actor.setNoAi(true); check(level.addFreshEntity(actor), "Visual actor insertion failed");
                return new Setup(base, actor.getId());
            });
            context.waitTick(); world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.player != null && client.player.position().distanceToSqr(
                setup.base().getX() + 6.5, setup.base().getY() + 4, setup.base().getZ() + 8.5) < .01, 100);
            world.getConnection().waitForChunksRender();
            context.getInput().lookAt(setup.base().above());
            context.waitFor(client -> client.level != null && client.level.getEntity(setup.actor()) instanceof CreatureEntity actor && actor.definition() != null);
            world.getServer().runOnServer(server -> {
                var actor = (CreatureEntity) server.overworld().getEntity(setup.actor()); actor.setNoAi(false);
                check(WorldAbilityRuntime.start(server.overworld(), actor, AbilityVisualExample.SHOWCASE, actor.position(), null, 20, false), "Visual source did not enqueue");
            });
            awaitServer(context, () -> world.getServer().computeOnServer(server -> flag((CreatureEntity) server.overworld().getEntity(setup.actor()), "created")), 80);
            context.waitTicks(8); world.getConnection().waitForClientboundEntityUpdates(AbilityFxEntity.type(), OwnedItemDisplay.type(), CreatureRuntime.hostileType());
            context.waitFor(client -> client.level != null && AbilityVisualClient.activeParticles() > 0 && clipBone(client, setup.actor(), "left_arm"), 40);
            context.runOnClient(client -> check(client.level.getEntitiesOfClass(AbilityFxEntity.class, new AABB(setup.base()).inflate(12)).size() == 2
                && client.level.getEntitiesOfClass(OwnedItemDisplay.class, new AABB(setup.base()).inflate(12)).size() == 1, "The real visual entities were not tracked by the client"));
            screenshots.add(capture(context, "visual-01-textured-particles-path-display-left-clip"));

            awaitServer(context, () -> world.getServer().computeOnServer(server -> flag((CreatureEntity) server.overworld().getEntity(setup.actor()), "overlay_started")), 100);
            context.waitTicks(8); world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> clipBone(client, setup.actor(), "right_arm"), 10);
            screenshots.add(capture(context, "visual-02-transformed-item-new-right-clip"));

            awaitServer(context, () -> world.getServer().computeOnServer(server -> flag((CreatureEntity) server.overworld().getEntity(setup.actor()), "overlay_finished")), 60);
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> clipBone(client, setup.actor(), "left_arm"), 15);
            context.runOnClient(client -> {
                var frame = AbilityVisualClient.animation((CreatureEntity) client.level.getEntity(setup.actor()), 0);
                check(frame != null && frame.elapsed() >= 60, "Restoring an older clip incorrectly restarted its timeline");
            });
            screenshots.add(capture(context, "visual-03-older-live-clip-restored"));

            awaitServer(context, () -> world.getServer().computeOnServer(server -> flag((CreatureEntity) server.overworld().getEntity(setup.actor()), "cleared")), 60);
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.level != null && AbilityVisualClient.activeClips() == 0
                && client.level.getEntitiesOfClass(AbilityFxEntity.class, new AABB(setup.base()).inflate(12)).stream().noneMatch(e -> e.visual().kind() == AbilityVisualData.Kind.PATH), 20);
            context.runOnClient(client -> check(AbilityVisualClient.activeParticles() > 0
                && !client.level.getEntitiesOfClass(OwnedItemDisplay.class, new AABB(setup.base()).inflate(12)).isEmpty(), "Removing one owned visual cleared the other visuals"));
            screenshots.add(capture(context, "visual-04-explicit-path-and-clip-stop"));

            awaitServer(context, () -> world.getServer().computeOnServer(server -> {
                var actor = (CreatureEntity) server.overworld().getEntity(setup.actor());
                return flag(actor, "done") && !WorldAbilityRuntime.isActive(actor, AbilityVisualExample.SHOWCASE);
            }), 130);
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.level != null && AbilityVisualClient.activeParticles() == 0 && AbilityVisualClient.activeClips() == 0
                && client.level.getEntitiesOfClass(AbilityFxEntity.class, new AABB(setup.base()).inflate(12)).isEmpty()
                && client.level.getEntitiesOfClass(OwnedItemDisplay.class, new AABB(setup.base()).inflate(12)).isEmpty(), 30);
            screenshots.add(capture(context, "visual-05-all-owned-resources-expired"));
            world.getServer().runOnServer(server -> check(WorldAbilityRuntime.activeCount(server.overworld()) == 0, "Completed visual source retained server resources"));
        }
        context.waitFor(client -> client.level == null && WorldContentClientRuntime.activeScope() == null);
        check(WorldContentRuntime.boundLevelCount() == 0 && AbilityVisualClient.activeClips() == 0 && AbilityVisualClient.activeParticles() == 0,
            "Normal disconnect retained a visual cache or content binding");
        screenshots.addAll(AbilityExtensionApprovalClientChecks.capture(context));
        try {
            for (var screenshot : screenshots) check(Files.isRegularFile(screenshot) && Files.size(screenshot) > 0, "Missing visual screenshot " + screenshot);
            var report = Path.of(System.getProperty("worldsmith.visual.client-report")); Files.createDirectories(report.getParent());
            Files.writeString(report, "PASS programmable visuals client\nActual PNG particles, arbitrary path, native item display transform, custom bone tracks, latest-live fallback, stop/expiry/disconnect\n"
                + screenshots.stream().map(Path::toString).collect(java.util.stream.Collectors.joining("\n")) + "\n");
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    private static boolean flag(CreatureEntity actor, String key) {
        var value = WorldAbilityRuntime.state(actor, AbilityVisualExample.SHOWCASE).get(key);
        return value instanceof AbilityValue.BoolValue b && b.getValue();
    }
    private static boolean clipBone(net.minecraft.client.Minecraft client, int actorId, String bone) {
        if (client.level == null || !(client.level.getEntity(actorId) instanceof CreatureEntity actor)) return false;
        var frame = AbilityVisualClient.animation(actor, 0);
        return frame != null && frame.clip().tracks().size() == 1 && frame.clip().tracks().getFirst().bone().equals(bone);
    }
    private static Path capture(ClientGameTestContext context, String name) {
        context.waitFor(client -> client.gui.overlay() == null && client.gui.screen() == null && client.level != null);
        context.waitTicks(3); return context.takeScreenshot(name);
    }
    private static void awaitServer(ClientGameTestContext context, BooleanSupplier ready, int ticks) {
        for (int i = 0; i < ticks; i++) { if (ready.getAsBoolean()) return; context.waitTick(); }
        throw new AssertionError("Visual source did not reach its expected phase");
    }
    private static void await(ClientGameTestContext context, CompletableFuture<Void> future) {
        context.waitFor(client -> future.isDone(), 20 * 120); future.join(); context.waitFor(client -> client.gui.overlay() == null, 20 * 120); context.waitTicks(3);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
