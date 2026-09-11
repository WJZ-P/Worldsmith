package com.wjz.worldsmith.content.quest.server;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldRewardItems;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.content.item.WorldItemIdentity;
import com.wjz.worldsmith.content.quest.QuestProtocol;
import com.wjz.worldsmith.core.content.Quest;
import com.wjz.worldsmith.core.content.QuestObjective;
import com.wjz.worldsmith.core.content.QuestValidation;
import com.wjz.worldsmith.core.content.CreatureCategory;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative linear quest state. Its sole durable ledger is a persistent PLAYER attachment. */
public final class QuestRuntime {
    private static final Map<ServerLevel, Snapshot> WORLDS = new ConcurrentHashMap<>();
    private static final Set<LivingEntity> CREDITED_DEATHS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static AttachmentType<QuestPlayerState> progressAttachment;
    private static boolean registered;

    private QuestRuntime() {}

    public static synchronized void register() {
        if (registered) return;
        progressAttachment = AttachmentRegistry.<QuestPlayerState>create(Worldsmith.id("quest_progress"),
            builder -> builder.persistent(QuestPlayerState.CODEC).copyOnDeath());
        // Deliberately no syncWith: clients receive a journal, never write this attachment or objective counts.
        QuestProtocol.registerTypes();
        if (!ServerPlayNetworking.registerGlobalReceiver(QuestProtocol.Action.TYPE, (action, context) -> handle(context.player(), action)))
            throw new IllegalStateException("The Worldsmith quest action channel is already registered");
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> afterDeath(entity));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendJournal(handler.player, 0, QuestProtocol.Feedback.NONE, ""));
        registered = true;
    }

    /** Validates every reward and delivery reference before publication, including locked and later quests. */
    public static Snapshot prepare(WorldsmithPack pack, CreatureRuntime.Snapshot creatures, CustomItemRuntime.Snapshot items, WorldBlockBindings.Resolver blocks) {
        String scope = pack.getManifest().getId();
        if (!scope.equals(creatures.bundleHash()) || !scope.equals(items.bundleHash()) || !scope.equals(blocks.snapshot().getScope()))
            throw new IllegalArgumentException("Quest domains belong to different immutable world scopes");
        var library = QuestValidation.freeze(pack.getQuests());
        List<Quest> order = QuestValidation.ordered(library);
        Map<String, String> blockNames = new LinkedHashMap<>();
        pack.getBlocks().getBlocks().forEach(block -> blockNames.put("worldsmith:content/" + block.getId(), block.getDisplayName()));
        Map<String, PreparedQuest> quests = new LinkedHashMap<>();
        for (Quest quest : order) {
            List<Objective> objectives = new ArrayList<>();
            for (QuestObjective objective : quest.getObjectives()) {
                if (objective instanceof QuestObjective.KillCreature kill) {
                    var creature = creatures.definitions().get(kill.getCreature());
                    if (creature == null) throw new IllegalArgumentException("Quest references an undefined world creature: " + kill.getCreature());
                    objectives.add(new Objective("kill_creature", kill.getCreature(), clean(creature.getDisplayName(), 128), kill.getCount(), null, null));
                } else if (objective instanceof QuestObjective.DeliverItem deliver) {
                    ItemStack prototype = WorldRewardItems.stack(deliver.getItem(), 1, blocks, items);
                    WorldItemIdentity identity = prototype.getItem() == CustomItemRuntime.host() ? prototype.get(CustomItemRuntime.identityComponent()) : null;
                    objectives.add(new Objective("deliver_item", deliver.getItem(), itemLabel(deliver.getItem(), prototype, blockNames, items), deliver.getCount(), prototype.getItem(), identity));
                } else throw new IllegalArgumentException("Unsupported native quest objective");
            }
            List<Reward> rewards = new ArrayList<>();
            for (var reward : quest.getRewards()) {
                ItemStack prototype = WorldRewardItems.stack(reward.getItem(), reward.getCount(), blocks, items);
                rewards.add(new Reward(reward.getItem(), reward.getCount(), itemLabel(reward.getItem(), prototype, blockNames, items)));
            }
            quests.put(quest.getId(), new PreparedQuest(quest, List.copyOf(objectives), List.copyOf(rewards)));
        }
        return new Snapshot(scope, clean(pack.getManifest().getDisplayName(), 160), quests, items, blocks);
    }

    public static void bind(ServerLevel level, Snapshot snapshot) {
        Snapshot previous = WORLDS.putIfAbsent(Objects.requireNonNull(level), Objects.requireNonNull(snapshot));
        if (previous != null && (!previous.scope.equals(snapshot.scope) || !previous.quests.equals(snapshot.quests)))
            throw new IllegalStateException("A running level already owns different immutable quests");
    }
    public static void unbind(ServerLevel level) { WORLDS.remove(level); }
    public static Snapshot snapshot(ServerLevel level) { return WORLDS.get(level); }

    private static void handle(ServerPlayer player, QuestProtocol.Action action) {
        requireServerThread(player);
        Snapshot world = WORLDS.get(player.level());
        if (world == null || world.quests.isEmpty()) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.UNAVAILABLE, "当前世界没有主线任务。"); return; }
        if (action.kind() == QuestProtocol.ActionKind.SYNC) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.NONE, ""); return; }
        if (!world.scope.equals(action.scope())) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.SCOPE_MISMATCH, "这条操作属于另一个世界；物品和进度保持原样。"); return; }
        try {
            if (!player.isAlive() || player.isSpectator()) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.UNAVAILABLE, "请在存活且非旁观模式时操作任务。"); return; }
            world.requireBound(player.level());
            QuestPlayerState stored = attached(player);
            QuestPlayerState state = world.state(stored);
            if (state.revision() != action.expectedRevision()) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.STALE_REVISION, "任务状态刚刚更新，请查看最新进度后重试。"); return; }
            PreparedQuest quest = world.quests.get(action.questId());
            if (quest == null) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.UNAVAILABLE, "当前世界没有这项任务。"); return; }
            QuestPlayerState.Progress progress = progress(state, quest);
            if (progress.claimed()) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.ALREADY_CLAIMED, "这项任务已经领取过奖励。"); return; }
            if (!unlocked(state, quest)) { sendJournal(player, action.requestId(), QuestProtocol.Feedback.LOCKED, "先完成并领取前置任务奖励。"); return; }
            if (action.kind() == QuestProtocol.ActionKind.DELIVER) deliver(player, action.requestId(), world, quest, stored, state, progress);
            else if (action.kind() == QuestProtocol.ActionKind.CLAIM) claim(player, action.requestId(), world, quest, stored, state, progress);
        } catch (ScopeMismatch failure) {
            sendJournal(player, action.requestId(), QuestProtocol.Feedback.SCOPE_MISMATCH, failure.getMessage());
        } catch (RuntimeException failure) {
            Worldsmith.LOGGER.error("Quest action failed for {}", player.getUUID(), failure);
            sendJournal(player, action.requestId(), QuestProtocol.Feedback.ERROR, "任务操作未完成，请检查游戏日志；没有把奖励丢到地面。");
        }
    }

    private static void deliver(ServerPlayer player, int requestId, Snapshot world, PreparedQuest quest, QuestPlayerState stored,
        QuestPlayerState state, QuestPlayerState.Progress progress) {
        var transaction = new QuestInventoryTransaction(player);
        List<Integer> counts = new ArrayList<>(progress.counts());
        int delivered = 0;
        for (int index = 0; index < quest.objectives.size(); index++) {
            Objective objective = quest.objectives.get(index);
            if (!objective.kind.equals("deliver_item")) continue;
            int taken = transaction.consume(objective::matches, objective.required - counts.get(index));
            counts.set(index, counts.get(index) + taken); delivered += taken;
        }
        if (delivered == 0) { sendJournal(player, requestId, QuestProtocol.Feedback.NO_MATERIALS, "主背包里没有可继续交付的所需物品；没有扣除物品。"); return; }
        QuestPlayerState next = state.withProgress(quest.definition.getId(), new QuestPlayerState.Progress(counts, false));
        commit(player, stored, next, transaction);
        sendJournal(player, requestId, QuestProtocol.Feedback.DELIVERED, "已交付 " + delivered + " 件物品，任务进度已更新。");
    }

    private static void claim(ServerPlayer player, int requestId, Snapshot world, PreparedQuest quest, QuestPlayerState stored,
        QuestPlayerState state, QuestPlayerState.Progress progress) {
        if (!ready(quest, progress)) { sendJournal(player, requestId, QuestProtocol.Feedback.NOT_READY, "还有目标尚未完成；交付目标按实际已交付数量计算。"); return; }
        var transaction = new QuestInventoryTransaction(player);
        for (Reward reward : quest.rewards) {
            if (!transaction.insert(WorldRewardItems.stack(reward.reference, reward.count, world.blocks, world.items))) {
                sendJournal(player, requestId, QuestProtocol.Feedback.NO_SPACE, "主背包放不下全部奖励；本次没有发放、掉落或标记已领奖。"); return;
            }
        }
        QuestPlayerState next = state.withProgress(quest.definition.getId(), new QuestPlayerState.Progress(progress.counts(), true));
        commit(player, stored, next, transaction);
        sendJournal(player, requestId, QuestProtocol.Feedback.CLAIMED, "奖励已放入主背包，这项任务已领奖。");
    }

    /** Simulation is complete. Vanilla inventory setters and the attachment change happen in one server-thread turn. */
    private static void commit(ServerPlayer player, QuestPlayerState expectedStored, QuestPlayerState next, QuestInventoryTransaction transaction) {
        requireServerThread(player);
        AttachmentTarget target = (AttachmentTarget)player;
        if (target.getAttached(progressAttachment) != expectedStored) throw new IllegalStateException("Quest progress changed while preparing inventory updates");
        transaction.assertUnchanged();
        try {
            transaction.apply();
            target.setAttached(progressAttachment, next);
        } catch (RuntimeException failure) {
            try { transaction.rollback(); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            try { target.setAttached(progressAttachment, expectedStored); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        player.getInventory().setChanged();
        try {
            player.inventoryMenu.broadcastChanges();
            if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
        } catch (RuntimeException syncFailure) {
            // The server transaction is already committed; a transport/UI failure must not replay the reward.
            Worldsmith.LOGGER.error("Quest inventory synchronization failed after commit for {}", player.getUUID(), syncFailure);
        }
    }

    private static void afterDeath(LivingEntity entity) {
        if (!(entity instanceof CreatureEntity creature) || !(entity.level() instanceof ServerLevel level)) return;
        if (!(creature.getKillCredit() instanceof ServerPlayer credited)) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(credited.getUUID());
        if (player == null || player.isSpectator()) return;
        Snapshot world = WORLDS.get(player.level());
        if (world == null || world.quests.isEmpty() || !world.scope.equals(creature.bundleHash())) return;
        var creatureWorld = CreatureRuntime.snapshot(level);
        var definition = creature.definition();
        if (creatureWorld == null || !world.scope.equals(creatureWorld.bundleHash()) || definition == null) return;
        if (!CreatureRuntime.matchesHost(creature.getType(), definition)) return;
        if (!CREDITED_DEATHS.add(entity)) return;
        try {
            requireServerThread(player); world.requireBound(player.level());
            QuestPlayerState state = world.state(attached(player));
            for (PreparedQuest quest : world.quests.values()) {
                QuestPlayerState.Progress progress = progress(state, quest);
                if (progress.claimed() || !unlocked(state, quest)) continue;
                List<Integer> counts = new ArrayList<>(progress.counts());
                boolean changed = false;
                for (int index = 0; index < quest.objectives.size(); index++) {
                    Objective objective = quest.objectives.get(index);
                    if (objective.kind.equals("kill_creature") && objective.reference.equals(creature.creatureId()) && counts.get(index) < objective.required) {
                        counts.set(index, counts.get(index) + 1); changed = true;
                    }
                }
                if (changed) {
                    ((AttachmentTarget)player).setAttached(progressAttachment, state.withProgress(quest.definition.getId(), new QuestPlayerState.Progress(counts, false)));
                    // The open journal polls every 40 client ticks; do not send a full 2 MiB journal for every AOE kill.
                }
                break; // The validated graph is one line; the next quest unlocks only after this one is claimed.
            }
        } catch (RuntimeException failure) { Worldsmith.LOGGER.error("Quest kill credit failed for {}", player.getUUID(), failure); }
    }

    public static void sendJournal(ServerPlayer player, int requestId, QuestProtocol.Feedback feedback, String message) {
        requireServerThread(player);
        if (!ServerPlayNetworking.canSend(player, QuestProtocol.Snapshot.TYPE)) return;
        Snapshot world = WORLDS.get(player.level());
        QuestProtocol.Snapshot journal;
        if (world == null) journal = new QuestProtocol.Snapshot("", "", requestId, 0, List.of(), QuestProtocol.Feedback.UNAVAILABLE, "当前世界没有主线任务。");
        else {
            try { journal = world.journal(world.state(attached(player)), requestId, feedback, cleanMultiline(message, 512)); }
            catch (RuntimeException failure) {
                QuestPlayerState old = attached(player);
                journal = new QuestProtocol.Snapshot(world.scope, world.worldTitle, requestId, old == null ? 0 : old.revision(), List.of(),
                    failure instanceof ScopeMismatch ? QuestProtocol.Feedback.SCOPE_MISMATCH : QuestProtocol.Feedback.ERROR,
                    "玩家任务记录与当前世界不一致；原记录和物品未被重置。");
            }
        }
        ServerPlayNetworking.send(player, journal);
    }

    private static QuestPlayerState attached(ServerPlayer player) {
        if (progressAttachment == null) throw new IllegalStateException("Quest runtime has not been registered");
        return ((AttachmentTarget)player).getAttached(progressAttachment);
    }
    private static void requireServerThread(ServerPlayer player) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Quest player state is server-thread-only");
    }
    private static QuestPlayerState.Progress progress(QuestPlayerState state, PreparedQuest quest) {
        var value = state.quests().get(quest.definition.getId());
        return value == null ? new QuestPlayerState.Progress(Collections.nCopies(quest.objectives.size(), 0), false) : value;
    }
    private static boolean unlocked(QuestPlayerState state, PreparedQuest quest) {
        if (quest.definition.getPrerequisites().isEmpty()) return true;
        QuestPlayerState.Progress previous = state.quests().get(quest.definition.getPrerequisites().getFirst());
        return previous != null && previous.claimed();
    }
    private static boolean ready(PreparedQuest quest, QuestPlayerState.Progress progress) {
        for (int index = 0; index < quest.objectives.size(); index++) if (progress.counts().get(index) < quest.objectives.get(index).required) return false;
        return true;
    }

    public static final class Snapshot {
        private final String scope;
        private final String worldTitle;
        private final Map<String, PreparedQuest> quests;
        private final CustomItemRuntime.Snapshot items;
        private final WorldBlockBindings.Resolver blocks;
        private Snapshot(String scope, String worldTitle, Map<String, PreparedQuest> quests, CustomItemRuntime.Snapshot items, WorldBlockBindings.Resolver blocks) {
            this.scope = scope; this.worldTitle = worldTitle; this.quests = Collections.unmodifiableMap(new LinkedHashMap<>(quests)); this.items = items; this.blocks = blocks;
        }
        public String scope() { return scope; }
        public int questCount() { return quests.size(); }
        public Map<String, Quest> definitions() {
            Map<String, Quest> values = new LinkedHashMap<>(); quests.forEach((id, quest) -> values.put(id, quest.definition)); return Collections.unmodifiableMap(values);
        }
        private void requireBound(ServerLevel level) {
            if (WORLDS.get(level) != this || CustomItemRuntime.snapshot(level) == null
                || !scope.equals(CustomItemRuntime.snapshot(level).bundleHash()) || !items.definitions().equals(CustomItemRuntime.snapshot(level).definitions())
                || !blocks.snapshot().equals(WorldBlockBindings.active())) throw new IllegalStateException("Quest domain bindings are not active for this level");
        }
        private QuestPlayerState state(QuestPlayerState stored) {
            QuestPlayerState state = stored == null ? QuestPlayerState.empty(scope) : stored;
            if (!scope.equals(state.bundleHash())) throw new ScopeMismatch("玩家任务进度属于另一个世界，未覆盖旧进度。");
            for (var entry : state.quests().entrySet()) {
                PreparedQuest quest = quests.get(entry.getKey());
                if (quest == null || entry.getValue().counts().size() != quest.objectives.size()) throw new IllegalStateException("Persistent quest progress differs from its immutable definitions");
                for (int index = 0; index < quest.objectives.size(); index++) {
                    if (entry.getValue().counts().get(index) > quest.objectives.get(index).required) throw new IllegalStateException("Persistent quest objective exceeds its definition");
                }
                if ((!unlocked(state, quest) && (entry.getValue().claimed() || entry.getValue().counts().stream().anyMatch(count -> count > 0)))
                    || entry.getValue().claimed() && !ready(quest, entry.getValue())) throw new IllegalStateException("Persistent quest order or claim state is inconsistent");
            }
            return state;
        }
        private QuestProtocol.Snapshot journal(QuestPlayerState state, int requestId, QuestProtocol.Feedback feedback, String message) {
            List<QuestProtocol.Entry> entries = new ArrayList<>();
            for (PreparedQuest quest : quests.values()) {
                var progress = progress(state, quest);
                var status = progress.claimed() ? QuestProtocol.Status.CLAIMED : !unlocked(state, quest) ? QuestProtocol.Status.LOCKED
                    : ready(quest, progress) ? QuestProtocol.Status.READY : QuestProtocol.Status.ACTIVE;
                List<QuestProtocol.Objective> objectives = new ArrayList<>();
                for (int index = 0; index < quest.objectives.size(); index++) {
                    var objective = quest.objectives.get(index);
                    objectives.add(new QuestProtocol.Objective(objective.kind, objective.label, progress.counts().get(index), objective.required));
                }
                var rewards = quest.rewards.stream().map(reward -> new QuestProtocol.Reward(reward.label, reward.count)).toList();
                entries.add(new QuestProtocol.Entry(quest.definition.getId(), quest.definition.getTitle(), quest.definition.getDescription(), status, objectives, rewards));
            }
            return new QuestProtocol.Snapshot(scope, worldTitle, requestId, state.revision(), entries, feedback, message);
        }
    }

    private record PreparedQuest(Quest definition, List<Objective> objectives, List<Reward> rewards) {}
    private record Reward(String reference, int count, String label) {}
    private record Objective(String kind, String reference, String label, int required, Item item, WorldItemIdentity identity) {
        boolean matches(ItemStack stack) {
            if (stack.isEmpty() || stack.getItem() != item) return false;
            // Ordinary world items require the exact bundle/id identity; cosmetic naming does not change their identity.
            return identity == null || identity.equals(stack.get(CustomItemRuntime.identityComponent()));
        }
    }
    private static String itemLabel(String reference, ItemStack stack, Map<String, String> blockNames, CustomItemRuntime.Snapshot items) {
        if (CustomItemRuntime.isLogicalId(reference)) return clean(items.requireDefinition(reference).getDisplayName(), 128);
        if (blockNames.containsKey(reference)) return clean(blockNames.get(reference), 128);
        return clean(stack.getHoverName().getString(), 128);
    }
    private static String clean(String text, int maximum) { return bounded(text.chars().filter(value -> !Character.isISOControl(value)).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString(), maximum); }
    private static String cleanMultiline(String text, int maximum) { return bounded(text.chars().filter(value -> !Character.isISOControl(value) || value == '\n').collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString(), maximum); }
    private static String bounded(String text, int maximum) {
        int end = Math.min(text.length(), maximum);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
    private static final class ScopeMismatch extends IllegalStateException { ScopeMismatch(String message) { super(message); } }
}
