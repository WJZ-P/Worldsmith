package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.drawhost.DrawingHost
import com.wjz.worldsmith.core.drawhost.DrawingRequest
import com.wjz.worldsmith.core.drawhost.DrawingRuntime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GenerationProgressGatewayTest {
    @TempDir lateinit var temp: Path
    private val empty = JsonObject(emptyMap())
    private fun session(number: Int, mode: WorkflowMode = WorkflowMode.COMPLETE_WORLD) = WorkflowSession(number.toString().padStart(32, '0'), "World $number", mode = mode)
    private fun arguments(id: String) = buildJsonObject { put("sessionId", id) }
    private fun tool(name: String, readOnly: Boolean = false, action: () -> McpToolResult = { McpToolResult.success(empty) }) =
        McpTool(name, name, name, empty, readOnly, handler = { action() })

    @Test fun `restart and read-only snapshots do not invent a current live session`() {
        val a = session(1); val b = session(2)
        val gateway = GenerationProgressGateway({ listOf(a, b) }, { emptyList() })
        assertNull(gateway.snapshot(null).defaultSessionId)
        assertNull(gateway.snapshot(null).view)
        assertEquals(a.id, gateway.snapshot(a.id).view?.sessionId)
        assertNull(gateway.snapshot(null).defaultSessionId, "A manual display read is not an AI resume event")
    }

    @Test fun `mutations and explicit resume set world-prioritized defaults while reads never steal focus`() {
        val a = session(1); val b = session(2); val standalone = session(3, WorkflowMode.STANDALONE)
        val live = mutableListOf(a, b, standalone)
        val gateway = GenerationProgressGateway({ live.toList() }, { emptyList() })
        val mutate = gateway.observe(tool("worldsmith_put_content_modules"))
        mutate.handler(arguments(a.id))
        mutate.handler(arguments(standalone.id))
        assertEquals(a.id, gateway.snapshot(null).defaultSessionId)
        mutate.handler(arguments(b.id))
        gateway.observe(tool("worldsmith_get_content_draft", true)).handler(arguments(a.id))
        assertEquals(b.id, gateway.snapshot(null).defaultSessionId)
        assertEquals(a.id, gateway.snapshot(a.id).selectedSessionId)
        live.remove(a)
        assertNull(gateway.snapshot(a.id).view, "Missing manual selection must not silently follow the other world")
        live.add(a)
        gateway.observe(tool("worldsmith_resume_session", true)).handler(arguments(a.id))
        assertEquals(a.id, gateway.snapshot(null).defaultSessionId)
        val restarted = GenerationProgressGateway({ live.toList() }, { emptyList() })
        assertNull(restarted.snapshot(null).defaultSessionId, "Saved iteration order is not recent live activity")
    }

    @Test fun `failed tool activity retains only a bounded error and clears running state`() {
        val a = session(1)
        val gateway = GenerationProgressGateway({ listOf(a) }, { emptyList() })
        gateway.observe(tool("worldsmith_put_content_modules") { McpToolResult.error("x".repeat(10000)) }).handler(arguments(a.id))
        var snapshot = gateway.snapshot(a.id)
        assertFalse(snapshot.toolRunning)
        assertEquals(512, snapshot.lastToolError?.length)
        assertThrows(IllegalArgumentException::class.java) {
            gateway.observe(tool("worldsmith_put_structure") { throw IllegalArgumentException("y".repeat(10000)) }).handler(arguments(a.id))
        }
        snapshot = gateway.snapshot(a.id)
        assertFalse(snapshot.toolRunning)
        assertEquals("worldsmith_put_structure", snapshot.lastTool)
        assertEquals(512, snapshot.lastToolError?.length)
    }

    @Test fun `older concurrent completion cannot erase newer tool activity`() {
        val a = session(1)
        val gateway = GenerationProgressGateway({ listOf(a) }, { emptyList() })
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val slow = gateway.observe(tool("older") {
                started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); McpToolResult.success(empty)
            })
            val future = executor.submit { slow.handler(arguments(a.id)) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(gateway.snapshot(a.id).toolRunning)
            gateway.observe(tool("newer") {
                assertEquals("newer", gateway.snapshot(a.id).lastTool, "Show the newest tool while its ticket is pending")
                McpToolResult.error("newer failure")
            }).handler(arguments(a.id))
            val overlapping = gateway.snapshot(a.id)
            assertTrue(overlapping.toolRunning)
            assertEquals("older", overlapping.lastTool, "The newer tool finished; only the older tool is still running")
            assertEquals("older", overlapping.sessions.single().lastTool)
            release.countDown(); future.get(5, TimeUnit.SECONDS)
            val snapshot = gateway.snapshot(a.id)
            assertFalse(snapshot.toolRunning)
            assertEquals("newer", snapshot.lastTool)
            assertEquals("newer failure", snapshot.lastToolError)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `actual worker state refreshes at the same draft revision without reading archived jobs`() {
        val a = session(1, WorkflowMode.STANDALONE)
        val root = temp.resolve("jobs")
        val runtime = DrawingRuntime(temp.resolve("unused-runtime"), Path.of(System.getProperty("java.home")))
        DrawingHost(root, runtime).use { host ->
            val job = host.submit(a.id, DrawingRequest("house", "one", "Example", mapOf("Example.java" to "class Example {}")))
            val archive = root.resolve("archive/${a.id}")
            Files.createDirectories(archive)
            Files.writeString(archive.resolve("f".repeat(32) + ".json"), "Not valid JSON; UI polling must never read this archive")
            val gateway = GenerationProgressGateway({ listOf(a) }, host::liveJobs)
            val first = gateway.snapshot(a.id)
            assertEquals("WAITING_APPROVAL", first.view?.jobs?.single { it.jobId == job.id }?.stage)
            host.deny(a.id) // Changes the real in-memory worker record without starting Java compilation.
            val second = gateway.snapshot(a.id)
            assertEquals(first.view?.revision, second.view?.revision)
            assertEquals("CANCELLED", second.view?.jobs?.single { it.jobId == job.id }?.stage)
            assertFalse(Files.exists(root.resolve("work")), "No authoring process should have run")
        }
    }

    @Test fun `saturated observation drops labels without rejecting real tool execution`() {
        val a = session(1)
        val gateway = GenerationProgressGateway({ listOf(a) }, { emptyList() })
        var completed = 0
        fun nested(depth: Int) {
            gateway.observe(tool("pending-$depth") {
                if (depth < 257) nested(depth + 1)
                if (depth == 1) {
                    val remaining = gateway.snapshot(a.id)
                    assertTrue(remaining.toolRunning)
                    assertNull(remaining.lastTool, "The oldest pending label was evicted; do not show a completed newer tool")
                }
                completed++
                McpToolResult.success(empty)
            }).handler(arguments(a.id))
        }
        nested(1)
        assertEquals(257, completed, "Observation capacity must not reject or skip any actual handler")
        val idle = gateway.snapshot(a.id)
        assertFalse(idle.toolRunning)
        assertEquals("pending-257", idle.lastTool)
    }

    @Test fun `real tool catalog begin is tracked and diagnostic progress tool stays read-only`() {
        val sessions = WorkflowSessions(8, { "a".repeat(32) })
        DrawingHost(temp.resolve("drawings")).use { host ->
            val tools = WorldsmithMcpTools(temp.resolve("packs"), sessions = sessions, drawings = host)
            val catalog = tools.all().associateBy { it.name }
            catalog.getValue("worldsmith_begin_world").handler(buildJsonObject {
                put("prompt", "A planned world"); put("mode", "COMPLETE_WORLD"); put("detail", "summary")
            })
            assertEquals("a".repeat(32), tools.progressSnapshot().selectedSessionId)
            val before = tools.progressSnapshot().sessions.single().lastActivitySequence
            val diagnostic = catalog.getValue("worldsmith_get_generation_progress_view")
            assertTrue(diagnostic.readOnly)
            val result = diagnostic.handler(empty)
            assertEquals("a".repeat(32), result.structuredContent["selectedSessionId"]?.jsonPrimitive?.content)
            assertEquals(before, tools.progressSnapshot().sessions.single().lastActivitySequence)
        }
    }
}
