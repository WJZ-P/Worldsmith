package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.AnchorPlacement
import com.wjz.worldsmith.core.model.TerrainShape
import com.wjz.worldsmith.core.model.WorldsmithPack

/**
 * Bounded constructive production proof. Native materials are external; logical inputs require a real
 * positive source. Recipes are full initial-state-to-action paths, not disconnected action declarations.
 * Finite inputs are consumed, state-path alternatives are tried on snapshots, and producer cycles never
 * invent their own first offering. This proves a configured route, not a generated location or loot roll.
 */
object WorldMechanicReachability {
    data class Proof(val mechanics: Set<String>, val creatures: Set<String>)

    /** Coverage queries are independent possible routes; their hypothetical costs are not combined. */
    fun analyze(pack: WorldsmithPack, inventory: DesignInventory): Proof {
        if (pack.mechanics.mechanics.isEmpty() || WorldMechanicValidation.validate(pack.mechanics).isNotEmpty()) return Proof(emptySet(), emptySet())
        val current = planner(pack, inventory)
        val mechanics = linkedSetOf<String>()
        val creatures = linkedSetOf<String>()
        val summoned = pack.mechanics.mechanics.flatMap { it.rules }.flatMap { it.actions }
            .filterIsInstance<MechanicAction.SpawnCreature>().map { it.creature }.distinct()
        fun inspect() {
            pack.mechanics.mechanics.forEach { if (it.id !in mechanics && current.fork().activate(it.id, 1)) mechanics += it.id }
            summoned.forEach { if (it !in creatures && current.fork().ensureCreature(it, 1)) creatures += it }
        }
        inspect()
        if (QuestValidation.validate(pack.quests).isEmpty()) {
            for (quest in QuestValidation.ordered(pack.quests)) {
                if (!current.satisfyQuest(quest)) break
                quest.rewards.forEach { reward -> logicalItem(reward.item)?.let { current.grant(it, reward.count.toLong()) } }
                inspect()
            }
        }
        return Proof(java.util.Set.copyOf(mechanics), java.util.Set.copyOf(creatures))
    }

    internal fun logicalItem(reference: String): ContentKey? = when {
        reference.startsWith(ExistingWorldContentModules.LOCAL_ITEM_PREFIX) -> ContentKey("item", reference.removePrefix(ExistingWorldContentModules.LOCAL_ITEM_PREFIX))
        reference.startsWith(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX) -> ContentKey("block_item", reference.removePrefix(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX))
        else -> null
    }

    internal fun planner(pack: WorldsmithPack, inventory: DesignInventory): Planner {
        val biomes = pack.biomes.biomes.map { it.id }.toSet()
        val structures = pack.structures.structures.filter {
            (it.placement.region?.chance ?: 1.0) > 0 && it.placement.biomes.any(biomes::contains)
        }
        val anchors = (pack.terrain.shape as? TerrainShape.Procedural)?.anchors.orEmpty().associateBy { it.id }
        val finite = structures.filter { structure -> structure.placement.anchor?.let { anchor ->
            val placement = anchors[anchor.id]?.placement
            placement is AnchorPlacement.Fixed || placement is AnchorPlacement.Line
        } == true }.map { it.id }.toSet()
        val activeStructures = structures.map { it.id }.toSet()
        val featureDefinitions = pack.features.features.associateBy { it.id }
        val features = pack.biomes.biomes.flatMap { biome -> biome.features.filter { reference ->
            featureDefinitions[reference.feature]?.let { (reference.density ?: it.density) > 0 } == true
        }.map { it.feature } }.toSet()
        val baseCreatures = inventory.naturalCreatures + inventory.structureCreatures
        val repeatable = linkedSetOf<ContentKey>()
        val stock = linkedMapOf<ContentKey, Long>()
        finite.forEach { id -> inventory.structureSupplies[id].orEmpty().forEach { (key, count) -> stock[key] = (stock[key] ?: 0L) + count } }
        inventory.links.forEach { link -> when (link.relation) {
            DesignRelation.DROPS_ITEM -> if (link.from.id in baseCreatures) repeatable += link.to
            DesignRelation.CONTAINS_REWARD -> if (link.from.id in activeStructures && link.from.id !in finite) repeatable += link.to
            DesignRelation.USES_BLOCK -> if (when (link.from.kind) {
                "terrain" -> true
                "biome" -> link.from.id in biomes
                "feature" -> link.from.id in features
                "structure" -> link.from.id in activeStructures && link.from.id !in finite
                else -> false // A mechanic's mere material reference is not a world supply.
            }) repeatable += ContentKey("block_item", link.to.id)
            else -> Unit
        } }
        val validMechanics = if (WorldMechanicValidation.validate(pack.mechanics).isEmpty()) pack.mechanics.mechanics else emptyList()
        return Planner(pack, validMechanics, biomes, baseCreatures, repeatable, stock)
    }

    internal class Planner private constructor(
        private val pack: WorldsmithPack,
        private val mechanics: List<WorldMechanicDefinition>,
        private val biomes: Set<String>,
        private val baseCreatures: Set<String>,
        val repeatable: Set<ContentKey>,
        private val stock: MutableMap<ContentKey, Long>,
        private val creatureStock: MutableMap<String, Long>,
        private val facts: MutableMap<String, Int>,
        private val questKillCredits: MutableMap<String, Int>,
        private val stateSupply: MutableMap<StateSupply, Long>,
        private val budget: Budget,
    ) {
        internal constructor(pack: WorldsmithPack, mechanics: List<WorldMechanicDefinition>, biomes: Set<String>, baseCreatures: Set<String>, repeatable: Set<ContentKey>, stock: Map<ContentKey, Long>) :
            this(pack, mechanics.sortedBy { it.id }, biomes, baseCreatures, repeatable, stock.toMutableMap(), linkedMapOf(), linkedMapOf(), linkedMapOf(), linkedMapOf(), Budget())

        val initialSupply: Map<ContentKey, Long> = stock.toMap()
        val exhausted: Boolean get() = budget.exhausted
        fun available(key: ContentKey): Long = if (key in repeatable) Long.MAX_VALUE else stock[key] ?: 0L
        fun grant(key: ContentKey, count: Long) { if (count > 0) stock[key] = ((stock[key] ?: 0L) + count).coerceAtMost(MAX_SUPPLY) }
        fun fork(): Planner = Planner(pack, mechanics, biomes, baseCreatures, repeatable, stock.toMutableMap(), creatureStock.toMutableMap(), facts.toMutableMap(), questKillCredits.toMutableMap(), stateSupply.toMutableMap(), budget)
        private fun adopt(other: Planner) {
            stock.clear(); stock.putAll(other.stock)
            creatureStock.clear(); creatureStock.putAll(other.creatureStock)
            facts.clear(); facts.putAll(other.facts)
            questKillCredits.clear(); questKillCredits.putAll(other.questKillCredits)
            stateSupply.clear(); stateSupply.putAll(other.stateSupply)
        }

        fun satisfy(objective: QuestObjective): Boolean = when (objective) {
            is QuestObjective.ActivateMechanic -> activate(objective.mechanic, objective.count)
            is QuestObjective.KillCreature -> observeKill(objective.creature, objective.count)
            is QuestObjective.DeliverItem -> logicalItem(objective.item)?.let { takeItem(it, objective.count) } ?: true
        }

        /** One current-quest kill credits all matching kill objectives and produces their delivery loot. */
        fun satisfyQuest(quest: Quest): Boolean {
            beginQuest()
            val kills = quest.objectives.filterIsInstance<QuestObjective.KillCreature>().groupBy { it.creature }
            if (!kills.all { (creature, objectives) -> observeKill(creature, objectives.maxOf { it.count }) }) return false
            if (!quest.objectives.filterIsInstance<QuestObjective.ActivateMechanic>().all(::satisfy)) return false
            return quest.objectives.filterIsInstance<QuestObjective.DeliverItem>().all(::satisfy)
        }

        fun beginQuest() { questKillCredits.clear() }

        /** Implicit recipe-input kills share credit within this quest, but never across quest unlocks. */
        fun observeKill(id: String, required: Int): Boolean {
            val missing = required - (questKillCredits[id] ?: 0)
            return missing <= 0 || killCreature(id, missing)
        }

        fun takeItem(key: ContentKey, count: Int): Boolean {
            val trial = fork()
            if (!trial.ensureItem(key, count.toLong(), emptySet())) return false
            trial.consume(key, count.toLong()); adopt(trial)
            return true
        }

        fun canAcquire(key: ContentKey): Boolean = fork().ensureItem(key, 1L, emptySet())

        private fun ensureItem(key: ContentKey, count: Long, trail: Set<String>): Boolean {
            if (available(key) >= count) return true
            val marker = "item:${key.kind}/${key.id}"
            if (marker in trail || !budget.step()) return false
            while (available(key) < count) {
                val before = available(key)
                var advanced = false
                for (mechanic in mechanics) {
                    for (rule in mechanic.rules.sortedBy { it.id }) {
                        if (rule.actions.none { produces(it, key) }) continue
                        val trial = fork()
                        if (trial.attemptRule(mechanic, rule, trail + marker) && trial.available(key) > before) {
                            adopt(trial); advanced = true; break
                        }
                    }
                    if (advanced) break
                }
                if (!advanced) {
                    for (creature in pack.creatures.creatures) {
                        if (creature.drops.none { it.chance > 0 && it.maxCount > 0 && logicalItem(it.item) == key }) continue
                        val trial = fork()
                        if (trial.killCreature(creature.id, 1, trail + marker) && trial.available(key) > before) {
                            adopt(trial); advanced = true; break
                        }
                    }
                }
                if (!advanced || !budget.step()) return false
            }
            return true
        }

        fun activate(id: String, count: Int): Boolean {
            val mechanic = mechanics.find { it.id == id } ?: return false
            val trial = fork()
            while ((trial.facts[id] ?: 0) < count) {
                val before = trial.facts[id] ?: 0
                var advanced = false
                for (rule in mechanic.rules.sortedBy { it.id }) {
                    val candidate = trial.fork()
                    if (candidate.attemptRule(mechanic, rule, setOf("mechanic:$id")) && (candidate.facts[id] ?: 0) > before) {
                        trial.adopt(candidate); advanced = true; break
                    }
                }
                if (!advanced || !budget.step()) return false
            }
            adopt(trial)
            return true
        }

        fun ensureCreature(id: String, count: Int): Boolean = ensureCreature(id, count, emptySet())
        private fun ensureCreature(id: String, count: Int, trail: Set<String>): Boolean {
            if (id in baseCreatures || (creatureStock[id] ?: 0L) >= count) return true
            if (pack.creatures.creatures.none { it.id == id }) return false
            val marker = "creature:$id"
            if (marker in trail || !budget.step()) return false
            while ((creatureStock[id] ?: 0L) < count) {
                var advanced = false
                for (mechanic in mechanics) {
                    for (rule in mechanic.rules.sortedBy { it.id }) {
                        if (rule.actions.none { it is MechanicAction.SpawnCreature && it.creature == id }) continue
                        val trial = fork()
                        if (trial.attemptRule(mechanic, rule, trail + marker)) {
                            adopt(trial); advanced = true; break
                        }
                    }
                    if (advanced) break
                }
                if (!advanced || !budget.step()) return false
            }
            return true
        }

        fun killCreature(id: String, count: Int): Boolean = killCreature(id, count, emptySet())
        private fun killCreature(id: String, count: Int, trail: Set<String>): Boolean {
            val trial = fork()
            if (!trial.ensureCreature(id, count, trail)) return false
            if (id !in baseCreatures) trial.creatureStock[id] = (trial.creatureStock[id] ?: 0L) - count
            trial.questKillCredits[id] = ((trial.questKillCredits[id] ?: 0) + count).coerceAtMost(QuestValidation.MAX_OBJECTIVE_COUNT)
            pack.creatures.creatures.find { it.id == id }?.drops.orEmpty().filter { it.chance > 0 && it.maxCount > 0 }.forEach { drop ->
                logicalItem(drop.item)?.let { trial.grant(it, drop.maxCount.toLong() * count) }
            }
            adopt(trial)
            return true
        }

        /** A new build may choose any finite simple path; unavailable/disabled states never count. */
        private fun attemptRule(mechanic: WorldMechanicDefinition, target: WorldMechanicRule, trail: Set<String>): Boolean {
            if (!enabled(target) || !budget.step()) return false
            fun performPath(reverse: List<WorldMechanicRule>, supply: StateSupply?): Boolean {
                val trial = fork()
                var possibleBiomes = supply?.biomes ?: biomes
                if (supply != null) {
                    val available = trial.stateSupply[supply] ?: 0L
                    if (available <= 0L) return false
                    // Reserve this anchor before obtaining inputs; a nested producer cannot reuse it.
                    if (available == 1L) trial.stateSupply.remove(supply) else trial.stateSupply[supply] = available - 1
                }
                for (rule in reverse.asReversed()) {
                    if (rule.biomes.isNotEmpty()) possibleBiomes = possibleBiomes.intersect(rule.biomes.toSet())
                    if (possibleBiomes.isEmpty() || !trial.perform(mechanic.id, rule, trail)) return false
                }
                if (target.toState != mechanic.initialState) {
                    val next = StateSupply(mechanic.id, target.toState, possibleBiomes)
                    trial.stateSupply[next] = ((trial.stateSupply[next] ?: 0L) + 1).coerceAtMost(MAX_SUPPLY)
                }
                adopt(trial)
                return true
            }
            fun search(state: String, reverse: List<WorldMechanicRule>, seen: Set<String>): Boolean {
                if (!budget.step()) return false
                val existing = stateSupply.keys.filter { it.mechanic == mechanic.id && it.state == state }
                for (supply in existing) if (performPath(reverse, supply)) return true
                if (state == mechanic.initialState) return performPath(reverse, null)
                if (state in seen) return false
                for (before in mechanic.rules.sortedBy { it.id }) {
                    if (before.toState == state && enabled(before) && search(before.fromState, reverse + before, seen + state)) return true
                }
                return false
            }
            return search(target.fromState, listOf(target), emptySet())
        }

        private fun perform(mechanicId: String, rule: WorldMechanicRule, trail: Set<String>): Boolean {
            if (!enabled(rule) || !budget.step()) return false
            val needed = linkedMapOf<ContentKey, Long>()
            val consumed = linkedMapOf<ContentKey, Long>()
            fun add(map: MutableMap<ContentKey, Long>, key: ContentKey, count: Long) { map[key] = (map[key] ?: 0L) + count }
            rule.pattern.forEach { cell -> logicalItem(cell.block.block)?.let { key ->
                add(needed, key, 1)
                if (cell.consume) add(consumed, key, 1)
            } }
            rule.heldItem?.let { cost -> logicalItem(cost.item)?.let { key -> add(needed, key, cost.count.toLong()); add(consumed, key, cost.count.toLong()) } }
            val cells = rule.pattern.associateBy { it.offset }
            rule.actions.filterIsInstance<MechanicAction.SetBlock>().forEach { action ->
                val previous = cells[action.offset]?.block?.block
                if (previous != action.block.block) previous?.let(::logicalItem)?.let { add(consumed, it, 1) }
            }
            for ((key, count) in needed) if (!ensureItem(key, count, trail)) return false
            // A producer used by a later requirement may have consumed an earlier requirement.
            if (needed.any { (key, count) -> available(key) < count }) return false
            consumed.forEach { (key, count) -> consume(key, count) }
            rule.actions.forEach { action -> when (action) {
                is MechanicAction.GiveItem -> logicalItem(action.item)?.let { grant(it, action.count.toLong()) }
                is MechanicAction.SetBlock -> if (cells[action.offset]?.block?.block != action.block.block) logicalItem(action.block.block)?.let { grant(it, 1) }
                is MechanicAction.SpawnCreature -> creatureStock[action.creature] = ((creatureStock[action.creature] ?: 0L) + 1).coerceAtMost(MAX_SUPPLY)
            } }
            facts[mechanicId] = ((facts[mechanicId] ?: 0) + 1).coerceAtMost(QuestValidation.MAX_OBJECTIVE_COUNT)
            return true
        }

        private fun enabled(rule: WorldMechanicRule): Boolean = rule.biomes.isEmpty() || rule.biomes.any(biomes::contains)
        private fun consume(key: ContentKey, count: Long) { if (key !in repeatable) stock[key] = (stock[key] ?: 0L) - count }
        private fun produces(action: MechanicAction, key: ContentKey): Boolean = when (action) {
            is MechanicAction.GiveItem -> logicalItem(action.item) == key
            is MechanicAction.SetBlock -> logicalItem(action.block.block) == key
            is MechanicAction.SpawnCreature -> false
        }
    }

    private data class StateSupply(val mechanic: String, val state: String, val biomes: Set<String>)

    private class Budget {
        private var steps = 0
        var exhausted = false
            private set
        fun step(): Boolean {
            if (++steps <= MAX_PROOF_STEPS) return true
            exhausted = true
            return false
        }
    }
    private const val MAX_PROOF_STEPS = 100_000
    private const val MAX_SUPPLY = 1_000_000_000L
}
