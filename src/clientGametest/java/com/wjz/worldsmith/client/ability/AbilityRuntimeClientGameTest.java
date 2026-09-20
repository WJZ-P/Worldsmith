package com.wjz.worldsmith.client.ability;

import com.mojang.authlib.GameProfile;
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.creature.HostileCreatureEntity;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.content.CreatureCombatState;
import com.wjz.worldsmith.core.examples.AbilityRuntimeExample;
import com.wjz.worldsmith.core.examples.MechanicDiscoveryExample;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real source-authored cues, native entities and item USE in Fabric's isolated client world. */
public final class AbilityRuntimeClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var pack = AbilityRuntimeExample.create();
        var names = new LinkedHashMap<String, String>();
        pack.getBiomes().getBiomes().forEach(b -> names.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
        var screenshots = new ArrayList<Path>();
        var actors = new Object[3];
        context.getInput().resizeWindow(1120, 700);
        context.runOnClient(client -> { client.options.guiScale().set(2); client.options.setCameraType(CameraType.FIRST_PERSON); });
        try (var world = context.worldBuilder().create()) {
            var prepared = WorldContentRuntime.prepare(pack, names);
            await(context, context.computeOnClient(client -> WorldContentClientRuntime.prepare(prepared).activate()));
            BlockPos base = world.getServer().computeOnServer(server -> {
                server.setDifficulty(Difficulty.NORMAL, true);
                var level = server.overworld(); WorldContentRuntime.bindLevel(level, prepared);
                var viewer = server.getPlayerList().getPlayers().getFirst(); actors[0] = viewer;
                var at = viewer.blockPosition().offset(0, 0, 8);
                for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
                    level.setBlock(at.offset(x, -1, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
                    for (int y = 0; y <= 8; y++) level.setBlock(at.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                viewer.setGameMode(GameType.CREATIVE); viewer.setNoGravity(true);
                viewer.teleportTo(at.getX() + 8.5, at.getY() + 6, at.getZ() + 9.5);
                var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "cue-target"), false);
                var target = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
                var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
                server.getPlayerList().placeNewPlayer(connection, target, cookie);
                target.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                target.setGameMode(GameType.SURVIVAL); target.setNoGravity(true);
                // Vanilla filters an invulnerable Player out of Mob.getTarget(), even if its
                // game mode is SURVIVAL. Keep a real hittable target alive with health instead.
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
                target.setHealth(100);
                check(target.canBeSeenAsEnemy() && !target.getAbilities().invulnerable, "Client fixture target is not attackable");
                target.snapTo(at.getX() + .5, at.getY(), at.getZ() + 4.5, 180, 0); actors[1] = target;
                var boss = new HostileCreatureEntity(CreatureRuntime.hostileType(), level);
                boss.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
                boss.initialize(pack.getComputedId(), MechanicDiscoveryExample.GUARDIAN, 9981);
                boss.setNoGravity(true); boss.setNoAi(true);
                check(level.addFreshEntity(boss), "Native Boss insertion failed");
                boss.setTarget(target); actors[2] = boss;
                return at;
            });
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.player != null
                && client.player.position().distanceToSqr(base.getX() + 8.5, base.getY() + 6, base.getZ() + 9.5) < .01, 100);
            world.getConnection().waitForChunksRender();
            // Apply the viewing angle only after the teleport packet, which also carries rotation.
            context.getInput().lookAt(base.above());
            int bossId = world.getServer().computeOnServer(server -> ((CreatureEntity) actors[2]).getId());
            context.waitFor(client -> client.level != null && client.level.getEntity(bossId) instanceof CreatureEntity boss
                && boss.definition() != null, 100);
            world.getServer().runOnServer(server -> {
                var boss = (CreatureEntity) actors[2]; boss.setNoAi(false); boss.setTarget((ServerPlayer) actors[1]);
            });
            try {
                context.waitFor(client -> client.gui.overlay() == null && client.level != null
                    && client.level.getEntity(bossId) instanceof CreatureEntity boss && boss.definition() != null
                    && boss.action() == CreatureCombatState.WINDUP.ordinal(), 300);
            } catch (AssertionError failure) {
                String serverState = world.getServer().computeOnServer(server -> {
                    var boss = (CreatureEntity) actors[2]; var target = (ServerPlayer) actors[1];
                    return "difficulty=" + server.overworld().getDifficulty() + "; paused=" + server.isPaused()
                        + "; bossAlive=" + boss.isAlive() + "; removed=" + boss.isRemoved() + "; noAI=" + boss.isNoAi()
                        + "; bossPosition=" + boss.position() + "; target=" + boss.getTarget() + "; action=" + boss.action()
                        + "; active=" + WorldAbilityRuntime.isActive(boss, AbilityRuntimeExample.ECHO)
                        + "; state=" + WorldAbilityRuntime.state(boss, AbilityRuntimeExample.ECHO)
                        + "; failure=" + WorldAbilityRuntime.lastFailure(boss, AbilityRuntimeExample.ECHO)
                        + "; targetAlive=" + target.isAlive() + "; targetHealth=" + target.getHealth()
                        + "; targetEnemy=" + target.canBeSeenAsEnemy() + "; targetPosition=" + target.position();
                });
                String clientState = context.computeOnClient(client -> {
                    var seen = client.level == null ? null : client.level.getEntity(bossId);
                    return "clientEntity=" + seen + "; clientAction=" + (seen instanceof CreatureEntity boss ? boss.action() : -1)
                        + "; clientDefinition=" + (seen instanceof CreatureEntity boss ? boss.definition() : null)
                        + "; scope=" + WorldContentClientRuntime.activeScope() + "; paused=" + client.isPaused()
                        + "; overlay=" + client.gui.overlay() + "; screen=" + client.gui.screen();
                });
                throw new AssertionError("Real NPC WINDUP never synchronized. " + serverState + " | " + clientState, failure);
            }
            context.waitTicks(8);
            world.getConnection().waitForClientboundPackets();
            screenshots.add(capture(context, "ability-01-npc-recorded-positions"));

            // Switch to actual player-host execution. The first demonstration above came from
            // the normal creature goal, not a test-injected damage or telegraph provider.
            world.getServer().computeOnServer(server -> {
                var boss = (CreatureEntity) actors[2]; WorldAbilityRuntime.cancelOwner(boss); boss.discard();
                var target = (ServerPlayer) actors[1]; server.getPlayerList().remove(target); actors[1] = null;
                var player = (ServerPlayer) actors[0]; player.setGameMode(GameType.SURVIVAL);
                player.getAbilities().invulnerable = true; player.onUpdateAbilities();
                player.teleportTo(base.getX() + .5, base.getY(), base.getZ() + .5);
                player.setYRot(0); player.setXRot(38);
                return null;
            });
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.player != null
                && client.player.position().distanceToSqr(base.getX() + .5, base.getY(), base.getZ() + .5) < .01, 100);
            context.runOnClient(client -> { client.options.setCameraType(CameraType.THIRD_PERSON_BACK); client.player.setYRot(0); client.player.setXRot(38); });
            // Vanilla dust has a short local lifetime after the server cue ends. Separate the
            // demonstrations so an old amber NPC marker is not mistaken for the blue player cue.
            context.waitTicks(48);
            world.getServer().runOnServer(server -> use((ServerPlayer) actors[0], AbilityRuntimeExample.CHANNEL_WAND));
            awaitServer(context, () -> world.getServer().computeOnServer(server -> state((ServerPlayer) actors[0], AbilityRuntimeExample.CHANNEL, "branch").equals("channeling")), 100);
            context.waitTicks(8);
            world.getConnection().waitForClientboundPackets();
            check(world.getServer().computeOnServer(server -> state((ServerPlayer) actors[0], AbilityRuntimeExample.CHANNEL, "branch").equals("channeling")),
                "The warning capture outlived the real channel");
            screenshots.add(capture(context, "ability-02-source-channel-warning"));
            world.getServer().computeOnServer(server -> {
                var player = (ServerPlayer) actors[0]; var at = base.offset(2, 0, 0);
                server.overworld().setBlock(at, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                check(player.gameMode.destroyBlock(at), "Real nearby block break failed");
                return null;
            });
            awaitServer(context, () -> world.getServer().computeOnServer(server -> state((ServerPlayer) actors[0], AbilityRuntimeExample.CHANNEL, "branch").equals("weakened")), 100);
            screenshots.add(capture(context, "ability-03-event-interrupted-recovery"));
            awaitServer(context, () -> world.getServer().computeOnServer(server -> !WorldAbilityRuntime.isActive((ServerPlayer) actors[0], AbilityRuntimeExample.CHANNEL)), 200);

            world.getServer().computeOnServer(server -> {
                var player = (ServerPlayer) actors[0];
                player.setYRot(0); player.setXRot(0); player.removeAllEffects();
                for (int x = -3; x <= 3; x++) for (int y = 0; y <= 4; y++)
                    server.overworld().setBlock(base.offset(x, y, 5), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
                use(player, AbilityRuntimeExample.ORB_WAND); return null;
            });
            context.runOnClient(client -> { client.player.setYRot(0); client.player.setXRot(0); });
            awaitServer(context, () -> world.getServer().computeOnServer(server -> state((ServerPlayer) actors[0], AbilityRuntimeExample.ORB, "branch").equals("split")), 150);
            screenshots.add(capture(context, "ability-04-projectile-callback-split"));
            awaitServer(context, () -> world.getServer().computeOnServer(server -> !WorldAbilityRuntime.isActive((ServerPlayer) actors[0], AbilityRuntimeExample.ORB)), 200);
            world.getServer().computeOnServer(server -> {
                check(WorldAbilityRuntime.activeCount(server.overworld()) == 0, "Finished source retained active resources"); return null;
            });
            context.waitTicks(48);
            screenshots.add(capture(context, "ability-05-finished-scene"));
        }
        context.waitFor(client -> client.level == null && WorldContentClientRuntime.activeScope() == null);
        check(WorldContentRuntime.boundLevelCount() == 0, "Normal world shutdown retained a content binding");
        try {
            for (var screenshot : screenshots) check(Files.isRegularFile(screenshot) && Files.size(screenshot) > 0, "Missing screenshot " + screenshot);
            Path report = Path.of(System.getProperty("worldsmith.ability.client-report"));
            Files.createDirectories(report.getParent());
            Files.writeString(report, "PASS programmable ability client\nNative creature source; player item source; real block-break branch; projectile callback; resource completion\n"
                + screenshots.stream().map(Path::toString).collect(java.util.stream.Collectors.joining("\n")) + "\n");
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private static void use(ServerPlayer player, String item) {
        var stack = CustomItemRuntime.snapshot(player.level()).stack("worldsmith:item/" + item, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        check(stack.use(player.level(), player, InteractionHand.MAIN_HAND).consumesAction(), "Actual item USE failed: " + item);
    }
    private static String state(ServerPlayer player, String program, String key) {
        var value = WorldAbilityRuntime.state(player, program).get(key);
        return value instanceof AbilityValue.TextValue t ? t.getValue() : "";
    }
    private static Path capture(ClientGameTestContext context, String name) {
        context.waitFor(client -> client.gui.overlay() == null && client.gui.screen() == null && client.level != null);
        context.waitTicks(3);
        return context.takeScreenshot(name);
    }
    private static void awaitServer(ClientGameTestContext context, BooleanSupplier ready, int ticks) {
        for (int i = 0; i < ticks; i++) { if (ready.getAsBoolean()) return; context.waitTick(); }
        throw new AssertionError("Source-authored server demonstration did not reach its expected state");
    }
    private static void await(ClientGameTestContext context, CompletableFuture<Void> future) {
        context.waitFor(client -> future.isDone(), 20 * 120); future.join();
        context.waitFor(client -> client.gui.overlay() == null, 20 * 120); context.waitTicks(3);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
