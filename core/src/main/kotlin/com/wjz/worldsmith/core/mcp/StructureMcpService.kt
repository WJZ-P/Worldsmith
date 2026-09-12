package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.drawhost.*
import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.validation.*
import kotlinx.serialization.json.*
import java.nio.file.*
import com.wjz.worldsmith.core.content.CustomBlockLibrary

class StructureMcpService(private val directory:Path,private val host:DrawingHost,private val sessions:WorkflowSessions,
    private val native:StructureNativeHost?,private val preview:StructurePreviewService,private val metrics:DrawingMcpService) {
    val authored=AuthoredDraftResolver(host)
    private val checks=linkedMapOf<String,Pair<String,StructureCheckService>>()
    private fun nativeFor(sid:String?):StructureNativeHost? {
        if(sid==null)return native
        val session=requireNotNull(sessions.find(sid)) { "Unknown workflow session" }
        val blocks=session.contentModules["blocks"]?.let {McpJson.decode<CustomBlockLibrary>(it)} ?: CustomBlockLibrary()
        return native?.forContent(sid,blocks)
    }
    private fun checksFor(sid:String):StructureCheckService {
        val scoped=try {nativeFor(sid.takeIf {it.isNotEmpty()})}catch(e:IllegalArgumentException) {
            // A broken content draft is a native-stage diagnostic, not a reason to discard usable geometry.
            object:StructureNativeHost {
                override val identity="invalid-content-scope:$sid:${e.message}"
                override fun query(ids:List<String>,search:String,limit:Int):JsonObject=throw e
                override fun inspect(geometry:CompiledStructure)=listOf(Diagnostic("contentModules.blocks","INVALID_NATIVE_CONTENT_CONTEXT",DiagnosticSeverity.ERROR,e.message ?: "Invalid custom block context"))
            }
        }
        val identity=scoped?.identity.orEmpty()
        return synchronized(checks) {
            checks[sid]?.takeIf {it.first==identity}?.second ?: StructureCheckService(scoped).also {
                checks[sid]=identity to it
                while(checks.size>64)checks.remove(checks.keys.first())
            }
        }
    }
    fun tools():List<McpTool> {
        val string=McpJson.type("string");val obj=McpJson.type("object");val integer=McpJson.type("integer")
        return listOf(
            McpTool("worldsmith_preflight_structure","Preflight a drawing or structure","Inspect geometry, semantic, native, assembly and deployment stages separately. Errors retain usable model geometry. Accept structure, blueprint, or drawingId. estimateLighting optionally adds non-blocking authored-voxel lighting diagnostics; default false.",McpJson.schema(mapOf("sessionId" to string,"structure" to obj,"blueprint" to obj,"drawingId" to string,"estimateLighting" to buildJsonObject {put("type","boolean");put("default",false)}),listOf("sessionId")),true,handler=::preflight),
            McpTool("worldsmith_query_block_states","Query native block vocabulary","Query block/state strings or search registered ids. Optional sessionId also resolves that draft's logical custom blocks. Return legal properties and actual emission, without exporting NBT.",McpJson.schema(mapOf("sessionId" to string,"ids" to McpJson.array(),"search" to string,"limit" to integer),emptyList()),true,handler={a->
                val scoped=nativeFor(a["sessionId"]?.jsonPrimitive?.content)
                if(scoped==null)McpToolResult.success(buildJsonObject {put("stage","NOT_RUN");put("message","A bootstrapped native host is required")})
                else McpToolResult.success(scoped.query(McpJson.strings(a,"ids"),a["search"]?.jsonPrimitive?.content.orEmpty(),a["limit"]?.jsonPrimitive?.int ?: 32))
            }),
            McpTool("worldsmith_put_architecture_draft","Commit coherent architecture revision","Atomically commit a plan plus changed structures and removals at expectedRevision. Store repairable drafts; publication still requires strict checks.",McpJson.schema(mapOf("sessionId" to string,"expectedRevision" to integer,"architecture" to obj,"structures" to buildJsonObject {put("type","array");put("items",obj)},"remove" to McpJson.array()),listOf("sessionId","expectedRevision")),false,handler=::putDraft),
            McpTool("worldsmith_archive_session","Archive a draft without deleting it","Archive session and terminal jobs to release active capacity. Cancel active jobs first. Resume restores data only.",McpJson.schema(mapOf("sessionId" to string),listOf("sessionId")),false,handler={a->
                val sid=McpJson.string(a,"sessionId");require(sessions.find(sid)!=null);host.archive(sid);val s=sessions.archive(sid)
                McpToolResult.success(buildJsonObject {put("sessionId",sid);put("archived",s.archived);put("sourceExecuted",false)})
            }),
        )
    }
    fun resolve(session:String,raw:JsonObject,inspection:Boolean=false)=authored.resolve(session,raw,inspection)
    fun attach(session:String,library:StructureLibrary,inspection:Boolean=false):StructureLibrary {
        val artifacts=linkedMapOf<String,DrawingArtifact>();val assets=linkedMapOf<String,com.wjz.worldsmith.core.draw.DrawStructure>();val sources=linkedMapOf<String,DrawingSourceRecord>()
        for(d in library.structures)for(b in listOf(d.blueprint)+d.assembly?.pieces.orEmpty().values)b.drawing?.let {source->
            require(source.variants.size in 1..8)
            for(id in source.variants)if(id !in artifacts){val a=host.artifact(session,id,inspection||source.allowPreviousRevision);artifacts[id]=a.copy(sessionId="",jobId="",revision=0);assets[id]=host.drawing(a);sources[a.sourceHash]=host.source(a)}
        }
        return library.copy(schemaVersion=if(artifacts.isEmpty())library.schemaVersion else 2,artifacts=artifacts,sources=sources,drawingAssets=assets)
    }
    private fun definition(sid:String,args:JsonObject):WorldStructureDefinition {
        require(listOf("structure","blueprint","drawingId").count {it in args}==1) {"Choose structure, blueprint OR drawingId"}
        args["structure"]?.let {return resolve(sid,it.jsonObject,true)}
        val blueprint=args["blueprint"]?.jsonObject ?: run {
            val id=McpJson.string(args,"drawingId");val artifact=host.artifact(sid,id,true);val drawing=host.drawing(artifact)
            buildJsonObject {
                put("id",artifact.name)
                if(artifact.semanticsHash!=null)putJsonObject("authored"){put("variants",JsonArray(listOf(JsonPrimitive(id))))}
                else {putJsonObject("drawing"){put("variants",JsonArray(listOf(JsonPrimitive(id))));put("allowPreviousRevision",true)};put("origin",McpJson.encode(BuildPos(drawing.bounds().min().x(),drawing.bounds().min().y(),drawing.bounds().min().z())))}
            }
        }
        return WorldStructureDefinition(blueprint.getValue("id").jsonPrimitive.content,McpJson.decode(authored.blueprint(sid,blueprint,true)),StructurePlacement(emptyList()))
    }
    fun inspect(sid:String,d:WorldStructureDefinition,assemblyContext:Boolean=true,estimateLighting:Boolean=false):StructureInspection {
        val library=attach(sid,StructureLibrary(structures=listOf(d)),true);val components=authored.components(sid,d.blueprint)
        val result=checksFor(sid).inspect(d,library.drawingAssets,components,sid,assemblyContext,estimateLighting)
        for((stage,data)in result.report.stages)metrics.record(sid,stage,data.elapsedMillis)
        return result
    }
    private fun preflight(a:JsonObject):McpToolResult {
        val sid=McpJson.string(a,"sessionId");require(sessions.find(sid)!=null)
        val d=definition(sid,a);val estimate=a["estimateLighting"]?.jsonPrimitive?.boolean ?: false
        var result=inspect(sid,d,"structure" in a,estimate)
        if(a["drawingId"]!=null&&host.artifact(sid,McpJson.string(a,"drawingId"),true).semanticsHash==null)
            result=result.copy(report=result.report.copy(stages=result.report.stages+("semantics" to StructureCheckStage("NOT_RUN"))+("assembly" to StructureCheckStage("NOT_RUN"))))
        return McpToolResult.success(buildJsonObject {put("checks",McpJson.encode(result.report));put("valid",result.report.valid);put("readyForPublication",false);put("lightingEstimated",estimate);put("lighting",McpJson.encode(result.lighting));put("nextTool",if(result.report.valid)"worldsmith_put_structure" else "worldsmith_preview_structure")})
    }
    fun completed(job:DrawingJob):List<StructureCheckReport> = job.drawingIds.map {id->
        try {inspect(job.sessionId,definition(job.sessionId,buildJsonObject {put("drawingId",id)}),false).report}
        catch(e:Exception){StructureCheckReport(job.request.name,mapOf("semantics" to StructureCheckStage("FAILED",1,listOf(Diagnostic("authored","AUTHORED_METADATA_ERROR",DiagnosticSeverity.ERROR,e.message ?: "Invalid metadata")))),false)}
    }
    private fun putDraft(a:JsonObject):McpToolResult {
        val sid=McpJson.string(a,"sessionId");val old=requireNotNull(sessions.find(sid));require(!old.archived)
        val plan=a["architecture"]?.let {McpJson.decode<StructureArchitecture>(it)}
        plan?.let {val errors=StructureArchitectureValidator.validatePlan(it);require(errors.none {d->d.severity==DiagnosticSeverity.ERROR}) {errors.toString()}}
        val structures=a["structures"]?.jsonArray?.map {resolve(sid,it.jsonObject)}.orEmpty()
        val result=sessions.putArchitectureDraft(sid,a.getValue("expectedRevision").jsonPrimitive.long,plan,structures,McpJson.strings(a,"remove")) ?: error("Unknown session")
        return McpToolResult.success(buildJsonObject {put("sessionId",sid);put("revision",result.revision);put("draftCount",result.structures.size);put("published",false);put("nextTool","worldsmith_validate_architecture")})
    }
    fun preview(a:JsonObject):McpToolResult {
        val sid=a["sessionId"]?.jsonPrimitive?.content.orEmpty();val d=definition(sid,a)
        val estimate=a["overlays"]?.jsonArray?.any {it.jsonPrimitive.content=="lighting"}==true
        val inspected=inspect(sid,d,"structure" in a,estimate);val variant=a["variant"]?.jsonPrimitive?.int ?: 0
        val g=inspected.geometries[d.blueprint.id]?.getOrNull(variant)
        if(g==null)return McpToolResult.error("No usable geometry for this preview",buildJsonObject {put("checks",McpJson.encode(inspected.report))})
        val raw=StructurePreviewService.drawing(g);val cutaway=a["cutaway"]?.jsonPrimitive?.boolean ?: false
        val localSlice=a["sliceY"]?.jsonPrimitive?.int ?: minOf(2,g.size.y-1)
        if(localSlice !in 0 until g.size.y)return McpToolResult.error("sliceY is outside this normalized blueprint")
        val options=JsonObject(a+buildJsonObject {put("sliceY",localSlice+g.sourceMin.y)})
        val result=preview.render("$sid:${d.blueprint.id}",raw,options,authored.components(sid,d.blueprint),inspected)
        Files.createDirectories(directory);val file=directory.resolve("${d.blueprint.id}-$variant.svg");Files.writeString(file,StructurePreview.svg(g,localSlice,cutaway))
        metrics.record(sid,"preview",result.structuredContent.getValue("previewMillis").jsonPrimitive.long)
        return result.copy(structuredContent=JsonObject(result.structuredContent+buildJsonObject {
            put("valid",inspected.report.valid);put("id",d.blueprint.id);put("minecraftCompiled",false);put("previewPath",file.toString());put("cutaway",cutaway);put("sliceY",localSlice);put("floorPlan",StructurePreview.floorPlan(g,localSlice));put("diagnostics",McpJson.encode(inspected.report.stages.values.flatMap {it.diagnostics}));put("variant",variant);put("variantCount",inspected.geometries.getValue(d.blueprint.id).size)
        }))
    }
    fun inspectDrawing(session:String,id:String,estimateLighting:Boolean=false):StructureInspection {
        val result=inspect(session,definition(session,buildJsonObject {put("drawingId",id)}),false,estimateLighting)
        return if(host.artifact(session,id,true).semanticsHash==null)result.copy(report=result.report.copy(stages=result.report.stages+("semantics" to StructureCheckStage("NOT_RUN"))))else result
    }
    fun previewAssembly(a:JsonObject):McpToolResult {
        val sid=a["sessionId"]?.jsonPrimitive?.content.orEmpty();val d=resolve(sid,a.getValue("structure").jsonObject,true);val inspected=inspect(sid,d)
        val library=attach(sid,StructureLibrary(structures=listOf(d)),true)
        val catalog=runCatching {StructureCatalogCompiler.compile(library)}.getOrNull()
        if(catalog==null) {
            val entries=inspected.geometries.entries.filter {it.value.isNotEmpty()}.take(4)
            val images=entries.flatMap {(id,g)->preview.render("$sid:$id",StructurePreviewService.drawing(g.first()),JsonObject(emptyMap())).images}
            return McpToolResult.success(buildJsonObject {put("valid",false);put("layoutPreviewAvailable",false);put("imageLabels",McpJson.encode(entries.map {it.key}));put("checks",McpJson.encode(inspected.report));put("diagnostics",McpJson.encode(inspected.report.stages.values.flatMap {it.diagnostics}));put("message","Assembly failed; images show individual source buildings, not an assembled layout")},images=images)
        }
        val plans=catalog.plans.getValue(d.id);val variant=a["variant"]?.jsonPrimitive?.int ?: 0;require(variant in plans.indices)
        val plan=plans[variant];val geometry=StructureCatalogCompiler.preview(d.id,plan)
        val result=preview.render("$sid:${d.id}:assembly",StructurePreviewService.drawing(geometry),a)
        Files.createDirectories(directory);val path=directory.resolve("${d.id}-assembly-$variant.svg");Files.writeString(path,StructurePreview.svg(geometry))
        return result.copy(structuredContent=JsonObject(result.structuredContent+buildJsonObject {
            put("valid",inspected.report.valid);put("layoutPreviewAvailable",true);put("minecraftCompiled",false);put("variant",variant);put("variantCount",plans.size);put("previewPath",path.toString())
            put("pieceCount",plan.parts.size);put("connectionCount",plan.connections.size);put("checks",McpJson.encode(inspected.report))
            putJsonArray("parts"){plan.parts.forEach {p->add(buildJsonObject {put("blueprint",p.blueprintId);put("variant",p.variant);put("offset",McpJson.encode(p.offset));put("rotation",p.rotation.name)})}}
            put("connections",McpJson.encode(plan.connections.map {c->buildJsonObject {put("fromPart",c.fromPart);put("fromPort",c.fromPort);put("toPart",c.toPart);put("toPort",c.toPort)}}))
        }))
    }
}
