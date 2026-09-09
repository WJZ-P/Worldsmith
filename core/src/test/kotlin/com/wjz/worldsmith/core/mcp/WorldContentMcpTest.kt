package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ExistingWorldContentModules
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class WorldContentMcpTest {
    @TempDir lateinit var root: Path
    private val tools by lazy { WorldsmithMcpTools(root.resolve("packs")) }
    private fun call(name: String, args: JsonObject = JsonObject(emptyMap())) = StructureTestWorld.call(tools, name, args)

    @Test fun `capabilities distinguish installed format3 modules from absent native host and future quest modules`() {
        val result = call("worldsmith_get_content_framework")
        assertFalse(result.isError)
        val data = result.structuredContent
        assertFalse(data.getValue("customBlockRuntime").jsonPrimitive.boolean)
        assertFalse(data.getValue("customCreatureRuntime").jsonPrimitive.boolean)
        assertTrue(data.getValue("newPackFormatEnabled").jsonPrimitive.boolean)
        assertEquals(3, data.getValue("packFormat").jsonPrimitive.int)
        assertTrue(data.getValue("legacyPackFormats").jsonArray.isEmpty())
        assertEquals(7, data.getValue("installedModules").jsonArray.size)
        assertEquals(setOf("quests", "achievements"), data.getValue("plannedModules").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
        for (tool in tools.all().filter { it.name in setOf("worldsmith_get_content_framework", "worldsmith_plan_world_content", "worldsmith_inspect_world_content") }) assertTrue(tool.readOnly)
    }

    @Test fun `planning parses real typed documents and unsupported modules never become supported content`() {
        val input = McpJson.encode(ExistingWorldContentModules.input(WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands"))).jsonObject
        val good = call("worldsmith_plan_world_content", input)
        assertFalse(good.isError, good.text)
        assertTrue(good.structuredContent.getValue("catalogValid").jsonPrimitive.boolean)
        assertFalse(good.structuredContent.getValue("activationVerified").jsonPrimitive.boolean)
        assertFalse(good.structuredContent.getValue("capabilitiesSatisfied").jsonPrimitive.boolean, "Core-only host does not advertise a native compiler")
        val extra = JsonObject(input + ("modules" to JsonObject(input.getValue("modules").jsonObject + ("quests" to buildJsonObject { put("schemaVersion", 1) }))))
        val bad = call("worldsmith_plan_world_content", extra)
        assertTrue(bad.isError)
        assertTrue(bad.structuredContent.getValue("plan").jsonObject.getValue("diagnostics").jsonArray.any { it.jsonObject.getValue("code").jsonPrimitive.content == "CONTENT_MODULE_UNAVAILABLE" })
    }

    @Test fun `saved pack inspection preserves the manifest and existing publication identity`() {
        val template = call(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
        val written = call(WorldsmithWorkflow.WRITE_TOOL, buildJsonObject {
            put("displayName", "Content catalog test")
            listOf("terrain", "biomes", "features", "structures", "theme", "blocks", "creatures").forEach { put(it, template.getValue(it)) }
        })
        assertFalse(written.isError, written.text)
        val id = written.structuredContent.getValue("id").jsonPrimitive.content
        val manifest = root.resolve("packs/$id/worldsmith.json")
        val before = Files.readAllBytes(manifest)
        val result = call("worldsmith_inspect_world_content", buildJsonObject { put("id", id) })
        assertFalse(result.isError, result.text)
        assertEquals(id, result.structuredContent.getValue("plan").jsonObject.getValue("catalog").jsonObject.getValue("scope").jsonPrimitive.content)
        assertArrayEquals(before, Files.readAllBytes(manifest))
        assertEquals(id, WorldsmithPackLoader.loadDirectory(manifest.parent).computedId)
        assertTrue(call("worldsmith_inspect_world_content", buildJsonObject { put("id", "../outside") }).isError)
    }

    @Test fun `content capabilities are callable over the real loopback MCP transport`() {
        McpHttpServer(tools.all(), "content-framework-test").use { server ->
            val endpoint = server.start(0)
            HttpClient.newHttpClient().use { client ->
                val body = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                    putJsonObject("params") { put("name", "worldsmith_get_content_framework"); putJsonObject("arguments") {} }
                }
                val request = HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                val result = Json.parseToJsonElement(response.body()).jsonObject.getValue("result").jsonObject
                val data = result.getValue("structuredContent").jsonObject
                assertEquals(2, data.getValue("frameworkVersion").jsonPrimitive.int)
                assertFalse(data.getValue("customCreatureRuntime").jsonPrimitive.boolean)
                assertEquals(7, data.getValue("installedModules").jsonArray.size)
            }
        }
    }
}
