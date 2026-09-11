package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.model.TerrainShape
import com.wjz.worldsmith.core.model.AnchorPlacement
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

/** Static configured-producer proof, not a promise of a particular generated chest, mob or player route. */
object WorldQuestReachability {
    fun validate(pack: WorldsmithPack, inventory: DesignInventory, plannedItems: Set<ContentKey> = emptySet()): List<Diagnostic> {
        val structural = QuestValidation.validate(pack.quests)
        if (structural.isNotEmpty()) return structural.map { it.copy(path = "quests.${it.path}") }
        val ordered = QuestValidation.ordered(pack.quests)
        val definitions = pack.quests.quests.withIndex().associate { it.value.id to it.index }
        fun item(reference: String): ContentKey? = when {
            reference.startsWith(ExistingWorldContentModules.LOCAL_ITEM_PREFIX) -> ContentKey("item", reference.removePrefix(ExistingWorldContentModules.LOCAL_ITEM_PREFIX))
            reference.startsWith(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX) -> ContentKey("block_item", reference.removePrefix(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX))
            else -> null // Vanilla and other mods have acquisition systems outside this bundle; do not invent their reachability.
        }
        val validBiomes = pack.biomes.biomes.map { it.id }.toSet()
        val activeStructures = pack.structures.structures.filter {
            (it.placement.region?.chance ?: 1.0) > 0 && it.placement.biomes.any { biome -> biome in validBiomes }
        }.map { it.id }.toSet()
        val anchors = (pack.terrain.shape as? TerrainShape.Procedural)?.anchors.orEmpty().associateBy { it.id }
        val finiteStructures = pack.structures.structures.filter { structure ->
            structure.id in activeStructures && structure.placement.anchor?.let { target ->
                val placement = anchors[target.id]?.placement
                placement is AnchorPlacement.Fixed || placement is AnchorPlacement.Line
            } == true
        }.map { it.id }.toSet()
        val finiteSupply = mutableMapOf<ContentKey, Long>()
        finiteStructures.forEach { id -> inventory.structureSupplies[id].orEmpty().forEach { (key, count) ->
            finiteSupply[key] = (finiteSupply[key] ?: 0L) + count
        } }
        val featureDefinitions = pack.features.features.associateBy { it.id }
        val activeFeatures = pack.biomes.biomes.flatMap { biome -> biome.features.filter { ref ->
            val feature = featureDefinitions[ref.feature]
            feature != null && (ref.density ?: feature.density) > 0
        }.map { it.feature } }.toSet()
        val external = linkedMapOf<ContentKey, MutableSet<String>>()
        fun source(key: ContentKey, description: String) { external.getOrPut(key) { linkedSetOf() } += description }
        inventory.links.forEach { link -> when (link.relation) {
            DesignRelation.DROPS_ITEM -> if (link.from.id in inventory.encounterCreatures) source(link.to, "creature/${link.from.id}")
            DesignRelation.CONTAINS_REWARD -> if (link.from.id in activeStructures && link.from.id !in finiteStructures) source(link.to, "structure/${link.from.id}")
            DesignRelation.USES_BLOCK -> {
                val usable = when (link.from.kind) {
                    "terrain" -> true
                    "biome" -> link.from.id in validBiomes
                    "feature" -> link.from.id in activeFeatures
                    "structure" -> link.from.id in activeStructures && link.from.id !in finiteStructures
                    else -> false
                }
                if (usable) source(ContentKey("block_item", link.to.id), "world block through ${link.from.kind}/${link.from.id}")
            }
            else -> Unit
        } }
        val rewardsByItem = linkedMapOf<ContentKey, MutableList<Pair<Int, String>>>()
        ordered.forEachIndexed { index, quest -> quest.rewards.forEach { reward -> item(reward.item)?.let {
            rewardsByItem.getOrPut(it) { mutableListOf() } += index to quest.id
        } } }
        // Fixed/line-anchor structures are one site. Their contents and earlier quest rewards are consumed, not renewed.
        val stock = finiteSupply.toMutableMap()
        val errors = mutableListOf<Diagnostic>()
        ordered.forEachIndexed quests@{ index, quest ->
            if (errors.isNotEmpty()) return@quests // Later nodes are locked behind this first unclaimable quest.
            quest.objectives.forEachIndexed objectives@{ objectiveIndex, objective ->
                if (objective is QuestObjective.KillCreature) {
                    if (objective.creature !in inventory.encounterCreatures) errors += Diagnostic(
                        "quests.quests[${definitions.getValue(quest.id)}].objectives[$objectiveIndex]", "DESIGN_KILL_TARGET_UNPLACED", DiagnosticSeverity.ERROR,
                        "Quest '${quest.id}' requires creature '${objective.creature}', but that creature has no positive configured encounter route in this world.",
                        hint = "Give the actual creature a valid spawn habitat or a supported structure encounter, rather than relying on creative-only spawning.")
                    return@objectives
                }
                if (objective !is QuestObjective.DeliverItem) return@objectives
                val key = item(objective.item) ?: return@objectives
                if (external[key].orEmpty().isNotEmpty()) return@objectives
                val available = stock[key] ?: 0L
                if (available >= objective.count) {
                    stock[key] = available - objective.count
                    return@objectives
                }
                val future = rewardsByItem[key].orEmpty().filter { it.first >= index }
                val code = when {
                    available > 0L || (finiteSupply[key] ?: 0L) > 0L || rewardsByItem[key].orEmpty().any { it.first < index } -> "DESIGN_DELIVERY_FINITE_SUPPLY_INSUFFICIENT"
                    future.any { it.first == index } -> "DESIGN_DELIVERY_SELF_LOCK"
                    future.isNotEmpty() -> "DESIGN_DELIVERY_FUTURE_LOCK"
                    else -> "DESIGN_DELIVERY_NO_PRODUCER"
                }
                val candidates = if (future.isEmpty()) "no remaining quest producer" else future.map { "quest/${it.second}" }.distinct().joinToString()
                errors += Diagnostic("quests.quests[${definitions.getValue(quest.id)}].objectives[$objectiveIndex]", code, DiagnosticSeverity.ERROR,
                    "Quest '${quest.id}' needs ${objective.count} ${objective.item}, but at most $available unconsumed earlier rewards and fixed-site supplies are available, with no positive repeatable world producer. Later/self candidates: $candidates.",
                    hint = "Add a positive repeatable creature/structure/world-block source, move the reward to an already-claimable predecessor, or lower the total deliveries. Fixed/line-anchor containers are finite; a task's own reward is awarded only after delivery.")
            }
            if (errors.isEmpty()) quest.rewards.forEach { reward -> item(reward.item)?.let { key ->
                stock[key] = (stock[key] ?: 0L) + reward.count
            } }
        }
        plannedItems.filter { it.kind == "item" && external[it].orEmpty().isEmpty() && (finiteSupply[it] ?: 0L) == 0L && rewardsByItem[it].orEmpty().isEmpty() }.forEach { key ->
            errors += Diagnostic("designPlan.targets", "DESIGN_ITEM_NO_REACHABLE_PRODUCER", DiagnosticSeverity.ERROR,
                "Planned item '${key.id}' has no positive configured world producer or quest reward. Drops on an unspawnable creature and containers in a disabled structure do not make it obtainable.")
        }
        return errors
    }
}
