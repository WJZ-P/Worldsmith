package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.drawhost.DrawingJob
import com.wjz.worldsmith.core.drawhost.DrawingJobStage
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class GenerationTargetState { DECLARED, MISSING, NEEDS_ASSET, REPAIR, FROZEN }

@Serializable data class GenerationCategoryProgress(
    val kind:String,val planned:Int?,val matchedDeclared:Int,val extraDefinitions:Int,val declaredTotal:Int,
)
@Serializable data class GenerationTargetProgress(
    val kind:String,val id:String,val name:String,val purpose:String,val state:GenerationTargetState,
    val drawingIds:List<String> = emptyList(),val jobIds:List<String> = emptyList(),
    val diagnosticCodes:List<String> = emptyList(),val detail:String? = null,
)
@Serializable data class GenerationDrawingProgress(
    val jobId:String,val name:String,val stage:String,val drawingIds:List<String>,val structureIds:List<String>,
    val needsApproval:Boolean,val detail:String? = null,
)
@Serializable data class GenerationProgressView(
    val sessionId:String,val revision:Long,val title:String,val prompt:String,val mode:WorkflowMode,val stage:String,
    val planPresent:Boolean,val packId:String?,val declaredTargets:Int,val totalPlannedTargets:Int?,val overallLabel:String,
    val categories:List<GenerationCategoryProgress>,val targets:List<GenerationTargetProgress>,val jobs:List<GenerationDrawingProgress>,
    val issues:List<GenerationIssue>,val nextTool:String,val nextInstruction:String,val requiresUserAction:Boolean,
    val nativeActivationVerified:Boolean,val targetsTruncated:Boolean=false,val jobsTruncated:Boolean=false,val issuesTruncated:Boolean=false,
    val verificationBoundary:String="目标状态只描述已提交草稿或已记录的冻结快照；不代表本次几何/贴图校验、资源包就绪或游戏内完成。",
)

/** Pure display projection over supplied in-memory snapshots. No files, PNG decoding, drawing compiler or host locks. */
object GenerationProgressViews {
    const val MAX_TARGETS=512
    const val MAX_JOBS=32
    const val MAX_ISSUES=32
    private const val MAX_INPUT_JOBS=256
    private val mainKinds=listOf("biome","structure","creature","block","item","quest")
    private val moduleKinds=mapOf("biomes" to "biome","features" to "feature","blocks" to "block","items" to "item","creatures" to "creature","quests" to "quest")
    private val activeStages=setOf(DrawingJobStage.WAITING_APPROVAL,DrawingJobStage.QUEUED,DrawingJobStage.COMPILING,DrawingJobStage.DRAWING,DrawingJobStage.VALIDATING)

    @JvmStatic @JvmOverloads fun inspect(session:WorkflowSession,jobs:List<DrawingJob> = emptyList()):GenerationProgressView {
        val originalPlan=session.designPlan
        val targetsTruncated=(originalPlan?.targets?.size ?: 0)>MAX_TARGETS
        // Invalid oversized plans remain visible as truncated, never as a fabricated completed denominator.
        val plan=originalPlan?.copy(targets=originalPlan.targets.take(MAX_TARGETS),links=originalPlan.links.take(WorldDesignPlans.MAX_LINKS),bosses=originalPlan.bosses.take(16))
        val current=if(plan==originalPlan)session else session.copy(designPlan=plan)
        val suppliedJobs=jobs.asSequence().filter {it.sessionId==session.id}.take(MAX_INPUT_JOBS+1).toList()
        val knownJobs=suppliedJobs.take(MAX_INPUT_JOBS).distinctBy {it.id}
        val inventory=WorldDesignCoverage.draft(current)
        val progress=WorldGenerationProgress.inspectUsingInventory(current,knownJobs,inventory)
        val uniqueTargets=plan?.targets.orEmpty().distinctBy {it.key}
        val plannedKeys=uniqueTargets.map {it.key}.toSet()
        val declared=plannedKeys.count {it in inventory.symbols}
        val denominator=if(originalPlan==null || targetsTruncated)null else plannedKeys.size
        val extraKinds=uniqueTargets.map {it.key.kind}.filter {it !in mainKinds}.distinct()
        val categories=(mainKinds+extraKinds).map {kind->
            val expected=plannedKeys.filter {it.kind==kind}.toSet()
            val actual=inventory.symbols.filter {it.kind==kind}.toSet()
            GenerationCategoryProgress(kind,if(originalPlan==null || targetsTruncated)null else expected.size,
                expected.count {it in actual},actual.count {it !in expected},actual.size)
        }
        // A declared Boss is already one creature target; neither the Boss profile nor its quest link adds progress units.
        val structureDrawings=current.structures.values.take(48).associate {structure->structure.id to
            (listOf(structure.blueprint)+structure.assembly?.pieces.orEmpty().values).flatMap {it.drawing?.variants.orEmpty()}.distinct().take(136)}
        val drawingOwners=linkedMapOf<String,MutableSet<String>>()
        structureDrawings.forEach {(id,drawings)->drawings.forEach {drawingOwners.getOrPut(it) {linkedSetOf()}+=id}}
        val jobsByDrawing=linkedMapOf<String,MutableList<DrawingJob>>()
        knownJobs.forEach {job->job.drawingIds.take(8).forEach {jobsByDrawing.getOrPut(it) {mutableListOf()}+=job}}
        val targetRepairs=repairs(current,progress,plannedKeys)
        val targets=uniqueTargets.map {target->
            val key=target.key
            val drawingIds=if(key.kind=="structure")structureDrawings[key.id].orEmpty() else emptyList()
            val linkedJobs=drawingIds.flatMap {jobsByDrawing[it].orEmpty()}.distinctBy {it.id}.take(32)
            val repair=targetRepairs[key].orEmpty()
            val missingAssets=inventory.textures[key].orEmpty().filter {it !in current.contentAssets}
            val state=when {
                key !in inventory.symbols -> GenerationTargetState.MISSING
                repair.isNotEmpty() -> GenerationTargetState.REPAIR
                missingAssets.isNotEmpty() -> GenerationTargetState.NEEDS_ASSET
                current.packId!=null -> GenerationTargetState.FROZEN
                else -> GenerationTargetState.DECLARED
            }
            val detail=when(state) {
                GenerationTargetState.MISSING -> "尚未提交此计划目标的定义；其他同类内容不计入这个目标。"
                GenerationTargetState.REPAIR -> repair.first().message.take(1536)
                GenerationTargetState.NEEDS_ASSET -> "已提交定义，仍缺少已挂载 PNG：${missingAssets.take(4).joinToString()}"
                GenerationTargetState.FROZEN -> "此目标已声明，当前会话记录了冻结包；此处没有重新校验几何或原生激活。"
                GenerationTargetState.DECLARED -> "已提交目标草稿；尚不代表校验通过或原生完成。"
            }
            GenerationTargetProgress(key.kind.take(64),key.id.take(128),target.title.take(160),target.purpose.take(2048),state,
                drawingIds,linkedJobs.map {it.id.take(128)},repair.map {it.code.take(128)}.distinct().take(8),detail)
        }
        val orderedJobs=knownJobs.withIndex().sortedWith(compareBy<IndexedValue<DrawingJob>>(
            {jobPriority(it.value.stage)},{-it.value.createdAtMillis},{-it.index})).map {it.value}
        val rows=orderedJobs.take(MAX_JOBS).map {job->
            val text=job.message.ifBlank {job.diagnostics.firstOrNull()?.message.orEmpty()}.take(1536).ifBlank {null}
            GenerationDrawingProgress(job.id.take(128),job.request.name.take(160),job.stage.name,job.drawingIds.take(8),
                job.drawingIds.take(8).flatMap {drawingOwners[it].orEmpty()}.distinct().take(48),job.stage==DrawingJobStage.WAITING_APPROVAL,text)
        }
        val visibleIssues=progress.issues.take(MAX_ISSUES).map {it.copy(code=it.code.take(128),category=it.category.take(64),message=it.message.take(2048),
            nextTool=it.nextTool.take(128),requiresAuthoring=it.requiresAuthoring.take(16).map {field->field.take(128)})}
        val themeTitle=(current.contentModules["theme"]?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf {it.isNotBlank()}
        val title=(themeTitle ?: plan?.goal?.takeIf {it.isNotBlank()} ?: current.prompt).lineSequence().firstOrNull().orEmpty().take(160)
        val label=if(denominator==null)"已提交目标草稿 $declared / 目标总数未知" else "已提交目标草稿 $declared/$denominator"
        return GenerationProgressView(current.id,current.revision,title,current.prompt.take(4000),current.mode,progress.stage,originalPlan!=null,current.packId,
            declared,denominator,label,categories,targets,rows,visibleIssues,progress.nextTool,progress.nextInstruction.take(2048),
            progress.requiresUserAction || knownJobs.any {it.stage==DrawingJobStage.WAITING_APPROVAL},progress.nativeActivationVerified,
            targetsTruncated,suppliedJobs.size>MAX_INPUT_JOBS || orderedJobs.size>MAX_JOBS,progress.issues.size>MAX_ISSUES)
    }

    private fun jobPriority(stage:DrawingJobStage)=when {
        stage==DrawingJobStage.WAITING_APPROVAL -> 0
        stage in activeStages -> 1
        stage in setOf(DrawingJobStage.FAILED,DrawingJobStage.INTERRUPTED,DrawingJobStage.CANCELLED) -> 2
        else -> 3
    }

    /** Only diagnostics with a concrete identity/index color a row; module-wide findings stay in the issue panel. */
    private fun repairs(session:WorkflowSession,progress:GenerationProgress,planned:Set<ContentKey>):Map<ContentKey,List<Diagnostic>> {
        val result=linkedMapOf<ContentKey,MutableList<Diagnostic>>()
        fun add(diagnostic:Diagnostic,inlineInputs:Set<String> = emptySet()) {
            if(diagnostic.severity!=DiagnosticSeverity.ERROR)return
            val module=diagnostic.path.removePrefix("modules.").substringBefore('.').substringBefore('[')
            // An unsaved inline document can have a different array order from the session being displayed.
            if(module in inlineInputs)return
            val key=targetForPath(session,diagnostic.path,diagnostic.code,diagnostic.structureId) ?: return
            if(key in planned)result.getOrPut(key) {mutableListOf()}.let {if(it.size<8)it+=diagnostic}
        }
        session.lastWriteFailure?.takeIf {it.revision==session.revision}?.let {receipt->
            receipt.diagnostics.take(PackValidationReceipt.MAX_DIAGNOSTICS).forEach {add(it,receipt.inlineInputs.toSet())}
        }
        progress.issues.filter {it.category=="module"}.take(32).forEach {issue->
            val path=issue.message.substringBefore(": ")
            if(path.length<=512 && path!=issue.message)add(Diagnostic(path,issue.code,DiagnosticSeverity.ERROR,issue.message))
        }
        return result
    }

    private fun targetForPath(session:WorkflowSession,rawPath:String,code:String,structureId:String?):ContentKey? {
        structureId?.let {if(it in session.structures)return ContentKey("structure",it)}
        val path=rawPath.removePrefix("modules.")
        val plan=session.designPlan
        fun index(pattern:String)=Regex(pattern).find(path)?.groupValues?.get(1)?.toIntOrNull()
        index("^designPlan\\.targets\\[(\\d+)]")?.let {return plan?.targets?.getOrNull(it)?.key}
        index("^designPlan\\.links\\[(\\d+)]")?.let {return plan?.links?.getOrNull(it)?.from}
        index("^designPlan\\.bosses\\[(\\d+)]")?.let {i->return plan?.bosses?.getOrNull(i)?.let {
            if(code=="DESIGN_BOSS_QUEST_MISSING")ContentKey("quest",it.quest) else ContentKey("creature",it.creature)
        }}
        index("^(?:structures\\.)*structures\\[(\\d+)]")?.let {return session.structures.values.elementAtOrNull(it)?.let {s->ContentKey("structure",s.id)}}
        moduleKinds.forEach {(module,kind)->
            index("^(?:$module\\.)*$module\\[(\\d+)]")?.let {i->
                val values=session.contentModules[module]?.get(module) as? JsonArray
                val id=((values?.getOrNull(i) as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                return id?.let {ContentKey(kind,it)}
            }
        }
        if(path.startsWith("terrain.")) {
            index("^terrain\\.shape\\.anchors\\[(\\d+)]")?.let {i->
                val shape=session.contentModules["terrain"]?.get("shape") as? JsonObject
                val anchors=shape?.get("anchors") as? JsonArray
                return (((anchors?.getOrNull(i) as? JsonObject)?.get("id")) as? JsonPrimitive)?.contentOrNull?.let {ContentKey("anchor",it)}
            }
            return ContentKey("terrain","main")
        }
        if(path.startsWith("theme.")) {
            index("^(?:theme\\.)*beats\\[(\\d+)]")?.let {i->
                val beats=session.contentModules["theme"]?.get("beats") as? JsonArray
                return (((beats?.getOrNull(i) as? JsonObject)?.get("id")) as? JsonPrimitive)?.contentOrNull?.let {ContentKey("narrative_beat",it)}
            }
            return (session.contentModules["theme"]?.get("id") as? JsonPrimitive)?.contentOrNull?.let {ContentKey("theme",it)}
        }
        return null
    }
}
