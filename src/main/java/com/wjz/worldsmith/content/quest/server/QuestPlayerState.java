package com.wjz.worldsmith.content.quest.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.content.QuestValidation;
import com.wjz.worldsmith.core.content.Quest;
import com.wjz.worldsmith.core.content.QuestObjective;
import com.wjz.worldsmith.core.content.WorldMechanicValidation;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable PLAYER attachment, serialized alongside that player's Inventory in the same entity NBT. */
public record QuestPlayerState(int schemaVersion, String bundleHash, long revision, Map<String, Progress> quests, Map<String, Integer> mechanicActivations) {
    private static final Codec<String> HASH = Codec.STRING.validate(value -> value.matches("[0-9a-f]{64}")
        ? DataResult.success(value) : DataResult.error(() -> "Invalid quest-state world scope"));
    private static final Codec<String> QUEST_ID = Codec.STRING.validate(value -> QuestValidation.validId(value)
        ? DataResult.success(value) : DataResult.error(() -> "Invalid persistent quest id"));
    private static final Codec<Map<String, Progress>> PROGRESS_MAP = Codec.unboundedMap(QUEST_ID, Progress.CODEC)
        .validate(value -> value.size() <= QuestValidation.MAX_QUESTS ? DataResult.success(value)
            : DataResult.error(() -> "Persistent quest count exceeds its budget"));
    private static final Codec<Map<String, Integer>> ACTIVATIONS = Codec.unboundedMap(QUEST_ID, Codec.intRange(1, QuestValidation.MAX_OBJECTIVE_COUNT))
        .validate(value -> value.size() <= WorldMechanicValidation.MAX_MECHANICS ? DataResult.success(value)
            : DataResult.error(() -> "Persistent mechanic fact count exceeds its budget"));
    public static final Codec<QuestPlayerState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.intRange(2, 2).fieldOf("schemaVersion").forGetter(QuestPlayerState::schemaVersion),
        HASH.fieldOf("bundleHash").forGetter(QuestPlayerState::bundleHash),
        Codec.LONG.validate(value -> value >= 0 ? DataResult.success(value) : DataResult.error(() -> "Quest revision must be nonnegative"))
            .fieldOf("revision").forGetter(QuestPlayerState::revision),
        PROGRESS_MAP.fieldOf("quests").forGetter(QuestPlayerState::quests),
        ACTIVATIONS.fieldOf("mechanicActivations").forGetter(QuestPlayerState::mechanicActivations)
    ).apply(instance, QuestPlayerState::new));

    public QuestPlayerState {
        if (schemaVersion != 2 || bundleHash == null || !bundleHash.matches("[0-9a-f]{64}") || revision < 0
            || quests == null || quests.size() > QuestValidation.MAX_QUESTS
            || mechanicActivations == null || mechanicActivations.size() > WorldMechanicValidation.MAX_MECHANICS)
            throw new IllegalArgumentException("Invalid persistent quest-state header");
        Map<String, Progress> copy = new LinkedHashMap<>();
        quests.forEach((id, progress) -> {
            if (id == null || !QuestValidation.validId(id) || progress == null) throw new IllegalArgumentException("Invalid persistent quest entry");
            copy.put(id, progress);
        });
        quests = Collections.unmodifiableMap(copy);
        Map<String, Integer> facts = new LinkedHashMap<>();
        mechanicActivations.forEach((id, count) -> {
            if (id == null || !WorldMechanicValidation.validId(id) || count == null || count < 1 || count > QuestValidation.MAX_OBJECTIVE_COUNT)
                throw new IllegalArgumentException("Invalid persistent mechanic activation fact");
            facts.put(id, count);
        });
        mechanicActivations = Collections.unmodifiableMap(facts);
    }

    public static QuestPlayerState empty(String bundleHash) { return new QuestPlayerState(2, bundleHash, 0, Map.of(), Map.of()); }

    public QuestPlayerState withProgress(String questId, Progress progress) {
        Map<String, Progress> next = new LinkedHashMap<>(quests); next.put(questId, progress);
        return new QuestPlayerState(schemaVersion, bundleHash, Math.incrementExact(revision), next, mechanicActivations);
    }

    /** Lifetime facts are independent of quest unlock order and capped at the largest objective. */
    public QuestPlayerState withMechanicActivation(String mechanicId) {
        if (!WorldMechanicValidation.validId(mechanicId)) throw new IllegalArgumentException("Invalid mechanic fact id");
        int count = mechanicActivations.getOrDefault(mechanicId, 0);
        if (count == QuestValidation.MAX_OBJECTIVE_COUNT) return this;
        Map<String, Integer> next = new LinkedHashMap<>(mechanicActivations); next.put(mechanicId, count + 1);
        return new QuestPlayerState(schemaVersion, bundleHash, Math.incrementExact(revision), quests, next);
    }

    public int mechanicActivationCount(String mechanicId) { return mechanicActivations.getOrDefault(mechanicId, 0); }

    public boolean questUnlocked(Quest quest) {
        if (quest.getPrerequisites().isEmpty()) return true;
        Progress previous = quests.get(quest.getPrerequisites().getFirst());
        return previous != null && previous.claimed();
    }

    /** Projection is read-only. Locked quests keep zero progress, but their lifetime facts are retained. */
    public Progress progressFor(Quest quest) {
        Progress value = quests.get(quest.getId());
        if (value == null) value = new Progress(Collections.nCopies(quest.getObjectives().size(), 0), false);
        if (!questUnlocked(quest)) return value;
        List<Integer> counts = new ArrayList<>(value.counts());
        for (int index = 0; index < quest.getObjectives().size(); index++) {
            if (quest.getObjectives().get(index) instanceof QuestObjective.ActivateMechanic activate)
                counts.set(index, Math.min(activate.getCount(), mechanicActivationCount(activate.getMechanic())));
        }
        return new Progress(counts, value.claimed());
    }

    public record Progress(List<Integer> counts, boolean claimed) {
        public static final Codec<Progress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, QuestValidation.MAX_OBJECTIVE_COUNT).listOf(1, QuestValidation.MAX_OBJECTIVES)
                .fieldOf("counts").forGetter(Progress::counts),
            Codec.BOOL.fieldOf("claimed").forGetter(Progress::claimed)
        ).apply(instance, Progress::new));
        public Progress {
            counts = List.copyOf(counts);
            if (counts.isEmpty() || counts.size() > QuestValidation.MAX_OBJECTIVES
                || counts.stream().anyMatch(count -> count < 0 || count > QuestValidation.MAX_OBJECTIVE_COUNT))
                throw new IllegalArgumentException("Invalid objective progress counts");
        }
    }
}
