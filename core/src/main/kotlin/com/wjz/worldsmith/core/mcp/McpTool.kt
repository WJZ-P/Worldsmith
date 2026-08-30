package com.wjz.worldsmith.core.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One MCP tool and its local handler. */
data class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    val idempotent: Boolean = readOnly,
    val handler: (JsonObject) -> McpToolResult,
)

/** A tool call result in both human-readable and structured forms. */
data class McpToolResult(
    val text: String,
    val structuredContent: JsonObject,
    val isError: Boolean = false,
) {
    companion object {
        fun success(structuredContent: JsonObject, text: String = structuredContent.toString()) =
            McpToolResult(text, structuredContent)

        fun error(message: String, structuredContent: JsonObject = buildJsonObject { put("error", message) }) =
            McpToolResult(message, structuredContent, isError = true)
    }
}
