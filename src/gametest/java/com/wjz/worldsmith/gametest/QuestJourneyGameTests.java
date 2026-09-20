package com.wjz.worldsmith.gametest;

import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.interaction.WorldMechanicSavedData;
import com.wjz.worldsmith.content.quest.QuestProtocol;
import com.wjz.worldsmith.content.quest.GeneratedQuestAdvancements;
import com.wjz.worldsmith.content.quest.server.QuestPlayerState;
import com.wjz.worldsmith.content.quest.server.QuestRuntime;
import com.wjz.worldsmith.content.story.StorySavedData;
import com.wjz.worldsmith.content.story.WorldStoryRuntime;
import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.ability.AbilityValues;
import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.examples.MechanicDiscoveryExample;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import com.wjz.worldsmith.core.story.*;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueOutput;

/** Actual native inventory, player attachment and story authority, not client-authored progress. */
public final class QuestJourneyGameTests {
    private static final StoryCondition ALWAYS = StoryCondition.Always.INSTANCE;
    private static final String OPEN = "opened", ONCE = "claim_count", ACCEPTED = "accept_count", ROUTE = "selected_route";

    @GameTest(environment = "worldsmith_mechanics_smoke:quest_journey", maxTicks = 100, skyAccess = true, padding = 20)
    public void discoveriesBranchesConsequencesAndInventoryAreAuthoritative(GameTestHelper helper) {
        var level = helper.getLevel(); var pack = fixture();
        check(WorldContentRuntime.boundLevelCount() == 0, "Quest test overlapped another bound pack");
        var names = new LinkedHashMap<String, String>();
        pack.getBiomes().getBiomes().forEach(b -> names.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
        var prepared = WorldContentRuntime.prepare(pack, names);
        var storage = level.getServer().overworld().getDataStorage();
        var priorStory = storage.get(StorySavedData.TYPE);
        var priorMechanic = level.getDataStorage().get(WorldMechanicSavedData.TYPE);
        storage.set(StorySavedData.TYPE, new StorySavedData(StorySavedData.State.empty(pack.getComputedId())));
        level.getDataStorage().set(WorldMechanicSavedData.TYPE, new WorldMechanicSavedData());
        ServerPlayer player = null; boolean bound = false;
        try {
            WorldContentRuntime.bindLevel(level, prepared); bound = true;
            player = helper.makeMockServerPlayerInLevel(); player.setGameMode(GameType.SURVIVAL);
            GameType.SURVIVAL.updatePlayerAbilities(player.getAbilities()); player.getInventory().clearContent(); player.setNoGravity(true);
            var initial = QuestRuntime.journalSnapshot(player);
            check(initial.feedback() == QuestProtocol.Feedback.NONE && initial.quests().stream().map(QuestProtocol.Entry::id).toList().equals(List.of("first")),
                "A future name/objective leaked before discovery, or automatic acceptance failed: " + initial);
            check(number(player, ACCEPTED) == 1, "Automatic acceptance consequence did not commit exactly once");
            QuestRuntime.journalSnapshot(player); check(number(player, ACCEPTED) == 1, "Reading the journal replayed onAccept");

            int nonce = 1;
            player.getInventory().setItem(0, new ItemStack(Items.STICK)); player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 2));
            act(player, "first", QuestProtocol.ActionKind.DELIVER, nonce++);
            check(count(player, Items.DIAMOND) == 2, "Ordinary delivery consumed optional valuable goods without optional intent");
            var ready = QuestRuntime.journalSnapshot(player);
            check(entry(ready, "first").status() == QuestProtocol.Status.READY, "An optional objective blocked the required claim condition");
            act(player, "first", QuestProtocol.ActionKind.DELIVER_OPTIONAL, nonce++);
            check(count(player, Items.DIAMOND) == 1, "Explicit optional delivery did not consume exactly its one item");

            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            act(player, "first", QuestProtocol.ActionKind.CLAIM, nonce++);
            check(number(player, ONCE) == 0 && entry(QuestRuntime.journalSnapshot(player), "first").status() == QuestProtocol.Status.READY,
                "Full inventory partially committed a reward consequence or claim");
            player.getInventory().setItem(0, ItemStack.EMPTY);
            long claimRevision = QuestRuntime.journalSnapshot(player).revision();
            var claim = new QuestProtocol.Action(pack.getComputedId(), "first", QuestProtocol.ActionKind.CLAIM, nonce++, claimRevision);
            QuestRuntime.handle(player, claim);
            check(number(player, ONCE) == 1 && count(player, Items.EMERALD) == 1, "Claim did not commit both fact and reward");
            var hidden = QuestRuntime.journalSnapshot(player);
            check(hidden.quests().size() == 1 && entry(hidden, "first").status() == QuestProtocol.Status.CLAIMED && !hidden.campaignComplete(),
                "The client-visible all-claimed prefix falsely completed the campaign");
            QuestRuntime.handle(player, claim);
            check(number(player, ONCE) == 1 && count(player, Items.EMERALD) == 1, "Replayed nonce duplicated reward or onClaim");

            change(player, OPEN, AbilityValues.bool(true));
            var discovered = QuestRuntime.journalSnapshot(player);
            check(discovered.quests().stream().map(QuestProtocol.Entry::id).toList().containsAll(List.of("first", "later", "side")),
                "A shared fact did not discover its real prerequisite-reachable chapters");
            act(player, "side", QuestProtocol.ActionKind.DECLINE, nonce++);
            check(entry(QuestRuntime.journalSnapshot(player), "side").status() == QuestProtocol.Status.DECLINED, "Manual offer deferral did not persist");
            change(player, OPEN, AbilityValues.bool(false));
            check(entry(QuestRuntime.journalSnapshot(player), "later").status() == QuestProtocol.Status.ACTIVE,
                "Previously discovered knowledge disappeared, or fact progress was cached instead of observed");
            change(player, OPEN, AbilityValues.bool(true));
            act(player, "later", QuestProtocol.ActionKind.CLAIM, nonce++);
            var crossroads = QuestRuntime.journalSnapshot(player);
            check(entry(crossroads, "left").status() == QuestProtocol.Status.AVAILABLE && entry(crossroads, "right").status() == QuestProtocol.Status.AVAILABLE,
                "Both explicit alternatives were not offered after their predecessor");
            act(player, "left", QuestProtocol.ActionKind.ACCEPT, nonce++);
            check(number(player, ROUTE) == 1 && entry(QuestRuntime.journalSnapshot(player), "right").status() == QuestProtocol.Status.EXCLUDED,
                "Explicit acceptance did not commit branch selection and story consequence together");
            act(player, "right", QuestProtocol.ActionKind.ACCEPT, nonce++);
            check(number(player, ROUTE) == 1, "A rejected branch modified the chosen route");

            var beforeStale = QuestRuntime.journalSnapshot(player);
            QuestRuntime.storyChanged(player);
            QuestRuntime.handle(player, new QuestProtocol.Action(pack.getComputedId(), "left", QuestProtocol.ActionKind.CLAIM, nonce++, beforeStale.revision()));
            check(entry(QuestRuntime.journalSnapshot(player), "left").status() == QuestProtocol.Status.READY, "Stale revision unexpectedly claimed an active branch");
            act(player, "left", QuestProtocol.ActionKind.CLAIM, nonce++);
            var merged = QuestRuntime.journalSnapshot(player);
            check(entry(merged, "ending").status() == QuestProtocol.Status.READY && !merged.campaignComplete(), "ANY merge did not remain a required final chapter");
            act(player, "ending", QuestProtocol.ActionKind.CLAIM, nonce++);
            var complete = QuestRuntime.journalSnapshot(player);
            check(complete.campaignComplete(), "Rejected alternative or deferred optional side quest deadlocked completion");
            check(!((AbilityValue.BoolValue)WorldStoryRuntime.value(player, new StoryFactRef(OPEN))).getValue()
                && entry(complete, "later").objectives().getFirst().progress() == 1,
                "A claim that changes an old objective fact erased historical completion");
            act(player, "ending", QuestProtocol.ActionKind.TRACK, nonce++);
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess()); player.saveWithoutId(output);
            var tag = output.buildResult().getCompoundOrEmpty(AttachmentTarget.NBT_ATTACHMENT_KEY).get("worldsmith:quest_progress");
            check(tag != null, "Native player NBT omitted quest progress");
            var persisted = QuestPlayerState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
            check(persisted.schemaVersion() == 3 && persisted.selectedBranches().get("route").equals("left") && persisted.trackedQuest().equals("ending")
                && persisted.quests().get("later").counts().equals(List.of(0)), "NBT lost choices/tracking or duplicated story fact counters");

            var resources = GeneratedQuestAdvancements.serverResources(pack, "Quest journey");
            for (Quest q : pack.getQuests().getQuests()) {
                var id = GeneratedQuestAdvancements.taskId(pack.getComputedId(), q.getId());
                var document = JsonParser.parseString(new String(resources.get("data/" + id.getNamespace() + "/advancement/" + id.getPath() + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
                check(document.getAsJsonObject("display").get("hidden").getAsBoolean()
                    && document.get("parent").getAsString().equals(GeneratedQuestAdvancements.rootId(pack.getComputedId()).toString()),
                    "A native advancement exposed a future ancestor or invented a single-parent quest dependency");
            }
            System.out.println("[QuestJourney] PASS native discovery + explicit optional cost + full-inventory fact/reward atomicity + nonce/revision + branch/ANY completion + player NBT");
            helper.succeed();
        } finally {
            if (player != null) {
                level.getServer().getPlayerList().remove(player); player.discard();
                check(!level.getServer().getPlayerList().getPlayers().contains(player)
                    && level.getServer().getPlayerList().getPlayer(player.getUUID()) != player,
                    "Quest fixture retained its mock player in the global player list");
            }
            if (bound) WorldContentRuntime.unbindLevel(level);
            storage.set(StorySavedData.TYPE, priorStory == null ? new StorySavedData(StorySavedData.State.empty(pack.getComputedId())) : priorStory);
            level.getDataStorage().set(WorldMechanicSavedData.TYPE, priorMechanic == null ? new WorldMechanicSavedData() : priorMechanic);
        }
    }
    private static void act(ServerPlayer player, String quest, QuestProtocol.ActionKind kind, int nonce) {
        var snapshot = QuestRuntime.journalSnapshot(player);
        QuestRuntime.handle(player, new QuestProtocol.Action(snapshot.scope(), quest, kind, nonce, snapshot.revision()));
    }
    private static QuestProtocol.Entry entry(QuestProtocol.Snapshot snapshot, String id) {
        return snapshot.quests().stream().filter(q -> q.id().equals(id)).findFirst().orElseThrow(() -> new IllegalStateException("Missing discovered quest " + id + " in " + snapshot));
    }
    private static double number(ServerPlayer player, String fact) { return ((AbilityValue.NumberValue)WorldStoryRuntime.value(player, new StoryFactRef(fact))).getValue(); }
    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0; for (int slot = 0; slot < 36; slot++) { var stack = player.getInventory().getItem(slot); if (stack.is(item)) count += stack.getCount(); } return count;
    }
    private static void change(ServerPlayer player, String id, AbilityValue value) {
        WorldStoryRuntime.prepareChanges(player, List.of(new StoryFactChange(new StoryFactRef(id), value))).commit();
        WorldStoryRuntime.changed(player); QuestRuntime.storyChanged(player);
    }
    private static Quest quest(String id, List<String> parents, List<QuestObjective> goals, List<QuestReward> rewards,
            boolean optional, boolean manual, String group, StoryCondition discovery, List<StoryFactChange> accept, List<StoryFactChange> claim, QuestPrerequisiteMode mode) {
        return new Quest(id, id, "Native authoritative journey", parents, goals, rewards, "journey", optional, manual, group, discovery, ALWAYS, accept, claim, null, mode);
    }
    private static StoryFactChange set(String id, AbilityValue value) { return new StoryFactChange(new StoryFactRef(id), value); }
    private static StoryFactChange add(String id) { return new StoryFactChange(new StoryFactRef(id), AbilityValues.number(1), StoryChangeMode.ADD); }
    private static WorldsmithPack fixture() {
        var base = MechanicDiscoveryExample.create();
        var open = new StoryCondition.Compare(new StoryFactRef(OPEN), StoryComparison.EQ, AbilityValues.bool(true));
        var facts = List.of(new StoryFact(OPEN, StoryFactScope.WORLD, StoryFactType.BOOL, AbilityValues.bool(false)),
            new StoryFact(ONCE, StoryFactScope.PLAYER, StoryFactType.NUMBER, AbilityValues.number(0), 0, 20),
            new StoryFact(ACCEPTED, StoryFactScope.PLAYER, StoryFactType.NUMBER, AbilityValues.number(0), 0, 20),
            new StoryFact(ROUTE, StoryFactScope.PLAYER, StoryFactType.NUMBER, AbilityValues.number(0), 0, 2));
        var story = new StoryLibrary(2, facts);
        var quests = new QuestLibrary(2, List.of(
            quest("first", List.of(), List.of(new QuestObjective.DeliverItem("minecraft:stick"), new QuestObjective.DeliverItem("minecraft:diamond", 1, true)),
                List.of(new QuestReward("minecraft:emerald")), false, false, null, ALWAYS, List.of(add(ACCEPTED)), List.of(add(ONCE)), QuestPrerequisiteMode.ALL),
            quest("later", List.of("first"), List.of(new QuestObjective.Fact("A remembered event", open)), List.of(), false, false, null, open, List.of(), List.of(), QuestPrerequisiteMode.ALL),
            quest("left", List.of("later"), List.of(new QuestObjective.Fact("Left promise", ALWAYS)), List.of(), false, true, "route", ALWAYS, List.of(set(ROUTE, AbilityValues.number(1))), List.of(), QuestPrerequisiteMode.ALL),
            quest("right", List.of("later"), List.of(new QuestObjective.Fact("Right promise", ALWAYS)), List.of(), false, true, "route", ALWAYS, List.of(set(ROUTE, AbilityValues.number(2))), List.of(), QuestPrerequisiteMode.ALL),
            quest("ending", List.of("left", "right"), List.of(new QuestObjective.Fact("Return", ALWAYS)), List.of(), false, false, null, ALWAYS, List.of(), List.of(set(OPEN, AbilityValues.bool(false))), QuestPrerequisiteMode.ANY),
            quest("side", List.of(), List.of(new QuestObjective.Fact("A side story", ALWAYS)), List.of(), true, true, null, open, List.of(), List.of(), QuestPrerequisiteMode.ALL)
        ));
        var theme = new WorldTheme(1, "main", "Native quest journey", "Choices persist in this test world", "A traveler",
            List.of("Only accepted authoritative actions change the journey"), "Two alternatives and one return",
            List.of(new WorldNarrativeBeat("journey", "Journey", "Native story journey", quests.getQuests().stream().map(q -> new ContentKey("quest", q.getId())).toList())));
        return WorldContentBundleIO.create("Native quest journey", "Native authority fixture", base.getTerrain(), base.getBiomes(), base.getFeatures(), base.getStructures(), theme,
            base.getBlocks(), base.getCreatures(), base.getAssets(), base.getItems(), quests, base.getManifest().getRepresentativeContent(), base.getMechanics(), base.getAbilities(), story);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
