package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.WorldsmithPack

/**
 * Constructive material routes through a quest DAG. Story predicates are separately validated
 * observations, not claimed to be satisfiable here. Alternative branches never share inventory.
 */
internal object QuestMaterialRoutes {
    private const val MAX_SCENARIOS = 128
    private const val MAX_STATES = 4096
    data class Result(val completedRoutes: Int, val completedBranches: Set<String>, val obtainableItems: Set<ContentKey>,
        val blocked: Set<String>, val exhausted: Boolean)

    fun needsGraphProof(quests: List<Quest>): Boolean = quests.count { it.prerequisites.isEmpty() } > 1 ||
        quests.any { it.optional || it.exclusiveGroup != null || it.prerequisites.size > 1 || it.prerequisiteMode == QuestPrerequisiteMode.ANY || it.objectives.any { objective -> objective.optional || objective is QuestObjective.Fact } } ||
        quests.flatMap { it.prerequisites }.groupingBy { it }.eachCount().any { it.value > 1 }

    fun explore(pack: WorldsmithPack, inventory: DesignInventory, plannedItems: Set<ContentKey> = emptySet(),
        observe: (WorldMechanicReachability.Planner) -> Unit = {}): Result {
        val ordered = QuestValidation.ordered(pack.quests)
        val groups = ordered.filter { it.exclusiveGroup != null }.groupBy { it.exclusiveGroup!! }.toSortedMap()
        var scenarios = listOf(emptyMap<String, String>())
        for ((group, choices) in groups) {
            if (scenarios.size.toLong() * choices.size > MAX_SCENARIOS)
                return Result(0, emptySet(), emptySet(), emptySet(), true)
            scenarios = scenarios.flatMap { previous -> choices.map { previous + (group to it.id) } }
        }
        val base = WorldMechanicReachability.planner(pack, inventory)
        val obtainable = linkedSetOf<ContentKey>(); val completedBranches = linkedSetOf<String>(); val blocked = linkedSetOf<String>()
        var completedRoutes = 0; var states = 0; var exhausted = false
        fun inspect(planner: WorldMechanicReachability.Planner) {
            plannedItems.filter { it.kind == "item" && it !in obtainable && planner.canAcquire(it) }.forEach(obtainable::add)
            observe(planner)
        }
        inspect(base)
        for (selection in scenarios) {
            val excluded = linkedSetOf<String>()
            repeat(ordered.size + 1) {
                ordered.forEach { quest ->
                    val branch = quest.exclusiveGroup?.let { selection[it] != quest.id } == true
                    val inherited = quest.prerequisites.isNotEmpty() && if (quest.prerequisiteMode == QuestPrerequisiteMode.ANY)
                        quest.prerequisites.all { it in excluded } else quest.prerequisites.any { it in excluded }
                    if (branch || inherited) excluded += quest.id
                }
            }
            fun unlocked(quest: Quest, done: Set<String>) = quest.prerequisites.isEmpty() || if (quest.prerequisiteMode == QuestPrerequisiteMode.ANY)
                quest.prerequisites.any { it in done } else quest.prerequisites.all { it in done }
            val required = ordered.filter { !it.optional && it.id !in excluded }.map { it.id }.toSet()
            val seen = hashSetOf<String>()
            fun search(planner: WorldMechanicReachability.Planner, done: Set<String>): Pair<WorldMechanicReachability.Planner, Set<String>>? {
                if (++states > MAX_STATES || planner.exhausted) { exhausted = true; return null }
                val key = done.sorted().joinToString(",") + ":" + planner.proofKey()
                if (!seen.add(key)) return null
                inspect(planner)
                if (done.containsAll(required)) return planner to done
                val available = ordered.filter { it.id !in done && it.id !in excluded && unlocked(it, done) }
                for (quest in available) {
                    val trial = planner.fork()
                    if (!trial.satisfyQuest(quest)) { if (trial.exhausted) exhausted = true; continue }
                    quest.rewards.forEach { reward -> WorldMechanicReachability.logicalItem(reward.item)?.let { trial.grant(it, reward.count.toLong()) } }
                    val result = search(trial, done + quest.id)
                    if (result != null) return result
                    if (exhausted) return null
                }
                blocked += available.filter { !it.optional }.map { it.id }
                return null
            }
            val result = search(base.fork(), emptySet())
            if (result != null) {
                completedRoutes++
                var planner = result.first; var done = result.second
                completedBranches += ordered.filter { it.exclusiveGroup != null && it.id in done }.map { it.id }
                // Optional side journeys may offer planned items after the required campaign route.
                repeat(ordered.size) {
                    val next = ordered.firstNotNullOfOrNull { quest ->
                        if (!quest.optional || quest.id in done || quest.id in excluded || !unlocked(quest, done)) null
                        else planner.fork().takeIf { it.satisfyQuest(quest) }?.let { quest to it }
                    }
                    if (next != null) {
                        next.first.rewards.forEach { reward -> WorldMechanicReachability.logicalItem(reward.item)?.let { next.second.grant(it, reward.count.toLong()) } }
                        planner = next.second; done = done + next.first.id; inspect(planner)
                    }
                }
                inspect(planner)
            }
            if (exhausted) break
        }
        return Result(completedRoutes, completedBranches, obtainable, blocked, exhausted || base.exhausted)
    }
}
