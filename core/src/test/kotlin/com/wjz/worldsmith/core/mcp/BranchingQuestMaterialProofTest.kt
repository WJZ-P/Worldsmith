package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BranchingQuestMaterialProofTest {
    private val base by lazy { WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands") }
    private val empty = DesignInventory(emptySet(), emptySet(), emptyMap(), emptySet(), emptySet(), emptySet(), emptySet(), emptyMap())
    private fun quest(id: String, previous: List<String> = emptyList(), objectives: List<QuestObjective> = listOf(QuestObjective.DeliverItem("minecraft:stick")), rewards: List<QuestReward> = emptyList()) =
        Quest(id, id, "Material path", previous, objectives, rewards)
    private fun validate(quests: List<Quest>) = WorldQuestReachability.validate(base.copy(quests = QuestLibrary(quests = quests)), empty)

    @Test fun `exclusive route rewards never pay for the other alternative`() {
        val left = quest("left", rewards = listOf(QuestReward("worldsmith:item/relic"))).copy(manualAccept = true, exclusiveGroup = "route")
        val right = quest("right", objectives = listOf(QuestObjective.DeliverItem("worldsmith:item/relic"))).copy(manualAccept = true, exclusiveGroup = "route")
        assertTrue(validate(listOf(left, right)).any { it.code == "DESIGN_QUEST_BRANCH_BLOCKED" })
    }
    @Test fun `any merge completes either route without consuming both routes rewards`() {
        val left = quest("left", rewards = listOf(QuestReward("worldsmith:item/relic"))).copy(manualAccept = true, exclusiveGroup = "route")
        val right = quest("right", rewards = listOf(QuestReward("worldsmith:item/relic"))).copy(manualAccept = true, exclusiveGroup = "route")
        val end = quest("ending", listOf("left", "right"), listOf(QuestObjective.DeliverItem("worldsmith:item/relic"))).copy(prerequisiteMode = QuestPrerequisiteMode.ANY)
        assertTrue(validate(listOf(end, left, right)).isEmpty())
    }
    @Test fun `independent roots may complete in a material compatible order`() {
        val target = quest("target", objectives = listOf(QuestObjective.DeliverItem("worldsmith:item/relic")))
        val producer = quest("producer", rewards = listOf(QuestReward("worldsmith:item/relic")))
        assertTrue(validate(listOf(target, producer)).isEmpty())
    }
    @Test fun `concurrent required quests share finite supply rather than duplicating it`() {
        val start = quest("start", rewards = listOf(QuestReward("worldsmith:item/relic", 2)))
        val a = quest("a", listOf("start"), listOf(QuestObjective.DeliverItem("worldsmith:item/relic", 2)))
        val b = quest("b", listOf("start"), listOf(QuestObjective.DeliverItem("worldsmith:item/relic", 2)))
        assertTrue(validate(listOf(start, a, b)).any { it.code == "DESIGN_QUEST_ROUTE_BLOCKED" })
    }
    @Test fun `optional goals do not become hidden mandatory material costs`() {
        val value = quest("one", objectives = listOf(QuestObjective.DeliverItem("minecraft:stick"), QuestObjective.DeliverItem("worldsmith:item/unobtainable", optional = true)))
        assertTrue(validate(listOf(value)).isEmpty())
    }
    @Test fun `oversized independent branch combinations report proof budget instead of inventing a route`() {
        val branches = (0..7).flatMap { group -> listOf("a", "b").map { suffix -> quest("route_${group}_$suffix").copy(manualAccept = true, exclusiveGroup = "choice_$group") } }
        assertTrue(validate(branches).any { it.code == "DESIGN_QUEST_PROOF_BUDGET" })
    }
}
