package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StructureWorkflowTest {
    @TempDir lateinit var root:Path
    private val tools by lazy {WorldsmithMcpTools(root.resolve("packs"))}
    private fun call(name:String,args:JsonObject=JsonObject(emptyMap()))=tools.all().single {it.name==name}.handler(args)
    private fun begin()=call(WorldsmithWorkflow.BEGIN_TOOL,buildJsonObject {put("prompt","A forest of shrines")}).structuredContent.getValue("sessionId").jsonPrimitive.content
    private fun example()=WorldsmithJson.format.decodeFromJsonElement<StructureBlueprint>(call("worldsmith_get_structure_example").structuredContent.getValue("blueprint"))
    private fun definition()=WorldStructureDefinition("forest_shrine",example(),StructurePlacement(listOf("ashfall_plain"),terrainFit=StructureTerrainFit(foundation=StructureFoundation(FoundationMode.FILL,"foundation",6))))
    private fun write(session:String):McpToolResult {
        val template=call(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
        return call(WorldsmithWorkflow.WRITE_TOOL,buildJsonObject {
            put("sessionId",session);put("displayName","Built structures")
            listOf("terrain","biomes","features").forEach {put(it,template.getValue(it))}
        })
    }

    @Test fun `MCP contract exposes the executable grammar and preview stays lightweight`() {
        val session=begin()
        assertTrue(session.isNotEmpty())
        val contract=call(WorldsmithWorkflow.CONTRACT_TOOL,buildJsonObject {put("id","structure")})
        listOf("SHELL","ROOF","INSTANCE","worldsmith_put_structure").forEach {assertTrue(it in contract.text)}
        val preview=call("worldsmith_preview_structure",buildJsonObject {put("blueprint",WorldsmithJson.format.encodeToJsonElement(example()))})
        assertFalse(preview.isError)
        assertFalse(preview.structuredContent.getValue("minecraftCompiled").jsonPrimitive.boolean)
        val file=Path.of(preview.structuredContent.getValue("previewPath").jsonPrimitive.content)
        assertTrue(Files.readString(file).contains("<svg"))
    }

    @Test fun `session drafts are persisted as separate blueprint files and included in finish`() {
        val session=begin()
        val put=call("worldsmith_put_structure",buildJsonObject {put("sessionId",session);put("structure",WorldsmithJson.format.encodeToJsonElement(definition()))})
        assertFalse(put.isError,put.text)
        val write=write(session)
        assertFalse(write.isError,write.text)
        val directory=Path.of(write.structuredContent.getValue("path").jsonPrimitive.content)
        assertTrue(Files.exists(directory.resolve("structures/forest_shrine.json")))
        val loaded=WorldsmithPackLoader.loadDirectory(directory)
        assertEquals(1,loaded.structures.structures.size)
        assertEquals(loaded.computedId,loaded.manifest.id)
        val finish=call(WorldsmithWorkflow.FINISH_TOOL,buildJsonObject {put("sessionId",session)})
        assertTrue(finish.structuredContent.getValue("complete").jsonPrimitive.boolean)
        assertEquals(1,finish.structuredContent.getValue("structureCount").jsonPrimitive.int)
        assertFalse(finish.structuredContent.getValue("minecraftCompiled").jsonPrimitive.boolean)
    }

    @Test fun `changing a draft invalidates old completion and changing a saved blueprint breaks its hash`() {
        val session=begin()
        val original=definition()
        fun put(d:WorldStructureDefinition)=call("worldsmith_put_structure",buildJsonObject {put("sessionId",session);put("structure",WorldsmithJson.format.encodeToJsonElement(d))})
        put(original)
        val written=write(session)
        val dir=Path.of(written.structuredContent.getValue("path").jsonPrimitive.content)
        val changed=original.copy(blueprint=original.blueprint.copy(palette=original.blueprint.palette+("wood" to BuildMaterial("minecraft:birch_planks"))))
        put(changed)
        val unfinished=call(WorldsmithWorkflow.FINISH_TOOL,buildJsonObject {put("sessionId",session)})
        assertFalse(unfinished.structuredContent.getValue("complete").jsonPrimitive.boolean)
        assertEquals(WorldsmithWorkflow.WRITE_TOOL,unfinished.structuredContent.getValue("nextTool").jsonPrimitive.content)
        Files.writeString(dir.resolve("structures/forest_shrine.json"),WorldsmithJson.encode(changed.blueprint))
        val tampered=WorldsmithPackLoader.loadDirectory(dir)
        assertNotEquals(tampered.computedId,tampered.manifest.id)
    }

    @Test fun `replacing a completed session pack queues the new identity again`() {
        val sessions=WorkflowSessions()
        val session=sessions.begin("build a new shrine")
        sessions.recordPack(session.id,"a".repeat(64))
        sessions.finish(session.id)
        assertTrue(sessions.recordPack(session.id,"a".repeat(64))!!.finished,"identical saves remain idempotent")
        assertFalse(sessions.recordPack(session.id,"b".repeat(64))!!.finished,"new pack must activate even after a completed prior version")
    }

    @Test fun `MCP preview accepts warnings and can select an upper floor or cutaway`() {
        val b=example().let { it.copy(build=it.build+listOf(
            BuildOperation.Clear("door_test",BuildPos(7,2,5),BuildPos(7,3,5)),
            BuildOperation.Fill("refill_test",BuildPos(7,2,5),BuildPos(7,3,5),"wood"),
        )) }
        val result=call("worldsmith_preview_structure",buildJsonObject {
            put("blueprint",WorldsmithJson.format.encodeToJsonElement(b));put("sliceY",4);put("cutaway",true)
        })
        assertFalse(result.isError,result.text)
        val body=result.structuredContent
        assertTrue(body.getValue("cutaway").jsonPrimitive.boolean)
        assertEquals(4,body.getValue("sliceY").jsonPrimitive.int)
        assertTrue(body.getValue("floorPlan").jsonPrimitive.content.startsWith("Local Y=4"))
        assertTrue(body.getValue("diagnostics").jsonArray.any { it.jsonObject.getValue("code").jsonPrimitive.content=="CLEAR_REGION_REFILLED" })
        assertTrue(Files.readString(Path.of(body.getValue("previewPath").jsonPrimitive.content)).contains("ISOMETRIC"))
        val invalid=call("worldsmith_validate_structure",buildJsonObject {
            put("blueprint",WorldsmithJson.format.encodeToJsonElement(b));put("sliceY",63)
        })
        assertTrue(invalid.isError)
    }

    @Test fun `optional structure anchors survive the MCP draft write and portable pack path`() {
        val session=begin()
        val structure=definition().let { it.copy(placement=it.placement.copy(anchor=StructureAnchorTarget("holy_peak"))) }
        val draft=call("worldsmith_put_structure",buildJsonObject {put("sessionId",session);put("structure",WorldsmithJson.format.encodeToJsonElement(structure))})
        assertFalse(draft.isError,draft.text)
        val template=call(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
        val terrain=WorldsmithJson.format.decodeFromJsonElement<com.wjz.worldsmith.core.model.TerrainPlan>(template.getValue("terrain"))
        val shape=(terrain.shape as com.wjz.worldsmith.core.model.TerrainShape.Procedural).copy(anchors=listOf(
            com.wjz.worldsmith.core.model.Anchor("holy_peak",com.wjz.worldsmith.core.model.AnchorPlacement.Fixed(-31,17),200,30.0)))
        val saved=call(WorldsmithWorkflow.WRITE_TOOL,buildJsonObject {
            put("sessionId",session);put("displayName","Anchored shrine")
            put("terrain",WorldsmithJson.format.encodeToJsonElement(terrain.copy(shape=shape)))
            listOf("biomes","features").forEach {put(it,template.getValue(it))}
        })
        assertFalse(saved.isError,saved.text)
        val pack=WorldsmithPackLoader.loadDirectory(Path.of(saved.structuredContent.getValue("path").jsonPrimitive.content))
        assertEquals("holy_peak",pack.structures.structures.single().placement.anchor!!.id)
        assertEquals(pack.computedId,pack.manifest.id)
        assertFalse(call(WorldsmithWorkflow.FINISH_TOOL,buildJsonObject {put("sessionId",session)}).structuredContent.getValue("minecraftCompiled").jsonPrimitive.boolean)
    }
}
