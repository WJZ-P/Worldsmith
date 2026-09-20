package com.wjz.worldsmith.client.story;

import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import com.wjz.worldsmith.client.quest.QuestJournalClient;
import com.wjz.worldsmith.client.quest.QuestJournalScreen;
import com.wjz.worldsmith.client.quest.QuestClientTestAccess;
import com.wjz.worldsmith.client.quest.WorldArrivalOverlay;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.content.quest.QuestProtocol;
import com.wjz.worldsmith.content.quest.server.QuestRuntime;
import com.wjz.worldsmith.content.story.WorldStoryRuntime;
import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.examples.ImmersiveVillageExample;
import com.wjz.worldsmith.core.story.StoryFactRef;
import com.wjz.worldsmith.core.structure.BuildPos;
import com.wjz.worldsmith.core.structure.StructureGeometryCompiler;
import com.wjz.worldsmith.worldgen.CompiledPack;
import com.wjz.worldsmith.worldgen.WorldsmithStructureTemplates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.ScreenNarrationCollector;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Actual exported markers, native interaction packets and UI choices in a new isolated test save. */
public final class StoryClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var pack = ImmersiveVillageExample.create();
        var names = new LinkedHashMap<String, String>();
        pack.getBiomes().getBiomes().forEach(b -> names.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
        var screenshots = new ArrayList<Path>();
        context.getInput().resizeWindow(1120, 700);
        context.runOnClient(client -> { client.options.guiScale().set(2); client.options.setCameraType(CameraType.FIRST_PERSON); client.options.showSubtitles().set(true); });
        try (var world = context.worldBuilder().create()) {
            var prepared = WorldContentRuntime.prepare(pack, names);
            await(context, context.computeOnClient(client -> WorldContentClientRuntime.prepare(prepared).activate()));
            // Exercise the real startup race deterministically: resources are active, but this level is not bound yet.
            // The ordinary journal-open intent must consume the server's empty-scope UNAVAILABLE reply promptly.
            context.runOnClient(client -> StoryJournalClient.open());
            context.waitFor(client -> !StoryJournalClient.pending(), 120);
            context.runOnClient(client -> check(StoryJournalClient.snapshot() == null, "Early negative acknowledgement must not invent a story snapshot"));
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitForScreen(null);
            BlockPos base = world.getServer().computeOnServer(server -> {
                server.setDifficulty(Difficulty.PEACEFUL, true);
                var level = server.overworld(); WorldContentRuntime.bindLevel(level, prepared);
                var clock = level.registryAccess().get(WorldClocks.OVERWORLD).orElseThrow();
                server.clockManager().setTotalTicks(clock, 12000); server.clockManager().setPaused(clock, true);
                var player = server.getPlayerList().getPlayers().getFirst(); player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();
                var at = player.blockPosition().offset(-12, -1, 8);
                for (int x = at.getX() >> 4; x <= (at.getX() + 24) >> 4; x++) for (int z = at.getZ() >> 4; z <= (at.getZ() + 62) >> 4; z++) level.getChunk(x, z);
                // Direct template fixtures do not run the production terrain-fit foundation pass.
                // Supply its actual physical support rather than leaving gravity blocks suspended in a test void.
                for (int x = 0; x < 25; x++) for (int z = 0; z < 63; z++) level.setBlock(at.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                var geometry = StructureGeometryCompiler.compile(pack.getStructures().getStructures().getFirst().getBlueprint());
                var template = new StructureTemplate(); template.load(BuiltInRegistries.BLOCK, WorldsmithStructureTemplates.encode(geometry, level.registryAccess(), CompiledPack.scoped(pack)));
                check(template.placeInWorld(level, at, at, new StructurePlaceSettings().setIgnoreEntities(false), level.getRandom(), Block.UPDATE_ALL), "Authored village template did not place");
                stand(player, at(at, ImmersiveVillageExample.ENTRY_POSITION)); return at;
            });
            world.getConnection().waitForChunksDownload();
            Supplier<QuestNativeState> questProbe = () -> world.getServer().computeOnServer(server -> questState(server.getPlayerList().getPlayers().getFirst()));
            var bounds = new AABB(Vec3.atLowerCornerOf(base.offset(-3, -2, -3)), Vec3.atLowerCornerOf(base.offset(28, 14, 66)));
            awaitServer(context, () -> world.getServer().computeOnServer(server -> server.overworld().getEntitiesOfClass(CreatureEntity.class, bounds).size() == 3), 400);
            Map<String, Integer> residents = world.getServer().computeOnServer(server -> {
                var result = new LinkedHashMap<String, Integer>();
                server.overworld().getEntitiesOfClass(CreatureEntity.class, bounds).forEach(e -> result.put(e.creatureId(), e.getId())); return result;
            });
            context.waitFor(client -> StoryJournalClient.snapshot() != null && StoryJournalClient.hasDiscoveredPlace(ImmersiveVillageExample.VILLAGE), 400);
            context.runOnClient(client -> {
                check(!StoryJournalClient.hasDiscoveredPlace(ImmersiveVillageExample.RUIN), "Initial snapshot disclosed the unseen ruin");
                check(StoryJournalClient.snapshot().knowledge().isEmpty(), "Initial snapshot disclosed future knowledge");
            });
            context.getInput().lookAt(at(base, ImmersiveVillageExample.LAMP_POSITION));
            screenshots.add(capture(context, "story-01-native-village-arrival", null));
            if (context.computeOnClient(client -> WorldArrivalOverlay.isVisible())) {
                context.runOnClient(client -> check(!StoryJournalClient.topRightHudUnobstructed(), "Story HUD must yield to the actual arrival overlay"));
                context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                context.waitFor(client -> !WorldArrivalOverlay.isVisible() && client.gui.screen() == null);
            }

            world.getServer().runOnServer(server -> stand(server.getPlayerList().getPlayers().getFirst(), at(base, ImmersiveVillageExample.KEEPER_POSITION).east(2)));
            interact(context, residents.get("keeper_resident"));
            context.waitForScreen(StoryDialogueScreen.class);
            screenshots.add(capture(context, "story-02-dialogue-english", StoryDialogueScreen.class));
            choose(context, "hear_history", "history");
            screenshots.add(capture(context, "story-03-heard-account", StoryDialogueScreen.class));
            choose(context, "depart", null);
            claimQuest(context, "借一盏归灯", ImmersiveVillageExample.INTRO, questProbe);
            awaitServer(context, () -> world.getServer().computeOnServer(server -> keyCount(server.getPlayerList().getPlayers().getFirst()) == 1), 100);
            context.runOnClient(client -> StoryJournalClient.open());
            context.waitForScreen(StoryJournalScreen.class);
            context.runOnClient(client -> check(StoryJournalClient.snapshot().knowledge().stream().noneMatch(k -> k.id().startsWith("living_")), "Journal leaked the unchosen future"));
            screenshots.add(capture(context, "story-04-discovered-journal", StoryJournalScreen.class));
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitForScreen(null);

            // Lose the actual quest reward through the ordinary vanilla drop packet, not by changing a story fact.
            context.runOnClient(client -> { client.player.getInventory().setSelectedSlot(0); check(client.player.drop(false), "Expected the actual carried key to drop"); });
            awaitServer(context, () -> world.getServer().computeOnServer(server -> keyCount(server.getPlayerList().getPlayers().getFirst()) == 0), 100);
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var chest = (Container)server.overworld().getBlockEntity(at(base, ImmersiveVillageExample.SUPPLIES_POSITION));
                check(chest != null && chest.getItem(0).is(Items.COPPER_INGOT), "Real supply chest is missing its authored copper");
                check(player.getInventory().add(chest.removeItem(0, 3)), "Could not collect copper from the authored chest");
                stand(player, at(base, ImmersiveVillageExample.ARTISAN_POSITION).west(2));
            });
            interact(context, residents.get("artisan_resident"));
            context.getInput().pressKey(GLFW.GLFW_KEY_TAB);
            context.runOnClient(client -> {
                var choice = StoryJournalClient.snapshot().dialogue().choices().stream().filter(c -> c.id().equals("replace_key")).findFirst().orElseThrow();
                check(!choice.details().isBlank() && choice.details().contains("3"), "The actual trade must disclose its cost before commitment");
                var button = buttons(client.gui.screen()).stream().filter(b -> b.getMessage().getString().endsWith(choice.label())).findFirst().orElseThrow();
                var narration = new ScreenNarrationCollector(); narration.update(button::updateNarration);
                check(narration.collectNarrationText(true).contains(choice.details()), "Focused choice narration omitted canonical trade terms");
            });
            screenshots.add(capture(context, "story-05a-trade-costs-before-commit", StoryDialogueScreen.class));
            choose(context, "replace_key", "recast");
            awaitServer(context, () -> world.getServer().computeOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                return keyCount(player) == 1 && player.getInventory().countItem(Items.COPPER_INGOT) == 0
                    && WorldStoryRuntime.value(player, new StoryFactRef("keys_recast")).equals(new AbilityValue.NumberValue(1));
            }), 100);
            screenshots.add(capture(context, "story-05-lost-key-recast", StoryDialogueScreen.class));
            choose(context, "thank", null);

            world.getServer().runOnServer(server -> stand(server.getPlayerList().getPlayers().getFirst(), at(base, ImmersiveVillageExample.RUIN_POSITION).north(3)));
            context.waitFor(client -> StoryJournalClient.hasDiscoveredPlace(ImmersiveVillageExample.RUIN), 300);
            context.runOnClient(client -> check(StoryJournalClient.trackPlace(ImmersiveVillageExample.RUIN), "Actual discovered ruin could not be selected"));
            context.getInput().lookAt(at(base, new BuildPos(16, 6, 59)));
            context.runOnClient(client -> {
                check(!WorldArrivalOverlay.isVisible(), "Arrival must be dismissed by the ordinary Escape input before inspecting the route HUD");
                if (StoryJournalClient.nativeTopRightToastVisible()) {
                    check(!StoryJournalClient.topRightHudUnobstructed(), "Story HUD must yield while the actual native recipe toast is visible");
                    System.out.println("[StoryClient] Native toast arbitration verified before its natural expiry");
                }
            });
            // Production arbitration above hides only the overlapping story cards, not the native notification.
            // Capture the resumed route after its actual toast animation expires; never clear or suppress the toast in a fixture.
            context.waitFor(client -> StoryJournalClient.topRightHudUnobstructed() && StoryJournalClient.trackedPlace() != null
                && client.gui.screen() == null && client.gui.overlay() == null && !client.gui.hud.isHidden(), 600);
            screenshots.add(capture(context, "story-06-actual-ruin-navigation", null));
            claimQuest(context, "循灰白石径而行", ImmersiveVillageExample.ROAD, questProbe);
            context.runOnClient(QuestJournalClient::open); context.waitForScreen(QuestJournalScreen.class);
            selectQuest(context, "让归路重新明亮"); clickKey(context, "worldsmith.quests.accept");
            screenshots.add(capture(context, "story-07-branch-confirmation", QuestJournalScreen.class));
            clickKey(context, "worldsmith.quests.branch_confirm");
            awaitServer(context, () -> world.getServer().computeOnServer(server -> WorldStoryRuntime.value(server.getPlayerList().getPlayers().getFirst(), new StoryFactRef(ImmersiveVillageExample.ROUTE)).equals(new AbilityValue.NumberValue(1))), 100);
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitForScreen(null);

            world.getServer().runOnServer(server -> stand(server.getPlayerList().getPlayers().getFirst(), at(base, ImmersiveVillageExample.KEEPER_POSITION).east(2)));
            interact(context, residents.get("keeper_resident")); choose(context, "discuss_beacon", "decision");
            choose(context, "rekindle", "light_committed");
            awaitServer(context, () -> world.getServer().computeOnServer(server -> server.overworld().getBlockState(at(base, ImmersiveVillageExample.LAMP_POSITION)).is(Blocks.SEA_LANTERN)), 160);
            choose(context, "close", null); claimQuest(context, "让归路重新明亮", ImmersiveVillageExample.REKINDLE, questProbe);
            interact(context, residents.get("keeper_resident")); choose(context, "return_light", "ending_light");
            screenshots.add(capture(context, "story-08-world-remembers", StoryDialogueScreen.class));

            await(context, context.computeOnClient(client -> { client.options.languageCode = "zh_cn"; client.getLanguageManager().setSelected("zh_cn"); return client.reloadResourcePacks(); }));
            context.runOnClient(client -> client.options.guiScale().set(1)); context.getInput().resizeWindow(320, 180);
            context.waitFor(client -> client.gui.screen() instanceof StoryDialogueScreen screen && screen.width == 320 && screen.height == 180);
            screenshots.add(capture(context, "story-09-compact-dialogue-chinese", StoryDialogueScreen.class));
            context.getInput().pressKey(GLFW.GLFW_KEY_END);
            screenshots.add(capture(context, "story-10-compact-dialogue-bottom", StoryDialogueScreen.class));
            choose(context, "farewell", null);
            context.runOnClient(client -> StoryJournalClient.open()); context.waitForScreen(StoryJournalScreen.class);
            screenshots.add(capture(context, "story-11-compact-discoveries", StoryJournalScreen.class));
            context.runOnClient(client -> {
                String label = Component.translatable("worldsmith.story.places_tab", StoryJournalClient.snapshot().places().size()).getString();
                buttons(client.gui.screen()).stream().filter(b -> b.getMessage().getString().equals(label)).findFirst().orElseThrow()
                    .onPress(new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
            });
            screenshots.add(capture(context, "story-12-compact-actual-places", StoryJournalScreen.class));
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitForScreen(null);
            context.runOnClient(client -> client.options.guiScale().set(2)); context.getInput().resizeWindow(1120, 700);
            claimQuest(context, "再听一次村庄的回答", ImmersiveVillageExample.RETURN, questProbe);
            awaitServer(context, () -> world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getInventory().countItem(Items.CLOCK) == 1), 100);
            world.getServer().runOnServer(server -> stand(server.getPlayerList().getPlayers().getFirst(), at(base, ImmersiveVillageExample.VILLAGE_POSITION).north(3)));
            context.getInput().lookAt(at(base, ImmersiveVillageExample.LAMP_POSITION));
            screenshots.add(capture(context, "story-13-returned-village-light", null));
            context.runOnClient(client -> StoryJournalClient.open()); context.waitForScreen(StoryJournalScreen.class);
            // Leave both a player screen and active soundscape alive for the normal disconnect cleanup test.
        }
        context.waitFor(client -> client.level == null && WorldContentClientRuntime.activeScope() == null);
        context.runOnClient(client -> check(StoryJournalClient.snapshot() == null && StorySoundscapeClient.activeLayers() == 0
            && !(client.gui.screen() instanceof StoryJournalScreen) && !(client.gui.screen() instanceof StoryDialogueScreen), "Normal disconnect retained story UI or sound state"));
        try {
            for (Path screenshot : screenshots) check(Files.isRegularFile(screenshot) && Files.size(screenshot) > 0, "Missing real screenshot " + screenshot);
            Path report = Path.of(System.getProperty("worldsmith.story.client-report")); Files.createDirectories(report.getParent());
            Files.writeString(report, "PASS immersive story client\nActual exported markers, native entity interaction packets, choice tokens, progressive journal, key drop/recast, exclusive route, persistent lamp change, real arrival/native-toast HUD arbitration, compact Chinese and disconnect cleanup\n"
                + screenshots.stream().map(Path::toString).collect(java.util.stream.Collectors.joining("\n")) + "\n");
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private static void interact(ClientGameTestContext context, int entityId) {
        context.waitFor(client -> client.gui.screen() == null && client.player != null && client.level != null && client.level.getEntity(entityId) instanceof CreatureEntity
            && client.player.distanceToSqr(client.level.getEntity(entityId)) < 12, 160);
        context.runOnClient(client -> {
            var entity = client.level.getEntity(entityId); check(entity != null, "Native resident has not arrived");
            client.gameMode.interact(client.player, entity, new EntityHitResult(entity, entity.getEyePosition()), InteractionHand.MAIN_HAND);
        });
        context.waitFor(client -> client.gui.screen() instanceof StoryDialogueScreen && StoryJournalClient.snapshot() != null && StoryJournalClient.snapshot().dialogue() != null, 200);
    }
    private static void choose(ClientGameTestContext context, String id, String next) {
        try {
            context.waitFor(client -> !StoryJournalClient.pending() && StoryJournalClient.snapshot() != null && StoryJournalClient.snapshot().dialogue() != null, 120);
        } catch (AssertionError failure) {
            throw new AssertionError("Dialogue choice not ready before click: " + id + "; " + context.computeOnClient(client -> {
                var state = StoryJournalClient.snapshot();
                return "pending=" + StoryJournalClient.pending() + "; activeScope=" + WorldContentClientRuntime.activeScope()
                    + "; snapshotRequest=" + (state == null ? null : state.requestId()) + "; revision=" + (state == null ? null : state.revision())
                    + "; node=" + (state == null || state.dialogue() == null ? null : state.dialogue().nodeId())
                    + "; feedback=" + (state == null ? null : state.feedback()) + "; notice=" + StoryJournalClient.notice().getString()
                    + "; screen=" + client.gui.screen() + "; position=" + (client.player == null ? null : client.player.position());
            }), failure);
        }
        context.runOnClient(client -> {
            var dialogue = StoryJournalClient.snapshot().dialogue();
            String text = dialogue.choices().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow().label();
            var button = buttons(client.gui.screen()).stream().filter(b -> b.getMessage().getString().endsWith(text)).findFirst().orElseThrow();
            check(button.active, "Choice is not currently enabled: " + id); button.onPress(new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        });
        context.waitFor(client -> !StoryJournalClient.pending() && StoryJournalClient.snapshot() != null
            && (next == null ? StoryJournalClient.snapshot().dialogue() == null && !(client.gui.screen() instanceof StoryDialogueScreen)
                : StoryJournalClient.snapshot().dialogue() != null && StoryJournalClient.snapshot().dialogue().nodeId().equals(next)), 200);
    }
    private static void claimQuest(ClientGameTestContext context, String title, String id, Supplier<QuestNativeState> serverProbe) {
        context.runOnClient(QuestJournalClient::open); context.waitForScreen(QuestJournalScreen.class);
        // Opening the journal queues SYNC; do not mistake an old, briefly enabled render button for a current snapshot.
        context.waitFor(client -> !QuestClientTestAccess.pending() && QuestClientTestAccess.snapshot() != null, 200);
        for (int attempt = 1; attempt <= 3; attempt++) {
            selectQuest(context, title);
            var before = context.computeOnClient(client -> QuestClientTestAccess.snapshot());
            check(status(before, id) == QuestProtocol.Status.READY, "Claim must start from an authoritative READY entry: " + before);
            clickKey(context, "worldsmith.quests.claim");
            try { context.waitFor(client -> !QuestClientTestAccess.pending(), 200); }
            catch (AssertionError timeout) {
                throw new AssertionError("Claim response did not arrive: " + id + "; client=" + context.computeOnClient(client -> QuestClientTestAccess.snapshot())
                    + "; notice=" + context.computeOnClient(client -> QuestClientTestAccess.notice()) + "; server=" + serverProbe.get(), timeout);
            }
            var after = context.computeOnClient(client -> QuestClientTestAccess.snapshot());
            var actual = serverProbe.get();
            if (status(after, id) == QuestProtocol.Status.CLAIMED && status(actual.journal(), id) == QuestProtocol.Status.CLAIMED) {
                System.out.println("[StoryClient] CLAIM verified " + id + "; client revision=" + after.revision() + "; server=" + actual);
                context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitForScreen(null); return;
            }
            // A server-confirmed stale revision is a rejection with no reward commit. Re-read the current entry and
            // explicitly click again as a player would; never replay a timeout, arbitrary error, or accepted claim.
            if (status(actual.journal(), id) != QuestProtocol.Status.CLAIMED && context.computeOnClient(client -> QuestClientTestAccess.staleRevisionFeedback())) {
                System.out.println("[StoryClient] CLAIM stale; explicit fresh-choice retry " + id + "; before=" + before + "; after=" + after + "; server=" + actual);
                context.waitTick(); continue;
            }
            var screenshot = context.takeScreenshot("story-claim-failed-" + id + "-" + attempt);
            throw new AssertionError("Claim was not committed: " + id + "; before=" + before + "; client=" + after
                + "; notice=" + context.computeOnClient(client -> QuestClientTestAccess.notice()) + "; server=" + actual + "; screenshot=" + screenshot);
        }
        throw new AssertionError("Claim kept receiving stale revisions: " + id + "; client=" + context.computeOnClient(client -> QuestClientTestAccess.snapshot()) + "; server=" + serverProbe.get());
    }
    private static QuestProtocol.Status status(QuestProtocol.Snapshot snapshot, String id) {
        return snapshot == null ? null : snapshot.quests().stream().filter(q -> q.id().equals(id)).findFirst().map(QuestProtocol.Entry::status).orElse(null);
    }
    private record QuestNativeState(QuestProtocol.Snapshot journal, List<String> inventory) {}
    private static QuestNativeState questState(ServerPlayer player) {
        var inventory = new ArrayList<String>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var item = player.getInventory().getItem(slot);
            if (!item.isEmpty()) inventory.add(slot + ": " + item.getHoverName().getString() + " x" + item.getCount() + "; identity=" + item.get(CustomItemRuntime.identityComponent()));
        }
        return new QuestNativeState(QuestRuntime.journalSnapshot(player), List.copyOf(inventory));
    }
    private static void selectQuest(ClientGameTestContext context, String title) {
        for (int i = 0; i < 200; i++) {
            boolean selected = context.computeOnClient(client -> {
                var match = buttons(client.gui.screen()).stream().filter(b -> b.getMessage().getString().contains(" | ") && b.getMessage().getString().endsWith(title)).findFirst();
                if (match.isEmpty()) return false; match.get().onPress(new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)); return true;
            });
            if (selected) return;
            context.runOnClient(client -> {
                var next = buttons(client.gui.screen()).stream().filter(b -> b.active && b.getMessage().getString().equals(">")).findFirst();
                if (next.isPresent()) next.get().onPress(new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
                else buttons(client.gui.screen()).stream().filter(b -> b.active && b.getMessage().getString().equals("<")).findFirst()
                    .ifPresent(button -> button.onPress(new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)));
            });
            context.waitTick();
        }
        throw new AssertionError("Quest did not appear in bounded visible pages: " + title);
    }
    private static void clickKey(ClientGameTestContext context, String key) {
        context.waitFor(client -> buttons(client.gui.screen()).stream().anyMatch(b -> b.active && b.getMessage().getString().equals(Component.translatable(key).getString())), 200);
        context.clickScreenButton(key); context.waitTick();
    }
    private static List<Button> buttons(Screen screen) { return screen == null ? List.of() : screen.children().stream().filter(c -> c instanceof Button).map(c -> (Button)c).toList(); }
    private static Path capture(ClientGameTestContext context, String name, Class<? extends Screen> expected) {
        context.waitFor(client -> client.gui.overlay() == null && (expected == null ? client.gui.screen() == null : expected.isInstance(client.gui.screen())), 200);
        context.waitTicks(3); return context.takeScreenshot(name);
    }
    private static void stand(ServerPlayer player, BlockPos at) { player.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5); player.setDeltaMovement(0, 0, 0); }
    private static BlockPos at(BlockPos origin, BuildPos local) { return origin.offset(local.getX(), local.getY(), local.getZ()); }
    private static int keyCount(ServerPlayer player) {
        int count = 0; for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i); var definition = CustomItemRuntime.definition(player.level(), stack);
            if (definition != null && definition.getId().equals(ImmersiveVillageExample.KEY)) count += stack.getCount();
        } return count;
    }
    private static void await(ClientGameTestContext context, CompletableFuture<Void> future) {
        context.waitFor(client -> future.isDone(), 20 * 120); future.join(); context.waitFor(client -> client.gui.overlay() == null, 20 * 120); context.waitTicks(3);
    }
    private static void awaitServer(ClientGameTestContext context, BooleanSupplier condition, int ticks) {
        for (int i = 0; i < ticks; i++) { if (condition.getAsBoolean()) return; context.waitTick(); }
        throw new AssertionError("Actual native story condition did not arrive within " + ticks + " ticks");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
