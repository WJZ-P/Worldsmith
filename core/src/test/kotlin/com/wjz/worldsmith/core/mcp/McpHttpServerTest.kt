package com.wjz.worldsmith.core.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

class McpHttpServerTest {
    @TempDir
    lateinit var packDirectory: Path

    @Test
    fun `streamable http exposes the guided Worldsmith workflow`() {
        McpHttpServer(WorldsmithMcpTools(packDirectory).all(), "test").use { server ->
            val endpoint = server.start(0)
            val initialized = post(
                endpoint,
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
            )
            assertEquals(
                McpHttpServer.PROTOCOL_VERSION,
                initialized.getValue("result").jsonObject.getValue("protocolVersion").jsonPrimitive.content,
            )
            val unsupportedProposal = post(
                endpoint,
                """{"jsonrpc":"2.0","id":4,"method":"initialize","params":{"protocolVersion":"draft-obsolete","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
            )
            assertEquals(
                McpHttpServer.PROTOCOL_VERSION,
                unsupportedProposal.getValue("result").jsonObject.getValue("protocolVersion").jsonPrimitive.content,
                "the server publishes one current protocol rather than negotiating older variants",
            )

            val listed = post(endpoint, """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
            val names = listed.getValue("result").jsonObject.getValue("tools").jsonArray
                .map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertTrue(WorldsmithWorkflow.BEGIN_TOOL in names)
            assertTrue(WorldsmithWorkflow.FINISH_TOOL in names)

            val begun = post(
                endpoint,
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"${WorldsmithWorkflow.BEGIN_TOOL}","arguments":{"prompt":"a glass desert"}}}""",
            )
            val structured = begun.getValue("result").jsonObject.getValue("structuredContent").jsonObject
            assertEquals(WorldsmithWorkflow.TEMPLATE_TOOL, structured.getValue("nextTool").jsonPrimitive.content)
            assertTrue(structured.getValue("sessionId").jsonPrimitive.content.isNotBlank())
        }
    }

    private fun post(endpoint: URI, body: String) = Json.parseToJsonElement(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
            HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).also { response -> assertEquals(200, response.statusCode(), response.body()) }.body(),
    ).jsonObject
}
