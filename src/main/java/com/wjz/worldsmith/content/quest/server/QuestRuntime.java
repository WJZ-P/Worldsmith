package com.wjz.worldsmith.content.quest.server;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldRewardItems;
import com.wjz.worldsmith.content.WorldInventoryTransaction;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.content.item.WorldItemIdentity;
import com.wjz.worldsmith.content.quest.QuestProtocol;
import com.wjz.worldsmith.content.story.WorldStoryRuntime;
import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.story.StoryCondition;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative discovered quest DAG. Story facts remain in the shared story ledger. */
public final class QuestRuntime {
    private static final Map<ServerLevel, Snapshot> WORLDS = new ConcurrentHashMap<>();
    private static final Set<LivingEntity> CREDITED_DEATHS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final QuestJournalUpdates DIRTY_JOURNALS = new QuestJournalUpdates();
    private static final Map<UUID, RequestNonce> REQUESTS = new ConcurrentHashMap<>();
    private static AttachmentType<QuestPlayerState> progressAttachment;
    private static boolean registered;
    private QuestRuntime() {}

    public static synchronized void register() {
        if (registered) return;
        progressAttachment = AttachmentRegistry.<QuestPlayerState>create(Worldsmith.id("quest_progress"), builder -> builder.persistent(QuestPlayerState.CODEC).copyOnDeath());
        QuestProtocol.registerTypes();
        if (!ServerPlayNetworking.registerGlobalReceiver(QuestProtocol.Action.TYPE, (action, context) -> handle(context.player(), action)))
            throw new IllegalStateException("The Worldsmith quest action channel is already registered");
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> afterDeath(entity));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            REQUESTS.remove(handler.player.getUUID()); sendJournal(handler.player, 0, QuestProtocol.Feedback.NONE, "");
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> requestJournal(newPlayer));
        ServerTickEvents.END_SERVER_TICK.register(QuestRuntime::flushJournals);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DIRTY_JOURNALS.remove(handler.player.getUUID()); REQUESTS.remove(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            DIRTY_JOURNALS.clear(server); REQUESTS.entrySet().removeIf(entry -> entry.getValue().server == server);
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) server.execute(() -> server.getPlayerList().getPlayers().forEach(QuestRuntime::requestJournal));
        });
        QuestAdvancementBridge.initialize(); registered = true;
    }

    /** Resolve every objective/reward before publication, including secret future content. */
    public static Snapshot prepare(WorldsmithPack pack, CreatureRuntime.Snapshot creatures, CustomItemRuntime.Snapshot items, WorldBlockBindings.Resolver blocks) {
        String scope = pack.getManifest().getId();
        if (!scope.equals(creatures.bundleHash()) || !scope.equals(items.bundleHash()) || !scope.equals(blocks.snapshot().getScope()))
            throw new IllegalArgumentException("Quest domains belong to different immutable world scopes");
        var library = QuestValidation.freeze(pack.getQuests());
        var storyErrors = QuestValidation.validateStory(library, pack.getStory());
        if (!storyErrors.isEmpty()) throw new IllegalArgumentException("Invalid quest story references: " + storyErrors);
        List<Quest> order = QuestValidation.ordered(library);
        Map<String, String> blockNames = new LinkedHashMap<>();
        pack.getBlocks().getBlocks().forEach(block -> blockNames.put("worldsmith:content/" + block.getId(), block.getDisplayName()));
        Map<String, String> mechanicNames = new LinkedHashMap<>();
        pack.getMechanics().getMechanics().forEach(mechanic -> mechanicNames.put(mechanic.getId(), clean(mechanic.getDisplayName(), 128)));
        Map<String, PreparedQuest> quests = new LinkedHashMap<>();
        for (Quest quest : order) {
            List<Objective> objectives = new ArrayList<>();
            for (QuestObjective objective : quest.getObjectives()) {
                if (objective instanceof QuestObjective.KillCreature kill) {
                    var creature = creatures.definitions().get(kill.getCreature());
                    if (creature == null) throw new IllegalArgumentException("Unknown quest creature: " + kill.getCreature());
                    objectives.add(new Objective("kill_creature", kill.getCreature(), clean(creature.getDisplayName(), 128), kill.getCount(), kill.getOptional(), null, null, null));
                } else if (objective instanceof QuestObjective.DeliverItem deliver) {
                    ItemStack prototype = WorldRewardItems.stack(deliver.getItem(), 1, blocks, items);
                    WorldItemIdentity identity = prototype.getItem() == CustomItemRuntime.host() ? prototype.get(CustomItemRuntime.identityComponent()) : null;
                    objectives.add(new Objective("deliver_item", deliver.getItem(), itemLabel(deliver.getItem(), prototype, blockNames, items), deliver.getCount(), deliver.getOptional(), prototype.getItem(), identity, null));
                } else if (objective instanceof QuestObjective.ActivateMechanic activate) {
                    String label = mechanicNames.get(activate.getMechanic());
                    if (label == null) throw new IllegalArgumentException("Unknown quest mechanic: " + activate.getMechanic());
                    objectives.add(new Objective("activate_mechanic", activate.getMechanic(), label, activate.getCount(), activate.getOptional(), null, null, null));
                } else if (objective instanceof QuestObjective.Fact fact) {
                    objectives.add(new Objective("fact", quest.getId() + "." + objectives.size(), fact.getLabel(), 1, fact.getOptional(), null, null, fact.getCondition()));
                } else throw new IllegalArgumentException("Unsupported native quest objective");
            }
            List<Reward> rewards = new ArrayList<>();
            for (var reward : quest.getRewards()) {
                ItemStack prototype = WorldRewardItems.stack(reward.getItem(), reward.getCount(), blocks, items);
                rewards.add(new Reward(reward.getItem(), reward.getCount(), itemLabel(reward.getItem(), prototype, blockNames, items)));
            }
            quests.put(quest.getId(), new PreparedQuest(quest, List.copyOf(objectives), List.copyOf(rewards)));
        }
        return new Snapshot(scope, clean(pack.getManifest().getDisplayName(), 160), quests, mechanicNames, items, blocks);
    }
    public static void bind(ServerLevel level, Snapshot snapshot) {
        Snapshot previous = WORLDS.putIfAbsent(Objects.requireNonNull(level), Objects.requireNonNull(snapshot));
        if (previous != null && (!previous.scope.equals(snapshot.scope) || !previous.quests.equals(snapshot.quests) || !previous.mechanicNames.equals(snapshot.mechanicNames)))
            throw new IllegalStateException("A running level already owns different immutable quests");
    }
    public static void unbind(ServerLevel level) { WORLDS.remove(level); }
    public static Snapshot snapshot(ServerLevel level) { return WORLDS.get(level); }

    /** Facts are observed asynchronously; this never calls Story.changed or mutates story facts. */
    public static void storyChanged(ServerPlayer player) {
        requireServerThread(player); Snapshot world = WORLDS.get(player.level());
        if (world == null) return;
        try {
            var state = world.state(attached(player));
            ((AttachmentTarget)player).setAttached(progressAttachment, state.touch());
            requestJournal(player); QuestAdvancementBridge.request(player);
        } catch (RuntimeException failure) { Worldsmith.LOGGER.error("Quest story refresh failed for {}", player.getUUID(), failure); }
    }
    public static void refresh(ServerPlayer player) { storyChanged(player); }

    public static boolean canReadMechanicGuide(ServerPlayer player, String mechanicId) {
        requireServerThread(player); Snapshot world = WORLDS.get(player.level());
        if (world == null || !player.isAlive() || player.isSpectator() || !world.mechanicNames.containsKey(mechanicId)) return false;
        try {
            world.requireBound(player.level()); reconcile(player, world); var state = world.state(attached(player)); var excluded = state.excluded(world.definitions);
            return world.quests.values().stream().anyMatch(quest -> state.discovered(quest.definition) && !excluded.contains(quest.definition.getId())
                && (state.progressFor(quest.definition).accepted() || state.progressFor(quest.definition).claimed())
                && quest.objectives.stream().anyMatch(objective -> objective.kind.equals("activate_mechanic") && objective.reference.equals(mechanicId)));
        } catch (RuntimeException invalidState) { return false; }
    }

    public static void handle(ServerPlayer player, QuestProtocol.Action action) {
        requireServerThread(player); Snapshot world = WORLDS.get(player.level());
        if (world == null) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.UNAVAILABLE, "当前世界没有旅程记录。"); return; }
        if (!world.scope.equals(action.scope())) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.SCOPE_MISMATCH, "请翻开这片天地的旅程日志。"); return; }
        RequestNonce previous = REQUESTS.get(player.getUUID());
        if (previous != null && previous.server == player.level().getServer() && action.requestId() <= previous.value) {
            sendJournal(player, action.requestId(), QuestProtocol.Feedback.REPLAYED, "这项操作已经处理，请查看最新日志。"); return;
        }
        REQUESTS.put(player.getUUID(), new RequestNonce(player.level().getServer(), action.requestId()));
        if (action.kind() == QuestProtocol.ActionKind.SYNC) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.NONE, ""); return; }
        try {
            if (!player.isAlive() || player.isSpectator()) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.UNAVAILABLE, "请在存活且非旁观模式时操作任务。"); return; }
            world.requireBound(player.level()); reconcile(player, world);
            QuestPlayerState stored = attached(player), state = world.state(stored);
            if (state.revision() != action.expectedRevision()) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.STALE_REVISION, "旅程刚刚更新，请确认最新状态后重试。"); return; }
            if (action.kind() == QuestProtocol.ActionKind.UNTRACK) {
                ((AttachmentTarget)player).setAttached(progressAttachment, state.track(""));
                sendJournal(player, action.requestId(), QuestProtocol.Feedback.TRACKED, "已取消追踪。"); return;
            }
            PreparedQuest quest = world.quests.get(action.questId());
            if (quest == null || !state.discovered(quest.definition)) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.UNAVAILABLE, "尚未发现这段旅程。"); return; }
            if (state.excluded(world.definitions).contains(action.questId())) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.EXCLUDED, "此前的选择已经开启了另一条道路。"); return; }
            if (action.kind() == QuestProtocol.ActionKind.TRACK) {
                ((AttachmentTarget)player).setAttached(progressAttachment, state.track(quest.definition.getId()));
                sendJournal(player, action.requestId(), QuestProtocol.Feedback.TRACKED, "已追踪这段旅程。"); return;
            }
            var progress = state.progressFor(quest.definition);
            if (progress.claimed()) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.ALREADY_CLAIMED, "这段旅程已经完成并领取过奖励。"); return; }
            if (!available(player, state, quest)) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.LOCKED, "这段旅程的条件尚未满足。"); return; }
            if (action.kind() == QuestProtocol.ActionKind.ACCEPT) {
                if (progress.accepted() || !quest.definition.getManualAccept()) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.UNAVAILABLE, "这段旅程已经开始。"); return; }
                accept(player, quest, stored, state);
                sendJournal(player, action.requestId(), QuestProtocol.Feedback.ACCEPTED, "已记下你的承诺。"); return;
            }
            if (action.kind() == QuestProtocol.ActionKind.DECLINE) {
                if (progress.accepted() || !quest.definition.getManualAccept()) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.UNAVAILABLE, "已接受的承诺会保留在旅程中。"); return; }
                ((AttachmentTarget)player).setAttached(progressAttachment, state.decline(quest.definition).track(state.trackedQuest().equals(action.questId()) ? "" : state.trackedQuest()));
                sendJournal(player, action.requestId(), QuestProtocol.Feedback.DECLINED, "暂时搁下；条件允许时仍可接受。"); return;
            }
            if (!progress.accepted()) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.NOT_ACCEPTED, "请先接受这段旅程。"); return; }
            if (action.kind() == QuestProtocol.ActionKind.DELIVER || action.kind() == QuestProtocol.ActionKind.DELIVER_OPTIONAL)
                deliver(player, action.requestId(), world, quest, stored, state, progress, action.kind() == QuestProtocol.ActionKind.DELIVER_OPTIONAL);
            else if (action.kind() == QuestProtocol.ActionKind.CLAIM) claim(player, action.requestId(), world, quest, stored, state, progress);
        } catch (ScopeMismatch failure) {
            Worldsmith.LOGGER.warn("Quest scope mismatch for {}", player.getUUID(), failure);
            sendJournal(player, action.requestId(), QuestProtocol.Feedback.SCOPE_MISMATCH, "这份旅程记录属于另一片天地。");
        } catch (RuntimeException failure) {
            Worldsmith.LOGGER.error("Quest action failed for {}", player.getUUID(), failure);
            sendJournal(player, action.requestId(), QuestProtocol.Feedback.ERROR, "旅程记录暂未落定，请稍后再试。");
        }
    }

    /** Discovery is durable; automatic acceptance executes its once-only transition transaction. */
    private static void reconcile(ServerPlayer player, Snapshot world) {
        if (!player.isAlive() || player.isSpectator()) return;
        for (int pass = 0; pass <= world.quests.size(); pass++) {
            boolean changed = false;
            for (PreparedQuest quest : world.quests.values()) {
                var stored = attached(player); var state = world.state(stored);
                if (state.excluded(world.definitions).contains(quest.definition.getId()) || !state.questUnlocked(quest.definition)) continue;
                if (!state.discovered(quest.definition) && WorldStoryRuntime.test(player, quest.definition.getDiscoverWhen())) {
                    state = state.discover(quest.definition); ((AttachmentTarget)player).setAttached(progressAttachment, state); stored = state; changed = true;
                }
                var progress = state.progressFor(quest.definition);
                if (state.discovered(quest.definition) && !progress.accepted() && !progress.declined() && !quest.definition.getManualAccept()
                        && WorldStoryRuntime.test(player, quest.definition.getAvailableWhen())) {
                    accept(player, quest, stored, state); changed = true;
                }
            }
            if (!changed) return;
        }
    }
    private static boolean available(ServerPlayer player, QuestPlayerState state, PreparedQuest quest) {
        return state.questUnlocked(quest.definition) && WorldStoryRuntime.test(player, quest.definition.getAvailableWhen());
    }
    private static void accept(ServerPlayer player, PreparedQuest quest, QuestPlayerState stored, QuestPlayerState state) {
        var next = state.accept(quest.definition);
        var changes = WorldStoryRuntime.prepareChanges(player, quest.definition.getOnAccept());
        commit(player, stored, next, new WorldInventoryTransaction(player), changes);
    }
    private static void deliver(ServerPlayer player, int requestId, Snapshot world, PreparedQuest quest, QuestPlayerState stored,
            QuestPlayerState state, QuestPlayerState.Progress progress, boolean optional) {
        var transaction = new WorldInventoryTransaction(player); List<Integer> counts = new ArrayList<>(progress.counts()); int delivered = 0;
        for (int index = 0; index < quest.objectives.size(); index++) {
            Objective objective = quest.objectives.get(index);
            if (!objective.kind.equals("deliver_item") || objective.optional != optional) continue;
            int taken = transaction.consume(objective::matches, objective.required - counts.get(index));
            counts.set(index, counts.get(index) + taken); delivered += taken;
        }
        if (delivered == 0) { sendJournal(player, requestId, QuestProtocol.Feedback.NO_MATERIALS, "收集所需物品后，再来交付吧。"); return; }
        QuestPlayerState next = state.withProgress(quest.definition.getId(), persistentProgress(quest, progress.withCounts(counts)));
        commit(player, stored, next, transaction, WorldStoryRuntime.prepareChanges(player, List.of()));
        sendJournal(player, requestId, QuestProtocol.Feedback.DELIVERED, "已交付 " + delivered + " 件物品。");
    }
    private static void claim(ServerPlayer player, int requestId, Snapshot world, PreparedQuest quest, QuestPlayerState stored,
            QuestPlayerState state, QuestPlayerState.Progress progress) {
        if (!ready(player, quest, progress)) { sendJournal(player, requestId, QuestProtocol.Feedback.NOT_READY, "还有必要目标尚未完成。"); return; }
        var transaction = new WorldInventoryTransaction(player);
        for (Reward reward : quest.rewards) {
            if (!transaction.insert(WorldRewardItems.stack(reward.reference, reward.count, world.blocks, world.items))) {
                sendJournal(player, requestId, QuestProtocol.Feedback.NO_SPACE, "请在主背包中为这份馈赠腾出位置。"); return;
            }
        }
        var changes = WorldStoryRuntime.prepareChanges(player, quest.definition.getOnClaim());
        QuestPlayerState next = state.withProgress(quest.definition.getId(), persistentProgress(quest, progress.withClaimed()));
        commit(player, stored, next, transaction, changes);
        sendJournal(player, requestId, QuestProtocol.Feedback.CLAIMED, "这段旅程已落定；世界会记住你的行动。");
    }
    private static QuestPlayerState.Progress persistentProgress(PreparedQuest quest, QuestPlayerState.Progress progress) {
        List<Integer> counts = new ArrayList<>(progress.counts());
        for (int index = 0; index < quest.objectives.size(); index++)
            if (quest.objectives.get(index).condition != null || quest.objectives.get(index).kind.equals("activate_mechanic")) counts.set(index, 0);
        return progress.withCounts(counts);
    }
    /** Single server-thread transaction. Transport failures after commit never replay costs/rewards. */
    private static void commit(ServerPlayer player, QuestPlayerState expectedStored, QuestPlayerState next, WorldInventoryTransaction transaction,
            WorldStoryRuntime.PreparedChanges changes) {
        requireServerThread(player); AttachmentTarget target = (AttachmentTarget)player;
        if (target.getAttached(progressAttachment) != expectedStored) throw new IllegalStateException("Quest state changed during transaction preparation");
        transaction.assertUnchanged(); changes.assertUnchanged();
        try {
            transaction.apply(); changes.commit(); target.setAttached(progressAttachment, next);
        } catch (RuntimeException failure) {
            try { changes.rollback(); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            try { transaction.rollback(); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            try { target.setAttached(progressAttachment, expectedStored); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        player.getInventory().setChanged();
        try {
            player.inventoryMenu.broadcastChanges();
            if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
        } catch (RuntimeException syncFailure) { Worldsmith.LOGGER.error("Quest inventory sync failed after commit for {}", player.getUUID(), syncFailure); }
        QuestAdvancementBridge.request(player);
        WorldStoryRuntime.changed(player);
    }

    private static void afterDeath(LivingEntity entity) {
        if (!(entity instanceof CreatureEntity creature) || !(entity.level() instanceof ServerLevel level)) return;
        if (!(creature.getKillCredit() instanceof ServerPlayer credited)) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(credited.getUUID());
        if (player == null || player.isSpectator()) return;
        Snapshot world = WORLDS.get(player.level());
        if (world == null || world.quests.isEmpty() || !world.scope.equals(creature.bundleHash())) return;
        var creatureWorld = CreatureRuntime.snapshot(level); var definition = creature.definition();
        if (creatureWorld == null || !world.scope.equals(creatureWorld.bundleHash()) || definition == null || !CreatureRuntime.matchesHost(creature.getType(), definition)) return;
        if (!CREDITED_DEATHS.add(entity)) return;
        try {
            requireServerThread(player); world.requireBound(player.level()); reconcile(player, world);
            QuestPlayerState state = world.state(attached(player)); var excluded = state.excluded(world.definitions); boolean anyChanged = false;
            for (PreparedQuest quest : world.quests.values()) {
                var progress = state.progressFor(quest.definition);
                if (!progress.accepted() || progress.claimed() || excluded.contains(quest.definition.getId()) || !available(player, state, quest)) continue;
                List<Integer> counts = new ArrayList<>(progress.counts()); boolean changed = false;
                for (int index = 0; index < quest.objectives.size(); index++) {
                    Objective objective = quest.objectives.get(index);
                    if (objective.kind.equals("kill_creature") && objective.reference.equals(creature.creatureId()) && counts.get(index) < objective.required) {
                        counts.set(index, counts.get(index) + 1); changed = true;
                    }
                }
                if (changed) { state = state.withProgress(quest.definition.getId(), persistentProgress(quest, progress.withCounts(counts))); anyChanged = true; }
            }
            if (anyChanged) {
                ((AttachmentTarget)player).setAttached(progressAttachment, state); QuestAdvancementBridge.request(player); requestJournal(player);
            }
        } catch (RuntimeException failure) { Worldsmith.LOGGER.error("Quest kill credit failed for {}", player.getUUID(), failure); }
    }
    /** Observes a committed mechanic transaction; never executes the mechanic itself. */
    public static void afterMechanicActivation(ServerPlayer player, String mechanicId) {
        try {
            requireServerThread(player); Snapshot world = WORLDS.get(player.level());
            if (world == null || !world.mechanicNames.containsKey(mechanicId)) return;
            world.requireBound(player.level()); QuestPlayerState state = world.state(attached(player));
            QuestPlayerState next = state.withMechanicActivation(mechanicId); if (next == state) return;
            ((AttachmentTarget)player).setAttached(progressAttachment, next); QuestAdvancementBridge.request(player); requestJournal(player);
        } catch (RuntimeException failure) { Worldsmith.LOGGER.error("Quest mechanic fact recording failed for {}: {}", player.getUUID(), mechanicId, failure); }
    }

    public static void sendJournal(ServerPlayer player, int requestId, QuestProtocol.Feedback feedback, String message) {
        requireServerThread(player);
        if (!ServerPlayNetworking.canSend(player, QuestProtocol.Snapshot.TYPE)) return;
        ServerPlayNetworking.send(player, journalSnapshot(player, requestId, feedback, message));
        DIRTY_JOURNALS.remove(player.getUUID());
    }
    /** Same authoritative projection used by networking, available to native server integrations. */
    public static QuestProtocol.Snapshot journalSnapshot(ServerPlayer player) {
        return journalSnapshot(player, 0, QuestProtocol.Feedback.NONE, "");
    }
    private static QuestProtocol.Snapshot journalSnapshot(ServerPlayer player, int requestId, QuestProtocol.Feedback feedback, String message) {
        requireServerThread(player);
        Snapshot world = WORLDS.get(player.level()); QuestProtocol.Snapshot journal;
        if (world == null) journal = new QuestProtocol.Snapshot("", "", requestId, 0, List.of(), QuestProtocol.Feedback.UNAVAILABLE, "当前世界没有旅程记录。", false, null);
        else {
            try {
                world.requireBound(player.level()); reconcile(player, world);
                journal = world.journal(player, world.state(attached(player)), requestId, feedback, cleanMultiline(message, 512));
                QuestAdvancementBridge.request(player);
            } catch (RuntimeException failure) {
                QuestPlayerState old = attached(player);
                journal = new QuestProtocol.Snapshot(world.scope, world.worldTitle, requestId, old == null ? 0 : old.revision(), List.of(),
                    failure instanceof ScopeMismatch ? QuestProtocol.Feedback.SCOPE_MISMATCH : QuestProtocol.Feedback.ERROR,
                    "这份旅程记录暂未对上，请稍后重新打开日志。", false, null);
                Worldsmith.LOGGER.error("Quest journal projection failed for {}", player.getUUID(), failure);
            }
        }
        return journal;
    }
    private static void requestJournal(ServerPlayer player) {
        requireServerThread(player); MinecraftServer server = player.level().getServer();
        DIRTY_JOURNALS.mark(player.getUUID(), server, server.getTickCount());
    }
    private static void flushJournals(MinecraftServer server) {
        for (var id : DIRTY_JOURNALS.drain(server, server.getTickCount())) {
            ServerPlayer player = server.getPlayerList().getPlayer(id); if (player != null) sendJournal(player, 0, QuestProtocol.Feedback.NONE, "");
        }
    }
    private static QuestPlayerState attached(ServerPlayer player) {
        if (progressAttachment == null) throw new IllegalStateException("Quest runtime has not been registered");
        return ((AttachmentTarget)player).getAttached(progressAttachment);
    }
    private static void requireServerThread(ServerPlayer player) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Quest player state is server-thread-only");
    }
    private static int objectiveProgress(ServerPlayer player, PreparedQuest quest, QuestPlayerState.Progress progress, int index) {
        Objective objective = quest.objectives.get(index);
        if (objective.condition == null) return progress.counts().get(index);
        return progress.claimed() && !objective.optional || WorldStoryRuntime.test(player, objective.condition) ? 1 : 0;
    }
    private static boolean ready(ServerPlayer player, PreparedQuest quest, QuestPlayerState.Progress progress) {
        if (!progress.accepted()) return false;
        for (int index = 0; index < quest.objectives.size(); index++) {
            Objective objective = quest.objectives.get(index);
            if (!objective.optional && objectiveProgress(player, quest, progress, index) < objective.required) return false;
        }
        return true;
    }

    record TaskMilestones(boolean objectivesMet, boolean claimed) {}
    record AdvancementProjection(String scope, Map<String, TaskMilestones> tasks) {}
    static AdvancementProjection advancementProjection(ServerPlayer player) {
        requireServerThread(player); Snapshot world = WORLDS.get(player.level());
        if (world == null || world.quests.isEmpty()) return null;
        world.requireBound(player.level()); QuestPlayerState state = world.state(attached(player));
        var excluded = state.excluded(world.definitions); Map<String, TaskMilestones> tasks = new LinkedHashMap<>();
        for (var quest : world.quests.values()) {
            var progress = state.progressFor(quest.definition);
            boolean visible = state.discovered(quest.definition) && !excluded.contains(quest.definition.getId());
            tasks.put(quest.definition.getId(), new TaskMilestones(visible && (progress.claimed() || available(player, state, quest) && ready(player, quest, progress)), visible && progress.claimed()));
        }
        return new AdvancementProjection(world.scope, Collections.unmodifiableMap(tasks));
    }

    public static final class Snapshot {
        private final String scope, worldTitle;
        private final Map<String, PreparedQuest> quests;
        private final Map<String, Quest> definitions;
        private final Map<String, String> mechanicNames;
        private final CustomItemRuntime.Snapshot items;
        private final WorldBlockBindings.Resolver blocks;
        private Snapshot(String scope, String title, Map<String, PreparedQuest> quests, Map<String, String> mechanicNames, CustomItemRuntime.Snapshot items, WorldBlockBindings.Resolver blocks) {
            this.scope = scope; this.worldTitle = title; this.quests = Collections.unmodifiableMap(new LinkedHashMap<>(quests));
            Map<String, Quest> definitions = new LinkedHashMap<>(); quests.forEach((id, quest) -> definitions.put(id, quest.definition)); this.definitions = Collections.unmodifiableMap(definitions);
            this.mechanicNames = Collections.unmodifiableMap(new LinkedHashMap<>(mechanicNames)); this.items = items; this.blocks = blocks;
        }
        public String scope() { return scope; }
        public String worldTitle() { return worldTitle; }
        public int questCount() { return quests.size(); }
        public Map<String, Quest> definitions() { return definitions; }
        private void requireBound(ServerLevel level) {
            if (WORLDS.get(level) != this || CustomItemRuntime.snapshot(level) == null || !scope.equals(CustomItemRuntime.snapshot(level).bundleHash())
                || !items.definitions().equals(CustomItemRuntime.snapshot(level).definitions()) || !blocks.snapshot().equals(WorldBlockBindings.active()))
                throw new IllegalStateException("Quest domain bindings are not active for this level");
        }
        private QuestPlayerState state(QuestPlayerState stored) {
            QuestPlayerState state = stored == null ? QuestPlayerState.empty(scope) : stored;
            if (!scope.equals(state.bundleHash())) throw new ScopeMismatch("玩家任务进度属于另一个世界，未覆盖旧进度。");
            if (!mechanicNames.keySet().containsAll(state.mechanicActivations().keySet())) throw new IllegalStateException("Unknown persisted mechanic fact");
            for (var branch : state.selectedBranches().entrySet()) {
                Quest definition = definitions.get(branch.getValue()); var progress = state.quests().get(branch.getValue());
                if (definition == null || !branch.getKey().equals(definition.getExclusiveGroup()) || progress == null || !progress.accepted())
                    throw new IllegalStateException("Selected branch differs from its accepted definition");
            }
            var excluded = state.excluded(definitions);
            for (var entry : state.quests().entrySet()) {
                PreparedQuest quest = quests.get(entry.getKey()); var progress = entry.getValue();
                if (quest == null || progress.counts().size() != quest.objectives.size()) throw new IllegalStateException("Persistent quest shape differs from definition");
                if (!state.prerequisitesSatisfied(quest.definition)) throw new IllegalStateException("Discovered quest precedes its prerequisite history");
                for (int index = 0; index < quest.objectives.size(); index++) {
                    Objective objective = quest.objectives.get(index); int count = progress.counts().get(index);
                    if (count > objective.required || objective.condition != null && count != 0 || objective.kind.equals("activate_mechanic") && count != 0)
                        throw new IllegalStateException("Invalid persistent objective count or duplicated shared fact");
                    if (!progress.accepted() && count != 0) throw new IllegalStateException("Unaccepted quest has earned progress");
                    if (progress.claimed() && !objective.optional && objective.condition == null && !objective.kind.equals("activate_mechanic") && count < objective.required)
                        throw new IllegalStateException("Claimed quest has incomplete required progress");
                    if (progress.claimed() && !objective.optional && objective.kind.equals("activate_mechanic") && state.mechanicActivationCount(objective.reference) < objective.required)
                        throw new IllegalStateException("Claimed mechanic objective lacks its committed lifetime fact");
                }
                if (progress.accepted() && (!state.questUnlocked(quest.definition) || excluded.contains(quest.definition.getId())))
                    throw new IllegalStateException("Accepted quest conflicts with branch or prerequisites");
                if (progress.accepted() && quest.definition.getExclusiveGroup() != null
                        && !quest.definition.getId().equals(state.selectedBranches().get(quest.definition.getExclusiveGroup())))
                    throw new IllegalStateException("Accepted exclusive quest has no durable branch selection");
            }
            return state;
        }
        private QuestProtocol.Snapshot journal(ServerPlayer player, QuestPlayerState state, int requestId, QuestProtocol.Feedback feedback, String message) {
            List<QuestProtocol.Entry> entries = new ArrayList<>(); var excluded = state.excluded(definitions);
            for (PreparedQuest quest : quests.values()) {
                if (!state.discovered(quest.definition)) continue;
                var progress = state.progressFor(quest.definition);
                QuestProtocol.Status status = progress.claimed() ? QuestProtocol.Status.CLAIMED
                    : excluded.contains(quest.definition.getId()) ? QuestProtocol.Status.EXCLUDED
                    : !available(player, state, quest) ? QuestProtocol.Status.LOCKED
                    : !progress.accepted() ? (progress.declined() ? QuestProtocol.Status.DECLINED : QuestProtocol.Status.AVAILABLE)
                    : ready(player, quest, progress) ? QuestProtocol.Status.READY : QuestProtocol.Status.ACTIVE;
                List<QuestProtocol.Objective> objectives = new ArrayList<>();
                for (int index = 0; index < quest.objectives.size(); index++) {
                    var objective = quest.objectives.get(index);
                    objectives.add(new QuestProtocol.Objective(objective.kind, objective.reference, objective.label, objectiveProgress(player, quest, progress, index), objective.required, objective.optional));
                }
                var rewards = quest.rewards.stream().map(reward -> new QuestProtocol.Reward(reward.label, reward.count)).toList();
                entries.add(new QuestProtocol.Entry(quest.definition.getId(), quest.definition.getTitle(), quest.definition.getDescription(), status, objectives, rewards,
                    quest.definition.getOptional(), quest.definition.getDestination(), quest.definition.getExclusiveGroup() != null));
            }
            String tracked = state.trackedQuest().isEmpty() || excluded.contains(state.trackedQuest()) ? null : state.trackedQuest();
            return new QuestProtocol.Snapshot(scope, worldTitle, requestId, state.revision(), entries, feedback, message, state.campaignComplete(definitions), tracked);
        }
    }
    private record PreparedQuest(Quest definition, List<Objective> objectives, List<Reward> rewards) {}
    private record Reward(String reference, int count, String label) {}
    private record Objective(String kind, String reference, String label, int required, boolean optional, Item item, WorldItemIdentity identity, StoryCondition condition) {
        boolean matches(ItemStack stack) { return !stack.isEmpty() && stack.getItem() == item && (identity == null || identity.equals(stack.get(CustomItemRuntime.identityComponent()))); }
    }
    private record RequestNonce(MinecraftServer server, int value) {}
    private static String itemLabel(String reference, ItemStack stack, Map<String, String> blockNames, CustomItemRuntime.Snapshot items) {
        if (CustomItemRuntime.isLogicalId(reference)) return clean(items.requireDefinition(reference).getDisplayName(), 128);
        if (blockNames.containsKey(reference)) return clean(blockNames.get(reference), 128);
        return clean(stack.getHoverName().getString(), 128);
    }
    private static String clean(String text, int maximum) { return bounded(text.chars().filter(value -> !Character.isISOControl(value)).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString(), maximum); }
    private static String cleanMultiline(String text, int maximum) { return bounded(text.chars().filter(value -> !Character.isISOControl(value) || value == '\n').collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString(), maximum); }
    private static String bounded(String text, int maximum) {
        int end = Math.min(text.length(), maximum); if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--; return text.substring(0, end);
    }
    private static final class ScopeMismatch extends IllegalStateException { ScopeMismatch(String message) { super(message); } }
}
