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
    val images: List<McpImage> = emptyList(),
    val resources: List<McpBinaryResource> = emptyList(),
) {
    companion object {
        fun success(structuredContent: JsonObject, text: String = structuredContent.toString(), images: List<McpImage> = emptyList(),resources:List<McpBinaryResource> = emptyList()) =
            McpToolResult(text, structuredContent, images=images,resources=resources)

        fun error(message: String, structuredContent: JsonObject = buildJsonObject { put("error", message) }) =
            McpToolResult(message, structuredContent, isError = true)
    }
}

data class McpImage(val data: String, val mimeType: String = "image/png")
data class McpBinaryResource(val uri:String,val blob:String,val mimeType:String="application/octet-stream")
