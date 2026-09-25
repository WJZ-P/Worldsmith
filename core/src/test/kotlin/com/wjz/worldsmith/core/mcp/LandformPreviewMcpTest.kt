package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.analysis.LandformPreview
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

class LandformPreviewMcpTest {
    @TempDir lateinit var root: Path

    @Test fun `world procedures expose optional landform inspection in both compact and full modes`() {
        for (mode in listOf(WorkflowMode.COMPLETE_WORLD, WorkflowMode.WORLDGEN_ONLY)) for (summary in listOf(false, true)) {
            val procedure = WorldsmithWorkflow.procedure(mode, summary)
            val preview = procedure.single { it.tool == "worldsmith_preview_landform" }
            assertTrue("incomingSurfaceY" in preview.instruction)
            assertTrue("anchor" in preview.instruction)
            assertTrue(procedure.any { it.tool == "worldsmith_put_content_modules" && it.order < preview.order })
        }
        assertTrue(WorldsmithWorkflow.procedure(WorkflowMode.STANDALONE, true).none { it.tool == "worldsmith_preview_landform" })
        val summary = WorldAuthoringMcpService.summary(WorkflowSession("9".repeat(32), "one independent drawing", mode = WorkflowMode.STANDALONE))
        assertEquals(com.wjz.worldsmith.core.pack.WorldContentBundleIO.FORMAT_VERSION, summary.getValue("runtimePackFormat").jsonPrimitive.int)
    }

    @Test fun `read-only preview renders the actual session profile with revision and digest`() {
        val sessions = WorkflowSessions(directory = root.resolve("sessions"))
        val catalog = WorldsmithMcpTools(root.resolve("packs"), sessions = sessions)
        fun call(name: String, args: JsonObject) = catalog.all().single { it.name == name }.handler(args)
        val begun = call(WorldsmithWorkflow.BEGIN_TOOL, buildJsonObject { put("prompt", "A terraced sanctuary above a crater") })
        val id = begun.structuredContent.getValue("sessionId").jsonPrimitive.content
        val template = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").terrain
        val terrain = template.copy(shape = (template.shape as TerrainShape.Procedural).copy(
            anchors = listOf(Anchor("sanctuary", AnchorPlacement.Fixed(0, 0), 180, AnchorRelief.Caldera(72, 142, .25, .7, 2.0)))))
        val put = call("worldsmith_put_content_modules", buildJsonObject {
            put("sessionId", id); put("expectedRevision", sessions.find(id)!!.revision)
            put("modules", buildJsonObject { put("terrain", McpJson.encode(terrain)) })
        })
        assertFalse(put.isError, put.text)
        val before = sessions.find(id)!!
        val args = buildJsonObject { put("sessionId", id); put("anchorId", "sanctuary"); put("incomingSurfaceY", 90.0) }
        val tool = catalog.all().single { it.name == "worldsmith_preview_landform" }
        assertTrue(tool.readOnly)
        val result = tool.handler(args)
        assertFalse(result.isError, result.text)
        assertEquals(before.revision, result.structuredContent.getValue("revision").jsonPrimitive.long)
        assertEquals(1, result.images.size)
        val image = ImageIO.read(Base64.getDecoder().decode(result.images.single().data).inputStream())
        assertEquals(LandformPreview.WIDTH, image.width)
        val report = result.structuredContent.getValue("report").jsonObject
        assertEquals("caldera", report.getValue("anchor").jsonObject.getValue("relief").jsonObject.getValue("kind").jsonPrimitive.content)
        assertFalse(report.getValue("actualTerrainSampled").jsonPrimitive.boolean)
        assertEquals(result.structuredContent["terrainDigest"], tool.handler(args).structuredContent["terrainDigest"])
        assertEquals(before, sessions.find(id))
        assertThrows(IllegalArgumentException::class.java) { tool.handler(JsonObject(args + ("anchorId" to JsonPrimitive("missing")))) }
        assertEquals(before, sessions.find(id))
    }
}
