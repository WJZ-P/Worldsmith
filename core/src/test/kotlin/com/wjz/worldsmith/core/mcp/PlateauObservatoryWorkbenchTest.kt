package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.drawhost.*
import com.wjz.worldsmith.core.structure.BuildBox
import com.wjz.worldsmith.core.structure.BuildPos
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Real source-project -> isolated worker -> sidecar/preflight/preview, without game startup. */
class PlateauObservatoryWorkbenchTest {
    @TempDir lateinit var root: Path

    @Test fun `plateau example executes through real MCP workbench and bundled worker compiler`() {
        val project = Path.of(System.getProperty("worldsmith.projectRoot"))
        val source = Files.readString(project.resolve("docs/examples/theme-landscape/PlateauObservatory.java"))
        val runtime = DrawingRuntime(Path.of(System.getProperty("worldsmith.workerRuntime")), Path.of(System.getProperty("java.home")),
            listOf("--limit-modules=java.base,java.compiler,java.logging,java.xml,java.desktop"))
        DrawingHost(root.resolve("jobs"), runtime, 4903).use { host ->
            val tools = WorldsmithMcpTools(root.resolve("packs"), drawings = host)
            fun call(name: String, args: JsonObject) = StructureTestWorld.call(tools, name, args).also { assertFalse(it.isError, "$name: ${it.structuredContent}") }
            val begin = call("worldsmith_begin_world", buildJsonObject { put("prompt", "A quiet observing outpost on a broad wind-exposed plateau, with a clear horizon, a low service wing and an empty court."); put("detail", "summary") })
            val session = begin.structuredContent.getValue("sessionId").jsonPrimitive.content
            val uploaded = call("worldsmith_put_drawing_source", buildJsonObject {
                put("sessionId", session); put("name", "plateau-observatory"); put("expectedRevision", 0)
                putJsonObject("changes") { put("PlateauObservatory.java", source) }
                putJsonObject("targets") { putJsonObject("observatory") { put("entryClass", "PlateauObservatory"); put("files", McpJson.encode(listOf("PlateauObservatory.java"))) } }
            })
            val sourceProject = uploaded.structuredContent.getValue("projectId")
            val sourceRevision = uploaded.structuredContent.getValue("revision")
            val queued = call("worldsmith_build_drawing", buildJsonObject {
                put("sessionId", session); put("name", "plateau-observatory"); put("requestId", "refined-r1")
                putJsonObject("sourceRef") { put("projectId", sourceProject); put("revision", sourceRevision); put("target", "observatory") }
                put("seeds", McpJson.encode(listOf(381L))); putJsonObject("parameters") { put("study", "refined") }
            })
            val id = queued.structuredContent.getValue("jobId").jsonPrimitive.content
            host.approve(session)
            val deadline = System.nanoTime() + 90_000_000_000L
            var done = host.get(session, id)
            while (done.stage in setOf(DrawingJobStage.WAITING_APPROVAL, DrawingJobStage.QUEUED, DrawingJobStage.COMPILING, DrawingJobStage.DRAWING, DrawingJobStage.VALIDATING)) {
                assertTrue(System.nanoTime() < deadline, "Plateau worker exceeded its bounded test deadline")
                Thread.sleep(40); done = host.get(session, id)
            }
            assertEquals(DrawingJobStage.SUCCEEDED, done.stage, done.message + done.log + done.diagnostics)
            val drawing = done.drawingIds.single()
            val preflight = call("worldsmith_preflight_structure", buildJsonObject { put("sessionId", session); put("drawingId", drawing) })
            assertTrue(preflight.structuredContent.getValue("valid").jsonPrimitive.boolean, preflight.structuredContent.toString())
            val preview = call("worldsmith_preview_drawing", buildJsonObject {
                put("sessionId", session); put("drawingId", drawing); put("view", "isometric_back"); put("renderMode", "clay")
                put("frame", McpJson.encode(BuildBox(BuildPos(-24, 0, -18), BuildPos(24, 28, 20))))
            })
            assertEquals(1, preview.images.size)
            assertTrue(preview.structuredContent.getValue("visualEvidence").jsonObject.getValue("nonAirCells").jsonPrimitive.int > 6000)
            val components = preview.structuredContent.getValue("components").jsonObject.keys
            assertTrue(components.containsAll(listOf("main_tower", "low_service_wing", "open_court", "switchback_stair", "arrival_route", "arrival_threshold")))
            val output = Files.createDirectories(project.resolve("build/structure-quality-verification/plateau-observatory"))
            Files.write(output.resolve("worker-refined-clay-isometric_back.png"), Base64.getDecoder().decode(preview.images.single().data))
            Files.writeString(output.resolve("worker.json"), buildJsonObject {
                put("drawingId", drawing); put("sourceProject", sourceProject); put("sourceRevision", sourceRevision)
                put("workerStage", done.stage.name); put("sourceBytes", done.sourceBytes); put("checks", preflight.structuredContent)
                put("validationScope", "Real MCP source project, bundled isolated worker compiler, frozen authored sidecar, preflight and offline preview. No native site placement or game startup.")
            }.toString())
        }
    }
}
