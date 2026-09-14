package com.wjz.worldsmith.core.mcp

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Verifies the linked design fixture, not actual generation, combat or a Core-ready resource pack. */
class WorldAuthoringDesignExampleTest {
    @Test fun `crystal city design connects world rules resources encounters quests and useful reward`() {
        val text=requireNotNull(javaClass.getResourceAsStream("/authoring/crystal-world-design.json")).bufferedReader().use {it.readText()}
        val document=Json.parseToJsonElement(text).jsonObject
        val bible=McpJson.decode<WorldBible>(document.getValue("bible"))
        val plan=McpJson.decode<WorldDesignPlan>(document.getValue("plan"))
        val briefs=McpJson.decode<List<ModuleBrief>>(document.getValue("briefs"))
        assertTrue(WorldAuthoringModel.validateBible(bible,document.getValue("prompt").jsonPrimitive.content).isEmpty())
        assertTrue(WorldDesignPlans.validate(plan,true,bible.requiresBoss).isEmpty())
        assertTrue(WorldAuthoringModel.validateBriefs(briefs,bible).isEmpty())
        assertEquals(plan.targets.map {it.key}.toSet(),briefs.flatMap {it.targets}.toSet())
        assertTrue(bible.requirements.all {r->briefs.any {"requirement/${r.id}" in it.basisRefs}})
        assertTrue(plan.links.any {it.relation==DesignRelation.DROPS_ITEM && it.to.id=="keeper_core"})
        assertTrue(plan.links.any {it.relation==DesignRelation.DELIVERY_OBJECTIVE && it.to.id=="keeper_core"})
        assertTrue(plan.links.any {it.relation==DesignRelation.QUEST_REWARD && it.to.id=="dawn_amulet"})
        assertTrue(briefs.single {it.id=="quests"}.dependencies.containsAll(listOf("items","creatures","structures")))
        assertTrue(plan.bosses.isEmpty())
    }
}
