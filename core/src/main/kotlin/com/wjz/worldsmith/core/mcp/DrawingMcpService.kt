package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.drawhost.*
import com.wjz.worldsmith.core.structure.*
import kotlinx.serialization.json.*

/** Source editing/build orchestration is separate from world pack publication. */
class DrawingMcpService(private val host:DrawingHost,private val sessions:WorkflowSessions,private val preview:StructurePreviewService) {
    var inspectDrawing:((String,String,Boolean)->StructureInspection)?=null
    private val counters=java.util.concurrent.ConcurrentHashMap<String,Map<String,Long>>()
    private val metricFailures=java.util.concurrent.ConcurrentHashMap<String,String>()
    fun tools():List<McpTool> {
        val string=McpJson.type("string");val obj=McpJson.type("object");val integer=McpJson.type("integer")
        return listOf(
            McpTool("worldsmith_put_drawing_source","Save source project revision","Apply file changes (null deletes), targets and expectedRevision. Each target names entryClass and its source files. Immutable revisions share file content.",McpJson.schema(mapOf("sessionId" to string,"name" to string,"changes" to obj,"targets" to obj,"expectedRevision" to integer),listOf("sessionId","name","changes","expectedRevision")),false,handler=::putSource),
            McpTool("worldsmith_get_drawing_source","Read source project","Read a project revision OR the saved sources of an owned job; optionally select files. Never executes source.",McpJson.schema(mapOf("sessionId" to string,"projectId" to string,"jobId" to string,"revision" to integer,"files" to McpJson.array()),listOf("sessionId")),true,handler=::getSource),
            McpTool("worldsmith_authoring_stats","Authoring session metrics","Report actual host phases and cache hits; call intervals are not model-thinking time.",McpJson.schema(mapOf("sessionId" to string),listOf("sessionId")),true,handler=::stats),
        )
    }
    private fun active(id:String){require(sessions.find(id)?.archived==false) {"Begin or resume an active session first"}}
    private fun putSource(a:JsonObject):McpToolResult {
        val sid=McpJson.string(a,"sessionId");active(sid)
        val changes=a.getValue("changes").jsonObject.mapValues {if(it.value==JsonNull)null else it.value.jsonPrimitive.content}
        val revision=host.sourceStore.put(sid,McpJson.string(a,"name"),changes,a["targets"]?.let {McpJson.decode(it)},a.getValue("expectedRevision").jsonPrimitive.long)
        val affected=host.list(sid).groupBy {it.request.name}.values.mapNotNull {it.maxByOrNull {j->j.revision}}.any {it.request.sourceRef?.projectId==revision.projectId && !host.sourceStore.isCurrent(sid,it.request)}
        if(affected && sessions.find(sid)?.packId!=null)sessions.invalidate(sid)
        return McpToolResult.success(McpJson.encode(revision).jsonObject)
    }
    private fun getSource(a:JsonObject):McpToolResult {
        require(listOf("projectId","jobId").count {it in a}==1) {"Choose projectId OR jobId"}
        a["jobId"]?.let {id->
            val job=host.get(McpJson.string(a,"sessionId"),id.jsonPrimitive.content);val selected=McpJson.strings(a,"files").ifEmpty {job.request.sources.keys.toList()}
            require(selected.all {it in job.request.sources}) {"Unknown source file"}
            return McpToolResult.success(buildJsonObject {put("entryClass",job.request.entryClass);put("sources",McpJson.encode(job.request.sources.filterKeys {it in selected}));job.request.sourceRef?.let {put("sourceRef",McpJson.encode(it))}})
        }
        val revision=host.sourceStore.get(McpJson.string(a,"sessionId"),McpJson.string(a,"projectId"),a["revision"]?.jsonPrimitive?.long)
        val files=McpJson.strings(a,"files").ifEmpty {revision.files.keys.toList()}
        return McpToolResult.success(buildJsonObject {put("project",McpJson.encode(revision));put("sources",McpJson.encode(host.sourceStore.files(revision,files)))})
    }
    fun build(a:JsonObject):McpToolResult {
        val sid=McpJson.string(a,"sessionId");active(sid);val request=McpJson.decode<DrawingRequest>(JsonObject(a-"sessionId"))
        val existing=host.list(sid).any {it.request.requestId==request.requestId};val job=host.submit(sid,request)
        if(!existing)sessions.invalidate(sid)
        return McpToolResult.success(job(job))
    }
    fun job(j:DrawingJob):JsonObject=buildJsonObject {
        put("jobId",j.id);put("sessionId",j.sessionId);put("name",j.request.name);put("revision",j.revision);put("stage",j.stage.name);put("message",j.message);put("inputHash",j.inputHash)
        j.request.sourceRef?.let {put("sourceRef",McpJson.encode(it))};put("entryClass",j.request.entryClass);put("parameters",McpJson.encode(j.request.parameters));put("seeds",McpJson.encode(j.request.seeds))
        put("drawingIds",McpJson.encode(j.drawingIds));put("diagnostics",McpJson.encode(j.diagnostics));put("log",j.log.takeLast(8192));put("persistenceCommitted",j.persistenceCommitted)
        put("timingsMillis",McpJson.encode(j.timingsMillis));put("compileCacheHit",j.compileCacheHit);put("sourceBytes",j.sourceBytes)
        put("checks",McpJson.encode(j.checks));put("executionOnly",j.stage==DrawingJobStage.SUCCEEDED&&j.checks.isEmpty())
        if(j.stage==DrawingJobStage.SUCCEEDED)put("drawings",McpJson.encode(host.statistics(j.sessionId,j.id)))
        put("nextTool",if(j.stage==DrawingJobStage.SUCCEEDED)"worldsmith_preflight_structure" else if(j.stage in listOf(DrawingJobStage.FAILED,DrawingJobStage.INTERRUPTED,DrawingJobStage.CANCELLED))"worldsmith_build_drawing" else "worldsmith_get_drawing_job")
    }
    fun preview(a:JsonObject):McpToolResult {
        val sid=McpJson.string(a,"sessionId");val artifact=host.artifact(sid,McpJson.string(a,"drawingId"),true)
        val components=host.semantics(artifact)?.get("components")?.jsonObject?.mapValues {McpJson.decode<BuildBox>(it.value)}.orEmpty()
        val overlays=McpJson.strings(a,"overlays")
        val inspection=if(overlays.isEmpty())null else inspectDrawing?.invoke(sid,artifact.id,"lighting" in overlays)
        val drawing=host.drawing(artifact);val result=preview.render("$sid:${artifact.name}",drawing,a,components,inspection)
        record(sid,"preview",result.structuredContent.getValue("previewMillis").jsonPrimitive.long)
        return result.copy(structuredContent=JsonObject(result.structuredContent+buildJsonObject {put("drawingId",artifact.id);put("dataHash",artifact.dataHash);put("width",drawing.bounds().width());put("height",drawing.bounds().height());put("depth",drawing.bounds().depth());put("authoredCells",drawing.voxels().size);put("nonAirCells",drawing.nonAirCells());put("view",result.structuredContent.getValue("views").jsonArray.first());put("anchors",McpJson.encode(drawing.anchors().mapValues {(_,v)->BuildPos(v.x(),v.y(),v.z())}))}))
    }
    @Synchronized fun record(session:String,phase:String,millis:Long){
        val old=measurements(session);val next=old+(phase to ((old[phase] ?: 0)+millis))
        try {
            if(session.matches(Regex("[a-f0-9]{32}")))DurableFiles.write(host.root.resolve("metrics/$session.json"),McpJson.encode(next).toString().toByteArray(Charsets.UTF_8))
            counters[session]=next;metricFailures.remove(session)
        }catch(e:Exception){metricFailures[session]="Metrics were not committed: ${e.message}"}
    }
    private fun measurements(session:String):Map<String,Long> = counters.computeIfAbsent(session){
        if(!session.matches(Regex("[a-f0-9]{32}")))emptyMap()else {
            val p=host.root.resolve("metrics/$session.json")
            if(!java.nio.file.Files.isRegularFile(p))emptyMap()else runCatching {require(java.nio.file.Files.size(p)<65536);McpJson.decode<Map<String,Long>>(Json.parseToJsonElement(java.nio.file.Files.readString(p)))}.getOrElse {metricFailures[session]="Stored metrics unreadable; file preserved";emptyMap()}
        }
    }
    private fun stats(a:JsonObject):McpToolResult {
        val sid=McpJson.string(a,"sessionId");require(sessions.find(sid)!=null);val jobs=host.list(sid)
        return McpToolResult.success(buildJsonObject {
            put("sessionId",sid);put("jobs",jobs.size);put("compileCacheHits",jobs.count {it.compileCacheHit});put("sourceBytes",jobs.sumOf {it.sourceBytes});put("buildRevisions",jobs.groupBy {it.request.name}.size)
            put("phaseMillis",McpJson.encode(jobs.flatMap {it.timingsMillis.entries}.groupBy {it.key}.mapValues {it.value.sumOf {e->e.value}}))
            put("requestPhaseMillis",McpJson.encode(measurements(sid)));put("measurementScope","host work only; excludes model reasoning and network waiting")
            metricFailures[sid]?.let {put("metricsPersistenceError",it)}
            put("persistedJobCount",jobs.count {it.persistenceCommitted});put("sourceRecoveryDiagnostics",McpJson.encode(host.recoveryDiagnostics))
        })
    }
}
