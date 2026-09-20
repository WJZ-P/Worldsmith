package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.story.*
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server-owned bounded story DAG. Conditions observe shared facts, not a second interpreter. */
@Serializable
data class QuestLibrary @JvmOverloads constructor(val schemaVersion: Int = 2, val quests: List<Quest> = emptyList())

@Serializable enum class QuestPrerequisiteMode { ALL, ANY }

@Serializable
data class Quest @JvmOverloads constructor(
    val id: String,
    val title: String,
    val description: String,
    val prerequisites: List<String> = emptyList(),
    val objectives: List<QuestObjective>,
    val rewards: List<QuestReward> = emptyList(),
    val themeBeat: String? = null,
    val optional: Boolean = false,
    val manualAccept: Boolean = false,
    val exclusiveGroup: String? = null,
    val discoverWhen: StoryCondition = StoryCondition.Always,
    val availableWhen: StoryCondition = StoryCondition.Always,
    val onAccept: List<StoryFactChange> = emptyList(),
    val onClaim: List<StoryFactChange> = emptyList(),
    val destination: String? = null,
    val prerequisiteMode: QuestPrerequisiteMode = QuestPrerequisiteMode.ALL,
)

@Serializable
sealed interface QuestObjective {
    val count: Int
    val optional: Boolean

    @Serializable @SerialName("kill_creature")
    data class KillCreature @JvmOverloads constructor(val creature: String, override val count: Int = 1, override val optional: Boolean = false) : QuestObjective

    /** Items actually consumed by an explicit delivery, not items merely held. */
    @Serializable @SerialName("deliver_item")
    data class DeliverItem @JvmOverloads constructor(val item: String, override val count: Int = 1, override val optional: Boolean = false) : QuestObjective

    /** Observes committed interactions, including activations before this quest unlocked. */
    @Serializable @SerialName("activate_mechanic")
    data class ActivateMechanic @JvmOverloads constructor(val mechanic: String, override val count: Int = 1, override val optional: Boolean = false) : QuestObjective

    /** Live read of a declared story condition; no duplicate fact progress is persisted. */
    @Serializable @SerialName("fact")
    data class Fact @JvmOverloads constructor(val label: String, val condition: StoryCondition, override val optional: Boolean = false) : QuestObjective {
        override val count: Int get() = 1
    }
}

/** One full item stack per reward entry, granted in the same server transaction as onClaim. */
@Serializable
data class QuestReward @JvmOverloads constructor(val item: String, val count: Int = 1)

object QuestValidation {
    const val MAX_QUESTS = 64
    const val MAX_OBJECTIVES = 8
    const val MAX_REWARDS = 16
    const val MAX_PREREQUISITES = 16
    const val MAX_CHANGES = 32
    const val MAX_OBJECTIVE_COUNT = 1024
    private val ID = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    private val CREATURE_ID = Regex("[a-z0-9][a-z0-9_./-]{0,95}")
    private val ITEM = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

    @JvmStatic fun validate(library: QuestLibrary): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) { add(Diagnostic(path, code, DiagnosticSeverity.ERROR, message)) }
        if (library.schemaVersion != 2) error("schemaVersion", "quests.schema", "Story quest schema must be 2")
        if (library.quests.size > MAX_QUESTS) {
            error("quests", "quests.capacity", "At most $MAX_QUESTS quests per world")
            return@buildList
        }
        val byId = linkedMapOf<String, Quest>()
        library.quests.forEachIndexed { i, quest ->
            val path = "quests[$i]"
            if (!validId(quest.id)) error("$path.id", "quests.id", "Use a normalized lowercase local quest id")
            if (byId.putIfAbsent(quest.id, quest) != null) error("$path.id", "quests.duplicate", "Quest '${quest.id}' is defined more than once")
            if (quest.title.isBlank() || quest.title.length > 160 || quest.title.any(Char::isISOControl))
                error("$path.title", "quests.title", "Quest titles need 1..160 printable characters")
            if (quest.description.isBlank() || quest.description.length > 4096 || quest.description.any { it.isISOControl() && it != '\n' })
                error("$path.description", "quests.description", "Quest descriptions need 1..4096 plain-text characters")
            if (quest.prerequisites.size > MAX_PREREQUISITES || quest.prerequisites.distinct().size != quest.prerequisites.size)
                error("$path.prerequisites", "quests.prerequisite_count", "Use at most $MAX_PREREQUISITES distinct prerequisites")
            quest.prerequisites.take(MAX_PREREQUISITES).forEachIndexed { j, id ->
                if (!validId(id) || id == quest.id) error("$path.prerequisites[$j]", "quests.prerequisite_id", "Use another existing local quest id")
            }
            if (quest.themeBeat != null && !validId(quest.themeBeat)) error("$path.themeBeat", "quests.theme_beat", "Use a local narrative beat id")
            if (quest.destination != null && !validId(quest.destination)) error("$path.destination", "quests.destination", "Use a declared story place id")
            if (quest.exclusiveGroup != null) {
                if (!validId(quest.exclusiveGroup)) error("$path.exclusiveGroup", "quests.exclusive_group", "Use a normalized branch group id")
                if (!quest.manualAccept) error("$path.manualAccept", "quests.branch_accept", "Exclusive branches require explicit acceptance; automatic discovery never chooses for the player")
            }
            addAll(StoryConditions.validate(quest.discoverWhen, "$path.discoverWhen"))
            addAll(StoryConditions.validate(quest.availableWhen, "$path.availableWhen"))
            for ((field, changes) in listOf("onAccept" to quest.onAccept, "onClaim" to quest.onClaim)) {
                if (changes.size > MAX_CHANGES) error("$path.$field", "quests.changes", "At most $MAX_CHANGES fact changes in a quest transition")
                if (changes.map { it.fact }.distinct().size != changes.size) error("$path.$field", "quests.change_conflict", "Each transition changes a fact at most once")
                changes.take(MAX_CHANGES).forEachIndexed { j, change ->
                    if (!validId(change.fact.id) || change.fact.subject?.let { !validId(it) } == true)
                        error("$path.$field[$j]", "quests.change_reference", "Use a declared normalized fact and subject")
                }
            }
            if (quest.objectives.size !in 1..MAX_OBJECTIVES) error("$path.objectives", "quests.objective_count", "Each quest needs 1..$MAX_OBJECTIVES objectives")
            if (quest.objectives.isNotEmpty() && quest.objectives.all { it.optional })
                error("$path.objectives", "quests.required_objective", "At least one objective must be required")
            quest.objectives.take(MAX_OBJECTIVES).forEachIndexed { j, objective ->
                val at = "$path.objectives[$j]"
                if (objective.count !in 1..MAX_OBJECTIVE_COUNT) error("$at.count", "quests.objective_quantity", "Objective counts must be 1..$MAX_OBJECTIVE_COUNT")
                when (objective) {
                    is QuestObjective.KillCreature -> if (!CREATURE_ID.matches(objective.creature) || !WorldContentRegistry.validName(objective.creature))
                        error("$at.creature", "quests.creature_reference", "Use an existing normalized local creature id")
                    is QuestObjective.DeliverItem -> if (!validItemReference(objective.item))
                        error("$at.item", "quests.item_reference", "Use a non-air native item or logical world item/block-item alias")
                    is QuestObjective.ActivateMechanic -> if (!WorldMechanicValidation.validId(objective.mechanic))
                        error("$at.mechanic", "quests.mechanic_reference", "Use an existing normalized local mechanic id")
                    is QuestObjective.Fact -> {
                        if (objective.label.isBlank() || objective.label.length > 128 || objective.label.any(Char::isISOControl))
                            error("$at.label", "quests.fact_label", "Fact objectives need a bounded player-facing label")
                        addAll(StoryConditions.validate(objective.condition, "$at.condition"))
                    }
                }
            }
            if (quest.rewards.size > MAX_REWARDS) error("$path.rewards", "quests.reward_count", "At most $MAX_REWARDS item-stack reward entries")
            quest.rewards.take(MAX_REWARDS).forEachIndexed { j, reward ->
                if (!validItemReference(reward.item)) error("$path.rewards[$j].item", "quests.item_reference", "Use a non-air native item or logical world item/block-item alias")
                if (reward.count !in 1..64) error("$path.rewards[$j].count", "quests.reward_quantity", "Each reward is one stack of 1..64 items")
            }
        }
        library.quests.forEachIndexed { i, quest -> quest.prerequisites.take(MAX_PREREQUISITES).forEach { id ->
            if (id !in byId) error("quests[$i].prerequisites", "quests.prerequisite_missing", "Unknown prerequisite quest '$id'")
        } }
        val visiting = hashSetOf<String>(); val visited = hashSetOf<String>(); val cycles = linkedSetOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            if (!visiting.add(id)) { cycles += id; return }
            byId[id]?.prerequisites?.take(MAX_PREREQUISITES)?.forEach(::visit)
            visiting.remove(id); visited.add(id)
        }
        byId.keys.forEach(::visit)
        if (cycles.isNotEmpty()) error("quests", "quests.cycle", "Quest prerequisite cycle includes ${cycles.joinToString()}")
        if (cycles.isEmpty()) {
            val memo = mutableMapOf<String, Map<String, String>>()
            val indices = library.quests.withIndex().associate { it.value.id to it.index }
            fun mandatory(quest: Quest): Map<String, String> {
                memo[quest.id]?.let { return it }
                val parents = quest.prerequisites.take(MAX_PREREQUISITES).mapNotNull { byId[it]?.let(::mandatory) }
                val choices = linkedMapOf<String, String>()
                if (quest.prerequisiteMode == QuestPrerequisiteMode.ANY) {
                    parents.firstOrNull()?.forEach { (group, choice) -> if (parents.all { it[group] == choice }) choices[group] = choice }
                } else parents.forEach { parent -> parent.forEach { (group, choice) ->
                    val previous = choices.putIfAbsent(group, choice)
                    if (previous != null && previous != choice)
                        error("quests[${indices[quest.id]}].prerequisites", "quests.exclusive_prerequisites", "An ALL merge requires contradictory exclusive ancestors; use ANY to merge alternatives")
                } }
                quest.exclusiveGroup?.let { group ->
                    if (choices[group]?.let { it != quest.id } == true)
                        error("quests[${indices[quest.id]}].exclusiveGroup", "quests.branch_dependency", "A branch cannot require a previously accepted alternative in its own group")
                    choices[group] = quest.id
                }
                memo[quest.id] = choices; return choices
            }
            byId.values.forEach(::mandatory)
        }
    }

    /** Cross-domain semantic checks share the immutable story library used by native execution. */
    @JvmStatic fun validateStory(library: QuestLibrary, story: StoryLibrary): List<Diagnostic> = buildList {
        library.quests.forEachIndexed { i, quest ->
            val path = "quests.quests[$i]"
            addAll(StoryValidation.validateCondition(quest.discoverWhen, story, "$path.discoverWhen"))
            addAll(StoryValidation.validateCondition(quest.availableWhen, story, "$path.availableWhen"))
            addAll(StoryValidation.validateChanges(quest.onAccept, story, "$path.onAccept"))
            addAll(StoryValidation.validateChanges(quest.onClaim, story, "$path.onClaim"))
            quest.objectives.forEachIndexed { j, objective -> if (objective is QuestObjective.Fact)
                addAll(StoryValidation.validateCondition(objective.condition, story, "$path.objectives[$j].condition")) }
            if (quest.destination != null && story.places.none { it.id == quest.destination })
                add(Diagnostic("$path.destination", "quests.destination_missing", DiagnosticSeverity.ERROR, "Unknown story place '${quest.destination}'"))
            val refs = StoryConditions.references(quest.discoverWhen) + StoryConditions.references(quest.availableWhen) +
                quest.objectives.filterIsInstance<QuestObjective.Fact>().flatMap { StoryConditions.references(it.condition) } +
                (quest.onAccept + quest.onClaim).map { it.fact }
            if (refs.any { ref -> ref.subject == null && story.facts.any { it.id == ref.id && it.scope == StoryFactScope.CHARACTER } })
                add(Diagnostic(path, "quests.character_subject_required", DiagnosticSeverity.ERROR,
                    "Quest conditions and consequences have no implicit speaker; character facts require an explicit subject"))
        }
    }

    @JvmStatic fun freeze(library: QuestLibrary): QuestLibrary = library.copy(quests = java.util.List.copyOf(library.quests.map { quest ->
        quest.copy(prerequisites = java.util.List.copyOf(quest.prerequisites), objectives = java.util.List.copyOf(quest.objectives.map {
            if (it is QuestObjective.Fact) it.copy(condition = StoryConditions.freeze(it.condition)) else it
        }), rewards = java.util.List.copyOf(quest.rewards), discoverWhen = StoryConditions.freeze(quest.discoverWhen),
            availableWhen = StoryConditions.freeze(quest.availableWhen), onAccept = java.util.List.copyOf(quest.onAccept), onClaim = java.util.List.copyOf(quest.onClaim))
    }))

    /** Stable topological order. Independent roots preserve authored order, never become prerequisites. */
    @JvmStatic fun ordered(library: QuestLibrary): List<Quest> {
        val errors = validate(library)
        require(errors.isEmpty()) { errors.joinToString("; ") { "${it.path}: ${it.message}" } }
        val byId = library.quests.associateBy { it.id }; val visited = hashSetOf<String>(); val result = mutableListOf<Quest>()
        fun add(quest: Quest) {
            if (!visited.add(quest.id)) return
            quest.prerequisites.forEach { add(byId.getValue(it)) }; result += quest
        }
        library.quests.forEach(::add)
        return java.util.List.copyOf(result)
    }

    @JvmStatic fun validId(value: String): Boolean = ID.matches(value) && ".." !in value && !value.endsWith('.')
    @JvmStatic fun validItemReference(value: String): Boolean {
        if (value.length !in 1..256 || !ITEM.matches(value) || value == "minecraft:air" ||
            value.substringAfter(':').split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            value.startsWith("worldsmith:content/item/") || value.startsWith("worldsmith:content/block/")) return false
        if (value.startsWith(CustomItemValidation.LOGICAL_PREFIX)) return CustomItemValidation.validId(value.removePrefix(CustomItemValidation.LOGICAL_PREFIX))
        if (value.startsWith(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX)) return ID.matches(value.removePrefix(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX))
        return true
    }
}
