package com.wjz.worldsmith.core.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A small stateless MCP Streamable HTTP server.
 *
 * It deliberately supports only the MCP surface Worldsmith needs: initialize,
 * ping, tool discovery and tool calls. Sessions and server-sent notifications
 * are unnecessary for local pack generation, so every response is regular
 * JSON and every HTTP connection may close after one request.
 */
class McpHttpServer(
    tools: List<McpTool>,
    private val serverVersion: String,
) : AutoCloseable {
    private val tools = tools.associateBy { it.name }.also { indexed ->
        require(indexed.size == tools.size) { "MCP tool names must be unique" }
    }

    @Volatile
    private var httpServer: HttpServer? = null
    private var executor: ExecutorService? = null

    val endpoint: URI?
        get() = httpServer?.address?.port?.let { URI.create("http://$LOOPBACK_HOST:$it$MCP_PATH") }

    /**
     * Binds [preferredPort], or the first free port after it.
     *
     * A fixed port is what makes the bridge findable, and it is also what makes
     * it collide; a collision must not be the difference between the game
     * starting and not. Nothing outside should assume the preferred port was
     * the one taken - the returned URI and the discovery file both carry the
     * port that actually bound.
     *
     * Port 0 already means "any free port" to the OS, so it is bound once and
     * never walked.
     */
    @Synchronized
    @JvmOverloads
    fun start(preferredPort: Int, attempts: Int = DEFAULT_BIND_ATTEMPTS): URI {
        check(httpServer == null) { "MCP server is already running" }
        require(preferredPort in 0..65535) { "MCP port must be between 0 and 65535" }
        require(attempts >= 1) { "MCP bind attempts must be at least 1" }
        if (preferredPort == 0) {
            return bind(0)
        }

        var lastFailure: BindException? = null
        for (offset in 0 until attempts) {
            val port = preferredPort + offset
            if (port > 65535) {
                break
            }
            try {
                return bind(port)
            } catch (busy: BindException) {
                lastFailure = busy
            }
        }
        throw IOException(
            "No free port between $preferredPort and ${minOf(preferredPort + attempts - 1, 65535)}",
            lastFailure,
        )
    }

    private fun bind(port: Int): URI {
        val workerPool = Executors.newVirtualThreadPerTaskExecutor()
        val candidate = try {
            HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port), 0)
        } catch (failure: Throwable) {
            workerPool.close()
            throw failure
        }
        candidate.executor = workerPool
        candidate.createContext(MCP_PATH, ::handle)
        candidate.start()

        executor = workerPool
        httpServer = candidate
        return requireNotNull(endpoint)
    }

    @Synchronized
    override fun close() {
        httpServer?.stop(0)
        httpServer = null
        executor?.close()
        executor = null
    }

    private fun handle(exchange: HttpExchange) {
        try {
            addCommonHeaders(exchange)
            when (exchange.requestMethod.uppercase()) {
                "OPTIONS" -> respondEmpty(exchange, 204, "POST, OPTIONS")
                "POST" -> handlePost(exchange)
                else -> respondEmpty(exchange, 405, "POST, OPTIONS")
            }
        } catch (tooLarge: RequestTooLargeException) {
            respondJson(exchange, 413, errorResponse(null, -32600, tooLarge.message ?: "Request is too large"))
        } catch (failure: Throwable) {
            runCatching {
                respondJson(exchange, 400, errorResponse(null, -32700, "Invalid MCP request: ${failure.message}"))
            }.onFailure { exchange.close() }
        }
    }

    private fun handlePost(exchange: HttpExchange) {
        if (!isAllowedOrigin(exchange.requestHeaders.getFirst("Origin"))) {
            respondJson(exchange, 403, errorResponse(null, -32600, "Origin is not allowed"))
            return
        }
        val contentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty().lowercase()
        if (!contentType.startsWith("application/json")) {
            respondJson(exchange, 415, errorResponse(null, -32600, "Content-Type must be application/json"))
            return
        }

        val request = JSON.parseToJsonElement(readBody(exchange)).jsonObject
        if (request["jsonrpc"]?.jsonPrimitive?.contentOrNull != "2.0") {
            respondJson(exchange, 400, errorResponse(request["id"], -32600, "jsonrpc must be 2.0"))
            return
        }

        val method = request["method"]?.jsonPrimitive?.contentOrNull
        if (method == null) {
            respondJson(exchange, 400, errorResponse(request["id"], -32600, "MCP method is missing"))
            return
        }
        val id = request["id"]
        if (id == null) {
            // MCP notifications never receive JSON-RPC responses.
            respondEmpty(exchange, 202)
            return
        }

        val result = when (method) {
            "initialize" -> initializeResult()
            "ping" -> buildJsonObject { }
            "tools/list" -> toolsListResult()
            "tools/call" -> toolCallResult(request)
            else -> {
                respondJson(exchange, 200, errorResponse(id, -32601, "Unknown MCP method '$method'"))
                return
            }
        }
        respondJson(exchange, 200, successResponse(id, result))
    }

    private fun initializeResult(): JsonObject {
        return buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", SERVER_NAME)
                put("title", SERVER_TITLE)
                put("version", serverVersion)
            }
            put("instructions", SERVER_INSTRUCTIONS)
        }
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            tools.values.forEach { tool ->
                add(
                    buildJsonObject {
                        put("name", tool.name)
                        put("title", tool.title)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                        putJsonObject("annotations") {
                            put("title", tool.title)
                            put("readOnlyHint", tool.readOnly)
                            put("destructiveHint", false)
                            put("idempotentHint", tool.idempotent)
                            put("openWorldHint", false)
                        }
                    },
                )
            }
        }
    }

    private fun toolCallResult(request: JsonObject): JsonObject {
        val params = request["params"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return toolError("tools/call params must be an object")
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return toolError("tools/call requires a tool name")
        val tool = tools[name] ?: return toolError("Unknown Worldsmith tool '$name'")
        val arguments = params["arguments"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        val result = runCatching { tool.handler(arguments) }
            .getOrElse { McpToolResult.error(it.message ?: "Tool call failed") }

        return buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", result.text)
                    },
                )
            }
            put("structuredContent", result.structuredContent)
            if (result.isError) put("isError", true)
        }
    }

    private fun toolError(message: String): JsonObject {
        val result = McpToolResult.error(message)
        return buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", result.text) })
            }
            put("structuredContent", result.structuredContent)
            put("isError", true)
        }
    }

    private fun readBody(exchange: HttpExchange): String {
        val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_REQUEST_BYTES) {
            throw RequestTooLargeException("MCP request exceeds $MAX_REQUEST_BYTES bytes")
        }
        val bytes = exchange.requestBody.use { it.readNBytes(MAX_REQUEST_BYTES + 1) }
        if (bytes.size > MAX_REQUEST_BYTES) {
            throw RequestTooLargeException("MCP request exceeds $MAX_REQUEST_BYTES bytes")
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun isAllowedOrigin(origin: String?): Boolean {
        if (origin == null) return true
        val uri = runCatching { URI.create(origin) }.getOrNull() ?: return false
        return uri.host?.lowercase() in ALLOWED_ORIGIN_HOSTS
    }

    private fun addCommonHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("MCP-Protocol-Version", PROTOCOL_VERSION)
    }

    private fun respondEmpty(exchange: HttpExchange, status: Int, allow: String? = null) {
        allow?.let { exchange.responseHeaders.set("Allow", it) }
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun respondJson(exchange: HttpExchange, status: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun successResponse(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private class RequestTooLargeException(message: String) : RuntimeException(message)

    companion object {
        const val PROTOCOL_VERSION: String = "2025-11-25"
        const val MCP_PATH: String = "/mcp"
        const val LOOPBACK_HOST: String = "127.0.0.1"
        /** How far past the preferred port to walk before giving up. */
        const val DEFAULT_BIND_ATTEMPTS: Int = 16

        private const val SERVER_NAME = "worldsmith"
        private const val SERVER_TITLE = "Worldsmith MCP Bridge"
        private const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
        private val JSON = Json { ignoreUnknownKeys = true }
        private val ALLOWED_ORIGIN_HOSTS = setOf(LOOPBACK_HOST, "localhost", "::1")
        private const val SERVER_INSTRUCTIONS =
            "Worldsmith creates portable Minecraft world-generation packs. Call " +
                "${WorldsmithWorkflow.BEGIN_TOOL} first: it returns the procedure to follow, the rules a pack " +
                "must satisfy and a sessionId. Carry that sessionId through the run and keep going until " +
                "${WorldsmithWorkflow.FINISH_TOOL} answers complete=true. Use only Worldsmith tools for files " +
                "under the managed pack directory."
    }
}
