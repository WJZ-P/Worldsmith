package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.model.TerrainPlan
import com.wjz.worldsmith.core.model.TerrainShape
import com.wjz.worldsmith.core.model.VanillaNoisePreset
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WorldsmithWorkflowTest {
    @TempDir
    lateinit var packDirectory: Path

    private val tools: WorldsmithMcpTools by lazy { WorldsmithMcpTools(packDirectory) }

    private fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): McpToolResult =
        tools.all().single { it.name == name }.handler(arguments)

    private fun begin(prompt: String = "a wind-scoured wasteland"): JsonObject =
        call(WorldsmithWorkflow.BEGIN_TOOL, buildJsonObject { put("prompt", prompt) }).structuredContent

    /** Writes the template back unchanged, which is the shortest run that can succeed. */
    private fun writeTemplateAs(sessionId: String?): JsonObject {
        val template = call(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
        val arguments = buildJsonObject {
            if (sessionId != null) put("sessionId", sessionId)
            put("displayName", "Test World")
            put("terrain", template.getValue("terrain"))
            put("biomes", template.getValue("biomes"))
            put("features", template.getValue("features"))
        }
        return call(WorldsmithWorkflow.WRITE_TOOL, arguments).structuredContent
    }

    private fun finish(sessionId: String): McpToolResult =
        call(WorldsmithWorkflow.FINISH_TOOL, buildJsonObject { put("sessionId", sessionId) })

    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `every procedure step names a tool the catalog actually serves`() {
        val served = tools.all().map { it.name }.toSet()

        WorldsmithWorkflow.PROCEDURE.forEach { step ->
            assertTrue(step.tool in served, "procedure names ${step.tool}, which no tool serves")
        }
        assertTrue(WorldsmithWorkflow.BEGIN_TOOL in served)
        assertEquals(listOf(1, 2, 3), WorldsmithWorkflow.PROCEDURE.map { it.order })
    }

    @Test
    fun `beginning a world makes the prompt authoritative and exposes placement options`() {
        val brief = begin()

        assertFalse(brief.bool("complete"))
        assertTrue(brief.text("sessionId").isNotBlank())
        assertEquals(WorldsmithWorkflow.TEMPLATE_TOOL, brief.text("nextTool"))
        assertEquals(3, brief.getValue("procedure").jsonArray.size)

        // The rules handed to an outside agent are the same prompt the in-game
        // generator uses, so this fails if the two ever drift apart.
        val contract = brief.text("designContract")
        assertTrue("only standard" in contract.lowercase(), "the contract should make the prompt authoritative")
        val terrainContract = brief.text("terrainContract")
        assertTrue("landRatio" in terrainContract)
        assertTrue("continentScale" in terrainContract)
        assertTrue("coastRoughness" in terrainContract)
        assertTrue("verticalScale" in terrainContract)
        assertTrue("caveDensity" in terrainContract)
        assertTrue("set an unwanted landform to zero" in terrainContract)

        val placement = brief.getValue("climatePlacement").jsonObject
        assertTrue("only distribution standard" in placement.getValue("principle").jsonPrimitive.content)
        val presets = placement.getValue("semanticSlotPresets").jsonObject
        assertEquals(6, presets.getValue("relief").jsonArray.size)
        assertEquals(3, presets.getValue("temperature").jsonArray.size)
        assertEquals(2, presets.getValue("humidity").jsonArray.size)
        assertTrue("continentalness" in placement.getValue("rawClimateAxes").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("climateGrid" in brief)
    }

    @Test
    fun `the guided flow answers complete only at the end`() {
        val sessionId = begin().text("sessionId")

        val written = writeTemplateAs(sessionId)
        assertTrue(written.bool("valid"))
        assertTrue(written.bool("sessionRecorded"))
        assertEquals(WorldsmithWorkflow.FINISH_TOOL, written.text("nextTool"))

        val result = finish(sessionId)
        val done = result.structuredContent

        assertFalse(result.isError)
        assertTrue(done.bool("complete"))
        assertEquals(written.text("id"), done.text("packId"))
        assertEquals("worldsmith:generated/${written.text("id")}/wasteland", done.text("worldPresetId"))
        assertEquals(16, done.getValue("biomeCount").jsonPrimitive.int)
        assertEquals(JsonNull, done.getValue("nextTool"))

        val placement = done.getValue("climatePlacement").jsonObject
        assertEquals(16, placement.getValue("semanticSlots").jsonPrimitive.int)
        assertEquals(0, placement.getValue("rawClimateBoxes").jsonPrimitive.int)
        assertTrue(done.text("report").contains("16 biomes"))
    }

    @Test
    fun `finishing before anything was written asks for the write step`() {
        val sessionId = begin().text("sessionId")

        val result = finish(sessionId)

        // Not an error: the run is simply unfinished, and the agent is told what it owes.
        assertFalse(result.isError)
        assertFalse(result.structuredContent.bool("complete"))
        assertEquals(WorldsmithWorkflow.WRITE_TOOL, result.structuredContent.text("nextTool"))
    }

    @Test
    fun `an unknown session sends the agent back to the entry point`() {
        val result = finish("0".repeat(32))

        assertFalse(result.structuredContent.bool("complete"))
        assertEquals(WorldsmithWorkflow.BEGIN_TOOL, result.structuredContent.text("nextTool"))
    }

    @Test
    fun `finishing twice keeps answering complete`() {
        val sessionId = begin().text("sessionId")
        writeTemplateAs(sessionId)

        assertTrue(finish(sessionId).structuredContent.bool("complete"))
        assertTrue(finish(sessionId).structuredContent.bool("complete"))
    }

    @Test
    fun `a guided prompt run must replace compatibility terrain with procedural intent`() {
        val sessionId = begin().text("sessionId")
        val template = call(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
        val terrain = WorldsmithJson.format.decodeFromJsonElement<TerrainPlan>(template.getValue("terrain"))
            .copy(shape = TerrainShape.Vanilla(VanillaNoisePreset.OVERWORLD))
        val result = call(
            WorldsmithWorkflow.WRITE_TOOL,
            buildJsonObject {
                put("sessionId", sessionId)
                put("displayName", "Rejected compatibility terrain")
                put("terrain", WorldsmithJson.format.encodeToJsonElement(terrain))
                put("biomes", template.getValue("biomes"))
                put("features", template.getValue("features"))
            },
        )

        assertTrue(result.isError)
        assertFalse(result.structuredContent.bool("valid"))
        val codes = result.structuredContent.getValue("diagnostics").jsonArray
            .map { it.jsonObject }
            .map { it.getValue("code").jsonPrimitive.content }
        assertTrue("PROMPT_TERRAIN_REQUIRED" in codes)
    }

    @Test
    fun `finishing queues the validated pack for Minecraft exactly once`() {
        val activated = mutableListOf<String>()
        val tools = WorldsmithMcpTools(packDirectory, packFinished = activated::add)
        fun invoke(name: String, arguments: JsonObject = JsonObject(emptyMap())) =
            tools.all().single { it.name == name }.handler(arguments)
        val session = invoke(
            WorldsmithWorkflow.BEGIN_TOOL,
            buildJsonObject { put("prompt", "a quiet salt world") },
        ).structuredContent.text("sessionId")
        val template = invoke(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
        val written = invoke(
            WorldsmithWorkflow.WRITE_TOOL,
            buildJsonObject {
                put("sessionId", session)
                put("displayName", "Activated World")
                put("terrain", template.getValue("terrain"))
                put("biomes", template.getValue("biomes"))
                put("features", template.getValue("features"))
            },
        ).structuredContent

        assertTrue(invoke(WorldsmithWorkflow.FINISH_TOOL, buildJsonObject { put("sessionId", session) })
            .structuredContent.bool("complete"))
        assertTrue(invoke(WorldsmithWorkflow.FINISH_TOOL, buildJsonObject { put("sessionId", session) })
            .structuredContent.bool("complete"))
        assertEquals(listOf(written.text("id")), activated)
    }

    @Test
    fun `a pack written outside a run still saves and says the session was not recorded`() {
        val orphan = writeTemplateAs("not-a-session")

        assertTrue(orphan.bool("valid"))
        assertFalse(orphan.bool("sessionRecorded"))
        assertEquals(WorldsmithWorkflow.BEGIN_TOOL, orphan.text("nextTool"))

        val bare = writeTemplateAs(null)
        assertTrue(bare.bool("valid"))
        assertNull(bare["sessionId"])
    }

    @Test
    fun `a finished run is discarded before an unfinished one`() {
        var next = 0
        val sessions = WorkflowSessions(maxSessions = 2, idFactory = { "s" + next++ })

        val first = sessions.begin("first")
        val second = sessions.begin("second")
        sessions.finish(first.id)
        sessions.begin("third")

        assertEquals(2, sessions.size())
        assertNull(sessions.find(first.id), "the finished run should have been evicted first")
        assertNotNull(sessions.find(second.id), "an unfinished run should outlive a finished one")
    }
}
