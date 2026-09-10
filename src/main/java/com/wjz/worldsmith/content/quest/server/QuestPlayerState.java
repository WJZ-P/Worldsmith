package com.wjz.worldsmith.content.quest.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.content.QuestValidation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable PLAYER attachment, serialized alongside that player's Inventory in the same entity NBT. */
public record QuestPlayerState(int schemaVersion, String bundleHash, long revision, Map<String, Progress> quests) {
    private static final Codec<String> HASH = Codec.STRING.validate(value -> value.matches("[0-9a-f]{64}")
        ? DataResult.success(value) : DataResult.error(() -> "Invalid quest-state world scope"));
    private static final Codec<String> QUEST_ID = Codec.STRING.validate(value -> QuestValidation.validId(value)
        ? DataResult.success(value) : DataResult.error(() -> "Invalid persistent quest id"));
    private static final Codec<Map<String, Progress>> PROGRESS_MAP = Codec.unboundedMap(QUEST_ID, Progress.CODEC)
        .validate(value -> value.size() <= QuestValidation.MAX_QUESTS ? DataResult.success(value)
            : DataResult.error(() -> "Persistent quest count exceeds its budget"));
    public static final Codec<QuestPlayerState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.intRange(1, 1).fieldOf("schemaVersion").forGetter(QuestPlayerState::schemaVersion),
        HASH.fieldOf("bundleHash").forGetter(QuestPlayerState::bundleHash),
        Codec.LONG.validate(value -> value >= 0 ? DataResult.success(value) : DataResult.error(() -> "Quest revision must be nonnegative"))
            .fieldOf("revision").forGetter(QuestPlayerState::revision),
        PROGRESS_MAP.fieldOf("quests").forGetter(QuestPlayerState::quests)
    ).apply(instance, QuestPlayerState::new));

    public QuestPlayerState {
        if (schemaVersion != 1 || bundleHash == null || !bundleHash.matches("[0-9a-f]{64}") || revision < 0
            || quests == null || quests.size() > QuestValidation.MAX_QUESTS)
            throw new IllegalArgumentException("Invalid persistent quest-state header");
        Map<String, Progress> copy = new LinkedHashMap<>();
        quests.forEach((id, progress) -> {
            if (id == null || !QuestValidation.validId(id) || progress == null) throw new IllegalArgumentException("Invalid persistent quest entry");
            copy.put(id, progress);
        });
        quests = Collections.unmodifiableMap(copy);
    }

    public static QuestPlayerState empty(String bundleHash) { return new QuestPlayerState(1, bundleHash, 0, Map.of()); }

    public QuestPlayerState withProgress(String questId, Progress progress) {
        Map<String, Progress> next = new LinkedHashMap<>(quests); next.put(questId, progress);
        return new QuestPlayerState(schemaVersion, bundleHash, Math.incrementExact(revision), next);
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
