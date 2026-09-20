package com.wjz.worldsmith.content.quest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Bounded discovered-only journal. Requests are intent plus concurrency tokens, never client facts. */
public final class QuestProtocol {
    public static final int MAX_QUESTS = 64;
    public static final int MAX_OBJECTIVES = 8;
    public static final int MAX_REWARDS = 16;
    public static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;
    private static boolean registered;
    private QuestProtocol() {}

    public enum ActionKind { SYNC, DELIVER, CLAIM, ACCEPT, DECLINE, TRACK, UNTRACK, DELIVER_OPTIONAL }
    public enum Status { LOCKED, AVAILABLE, ACTIVE, READY, CLAIMED, DECLINED, EXCLUDED }
    public enum Feedback { NONE, DELIVERED, CLAIMED, NO_MATERIALS, NO_SPACE, LOCKED, NOT_READY, ALREADY_CLAIMED,
        STALE_REVISION, SCOPE_MISMATCH, UNAVAILABLE, ERROR, ACCEPTED, DECLINED, TRACKED, NOT_ACCEPTED, EXCLUDED, REPLAYED }

    public record Action(String scope, String questId, ActionKind kind, int requestId, long expectedRevision) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Identifier.fromNamespaceAndPath("worldsmith", "quest_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> STREAM_CODEC = StreamCodec.of(QuestProtocol::writeAction, QuestProtocol::readAction);
        public Action {
            QuestProtocol.scope(scope); Objects.requireNonNull(kind);
            boolean noTarget = kind == ActionKind.SYNC || kind == ActionKind.UNTRACK;
            QuestProtocol.id(questId, noTarget);
            if (requestId < 1 || expectedRevision < 0 || kind != ActionKind.SYNC && scope.isEmpty() || noTarget && !questId.isEmpty())
                throw new IllegalArgumentException("Invalid quest action identity or revision");
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Objective(String kind, String reference, String targetLabel, int progress, int required, boolean optional) {
        public Objective(String kind, String reference, String label, int progress, int required) { this(kind, reference, label, progress, required, false); }
        public Objective {
            if (!List.of("kill_creature", "deliver_item", "activate_mechanic", "fact").contains(kind)) throw new IllegalArgumentException("Unknown quest objective kind");
            text(reference, 256, false);
            if (reference.isBlank() || "activate_mechanic".equals(kind) && !com.wjz.worldsmith.core.content.WorldMechanicValidation.validId(reference))
                throw new IllegalArgumentException("Quest objective needs a stable content reference");
            text(targetLabel, 128, false);
            if (required < 1 || required > 1024 || progress < 0 || progress > required || kind.equals("fact") && required != 1)
                throw new IllegalArgumentException("Invalid server objective progress");
        }
    }
    public record Reward(String label, int count) {
        public Reward { text(label, 128, false); if (count < 1 || count > 64) throw new IllegalArgumentException("Invalid quest reward count"); }
    }
    public record Entry(String id, String title, String description, Status status, List<Objective> objectives, List<Reward> rewards,
            boolean optional, String destination, boolean branchChoice) {
        public Entry(String id, String title, String description, Status status, List<Objective> objectives, List<Reward> rewards) {
            this(id, title, description, status, objectives, rewards, false, null, false);
        }
        public Entry {
            QuestProtocol.id(id, false); text(title, 160, false); text(description, 4096, true); Objects.requireNonNull(status);
            if (destination != null) QuestProtocol.id(destination, false);
            objectives = List.copyOf(objectives); rewards = List.copyOf(rewards);
            if (objectives.isEmpty() || objectives.size() > MAX_OBJECTIVES || rewards.size() > MAX_REWARDS) throw new IllegalArgumentException("Quest entry exceeds bounds");
        }
    }
    public record Snapshot(String scope, String worldTitle, int requestId, long revision, List<Entry> quests, Feedback feedback, String message,
            boolean campaignComplete, String trackedQuest) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(Identifier.fromNamespaceAndPath("worldsmith", "quest_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> STREAM_CODEC = StreamCodec.of(QuestProtocol::writeSnapshot, QuestProtocol::readSnapshot);
        /** Source convenience; campaign completion is never inferred from a filtered list. */
        public Snapshot(String scope, String worldTitle, int requestId, long revision, List<Entry> quests, Feedback feedback, String message) {
            this(scope, worldTitle, requestId, revision, quests, feedback, message, false, null);
        }
        public Snapshot {
            QuestProtocol.scope(scope); text(worldTitle, 160, false); Objects.requireNonNull(feedback); text(message, 512, true);
            quests = List.copyOf(quests);
            if (requestId < 0 || revision < 0 || quests.size() > MAX_QUESTS || scope.isEmpty() && (!quests.isEmpty() || campaignComplete || trackedQuest != null))
                throw new IllegalArgumentException("Invalid quest journal snapshot");
            var ids = new HashSet<String>(); for (var quest : quests) if (!ids.add(quest.id)) throw new IllegalArgumentException("Duplicate quest journal entry");
            if (trackedQuest != null && !ids.contains(trackedQuest)) throw new IllegalArgumentException("Tracked quest must be discovered");
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static synchronized void registerTypes() {
        if (registered) return;
        PayloadTypeRegistry.serverboundPlay().register(Action.TYPE, Action.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(Snapshot.TYPE, Snapshot.STREAM_CODEC, MAX_SNAPSHOT_BYTES);
        registered = true;
    }
    private static void writeAction(RegistryFriendlyByteBuf buffer, Action value) {
        buffer.writeUtf(value.scope, 64); buffer.writeUtf(value.questId, 64); buffer.writeVarInt(value.kind.ordinal());
        buffer.writeVarInt(value.requestId); buffer.writeVarLong(value.expectedRevision);
    }
    private static Action readAction(RegistryFriendlyByteBuf buffer) {
        return new Action(buffer.readUtf(64), buffer.readUtf(64), enumeration(buffer, ActionKind.values()), buffer.readVarInt(), buffer.readVarLong());
    }
    private static void writeSnapshot(RegistryFriendlyByteBuf buffer, Snapshot value) {
        buffer.writeUtf(value.scope, 64); buffer.writeUtf(value.worldTitle, 160); buffer.writeVarInt(value.requestId); buffer.writeVarLong(value.revision);
        buffer.writeBoolean(value.campaignComplete); nullable(buffer, value.trackedQuest);
        buffer.writeVarInt(value.quests.size());
        for (var quest : value.quests) {
            buffer.writeUtf(quest.id, 64); buffer.writeUtf(quest.title, 160); buffer.writeUtf(quest.description, 4096); buffer.writeVarInt(quest.status.ordinal());
            buffer.writeBoolean(quest.optional); nullable(buffer, quest.destination); buffer.writeBoolean(quest.branchChoice);
            buffer.writeVarInt(quest.objectives.size());
            for (var objective : quest.objectives) {
                buffer.writeUtf(objective.kind, 24); buffer.writeUtf(objective.reference, 256); buffer.writeUtf(objective.targetLabel, 128);
                buffer.writeVarInt(objective.progress); buffer.writeVarInt(objective.required); buffer.writeBoolean(objective.optional);
            }
            buffer.writeVarInt(quest.rewards.size());
            for (var reward : quest.rewards) { buffer.writeUtf(reward.label, 128); buffer.writeVarInt(reward.count); }
        }
        buffer.writeVarInt(value.feedback.ordinal()); buffer.writeUtf(value.message, 512);
    }
    private static Snapshot readSnapshot(RegistryFriendlyByteBuf buffer) {
        String scope = buffer.readUtf(64), title = buffer.readUtf(160); int request = buffer.readVarInt(); long revision = buffer.readVarLong();
        boolean complete = buffer.readBoolean(); String tracked = nullable(buffer);
        int count = count(buffer, MAX_QUESTS); List<Entry> quests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buffer.readUtf(64), questTitle = buffer.readUtf(160), description = buffer.readUtf(4096); Status status = enumeration(buffer, Status.values());
            boolean optional = buffer.readBoolean(); String destination = nullable(buffer); boolean branchChoice = buffer.readBoolean();
            int objectivesCount = count(buffer, MAX_OBJECTIVES); List<Objective> objectives = new ArrayList<>(objectivesCount);
            for (int j = 0; j < objectivesCount; j++) objectives.add(new Objective(buffer.readUtf(24), buffer.readUtf(256), buffer.readUtf(128), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean()));
            int rewardsCount = count(buffer, MAX_REWARDS); List<Reward> rewards = new ArrayList<>(rewardsCount);
            for (int j = 0; j < rewardsCount; j++) rewards.add(new Reward(buffer.readUtf(128), buffer.readVarInt()));
            quests.add(new Entry(id, questTitle, description, status, objectives, rewards, optional, destination, branchChoice));
        }
        return new Snapshot(scope, title, request, revision, quests, enumeration(buffer, Feedback.values()), buffer.readUtf(512), complete, tracked);
    }
    private static void nullable(RegistryFriendlyByteBuf buffer, String value) { buffer.writeBoolean(value != null); if (value != null) buffer.writeUtf(value, 64); }
    private static String nullable(RegistryFriendlyByteBuf buffer) { return buffer.readBoolean() ? buffer.readUtf(64) : null; }
    private static int count(RegistryFriendlyByteBuf buffer, int maximum) {
        int count = buffer.readVarInt(); if (count < 0 || count > maximum) throw new IllegalArgumentException("Quest packet collection exceeds its limit"); return count;
    }
    private static <T> T enumeration(RegistryFriendlyByteBuf buffer, T[] values) {
        int ordinal = buffer.readVarInt(); if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("Unknown quest protocol enum value"); return values[ordinal];
    }
    private static void scope(String scope) {
        if (scope == null || !scope.isEmpty() && !scope.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid immutable quest world scope");
    }
    private static void id(String id, boolean emptyAllowed) {
        if (id == null || !(emptyAllowed && id.isEmpty()) && !com.wjz.worldsmith.core.content.QuestValidation.validId(id)) throw new IllegalArgumentException("Invalid quest journal id");
    }
    private static void text(String value, int maximum, boolean multiline) {
        if (value == null || value.length() > maximum || value.chars().anyMatch(c -> Character.isISOControl(c) && !(multiline && c == '\n')))
            throw new IllegalArgumentException("Invalid bounded quest journal text");
    }
}
