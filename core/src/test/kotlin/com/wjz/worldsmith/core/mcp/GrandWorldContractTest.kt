package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.PromptSet
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.structure.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GrandWorldContractTest {
    @TempDir lateinit var root:Path
    private val tools by lazy {WorldsmithMcpTools(root.resolve("packs"))}
    private fun call(name:String,args:JsonObject=JsonObject(emptyMap()))=tools.all().single {it.name==name}.handler(args)
    private fun contract(detail:String?=null,section:String?=null)=call(WorldsmithWorkflow.CONTRACT_TOOL,buildJsonObject {
        put("id",PromptSet.CONTRACT_GRAND_WORLD);detail?.let {put("detail",it)};section?.let {put("section",it)}
    })

    @Test fun `grand world contract is discoverable by full text index and every planning section`() {
        val definition=tools.all().single {it.name==WorldsmithWorkflow.CONTRACT_TOOL}
        assertTrue(PromptSet.CONTRACT_GRAND_WORLD in definition.inputSchema.getValue("properties").jsonObject.getValue("id")
            .jsonObject.getValue("enum").jsonArray.map {it.jsonPrimitive.content})
        val full=contract();assertFalse(full.isError,full.text)
        val text=full.structuredContent.getValue("contract").jsonPrimitive.content
        val index=contract(detail="index");assertFalse(index.isError,index.text)
        val expected=setOf("overview","world-atlas","content-budgets","region-routes","production-batches","global-review","runtime-boundaries")
        val sections=index.structuredContent.getValue("sections").jsonArray.map {it.jsonObject.getValue("id").jsonPrimitive.content}.toSet()
        assertTrue(sections.containsAll(expected),sections.toString())
        expected.forEach {section->
            val selected=contract(section=section);assertFalse(selected.isError,selected.text)
            assertEquals(ContractSections.split(text).getValue(section),selected.structuredContent.getValue("contract").jsonPrimitive.content)
        }
        assertTrue(contract(section="not_a_section").isError)
    }

    @Test fun `world begin reads macro planning before named plan and summaries never inline the full guide`() {
        val fullContract=contract().structuredContent.getValue("contract").jsonPrimitive.content
        val compact=ContractSections.split(fullContract).getValue("overview")
        assertTrue(compact.length<=2048,"The entry overview must remain compact rather than hiding a whole manual")
        for(mode in listOf(WorkflowMode.COMPLETE_WORLD,WorkflowMode.WORLDGEN_ONLY))for(detail in listOf("summary","full")) {
            val response=call(WorldsmithWorkflow.BEGIN_TOOL,buildJsonObject {put("prompt","A grand regional world");put("mode",mode.name);put("detail",detail)})
            assertFalse(response.isError,response.text)
            val body=response.structuredContent
            assertEquals(compact,body.getValue("worldPlanningGuide").jsonPrimitive.content)
            assertFalse(body.getValue("worldPlanningGuideTruncated").jsonPrimitive.boolean)
            assertEquals(PromptSet.CONTRACT_GRAND_WORLD,body.getValue("worldPlanningReference").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("world-atlas",body.getValue("nextArguments").jsonObject.getValue("section").jsonPrimitive.content)
            assertEquals(WorldsmithWorkflow.CONTRACT_TOOL,body.getValue("nextTool").jsonPrimitive.content)
            val procedure=body.getValue("procedure").jsonArray.map {it.jsonObject}
            assertTrue(procedure.first().getValue("instruction").jsonPrimitive.content.contains("grand_world"))
            if(mode==WorkflowMode.COMPLETE_WORLD) {
                assertTrue(procedure.indexOfFirst {it.getValue("tool").jsonPrimitive.content=="worldsmith_put_world_design_plan"}>0)
                assertEquals("worldsmith_put_world_design_plan",body.getValue("progress").jsonObject.getValue("nextTool").jsonPrimitive.content)
            }
            val advertised=body.getValue("contracts").jsonObject.getValue(PromptSet.CONTRACT_GRAND_WORLD)
            if(detail=="summary")assertTrue(advertised is JsonObject && "sections" in advertised && "contract" !in advertised)
            else assertEquals(fullContract,advertised.jsonPrimitive.content)
        }
        val focused=call(WorldsmithWorkflow.BEGIN_TOOL,buildJsonObject {put("prompt","One standalone tower");put("mode","STANDALONE");put("detail","summary")}).structuredContent
        assertFalse("worldPlanningGuide" in focused)
        assertFalse(focused.getValue("procedure").jsonArray.any {it.jsonObject.getValue("instruction").jsonPrimitive.content.contains("grand_world")})
    }

    @Test fun `authoring budgets use enforced constants and describe shared catalogs rather than map instances`() {
        val budgets=WorldsmithAuthoringBudgets.snapshot()
        fun number(group:String,key:String)=budgets.getValue(group).jsonObject.getValue(key).jsonPrimitive.long
        assertEquals(WorldDesignPlans.MAX_TARGETS.toLong(),number("designPlan","maxTargets"))
        assertEquals(WorldDesignPlans.MAX_LINKS.toLong(),number("designPlan","maxLinks"))
        assertEquals(StructureValidator.MAX_STRUCTURES.toLong(),number("structures","maxDefinitions"))
        assertEquals(StructureCatalogCompiler.MAX_BLUEPRINTS.toLong(),number("structures","maxBlueprints"))
        assertEquals(StructureCatalogCompiler.MAX_TOTAL_VOXELS.toLong(),number("structures","maxCatalogTemplateVoxels"))
        assertEquals(StructureCatalogCompiler.MAX_TOTAL_WORK.toLong(),number("structures","maxCatalogExpandedWork"))
        assertEquals(WorldContentBundleIO.MAX_TEXT_BYTES.toLong(),number("sharedCatalog","maxBundleTextBytes"))
        assertEquals(StructureCatalogCompiler.MAX_PLAN_VOXELS.toLong(),number("structures","maxPlanAuthoredCells"))
        assertTrue(budgets.getValue("structures").jsonObject.getValue("countsIncludeAir").jsonPrimitive.boolean)
        assertTrue(budgets.getValue("structures").jsonObject.getValue("catalogBudgetIncludesAllVariants").jsonPrimitive.boolean)
        assertEquals((CustomBlockProfile.entries.size*CustomBlockValidation.SLOTS_PER_PROFILE).toLong(),number("blocks","maxDefinitions"))
        val profiles=budgets.getValue("blocks").jsonObject.getValue("perProfile").jsonObject
        assertEquals(CustomBlockProfile.entries.map {it.name}.toSet(),profiles.keys)
        assertTrue(profiles.values.all {it.jsonPrimitive.int==CustomBlockValidation.SLOTS_PER_PROFILE})
        assertEquals(CustomCreatureValidator.MAX_CREATURES.toLong(),number("creatures","maxDefinitions"))
        assertEquals(CustomItemValidation.MAX_ITEMS.toLong(),number("items","maxDefinitions"))
        assertEquals(QuestValidation.MAX_QUESTS.toLong(),number("quests","maxDefinitions"))
        assertEquals(ContentAssetValidation.MAX_ASSETS.toLong(),number("pngAssets","maxAssets"))
        assertEquals(ContentAssetValidation.MAX_TOTAL_BYTES,number("pngAssets","maxTotalBytes"))
        assertEquals(ContentAssetValidation.MAX_TOTAL_PIXELS,number("pngAssets","maxTotalDecodedPixels"))
        assertEquals(JsonNull,budgets.getValue("biomes").jsonObject.getValue("independentDefinitionLimit"))
        assertTrue(budgets.getValue("mapInstancesAreNotCounted").jsonPrimitive.boolean)
        val framework=call("worldsmith_get_content_framework").structuredContent
        val begun=call(WorldsmithWorkflow.BEGIN_TOOL,buildJsonObject {put("prompt","Budgeted world");put("detail","summary")}).structuredContent
        assertEquals(budgets,framework.getValue("authoringBudgets"))
        assertEquals(budgets,begun.getValue("authoringBudgets"))
        val tooMany=StructureLibrary(structures=List(StructureValidator.MAX_STRUCTURES+1) {
            WorldStructureDefinition("structure_$it",StructureBlueprint(id="blueprint_$it"),StructurePlacement(emptyList()))
        })
        val error=assertThrows(StructureBuildException::class.java) {StructureCatalogCompiler.compile(tooMany)}
        assertEquals("STRUCTURE_CATALOG_LIMIT",error.diagnostic.code)
    }
}
