package com.wjz.worldsmith.client.quest;

import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.core.examples.MechanicDiscoveryExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

/** Opt-in real client test. Its only world is Fabric's fresh isolated flat test save. */
public final class MechanicGuideClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        List<Path> screenshots = new ArrayList<>();
        var pack = MechanicDiscoveryExample.create();
        var biomeNames = new LinkedHashMap<String, String>();
        pack.getBiomes().getBiomes().forEach(b -> biomeNames.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
        context.runOnClient(client -> client.options.guiScale().set(2));
        context.getInput().resizeWindow(960, 600);

        try (var world = context.worldBuilder().create()) {
            var prepared = WorldContentRuntime.prepare(pack, biomeNames);
            await(context, context.computeOnClient(client -> WorldContentClientRuntime.prepare(prepared).activate()));
            BlockPos anchor = world.getServer().computeOnServer(server -> {
                var level = server.overworld();
                WorldContentRuntime.bindLevel(level, prepared);
                var player = server.getPlayerList().getPlayers().getFirst();
                var at = player.blockPosition().offset(0, 0, 3);
                player.setGameMode(GameType.SURVIVAL);
                player.getInventory().clearContent();
                player.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() - 2.5);
                level.setBlock(at, Blocks.LODESTONE.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(at.west(), BuiltInRegistries.BLOCK.getOptional(Identifier.parse("minecraft:copper_block")).orElseThrow().defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(at.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(at.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                return at;
            });
            world.getConnection().waitForChunksDownload();
            context.getInput().lookAt(anchor);
            context.waitFor(client -> client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(anchor));
            context.runOnClient(QuestJournalClient::open);
            context.waitFor(client -> client.gui.screen() instanceof QuestJournalScreen && QuestJournalClient.snapshot(prepared.scope()) != null && !QuestJournalClient.pending());
            screenshots.add(capture(context, "guide-01-journal-unfinished", QuestJournalScreen.class));
            var journal = context.computeOnClient(client -> client.gui.screen());
            press(context, Component.translatable("worldsmith.mechanics.guide.open", 2));
            context.waitForScreen(MechanicGuideScreen.class);
            screenshots.add(capture(context, "guide-02-altar-en", MechanicGuideScreen.class));
            context.getInput().pressKey(GLFW.GLFW_KEY_PAGE_DOWN);
            screenshots.add(capture(context, "guide-02b-base-layer-en", MechanicGuideScreen.class));
            context.getInput().pressKey(GLFW.GLFW_KEY_HOME);

            // The request uses the actual crosshair and network receiver; an incomplete pattern is discoverable.
            context.clickScreenButton("worldsmith.mechanics.guide.inspect");
            context.waitFor(client -> !MechanicGuideClient.pending() && !MechanicGuideClient.notice(prepared.scope(), MechanicDiscoveryExample.ALTAR).getString().isEmpty());
            context.runOnClient(client -> check(MechanicGuideClient.notice(prepared.scope(), MechanicDiscoveryExample.ALTAR).getString()
                .equals(Component.translatable("worldsmith.mechanics.inspection.incomplete_pattern", 1).getString()), "Expected an incomplete-pattern network reply"));
            screenshots.add(capture(context, "guide-03-inspection", MechanicGuideScreen.class));

            // Layer selection preserves the global X/Z frame; the next layer explicitly contains air.
            pressArrow(context, false);
            context.getInput().pressKey(GLFW.GLFW_KEY_PAGE_DOWN);
            screenshots.add(capture(context, "guide-04-air-layer", MechanicGuideScreen.class));
            pressArrow(context, true);
            screenshots.add(capture(context, "guide-05-second-objective", MechanicGuideScreen.class));
            context.getInput().pressKey(GLFW.GLFW_KEY_END);
            screenshots.add(capture(context, "guide-06-cost-operation", MechanicGuideScreen.class));
            context.clickScreenButton("worldsmith.mechanics.guide.back");
            context.runOnClient(client -> check(client.gui.screen() == journal, "Back must preserve the same journal and selection"));

            // Exercise localized labels and the minimum supported GUI size in a real render pipeline.
            await(context, context.computeOnClient(client -> {
                client.options.languageCode = "zh_cn";
                client.getLanguageManager().setSelected("zh_cn");
                return client.reloadResourcePacks();
            }));
            press(context, Component.translatable("worldsmith.mechanics.guide.open", 2));
            context.waitForScreen(MechanicGuideScreen.class);
            screenshots.add(capture(context, "guide-07-altar-zh", MechanicGuideScreen.class));
            context.runOnClient(client -> client.options.guiScale().set(1));
            context.getInput().resizeWindow(320, 180);
            context.waitFor(client -> client.gui.screen() instanceof MechanicGuideScreen screen && screen.width == 320 && screen.height == 180);
            screenshots.add(capture(context, "guide-08-compact-zh", MechanicGuideScreen.class));
            context.getInput().pressKey(GLFW.GLFW_KEY_END);
            screenshots.add(capture(context, "guide-09-compact-bottom-zh", MechanicGuideScreen.class));
            context.getInput().pressKey(GLFW.GLFW_KEY_J);
            context.waitForScreen(null);
            context.runOnClient(QuestJournalClient::open);
            context.waitForScreen(QuestJournalScreen.class);
            press(context, Component.translatable("worldsmith.mechanics.guide.open", 2));
            context.waitForScreen(MechanicGuideScreen.class);
            // Leave the guide open while disconnecting: it must not survive into another world.
        }
        context.waitFor(client -> client.level == null && WorldContentClientRuntime.activeScope() == null);
        context.runOnClient(client -> check(!(client.gui.screen() instanceof MechanicGuideScreen), "Disconnect left stale mechanic details visible"));
        try {
            for (Path screenshot : screenshots) check(Files.isRegularFile(screenshot) && Files.size(screenshot) > 0, "Missing screenshot " + screenshot);
            Path report = Path.of(System.getProperty("worldsmith.guide.client-report"));
            Files.createDirectories(report.getParent());
            Files.writeString(report, "PASS mechanic guide client\n" + screenshots.stream().map(Path::toString).collect(java.util.stream.Collectors.joining("\n")) + "\n");
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private static void await(ClientGameTestContext context, CompletableFuture<Void> future) {
        context.waitFor(client -> future.isDone(), 20 * 120);
        future.join(); // On the test thread, never the render or server thread.
        context.waitFor(client -> client.gui.overlay() == null, 20 * 120);
        context.waitTicks(3);
    }

    private static Path capture(ClientGameTestContext context, String name, Class<? extends Screen> expectedScreen) {
        context.waitFor(client -> expectedScreen.isInstance(client.gui.screen()) && client.gui.overlay() == null);
        // State callbacks and cached text update on a tick; extraction and screenshot may otherwise lag one frame.
        context.waitTicks(3);
        context.runOnClient(client -> check(expectedScreen.isInstance(client.gui.screen()) && client.gui.overlay() == null,
            "Screenshot should show the expected UI, not a loading overlay: " + name));
        return context.takeScreenshot(name);
    }

    private static void press(ClientGameTestContext context, Component label) {
        context.runOnClient(client -> {
            var screen = client.gui.screen(); check(screen != null, "Expected a screen for " + label.getString());
            Button button = screen.children().stream().filter(child -> child instanceof Button).map(child -> (Button) child)
                .filter(child -> child.getMessage().getString().equals(label.getString())).findFirst().orElseThrow();
            check(button.active && button.visible, "Guide entry should be available before construction");
            button.onPress(new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        });
        context.waitTick();
    }

    private static void pressArrow(ClientGameTestContext context, boolean first) {
        context.runOnClient(client -> {
            var buttons = client.gui.screen().children().stream().filter(child -> child instanceof Button).map(child -> (Button) child)
                .filter(child -> child.getMessage().getString().equals(">")).toList();
            (first ? buttons.getFirst() : buttons.getLast()).onPress(new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        });
        context.waitTick();
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
