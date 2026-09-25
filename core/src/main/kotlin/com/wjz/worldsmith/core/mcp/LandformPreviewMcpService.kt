package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.analysis.LandformPreview
import com.wjz.worldsmith.core.model.TerrainPlan
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Base64

/** Read-only diagrams use the same mathematical profile reference as the native density compiler tests. */
class LandformPreviewMcpService(private val sessions: WorkflowSessions) {
    fun tools(): List<McpTool> = listOf(McpTool(
        "worldsmith_preview_landform", "Inspect an authored terrain landmark section",
        "Plot one draft anchor's offset, mesa or caldera profile against an explicitly supplied constant incomingSurfaceY. Returns an actual offline PNG, samples and caveats. No seed/noise/warp, caves, bands, site placement, native world or aesthetic validation is implied.",
        McpJson.schema(mapOf(
            "sessionId" to McpJson.type("string"),
            "anchorId" to McpJson.type("string"),
            "incomingSurfaceY" to buildJsonObject {
                put("type", "number"); put("minimum", -64); put("maximum", 319)
                put("description", "Explicit constant surrounding surface for this isolated comparison; not a measured terrain height. Repeat with low/high plausible surroundings to compare transitions.")
            },
        ), listOf("sessionId", "anchorId", "incomingSurfaceY")), true, handler = { args ->
            val session = requireNotNull(sessions.find(McpJson.string(args, "sessionId"))) { "Unknown authoring session" }
            val raw = requireNotNull(session.contentModules["terrain"]) { "Commit a terrain draft before previewing its landforms" }
            val terrain = McpJson.decode<TerrainPlan>(raw)
            val report = LandformPreview.analyze(terrain, McpJson.string(args, "anchorId"), args.getValue("incomingSurfaceY").jsonPrimitive.double)
            val bytes = LandformPreview.png(report)
            McpToolResult.success(buildJsonObject {
                put("sessionId", session.id); put("revision", session.revision)
                put("terrainDigest", MessageDigest.getInstance("SHA-256").digest(McpJson.encode(terrain).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) })
                put("report", McpJson.encode(report)); put("readOnly", true)
                put("imageWidth", LandformPreview.WIDTH); put("imageHeight", LandformPreview.HEIGHT)
                put("nextInstruction", "Compare core width, rim height and transition grade to the owning terrain brief and building footprint. This isolated diagram is not proof of theme fit, site clearance or world placement; retain those limits in any review.")
            }, images = listOf(McpImage(Base64.getEncoder().encodeToString(bytes))))
        },
    ))
}
