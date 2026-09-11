package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.drawhost.DrawingJob
import com.wjz.worldsmith.core.drawhost.DrawingJobStage
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.validation.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class GenerationIssue(
    val code: String,
    val category: String,
    val message: String,
    val nextTool: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val requiresAuthoring: List<String> = emptyList(),
    val requiresUserAction: Boolean = false,
    val priority: Int = 100,
)

/** One bounded failed publication receipt, tied to the validated revision rather than a growing event log. */
@Serializable
data class PackValidationReceipt(
    val revision: Long,
    val diagnostics: List<Diagnostic>,
    val diagnosticCount: Int,
    val displayName: String,
    val description: String = "",
    val inlineInputs: List<String> = emptyList(),
) {
    companion object {
        const val MAX_DIAGNOSTICS = 32
        fun bounded(revision: Long, diagnostics: List<Diagnostic>, displayName: String, description: String, inlineInputs: List<String>) =
            PackValidationReceipt(revision, diagnostics.sortedBy { if (it.severity == DiagnosticSeverity.ERROR) 0 else 1 }
                .take(MAX_DIAGNOSTICS).map { d -> d.copy(path = d.path.take(512), code = d.code.take(128), message = d.message.take(1536),
                    stage = d.stage?.take(128), structureId = d.structureId?.take(128), componentId = d.componentId?.take(128),
                    expected = d.expected?.take(512), actual = d.actual?.take(512), hint = d.hint?.take(1024),
                    metrics = d.metrics.entries.take(16).associate { it.key.take(128) to it.value }) },
                diagnostics.size, displayName.take(160), description.take(8192), inlineInputs.take(10).map { it.take(32) })
    }
}

@Serializable
data class GenerationProgress(
    val sessionId: String,
    val revision: Long,
    val mode: WorkflowMode,
    val stage: String,
    val contentReadyForFrozenCheck: Boolean,
    val nativeActivationVerified: Boolean,
    val planPresent: Boolean,
    val counts: Map<String, Int>,
    val missingModules: List<String>,
    val issues: List<GenerationIssue>,
    val nextTool: String,
    val nextArguments: JsonObject,
    val nextInstruction: String,
    val requiredAuthoring: List<String> = emptyList(),
    val requiresUserAction: Boolean = false,
    val draftChecksOnly: Boolean = true,
    val frozenGeometryChecked: Boolean = false,
    val lastWriteFailure: PackValidationReceipt? = null,
    val writeFailureCurrent: Boolean = false,
)

/** Read-only, bounded draft inspection. No drawing compilation, source execution, image decode or native reload. */
object WorldGenerationProgress {
    private val completeModules = listOf("theme", "terrain", "biomes", "features", "blocks", "items", "creatures", "quests")
    private val worldgenModules = listOf("theme", "terrain", "biomes", "features")
    private val modulePriority = mapOf("theme" to 10, "terrain" to 20, "biomes" to 25, "features" to 30,
        "blocks" to 35, "items" to 36, "creatures" to 45, "quests" to 75)

    fun inspect(session: WorkflowSession, jobs: List<DrawingJob> = emptyList()): GenerationProgress {
        val inventory = WorldDesignCoverage.draft(session)
        val issues = mutableListOf<GenerationIssue>()
        val sid = buildJsonObject { put("sessionId", session.id) }
        val cas = buildJsonObject { put("sessionId", session.id); put("expectedRevision", session.revision) }
        fun issue(code: String, category: String, message: String, tool: String, priority: Int,
            arguments: JsonObject = cas, authoring: List<String> = emptyList(), user: Boolean = false) {
            if (issues.size < 128) issues += GenerationIssue(code, category, message, tool, arguments, authoring, user, priority)
        }
        if (session.archived) issue("GENERATION_ARCHIVED", "session", "Resume this saved draft before editing it; its existing content remains available", "worldsmith_resume_session", -100, sid)
        val failure = session.lastWriteFailure
        val currentFailure = failure?.revision == session.revision
        failure?.takeIf { currentFailure }?.let { receipt -> receipt.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }.forEach { diagnostic ->
            val repair = repair(session, diagnostic)
            val inline = if (receipt.inlineInputs.isEmpty()) "" else " Failed write used unsaved inline inputs (${receipt.inlineInputs.joinToString()}); commit the corrected documents and preserve the other inline inputs before retrying."
            issue(diagnostic.code, "frozen_repair", "${diagnostic.path}: ${diagnostic.message}" + diagnostic.hint?.let { " Repair: $it" }.orEmpty() + inline,
                repair.first, -50, if (repair.first in setOf("worldsmith_put_content_modules", "worldsmith_put_world_design_plan", "worldsmith_build_texture")) cas else sid, repair.second)
        } }
        val complete = session.mode == WorkflowMode.COMPLETE_WORLD
        if (complete && session.designPlan == null) issue("GENERATION_DESIGN_PLAN_REQUIRED", "plan",
            "Translate this prompt into a named, linked complete-world plan before filling modules. Declare biomes, structures, blocks, items, creatures, main-line quests and an actual Boss.",
            "worldsmith_put_world_design_plan", 0, authoring = listOf("plan"))
        session.designPlan?.let { plan -> WorldDesignPlans.validate(plan, complete).take(16).forEach { diagnostic ->
            issue(diagnostic.code, "plan", diagnostic.message, "worldsmith_put_world_design_plan", 1, authoring = listOf("plan"))
        } }
        val required = when (session.mode) {
            WorkflowMode.COMPLETE_WORLD -> completeModules
            WorkflowMode.WORLDGEN_ONLY -> worldgenModules
            WorkflowMode.STANDALONE -> emptyList()
        }
        val missing = required.filter { it !in session.contentModules }
        missing.forEach { module -> issue("GENERATION_MODULE_MISSING", "module", "Author and commit the $module document for this world's prompt",
            "worldsmith_put_content_modules", modulePriority[module] ?: 40, authoring = listOf("modules.$module")) }
        inventory.moduleErrors.forEach { (module, message) -> issue("GENERATION_MODULE_UNREADABLE", "module", "$module: $message",
            "worldsmith_put_content_modules", modulePriority[module] ?: 40, authoring = listOf("modules.$module")) }
        cheapDiagnostics(session).take(24).forEach { (module, diagnostic) -> issue(diagnostic.code, "module", "$module.${diagnostic.path}: ${diagnostic.message}",
            "worldsmith_put_content_modules", (modulePriority[module] ?: 40) + 1, authoring = listOf("modules.$module")) }

        inventory.textures.forEach { (owner, references) -> references.filter { it !in session.contentAssets }.forEach { id ->
            issue("GENERATION_TEXTURE_MISSING", "asset", "${owner.kind}/${owner.id} needs attached PNG bytes for $id; a digest or a UV guide declaration alone is not a painted asset",
                "worldsmith_build_texture", 38, authoring = listOf("recipe"))
        } }
        val active = jobs.filter { it.stage in setOf(DrawingJobStage.WAITING_APPROVAL, DrawingJobStage.QUEUED, DrawingJobStage.COMPILING, DrawingJobStage.DRAWING, DrawingJobStage.VALIDATING) }
        active.take(8).forEach { job ->
            val approval = job.stage == DrawingJobStage.WAITING_APPROVAL
            issue(if (approval) "GENERATION_DRAWING_APPROVAL" else "GENERATION_DRAWING_RUNNING", "drawing",
                if (approval) "Drawing '${job.request.name}' awaits the host's source-execution approval; preserve this job and ask for the required user action" else "Drawing '${job.request.name}' is ${job.stage}; continue this job instead of replacing it",
                "worldsmith_get_drawing_job", 42, buildJsonObject { put("sessionId", session.id); put("jobId", job.id) }, user = approval)
        }
        val knownDrawings = jobs.flatMap { it.drawingIds }.toSet()
        inventory.drawingIds.filter { it !in knownDrawings }.take(16).forEach { drawing ->
            issue("GENERATION_DRAWING_NOT_IN_JOB_INDEX", "drawing", "Structure metadata refers to drawing $drawing, but no successful/current session job indexes it. Inspect the owned artifact before rebuilding anything.",
                "worldsmith_preview_drawing", 58, buildJsonObject { put("sessionId", session.id); put("drawingId", drawing) })
        }
        if (session.mode != WorkflowMode.STANDALONE) {
            if (session.architecture == null) issue("GENERATION_ARCHITECTURE_PLAN_MISSING", "architecture", "Plan this world's required building groups and landmark; existing drawing jobs remain reusable",
                WorldsmithWorkflow.ARCHITECTURE_TOOL, 50, authoring = listOf("architecture"))
            if (session.structures.isEmpty()) issue("GENERATION_STRUCTURES_MISSING", "structure", "No executable structure definition has been committed to this world yet",
                if (active.isEmpty()) WorldsmithWorkflow.STRUCTURE_TOOL else "worldsmith_get_drawing_job", 60,
                if (active.isEmpty()) sid else buildJsonObject { put("sessionId", session.id); put("jobId", active.first().id) },
                if (active.isEmpty()) listOf("structure") else emptyList())
        }
        if (complete) session.designPlan?.let { plan ->
            WorldDesignCoverage.missing(plan, inventory, false).take(48).forEach { diagnostic ->
                val targetIndex=Regex("targets\\[(\\d+)]").find(diagnostic.path)?.groupValues?.get(1)?.toIntOrNull()
                val linkIndex=Regex("links\\[(\\d+)]").find(diagnostic.path)?.groupValues?.get(1)?.toIntOrNull()
                val kind = targetIndex?.let {plan.targets.getOrNull(it)?.key?.kind} ?: linkIndex?.let {plan.links.getOrNull(it)?.from?.kind}
                val module = when {
                    diagnostic.code=="DESIGN_BOSS_QUEST_MISSING" || diagnostic.code=="DESIGN_QUEST_UNTHEMED" || diagnostic.code=="DESIGN_ITEM_UNCONNECTED" -> "quests"
                    "BOSS" in diagnostic.code || diagnostic.code=="DESIGN_CREATURE_UNPLACED" -> "creatures"
                    else -> moduleFor(kind)
                }
                val structural = kind in setOf("structure", "blueprint")
                val tool = if (structural) WorldsmithWorkflow.STRUCTURE_TOOL else "worldsmith_put_content_modules"
                issue(diagnostic.code, if ("BOSS" in diagnostic.code) "boss" else if (kind == "quest") "quest" else "coverage", diagnostic.message,
                    tool, if (structural) 61 else modulePriority[module] ?: if ("BOSS" in diagnostic.code) 65 else 85,
                    if (structural) sid else cas, if (structural) listOf("structure") else listOf("modules.${module ?: "<affected_module>"}"))
            }
        }
        val ordered = issues.sortedWith(compareBy({ it.priority }, { it.code }, { it.message }))
        val counts = linkedMapOf<String, Int>()
        listOf("biome", "structure", "creature", "block", "item", "quest").forEach { kind ->
            counts["${kind}Definitions"] = inventory.symbols.count { it.kind == kind }
            counts["${kind}Planned"] = session.designPlan?.targets?.count { it.key.kind == kind } ?: 0
        }
        counts["bossDefinitions"] = inventory.bosses.size
        counts["bossPlanned"] = session.designPlan?.bosses?.size ?: 0
        counts["attachedTextures"] = session.contentAssets.size
        counts["referencedTextures"] = inventory.textures.values.flatten().distinct().size
        counts["referencedDrawings"] = inventory.drawingIds.size
        counts["activeDrawingJobs"] = active.size
        val next = ordered.firstOrNull()
        val fallback = when {
            session.mode == WorkflowMode.STANDALONE && jobs.any { it.stage == DrawingJobStage.SUCCEEDED && it.drawingIds.isNotEmpty() } -> {
                val job = jobs.last { it.stage == DrawingJobStage.SUCCEEDED && it.drawingIds.isNotEmpty() }
                GenerationIssue("STANDALONE_PREVIEW", "artifact", "Inspect the completed artifact, then export it if requested; this focused run does not require a world pack",
                    "worldsmith_preview_drawing", buildJsonObject { put("sessionId", session.id); put("drawingId", job.drawingIds.first()) })
            }
            session.mode == WorkflowMode.STANDALONE -> GenerationIssue("STANDALONE_AUTHOR", "artifact", "Continue the requested artifact using the drawing or creature authoring contract; no full-world modules are required",
                WorldsmithWorkflow.CONTRACT_TOOL, buildJsonObject { put("id", "draw"); put("section", "authoring-workbench") })
            session.finished && session.packId != null -> GenerationIssue("GENERATION_NATIVE_COMPLETE", "publication", "This saved run has a native completion receipt. Inspect or continue deliberately; do not regenerate finished content",
                "worldsmith_inspect_world_content", buildJsonObject { put("id", requireNotNull(session.packId)) })
            session.packId != null -> GenerationIssue("GENERATION_FINISH", "publication", "The current revision has a frozen pack; request the native publication receipt",
                WorldsmithWorkflow.FINISH_TOOL, sid)
            else -> GenerationIssue("GENERATION_FREEZE", "publication", "Cheap draft checks are complete. Freeze this exact revision; final coverage will inspect real frozen geometry and the whole bundle",
                WorldsmithWorkflow.WRITE_TOOL, buildJsonObject {
                    cas.forEach { (key, value) -> put(key, value) }
                    failure?.takeIf { it.displayName.isNotBlank() }?.let { put("displayName", it.displayName); put("description", it.description) }
                }, if (failure?.displayName.isNullOrBlank()) listOf("displayName") else emptyList())
        }
        val action = next ?: fallback
        val stage = when {
            session.archived -> "ARCHIVED"
            action.requiresUserAction -> "WAITING_USER"
            next != null && next.category == "plan" -> "DESIGN_PLAN"
            next?.category == "frozen_repair" -> "FROZEN_REPAIR"
            next != null -> "AUTHORING"
            session.mode == WorkflowMode.STANDALONE -> "STANDALONE_ARTIFACT"
            session.finished && session.packId != null -> "NATIVE_COMPLETE"
            session.packId != null -> "CORE_SAVED"
            else -> "READY_FOR_FROZEN_CHECK"
        }
        return GenerationProgress(session.id, session.revision, session.mode, stage, next == null && session.mode != WorkflowMode.STANDALONE,
            session.finished && session.packId != null, session.designPlan != null, counts, missing, ordered,
            action.nextTool, action.arguments, action.message, action.requiresAuthoring, action.requiresUserAction,
            lastWriteFailure = failure, writeFailureCurrent = currentFailure)
    }

    private fun repair(session: WorkflowSession, diagnostic: Diagnostic): Pair<String, List<String>> {
        val path = diagnostic.path.removePrefix("modules.")
        val module = path.substringBefore('.').substringBefore('[')
        if (module in completeModules) return "worldsmith_put_content_modules" to listOf("modules.$module")
        if ("ARCHITECTURE" in diagnostic.code || path.startsWith("architecture") || path.startsWith("structures.architecture"))
            return WorldsmithWorkflow.ARCHITECTURE_TOOL to listOf("architecture")
        if (module == "structures" || diagnostic.structureId != null)
            return WorldsmithWorkflow.STRUCTURE_TOOL to listOf("structure")
        if (path.startsWith("designPlan")) {
            if (diagnostic.code == "DESIGN_TEXTURE_BYTES_MISSING") return "worldsmith_build_texture" to listOf("recipe")
            if (diagnostic.code in setOf("DESIGN_PLAN_REQUIRED", "DESIGN_FROZEN_GEOMETRY_INVALID"))
                return if (diagnostic.code == "DESIGN_PLAN_REQUIRED") "worldsmith_put_world_design_plan" to listOf("plan")
                else WorldsmithWorkflow.STRUCTURE_TOOL to listOf("structure")
            val plan = session.designPlan
            val target = Regex("targets\\[(\\d+)]").find(path)?.groupValues?.get(1)?.toIntOrNull()?.let { plan?.targets?.getOrNull(it)?.key?.kind }
            val link = Regex("links\\[(\\d+)]").find(path)?.groupValues?.get(1)?.toIntOrNull()?.let { plan?.links?.getOrNull(it)?.from?.kind }
            val kind = target ?: link
            if (kind in setOf("structure", "blueprint") || diagnostic.code == "DESIGN_BLOCK_UNUSED") return WorldsmithWorkflow.STRUCTURE_TOOL to listOf("structure")
            val affected = when {
                diagnostic.code in setOf("DESIGN_BOSS_QUEST_MISSING", "DESIGN_QUEST_UNTHEMED", "DESIGN_ITEM_UNCONNECTED", "DESIGN_ITEM_UNOBTAINABLE", "DESIGN_ITEM_NO_REACHABLE_PRODUCER") -> "quests"
                "BOSS" in diagnostic.code || diagnostic.code == "DESIGN_CREATURE_UNPLACED" -> "creatures"
                else -> moduleFor(kind)
            }
            if (affected != null) return "worldsmith_put_content_modules" to listOf("modules.$affected")
            return "worldsmith_put_world_design_plan" to listOf("plan")
        }
        // Preserve a concrete failed diagnostic rather than telling callers to restart successful generation work.
        return "worldsmith_get_content_draft" to emptyList()
    }

    private fun moduleFor(kind: String?) = when (kind) {
        "biome" -> "biomes"; "feature" -> "features"; "creature" -> "creatures"; "block" -> "blocks"; "item" -> "items"; "quest" -> "quests"
        "theme", "narrative_beat" -> "theme"; "terrain", "anchor" -> "terrain"; else -> null
    }

    private fun cheapDiagnostics(session: WorkflowSession): List<Pair<String, Diagnostic>> = buildList {
        session.contentModules.forEach { (id, raw) ->
            val result = runCatching { when (id) {
                "theme" -> WorldThemeValidation.validate(McpJson.decode(raw))
                "blocks" -> CustomBlockValidation.validate(McpJson.decode(raw))
                "items" -> CustomItemValidation.validate(McpJson.decode(raw))
                "creatures" -> CustomCreatureValidator.validate(McpJson.decode(raw))
                "quests" -> QuestValidation.validate(McpJson.decode(raw))
                "terrain" -> TerrainPlanValidator.validate(McpJson.decode(raw))
                "features" -> FeatureLibraryValidator.validate(McpJson.decode(raw))
                "biomes" -> session.contentModules["features"]?.let { BiomePlanValidator.validate(McpJson.decode(raw), McpJson.decode(it)) }.orEmpty()
                else -> emptyList()
            } }.getOrDefault(emptyList())
            result.filter { it.severity == DiagnosticSeverity.ERROR }.take(8).forEach { add(id to it) }
        }
    }
}
