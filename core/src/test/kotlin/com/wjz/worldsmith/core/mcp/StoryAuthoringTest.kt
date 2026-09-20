package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.ability.AbilityValues
import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.story.*
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StoryAuthoringTest {
    @TempDir lateinit var root: Path
    private val sessions by lazy { WorkflowSessions(directory=root.resolve("sessions")) }
    private val tools by lazy { WorldsmithMcpTools(root.resolve("packs"),sessions=sessions) }
    private fun call(name: String,args: JsonObject=JsonObject(emptyMap()))=tools.all().single { it.name==name }.handler(args)
    private fun library()=StoryLibrary(facts=listOf(StoryFact("bridge",StoryFactScope.WORLD,StoryFactType.BOOL,AbilityValues.bool(false))),knowledge=listOf(
        StoryKnowledge("crossing","The crossing","Neighbours laid every stone.",discoverWhen=StoryCondition.Compare(StoryFactRef("bridge"),StoryComparison.EQ,AbilityValues.bool(true)))))

    @Test fun `story contract template and named references share the implemented domain`() {
        val contract=call("worldsmith_get_content_contract",buildJsonObject { put("module","story") })
        assertFalse(contract.isError,contract.text)
        val text=contract.structuredContent.getValue("contract").jsonPrimitive.content
        listOf("story/story.json","story_anchor","StoryLibrary","maxUsesPerPlayer","WORLD","PLAYER","projections","onApplied").forEach { assertTrue(it in text,it) }
        val framework=call("worldsmith_get_content_framework").structuredContent
        assertEquals(10,framework.getValue("packFormat").jsonPrimitive.int)
        assertEquals(2,framework.getValue("storySchemaVersion").jsonPrimitive.int)
        assertFalse(framework.getValue("storyRuntime").jsonPrimitive.boolean)
        val template=call(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent;assertTrue("story" in template)
        val l=library();val s=WorkflowSession("a".repeat(32),"A remembered crossing",contentModules=mapOf("story" to McpJson.encode(l).jsonObject))
        val inventory=WorldDesignCoverage.draft(s)
        val fact=ContentKey("story_fact","bridge");val knowledge=ContentKey("knowledge","crossing")
        assertTrue(inventory.symbols.containsAll(listOf(fact,knowledge)))
        assertTrue(DesignLink(knowledge,fact,DesignRelation.STORY_REFERENCE) in inventory.links)
        val plan=WorldDesignPlan(goal="Remember the crossing",targets=listOf(DesignTarget(fact,"Bridge","The repaired crossing"),DesignTarget(knowledge,"Crossing","Its remembered history")),links=listOf(DesignLink(knowledge,fact,DesignRelation.STORY_REFERENCE)))
        assertTrue(WorldDesignPlans.validate(plan,false,false).isEmpty())
        assertEquals(1,WorldGenerationProgress.inspect(s).counts["knowledgeDefinitions"])
    }
    @Test fun `story draft retains exact CAS state and inline publication keeps required domain`() {
        val s=sessions.begin("A remembered crossing");val document=McpJson.encode(library()).jsonObject
        val args=buildJsonObject { put("sessionId",s.id);put("expectedRevision",s.revision);putJsonObject("modules") { put("story",document) } }
        assertFalse(call("worldsmith_put_content_modules",args).isError)
        assertEquals(document,sessions.find(s.id)!!.contentModules["story"])
        assertEquals(sessions.find(s.id),WorkflowSessions(directory=root.resolve("sessions")).find(s.id))
        assertThrows(IllegalArgumentException::class.java) { call("worldsmith_put_content_modules",args) }
        val template=call(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
        val written=call(WorldsmithWorkflow.WRITE_TOOL,buildJsonObject {
            put("displayName","The crossing");listOf("terrain","biomes","features","structures","theme","blocks","creatures","items","quests","mechanics","abilities").forEach { put(it,template.getValue(it)) };put("story",document)
        })
        assertFalse(written.isError,written.text)
        val pack=WorldsmithPackLoader.loadDirectory(Path.of(written.structuredContent.getValue("path").jsonPrimitive.content))
        assertEquals(library(),pack.story)
    }
}
