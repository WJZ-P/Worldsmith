package com.wjz.worldsmith.content.quest.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.content.QuestValidation;
import com.wjz.worldsmith.core.content.Quest;
import com.wjz.worldsmith.core.content.QuestObjective;
import com.wjz.worldsmith.core.content.QuestPrerequisiteMode;
import com.wjz.worldsmith.core.content.WorldMechanicValidation;
import java.util.*;

/** Persistent player story journey. An entry also records discovery; unknown quests have no entry. */
public record QuestPlayerState(int schemaVersion, String bundleHash, long revision, Map<String, Progress> quests,
        Map<String, Integer> mechanicActivations, Map<String, String> selectedBranches, String trackedQuest) {
    private static final Codec<String> HASH = Codec.STRING.validate(value -> value.matches("[0-9a-f]{64}")
        ? DataResult.success(value) : DataResult.error(() -> "Invalid quest-state world scope"));
    private static final Codec<String> QUEST_ID = Codec.STRING.validate(value -> QuestValidation.validId(value)
        ? DataResult.success(value) : DataResult.error(() -> "Invalid persistent quest id"));
    private static final Codec<Map<String, Progress>> PROGRESS_MAP = Codec.unboundedMap(QUEST_ID, Progress.CODEC)
        .validate(value -> value.size() <= QuestValidation.MAX_QUESTS ? DataResult.success(value) : DataResult.error(() -> "Too many persistent quests"));
    private static final Codec<Map<String, Integer>> ACTIVATIONS = Codec.unboundedMap(QUEST_ID, Codec.intRange(1, QuestValidation.MAX_OBJECTIVE_COUNT))
        .validate(value -> value.size() <= WorldMechanicValidation.MAX_MECHANICS ? DataResult.success(value) : DataResult.error(() -> "Too many mechanic facts"));
    private static final Codec<Map<String, String>> BRANCHES = Codec.unboundedMap(QUEST_ID, QUEST_ID)
        .validate(value -> value.size() <= QuestValidation.MAX_QUESTS ? DataResult.success(value) : DataResult.error(() -> "Too many selected branches"));
    public static final Codec<QuestPlayerState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.intRange(3, 3).fieldOf("schemaVersion").forGetter(QuestPlayerState::schemaVersion),
        HASH.fieldOf("bundleHash").forGetter(QuestPlayerState::bundleHash),
        Codec.LONG.validate(value -> value >= 0 ? DataResult.success(value) : DataResult.error(() -> "Negative quest revision"))
            .fieldOf("revision").forGetter(QuestPlayerState::revision),
        PROGRESS_MAP.fieldOf("quests").forGetter(QuestPlayerState::quests),
        ACTIVATIONS.fieldOf("mechanicActivations").forGetter(QuestPlayerState::mechanicActivations),
        BRANCHES.fieldOf("selectedBranches").forGetter(QuestPlayerState::selectedBranches),
        Codec.STRING.fieldOf("trackedQuest").forGetter(QuestPlayerState::trackedQuest)
    ).apply(instance, QuestPlayerState::new));

    /** Source convenience only: the sole on-disk schema is 3. */
    public QuestPlayerState(int schemaVersion, String bundleHash, long revision, Map<String, Progress> quests, Map<String, Integer> activations) {
        this(schemaVersion, bundleHash, revision, quests, activations, Map.of(), "");
    }
    public QuestPlayerState {
        if (schemaVersion != 3 || bundleHash == null || !bundleHash.matches("[0-9a-f]{64}") || revision < 0
            || quests == null || quests.size() > QuestValidation.MAX_QUESTS || mechanicActivations == null
            || mechanicActivations.size() > WorldMechanicValidation.MAX_MECHANICS || selectedBranches == null
            || selectedBranches.size() > QuestValidation.MAX_QUESTS || trackedQuest == null
            || !trackedQuest.isEmpty() && (!QuestValidation.validId(trackedQuest) || !quests.containsKey(trackedQuest)))
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
        Map<String, String> branches = new LinkedHashMap<>();
        selectedBranches.forEach((group, quest) -> {
            if (group == null || quest == null || !QuestValidation.validId(group) || !QuestValidation.validId(quest)) throw new IllegalArgumentException("Invalid selected branch");
            branches.put(group, quest);
        });
        selectedBranches = Collections.unmodifiableMap(branches);
    }

    public static QuestPlayerState empty(String bundleHash) { return new QuestPlayerState(3, bundleHash, 0, Map.of(), Map.of(), Map.of(), ""); }
    public QuestPlayerState touch() { return new QuestPlayerState(3, bundleHash, Math.incrementExact(revision), quests, mechanicActivations, selectedBranches, trackedQuest); }
    public QuestPlayerState withProgress(String questId, Progress progress) {
        if (progress.equals(quests.get(questId))) return this;
        Map<String, Progress> next = new LinkedHashMap<>(quests); next.put(questId, progress);
        return new QuestPlayerState(3, bundleHash, Math.incrementExact(revision), next, mechanicActivations, selectedBranches, trackedQuest);
    }
    public QuestPlayerState discover(Quest quest) {
        return quests.containsKey(quest.getId()) ? this : withProgress(quest.getId(), emptyProgress(quest));
    }
    public QuestPlayerState accept(Quest quest) {
        Progress previous = quests.get(quest.getId());
        if (previous == null || previous.accepted() || previous.claimed() || !questUnlocked(quest)) throw new IllegalStateException("Quest is not an available offer");
        Map<String, String> branches = new LinkedHashMap<>(selectedBranches);
        if (quest.getExclusiveGroup() != null) {
            String selected = branches.putIfAbsent(quest.getExclusiveGroup(), quest.getId());
            if (selected != null && !selected.equals(quest.getId())) throw new IllegalStateException("Another branch was selected");
        }
        Map<String, Progress> next = new LinkedHashMap<>(quests);
        next.put(quest.getId(), new Progress(previous.counts(), true, false, false));
        return new QuestPlayerState(3, bundleHash, Math.incrementExact(revision), next, mechanicActivations, branches, trackedQuest);
    }
    public QuestPlayerState decline(Quest quest) {
        Progress previous = quests.get(quest.getId());
        if (previous == null || previous.accepted() || previous.claimed()) throw new IllegalStateException("Only an unaccepted offer may be declined");
        return withProgress(quest.getId(), new Progress(previous.counts(), false, true, false));
    }
    public QuestPlayerState track(String questId) {
        if (!questId.isEmpty() && !quests.containsKey(questId)) throw new IllegalArgumentException("Undiscovered quest tracking");
        return questId.equals(trackedQuest) ? this : new QuestPlayerState(3, bundleHash, Math.incrementExact(revision), quests, mechanicActivations, selectedBranches, questId);
    }
    /** Lifetime facts remain independent of quest unlock and acceptance order. */
    public QuestPlayerState withMechanicActivation(String mechanicId) {
        if (!WorldMechanicValidation.validId(mechanicId)) throw new IllegalArgumentException("Invalid mechanic fact id");
        int count = mechanicActivations.getOrDefault(mechanicId, 0);
        if (count == QuestValidation.MAX_OBJECTIVE_COUNT) return this;
        Map<String, Integer> next = new LinkedHashMap<>(mechanicActivations); next.put(mechanicId, count + 1);
        return new QuestPlayerState(3, bundleHash, Math.incrementExact(revision), quests, next, selectedBranches, trackedQuest);
    }
    public int mechanicActivationCount(String mechanicId) { return mechanicActivations.getOrDefault(mechanicId, 0); }
    public boolean discovered(Quest quest) { return quests.containsKey(quest.getId()); }
    public boolean branchExcluded(Quest quest) {
        String selected = quest.getExclusiveGroup() == null ? null : selectedBranches.get(quest.getExclusiveGroup());
        return selected != null && !selected.equals(quest.getId());
    }
    public boolean questUnlocked(Quest quest) {
        return !branchExcluded(quest) && prerequisitesSatisfied(quest);
    }
    public boolean prerequisitesSatisfied(Quest quest) {
        if (quest.getPrerequisites().isEmpty()) return true;
        var completed = quest.getPrerequisites().stream().map(quests::get).map(progress -> progress != null && progress.claimed());
        return quest.getPrerequisiteMode() == QuestPrerequisiteMode.ANY ? completed.anyMatch(Boolean::booleanValue) : completed.allMatch(Boolean::booleanValue);
    }
    /** Propagate abandoned alternatives through the DAG; ANY merge remains live if another route can complete. */
    public Set<String> excluded(Map<String, Quest> definitions) {
        Set<String> result = new HashSet<>();
        for (int round = 0; round <= definitions.size(); round++) {
            boolean changed = false;
            for (Quest quest : definitions.values()) {
                boolean predecessorExcluded = !quest.getPrerequisites().isEmpty() && (quest.getPrerequisiteMode() == QuestPrerequisiteMode.ANY
                    ? quest.getPrerequisites().stream().allMatch(result::contains) : quest.getPrerequisites().stream().anyMatch(result::contains));
                if ((branchExcluded(quest) || predecessorExcluded) && result.add(quest.getId())) changed = true;
            }
            if (!changed) break;
        }
        return Set.copyOf(result);
    }
    public boolean campaignComplete(Map<String, Quest> definitions) {
        Set<String> excluded = excluded(definitions);
        boolean hasRequired = false;
        for (Quest quest : definitions.values()) {
            if (quest.getOptional() || excluded.contains(quest.getId())) continue;
            hasRequired = true; Progress progress = quests.get(quest.getId());
            if (progress == null || !progress.claimed()) return false;
        }
        return hasRequired;
    }
    /** Read-only mechanic projection. Story fact objectives are evaluated by the story runtime. */
    public Progress progressFor(Quest quest) {
        Progress value = quests.getOrDefault(quest.getId(), emptyProgress(quest));
        if (!questUnlocked(quest)) return value;
        List<Integer> counts = new ArrayList<>(value.counts());
        for (int index = 0; index < quest.getObjectives().size(); index++) {
            if (quest.getObjectives().get(index) instanceof QuestObjective.ActivateMechanic activate)
                counts.set(index, Math.min(activate.getCount(), mechanicActivationCount(activate.getMechanic())));
        }
        return new Progress(counts, value.accepted(), value.declined(), value.claimed());
    }
    static Progress emptyProgress(Quest quest) { return new Progress(Collections.nCopies(quest.getObjectives().size(), 0), false, false, false); }

    public record Progress(List<Integer> counts, boolean accepted, boolean declined, boolean claimed) {
        public static final Codec<Progress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, QuestValidation.MAX_OBJECTIVE_COUNT).listOf(1, QuestValidation.MAX_OBJECTIVES).fieldOf("counts").forGetter(Progress::counts),
            Codec.BOOL.fieldOf("accepted").forGetter(Progress::accepted),
            Codec.BOOL.fieldOf("declined").forGetter(Progress::declined),
            Codec.BOOL.fieldOf("claimed").forGetter(Progress::claimed)
        ).apply(instance, Progress::new));
        public Progress(List<Integer> counts, boolean claimed) { this(counts, true, false, claimed); }
        public Progress {
            counts = List.copyOf(counts);
            if (counts.isEmpty() || counts.size() > QuestValidation.MAX_OBJECTIVES
                || counts.stream().anyMatch(count -> count < 0 || count > QuestValidation.MAX_OBJECTIVE_COUNT)
                || accepted && declined || claimed && !accepted)
                throw new IllegalArgumentException("Invalid objective progress/lifecycle");
        }
        public Progress withCounts(List<Integer> values) { return new Progress(values, accepted, declined, claimed); }
        public Progress withClaimed() { return new Progress(counts, true, false, true); }
    }
}
