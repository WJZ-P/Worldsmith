package com.wjz.worldsmith.core.analysis

import com.wjz.worldsmith.core.content.CreatureCategory
import com.wjz.worldsmith.core.content.CreatureDefinition
import com.wjz.worldsmith.core.content.CreaturePassiveMode
import com.wjz.worldsmith.core.content.MechanicAction
import com.wjz.worldsmith.core.content.QuestObjective
import com.wjz.worldsmith.core.model.WorldsmithPack

/** One observation about how a world will play, with the evidence that produced it. */
data class PlayabilityFinding(
    val code: String,
    val message: String,
    val subjects: List<String> = emptyList(),
)

/** What a creature can do beyond standing somewhere, named so an author can see the gap. */
data class CreatureVerbs(val id: String, val verbs: List<String>)

data class PlayabilityReport(
    val mechanics: Int,
    val distinctMechanics: Int,
    val questObjectives: Map<String, Int>,
    val creatures: List<CreatureVerbs>,
    val landmarkStructures: Int,
    val repeatingStructures: Int,
    /** Relationships that play out whether or not the player is watching. */
    val livingLinks: Int,
    val unusedItems: List<String>,
    val findings: List<PlayabilityFinding>,
)

/**
 * Measures the symptoms of a world that is valid and dull.
 *
 * Every other check in Worldsmith asks whether a document is legal. None of
 * them can ask whether it is fun, and an author - human or model - spends its
 * effort on whatever is being checked. A pack can therefore pass every
 * validator while being six copies of one interaction, a quest line that is
 * mostly reading, and creatures that walk at random.
 *
 * Fun cannot be computed, but those symptoms can, and they are what this
 * reports. Nothing here blocks a write: the findings are handed back beside the
 * diagnostics so the author can see the gap while the design is still cheap to
 * change. There is deliberately no single score. A number would invite
 * optimising the number.
 */
object PlayabilityAnalyzer {
    /** Below this share of distinct interactions, the mechanics read as a template. */
    const val TEMPLATE_RATIO: Double = 0.5
    /** Above this share of fact objectives, the quest line is mostly reading. */
    const val READING_SHARE: Double = 0.5
    /** Above this share of creatures with no verb, the world stands still. */
    const val IDLE_SHARE: Double = 0.5

    private const val MIN_MECHANICS_FOR_TEMPLATE = 4
    private const val MIN_OBJECTIVES_FOR_READING = 6

    fun analyze(pack: WorldsmithPack): PlayabilityReport {
        val findings = mutableListOf<PlayabilityFinding>()

        // ---------------------------------------------------------------- mechanics
        val mechanics = pack.mechanics.mechanics
        val names = (pack.structures.structures.map { it.id } + mechanics.map { it.id } +
            pack.creatures.creatures.flatMap { listOf(it.id, it.displayName) } +
            pack.items.items.flatMap { listOf(it.id, it.displayName) }).filter { it.length >= 2 }
        val groups = mechanics.groupBy { mechanic ->
            // A mechanic's own id and title usually carry the place it is stamped onto.
            val own = mechanic.id.split('_', '-', '.', '/').filter { it.length >= 3 } + mechanic.displayName
            val text = normalise(mechanic.description, names + own)
            if (text.isNotEmpty()) "text:$text" else "shape:" + signature(mechanic)
        }
        val distinct = groups.size
        if (mechanics.size >= MIN_MECHANICS_FOR_TEMPLATE && distinct < mechanics.size * TEMPLATE_RATIO) {
            val repeated = groups.values.filter { it.size > 1 }.sortedByDescending { it.size }
            findings += PlayabilityFinding(
                "TEMPLATED_MECHANICS",
                "${mechanics.size} interactions but only $distinct distinct ones once names are removed; the same " +
                    "action is being stamped onto every place. A player learns it at the first landmark and has " +
                    "nothing left to discover at the rest. Give each place one interaction that could only happen there.",
                repeated.first().map { it.id },
            )
        }

        // ----------------------------------------------------------------- quests
        val objectives = pack.quests.quests.flatMap { it.objectives }
        val kinds = objectives.groupingBy { kindOf(it) }.eachCount()
        val facts = kinds["fact"] ?: 0
        if (objectives.size >= MIN_OBJECTIVES_FOR_READING && facts > objectives.size * READING_SHARE) {
            findings += PlayabilityFinding(
                "QUESTS_MOSTLY_READING",
                "$facts of ${objectives.size} quest objectives are story facts. A quest line that is mostly reading " +
                    "asks the player to travel and then gives them nothing to do on arrival. Trade some for things " +
                    "that change the world: build a pattern, deliver something gathered, drive something off.",
            )
        }

        // --------------------------------------------------------------- creatures
        val creatures = pack.creatures.creatures.map { CreatureVerbs(it.id, verbs(it)) }
        val idle = creatures.filter { it.verbs.isEmpty() }
        if (creatures.isNotEmpty() && idle.size > creatures.size * IDLE_SHARE) {
            findings += PlayabilityFinding(
                "IDLE_CREATURES",
                "${idle.size} of ${creatures.size} creatures only wander. A creature a player can ignore is scenery " +
                    "that moves. Give each one at least one reason to be where it is: something it eats, hunts, " +
                    "fears, guards or follows, or a time of day it keeps.",
                idle.map { it.id },
            )
        }

        // -------------------------------------------------------------- structures
        val structures = pack.structures.structures
        val landmarks = structures.count { it.placement.anchor != null }
        val repeating = structures.size - landmarks
        if (structures.isNotEmpty() && repeating == 0) {
            findings += PlayabilityFinding(
                "ONLY_LANDMARKS",
                "Every structure is pinned to an anchor, so each exists once and the world between them is empty. " +
                    "Exploration pays when walking in any direction can turn something up; add at least one small " +
                    "structure that repeats across the biomes - a shrine, a camp, a ruin.",
                structures.map { it.id },
            )
        }

        // ------------------------------------------------------------ living links
        val living = livingLinks(pack)
        if (pack.creatures.creatures.size >= 2 && living == 0) {
            findings += PlayabilityFinding(
                "NOTHING_HAPPENS_UNWATCHED",
                "No creature relates to any other creature, block, item or time of day. Everything in this world " +
                    "waits for the player, so nothing is ever found already happening. A predator that hunts a " +
                    "grazer, a grazer that eats a plant, a spirit that only walks at night - one relationship like " +
                    "that is worth more than several more quests.",
            )
        }

        // ------------------------------------------------------------------ items
        val used = usedItems(pack)
        val unused = pack.items.items
            .filter { it.equipment == null && it.consumable == null && it.actions.isEmpty() && it.abilityBindings.isEmpty() }
            .map { it.id }
            .filter { it !in used }
        if (unused.isNotEmpty()) {
            findings += PlayabilityFinding(
                "DEAD_END_ITEMS",
                "${unused.size} item(s) can be obtained but never used, spent, worn or handed in. An item that does " +
                    "nothing is a note in the inventory; make it a cost, a key, a gift someone wants, or a tool.",
                unused,
            )
        }

        return PlayabilityReport(
            mechanics = mechanics.size,
            distinctMechanics = distinct,
            questObjectives = kinds,
            creatures = creatures,
            landmarkStructures = landmarks,
            repeatingStructures = repeating,
            livingLinks = living,
            unusedItems = unused,
            findings = findings,
        )
    }

    /**
     * What a creature does, as a list of words.
     *
     * Wandering is not a verb: every creature does it, so it cannot tell two
     * creatures apart or give a player a reason to care about either.
     */
    fun verbs(creature: CreatureDefinition): List<String> = buildList {
        if (creature.category == CreatureCategory.HOSTILE) add("attacks players")
        if (creature.behavior.passiveMode == CreaturePassiveMode.FLEE_PLAYERS) add("flees players")
        if (creature.ability != null || creature.abilityBindings.isNotEmpty()) add("uses an ability")
        if (creature.boss != null) add("boss")
        addAll(creature.behavior.drives.verbs())
    }

    /** Relationships that need no player: creature to creature, to block, to item and to time. */
    fun livingLinks(pack: WorldsmithPack): Int =
        pack.creatures.creatures.sumOf { it.behavior.drives.links() }

    private fun usedItems(pack: WorldsmithPack): Set<String> = buildSet {
        pack.mechanics.mechanics.forEach { mechanic ->
            mechanic.rules.forEach { rule -> rule.heldItem?.let { add(bare(it.item)) } }
        }
        pack.quests.quests.forEach { quest ->
            quest.objectives.filterIsInstance<QuestObjective.DeliverItem>().forEach { add(bare(it.item)) }
        }
        pack.creatures.creatures.forEach { creature -> creature.behavior.drives.temptedBy.forEach { add(bare(it)) } }
    }

    private fun kindOf(objective: QuestObjective): String = when (objective) {
        is QuestObjective.KillCreature -> "kill_creature"
        is QuestObjective.DeliverItem -> "deliver_item"
        is QuestObjective.ActivateMechanic -> "activate_mechanic"
        is QuestObjective.Fact -> "fact"
    }

    /** The shape of an interaction with every name taken out. */
    private fun signature(mechanic: com.wjz.worldsmith.core.content.WorldMechanicDefinition): String =
        mechanic.rules.joinToString("|") { rule ->
            rule.event.name + ":" + rule.pattern.map { it.block.block }.sorted() + ":" +
                rule.actions.map { action ->
                    when (action) {
                        is MechanicAction.SetBlock -> "set:" + action.block.block
                        is MechanicAction.SpawnCreature -> "spawn"
                        is MechanicAction.GiveItem -> "give"
                        is MechanicAction.RunProgram -> "program"
                    }
                } + ":" + (rule.heldItem != null)
        }

    /** Lowercase, names removed, punctuation and digits dropped: what is left is the template. */
    private fun normalise(text: String, names: List<String>): String {
        var result = text.lowercase()
        names.sortedByDescending { it.length }.forEach { result = result.replace(it.lowercase(), "") }
        return result.filter { it.isLetter() }
    }

    private fun bare(id: String): String = id.substringAfter("worldsmith:item/").substringAfterLast('/')
}
