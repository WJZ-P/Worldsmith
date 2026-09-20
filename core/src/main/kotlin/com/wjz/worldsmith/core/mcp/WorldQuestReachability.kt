package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

/** Static configured-producer proof, not a promise of a particular generated chest, mob or player route. */
object WorldQuestReachability {
    fun validate(pack: WorldsmithPack, inventory: DesignInventory, plannedItems: Set<ContentKey> = emptySet()): List<Diagnostic> {
        val structural = QuestValidation.validate(pack.quests)
        if (structural.isNotEmpty()) return structural.map { it.copy(path = "quests.${it.path}") }
        val ordered = QuestValidation.ordered(pack.quests)
        if (QuestMaterialRoutes.needsGraphProof(ordered)) return validateGraph(pack, inventory, plannedItems)
        val definitions = pack.quests.quests.withIndex().associate { it.value.id to it.index }
        val planner = WorldMechanicReachability.planner(pack, inventory)
        val initialSupply = planner.initialSupply
        val rewardsByItem = linkedMapOf<ContentKey, MutableList<Pair<Int, String>>>()
        ordered.forEachIndexed { index, quest -> quest.rewards.forEach { reward -> WorldMechanicReachability.logicalItem(reward.item)?.let {
            rewardsByItem.getOrPut(it) { mutableListOf() } += index to quest.id
        } } }
        val obtainablePlanned = linkedSetOf<ContentKey>()
        fun inspectPlanned() { plannedItems.filter { it.kind == "item" && it !in obtainablePlanned && planner.canAcquire(it) }.forEach(obtainablePlanned::add) }
        inspectPlanned()
        val errors = mutableListOf<Diagnostic>()
        ordered.forEachIndexed quests@{ index, quest ->
            if (errors.isNotEmpty()) return@quests // Later nodes are locked behind this first unclaimable quest.
            planner.beginQuest()
            val killed = hashSetOf<String>()
            // Gameplay objectives are concurrent: a kill supplies its loot and credits every matching
            // kill objective before delivery, regardless of the author's JSON array order.
            quest.objectives.withIndex().sortedBy { when (it.value) {
                is QuestObjective.KillCreature -> 0
                is QuestObjective.ActivateMechanic -> 1
                is QuestObjective.DeliverItem -> 2
                is QuestObjective.Fact -> 3
            } }.forEach objectives@{ (objectiveIndex, objective) ->
                val path = "quests.quests[${definitions.getValue(quest.id)}].objectives[$objectiveIndex]"
                when (objective) {
                    is QuestObjective.ActivateMechanic -> {
                        if (!planner.activate(objective.mechanic, objective.count)) errors += Diagnostic(path,
                            if (planner.exhausted) "DESIGN_MECHANIC_PROOF_BUDGET" else "DESIGN_MECHANIC_UNREACHABLE", DiagnosticSeverity.ERROR,
                            "Quest '${quest.id}' needs ${objective.count} successful activations of '${objective.mechanic}', but no bounded initial-state route with obtainable inputs and a common biome supplies that many facts.",
                            hint = "Provide every actual hand/pattern input before this quest's reward, remove producer self-locks, or lower the count. Finite offerings are consumed across activations and earlier objectives; quests only observe completed interactions.")
                    }
                    is QuestObjective.KillCreature -> {
                        if (!killed.add(objective.creature)) return@objectives
                        val count = quest.objectives.filterIsInstance<QuestObjective.KillCreature>().filter { it.creature == objective.creature }.maxOf { it.count }
                        if (!planner.observeKill(objective.creature, count)) errors += Diagnostic(path, "DESIGN_KILL_TARGET_UNPLACED", DiagnosticSeverity.ERROR,
                            "Quest '${quest.id}' requires ${objective.count} '${objective.creature}', but no positive natural/structure route or affordable mechanic summon path supplies that encounter count.",
                            hint = "Give the creature a valid habitat, supported structure encounter, or reachable summon mechanic. A summon declaration with unavailable costs or an unreachable source state is not an encounter route.")
                    }
                    is QuestObjective.DeliverItem -> {
                        val key = WorldMechanicReachability.logicalItem(objective.item) ?: return@objectives
                        val available = planner.available(key)
                        if (planner.takeItem(key, objective.count)) return@objectives
                        val future = rewardsByItem[key].orEmpty().filter { it.first >= index }
                        val code = when {
                            planner.exhausted -> "DESIGN_MECHANIC_PROOF_BUDGET"
                            available > 0L || (initialSupply[key] ?: 0L) > 0L || rewardsByItem[key].orEmpty().any { it.first < index } -> "DESIGN_DELIVERY_FINITE_SUPPLY_INSUFFICIENT"
                            future.any { it.first == index } -> "DESIGN_DELIVERY_SELF_LOCK"
                            future.isNotEmpty() -> "DESIGN_DELIVERY_FUTURE_LOCK"
                            else -> "DESIGN_DELIVERY_NO_PRODUCER"
                        }
                        val candidates = if (future.isEmpty()) "no remaining quest producer" else future.map { "quest/${it.second}" }.distinct().joinToString()
                        errors += Diagnostic(path, code, DiagnosticSeverity.ERROR,
                            "Quest '${quest.id}' needs ${objective.count} ${objective.item}, but at most $available unconsumed earlier rewards and fixed-site supplies are available, with no affordable world or mechanic producer covering the shortfall. Later/self candidates: $candidates.",
                            hint = "Add a positive reachable producer, place its actual materials before use, move rewards to an already-claimable predecessor, or lower total deliveries. Mechanic offerings and earlier deliveries share the same finite stock; a task's own reward arrives only after its objectives.")
                    }
                    is QuestObjective.Fact -> Unit
                }
            }
            if (errors.isEmpty()) {
                quest.rewards.forEach { reward -> WorldMechanicReachability.logicalItem(reward.item)?.let { planner.grant(it, reward.count.toLong()) } }
                inspectPlanned()
            }
        }
        plannedItems.filter { it.kind == "item" && it !in obtainablePlanned }.forEach { key ->
            errors += Diagnostic("designPlan.targets", "DESIGN_ITEM_NO_REACHABLE_PRODUCER", DiagnosticSeverity.ERROR,
                "Planned item '${key.id}' has no positive affordable world, mechanic or already-claimable quest producer. A summon/drop/offering cycle without an initial source does not make the item obtainable.")
        }
        return errors
    }

    /** Each exclusive alternative gets its own inventory; rejected-route rewards never fund another route. */
    private fun validateGraph(pack: WorldsmithPack, inventory: DesignInventory, plannedItems: Set<ContentKey>): List<Diagnostic> {
        val proof = QuestMaterialRoutes.explore(pack, inventory, plannedItems)
        val result = mutableListOf<Diagnostic>()
        if (proof.exhausted) result += Diagnostic("quests.quests", "DESIGN_QUEST_PROOF_BUDGET", DiagnosticSeverity.ERROR,
            "The branched material-route proof exceeded its bounded search. Reduce independent irreversible alternatives or make their supply paths explicit.")
        else if (proof.completedRoutes == 0 && pack.quests.quests.any { !it.optional }) result += Diagnostic("quests.quests", "DESIGN_QUEST_ROUTE_BLOCKED", DiagnosticSeverity.ERROR,
            "No bounded prerequisite-consistent material route finishes all non-excluded required quests. Stalled goals: ${proof.blocked.sorted().joinToString()}.",
            hint = "Supply deliveries before rewards, keep exclusive branches separate, use ANY to merge alternatives, and do not require optional rewards without a prerequisite.")
        if (!proof.exhausted) pack.quests.quests.filter { it.exclusiveGroup != null && !it.optional && it.id !in proof.completedBranches }.forEach {
            result += Diagnostic("quests.quests", "DESIGN_QUEST_BRANCH_BLOCKED", DiagnosticSeverity.ERROR,
                "Exclusive branch '${it.id}' has no complete material route; another branch's reward is not a producer for this choice.")
        }
        plannedItems.filter { it.kind == "item" && it !in proof.obtainableItems }.forEach {
            result += Diagnostic("designPlan.targets", "DESIGN_ITEM_NO_REACHABLE_PRODUCER", DiagnosticSeverity.ERROR,
                "Planned item '${it.id}' has no verified producer on any bounded prerequisite-consistent branch.")
        }
        return result
    }
}
