package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.validation.*
import kotlinx.serialization.json.*

/** Revision-checked final publication. Preview/preflight never substitutes for this receipt. */
class PackPublicationService(private val store:ManagedPackStore,private val sessions:WorkflowSessions,private val publicationHost:PublicationHost,private val metrics:DrawingMcpService) {
    private fun diagnosticsJson(values:List<Diagnostic>)=McpJson.encode(values)
    fun finish(arguments: JsonObject): McpToolResult {
        val sessionId = McpJson.string(arguments, "sessionId").trim()
        val session = sessions.find(sessionId) ?: return incomplete(
            sessionId,
            WorldsmithWorkflow.BEGIN_TOOL,
            "Unknown sessionId. List saved sessions and resume an existing draft, or begin a new run.",
        )
        val packId = session.packId ?: return incomplete(
            sessionId,
            if (session.architecture == null) WorldsmithWorkflow.ARCHITECTURE_TOOL else WorldsmithWorkflow.WRITE_TOOL,
            "No pack has been saved for this session yet.",
        )
        val directory = store.managed(packId) ?: return incomplete(
            sessionId,
            WorldsmithWorkflow.WRITE_TOOL,
            "Pack '$packId' is no longer in the managed pack directory.",
        )
        val pack = runCatching { WorldsmithPackLoader.loadDirectory(directory) }.getOrElse { failure ->
            return incomplete(
                sessionId,
                WorldsmithWorkflow.WRITE_TOOL,
                "Pack '$packId' could not be read back: " + (failure.message ?: "unreadable"),
            )
        }
        if (pack.computedId != packId) {
            return incomplete(
                sessionId,
                WorldsmithWorkflow.WRITE_TOOL,
                "Pack '$packId' no longer hashes to its own id, so it was edited outside Worldsmith.",
            )
        }

        val diagnostics = WorldsmithPackValidator.validate(pack) + if (pack.structures.architecture == null) listOf(StructureArchitectureValidator.missingPlan()) else emptyList()
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            val failed = buildJsonObject {
                put("sessionId", sessionId)
                put("complete", false)
                put("packId", packId)
                put("nextTool", WorldsmithWorkflow.WRITE_TOOL)
                put("diagnostics", diagnosticsJson(diagnostics))
            }
            return McpToolResult.error("Pack '$packId' no longer passes validation", failed)
        }

        val nativeStart=System.nanoTime()
        val native = runCatching { publicationHost.request(pack,directory) }.getOrElse {
            PublicationStatus("FAILED",it.message ?: "Native publication failed")
        }
        metrics.record(sessionId,"nativePublication",(System.nanoTime()-nativeStart)/1_000_000)
        if(!native.complete) {
            val result=buildJsonObject {
                put("sessionId",sessionId);put("complete",false);put("packId",packId);put("stage",native.stage)
                put("message",native.message);put("diagnostics",diagnosticsJson(native.diagnostics));put("nextTool",WorldsmithWorkflow.FINISH_TOOL)
                put("minecraftCompiled",false);put("landmarkInstancesVerified",false)
            }
            return if(native.stage=="FAILED")McpToolResult.error("Native publication needs repair",result) else McpToolResult.success(result)
        }
        val activationQueued = !session.finished
        if(sessions.finishAtRevision(sessionId,packId,session.revision)==null)return incomplete(sessionId,WorldsmithWorkflow.WRITE_TOOL,"Draft revision changed during native publication; validate and publish the current revision")
        val report = "Worldsmith pack '${pack.manifest.displayName}' is saved and valid: " +
            "${pack.biomes.biomes.size} biomes, ${pack.features.features.size} features and ${pack.structures.structures.size} structures, stored at $directory. " +
            "Native export/readback and Minecraft activation have succeeded; actual world placement is not yet verified."
        val structured = buildJsonObject {
            put("sessionId", sessionId)
            put("complete", true)
            put("packId", pack.manifest.id)
            put("displayName", pack.manifest.displayName)
            put("description", pack.manifest.description)
            put("worldPresetId", "worldsmith:generated/$packId/wasteland")
            put("path", directory.toString())
            put("biomeCount", pack.biomes.biomes.size)
            put("featureCount", pack.features.features.size)
            put("structureCount", pack.structures.structures.size)
            put("blockCount",pack.blocks.blocks.size);put("creatureCount",pack.creatures.creatures.size)
            put("themeTitle",pack.theme.title);put("narrativeBeatCount",pack.theme.beats.size)
            put("assetCount",pack.manifest.assets.size);put("packFormat",3)
            put("clientResourcesVerified",true);put("runtimeScope","local_integrated_world")
            put("groupCount", pack.structures.architecture?.groups?.size ?: 0)
            put("landmarkGroupCount", pack.structures.architecture?.groups?.count { it.role == StructureGroupRole.LANDMARK } ?: 0)
            put("standaloneCount", pack.structures.architecture?.standalone?.size ?: 0)
            put("landmarkInstancesVerified", false)
            put("lightingAssessment", "conservative_authored_voxel_estimate; native_emission_checks_at_export")
            put("minecraftCompiled", true);put("stage","PUBLISHED")
            putJsonObject("climatePlacement") {
                put("semanticSlots", pack.biomes.biomes.count { it.slot != null })
                put("rawClimateBoxes", pack.biomes.biomes.count { it.climate != null })
            }
            put("diagnostics", diagnosticsJson(diagnostics))
            put("activationQueued", false);put("activationSucceeded",true);put("newlyCompleted",activationQueued)
            put("nextTool", JsonNull)
            put("report", report)
        }
        return McpToolResult.success(structured, report)
    }

    /** A false answer is not a failure; it names the step the agent still owes. */
    fun incomplete(sessionId: String, nextTool: String, reason: String): McpToolResult {
        val structured = buildJsonObject {
            put("sessionId", sessionId)
            put("complete", false)
            put("nextTool", nextTool)
            put("reason", reason)
        }
        return McpToolResult.success(structured, "complete=false. $reason Call $nextTool next.")
    }

}
