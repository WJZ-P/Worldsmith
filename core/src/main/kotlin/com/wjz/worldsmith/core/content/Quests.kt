package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One bounded main line, not a branching quest graph or a script/action interpreter. */
@Serializable
data class QuestLibrary @JvmOverloads constructor(val schemaVersion: Int = 1, val quests: List<Quest> = emptyList())

@Serializable
data class Quest @JvmOverloads constructor(
    val id: String,
    val title: String,
    val description: String,
    val prerequisites: List<String> = emptyList(),
    val objectives: List<QuestObjective>,
    val rewards: List<QuestReward> = emptyList(),
    val themeBeat: String? = null,
)

@Serializable
sealed interface QuestObjective {
    val count: Int

    @Serializable @SerialName("kill_creature")
    data class KillCreature @JvmOverloads constructor(val creature: String, override val count: Int = 1) : QuestObjective

    /** Progress means items actually consumed by a player's explicit delivery action, not items merely held. */
    @Serializable @SerialName("deliver_item")
    data class DeliverItem @JvmOverloads constructor(val item: String, override val count: Int = 1) : QuestObjective
}

/** One full item stack per reward entry. Rewards are granted by a separate once-only claim action. */
@Serializable
data class QuestReward @JvmOverloads constructor(val item: String, val count: Int = 1)

object QuestValidation {
    const val MAX_QUESTS = 64
    const val MAX_OBJECTIVES = 8
    const val MAX_REWARDS = 16
    const val MAX_OBJECTIVE_COUNT = 1024
    private val ID = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    private val CREATURE_ID = Regex("[a-z0-9][a-z0-9_./-]{0,95}")
    private val ITEM = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

    @JvmStatic fun validate(library: QuestLibrary): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) { add(Diagnostic(path, code, DiagnosticSeverity.ERROR, message)) }
        if (library.schemaVersion != 1) error("schemaVersion", "quests.schema", "Main-line quest schema must be 1")
        if (library.quests.size > MAX_QUESTS) {
            error("quests", "quests.capacity", "At most $MAX_QUESTS quests per world")
            return@buildList
        }
        val byId = linkedMapOf<String, Quest>()
        library.quests.forEachIndexed { i, quest ->
            val path = "quests[$i]"
            if (!validId(quest.id)) error("$path.id", "quests.id", "Use a lowercase local quest id, 1..64 characters, with no reserved path sequence")
            if (byId.putIfAbsent(quest.id, quest) != null) error("$path.id", "quests.duplicate", "Quest '${quest.id}' is defined more than once")
            if (quest.title.isBlank() || quest.title.length > 160 || quest.title.any(Char::isISOControl))
                error("$path.title", "quests.title", "Quest titles need 1..160 printable characters")
            if (quest.description.isBlank() || quest.description.length > 4096 || quest.description.any { it.isISOControl() && it != '\n' })
                error("$path.description", "quests.description", "Quest descriptions need 1..4096 plain-text characters")
            if (quest.prerequisites.size > 1) error("$path.prerequisites", "quests.prerequisite_count", "A main-line quest has at most one prerequisite")
            quest.prerequisites.take(1).forEachIndexed { j, id -> if (!validId(id)) error("$path.prerequisites[$j]", "quests.prerequisite_id", "Use an existing local quest id") }
            if (quest.themeBeat != null && !ID.matches(quest.themeBeat)) error("$path.themeBeat", "quests.theme_beat", "Use a local narrative beat id from the world's theme")
            if (quest.objectives.size !in 1..MAX_OBJECTIVES) error("$path.objectives", "quests.objective_count", "Each quest needs 1..$MAX_OBJECTIVES objectives")
            quest.objectives.take(MAX_OBJECTIVES).forEachIndexed { j, objective ->
                val at = "$path.objectives[$j]"
                if (objective.count !in 1..MAX_OBJECTIVE_COUNT) error("$at.count", "quests.objective_quantity", "Objective counts must be 1..$MAX_OBJECTIVE_COUNT; deliveries may be completed in several actual contributions")
                when (objective) {
                    is QuestObjective.KillCreature -> if (!CREATURE_ID.matches(objective.creature) || !WorldContentRegistry.validName(objective.creature))
                        error("$at.creature", "quests.creature_reference", "Use an existing normalized local creature id")
                    is QuestObjective.DeliverItem -> if (!validItemReference(objective.item))
                        error("$at.item", "quests.item_reference", "Use a non-air native item or a logical world item/block-item alias, never raw hosts or state properties")
                }
            }
            if (quest.rewards.size > MAX_REWARDS) error("$path.rewards", "quests.reward_count", "At most $MAX_REWARDS item-stack reward entries per quest")
            quest.rewards.take(MAX_REWARDS).forEachIndexed { j, reward ->
                if (!validItemReference(reward.item)) error("$path.rewards[$j].item", "quests.item_reference", "Use a non-air native item or a logical world item/block-item alias")
                if (reward.count !in 1..64) error("$path.rewards[$j].count", "quests.reward_quantity", "Each reward is one stack of 1..64 items and must respect the item's stack limit")
            }
        }
        if (byId.isEmpty()) return@buildList
        library.quests.forEachIndexed { i, quest -> quest.prerequisites.take(1).forEach { id ->
            if (id !in byId) error("quests[$i].prerequisites", "quests.prerequisite_missing", "Unknown prerequisite quest '$id'")
        } }
        val roots = byId.values.filter { it.prerequisites.isEmpty() }
        if (roots.size != 1) error("quests", "quests.root_count", "A nonempty main line needs exactly one root quest")
        val children = byId.values.flatMap { quest -> quest.prerequisites.take(1).map { it to quest.id } }
            .groupBy({ it.first }, { it.second })
        children.filterValues { it.distinct().size > 1 }.forEach { (id, next) ->
            error("quests", "quests.branching", "Quest '$id' branches into ${next.distinct().joinToString()}; this module supports one linear main line")
        }
        val cycles = linkedSetOf<String>()
        byId.keys.forEach { start ->
            val seen = linkedSetOf<String>(); var current: String? = start
            while (current != null && current in byId) {
                if (!seen.add(current)) { cycles += current; break }
                current = byId.getValue(current).prerequisites.firstOrNull()
            }
        }
        if (cycles.isNotEmpty()) error("quests", "quests.cycle", "Quest prerequisite cycle includes ${cycles.joinToString()}")
        if (roots.size == 1) {
            val reachable = linkedSetOf<String>(); val pending = ArrayDeque<String>(); pending.add(roots.single().id)
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (reachable.add(id)) children[id].orEmpty().forEach(pending::addLast)
            }
            if (reachable.size != byId.size) error("quests", "quests.disconnected", "Every quest must belong to the same root-to-finale main line; unreachable: ${(byId.keys - reachable).joinToString()}")
        }
    }

    @JvmStatic fun freeze(library: QuestLibrary): QuestLibrary = library.copy(quests = java.util.List.copyOf(library.quests.map {
        it.copy(prerequisites = java.util.List.copyOf(it.prerequisites), objectives = java.util.List.copyOf(it.objectives), rewards = java.util.List.copyOf(it.rewards))
    }))

    /** Stable graph order rather than incidental author JSON array order; validation precedes use. */
    @JvmStatic fun ordered(library: QuestLibrary): List<Quest> {
        val errors = validate(library)
        require(errors.isEmpty()) { errors.joinToString("; ") { "${it.path}: ${it.message}" } }
        if (library.quests.isEmpty()) return emptyList()
        val byPrerequisite = library.quests.filter { it.prerequisites.isNotEmpty() }.associateBy { it.prerequisites.single() }
        val result = mutableListOf<Quest>(); var next: Quest? = library.quests.single { it.prerequisites.isEmpty() }
        while (next != null) { result += next; next = byPrerequisite[next.id] }
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
