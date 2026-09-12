package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.draw.DrawStructure
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.validation.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import com.wjz.worldsmith.core.content.CustomBlockLibrary

/** The native adapter inspects live block state vocabulary without exporting a whole data pack. */
interface StructureNativeHost {
    val identity:String
    fun query(ids:List<String>,search:String,limit:Int):JsonObject
    fun inspect(geometry:CompiledStructure):List<Diagnostic>
    fun forContent(scope:String, blocks:CustomBlockLibrary):StructureNativeHost {
        require(blocks.blocks.isEmpty()) { "This native inspection host does not support scoped custom blocks" }
        return this
    }
}
@Serializable data class StructureCheckStage(val state:String,val totalDiagnostics:Int=0,val diagnostics:List<Diagnostic> = emptyList(),val elapsedMillis:Long=0)
@Serializable data class StructureCheckReport(val structureId:String,val stages:Map<String,StructureCheckStage>,val valid:Boolean,val readyForPublication:Boolean=false,val repeatedDiagnostics:Int=0)
data class StructureInspection(val report:StructureCheckReport,val geometries:Map<String,List<CompiledStructure>>,val lighting:Map<String,StructureLightingReport>)

/** Inspection retains usable geometry on semantic failure. Strict publishing keeps its existing gates. */
class StructureCheckService(private val native:StructureNativeHost?=null) {
    private data class Repetition(val fingerprint:String,val revision:String,val count:Int)
    private val repetitions=java.util.concurrent.ConcurrentHashMap<String,Repetition>()
    fun inspect(definition:WorldStructureDefinition,drawings:Map<String,DrawStructure>,components:Map<String,BuildBox> = emptyMap(),session:String="",assemblyContext:Boolean=true,estimateLighting:Boolean=false):StructureInspection {
        val stages=linkedMapOf<String,StructureCheckStage>();val geometries=linkedMapOf<String,List<CompiledStructure>>();val light=linkedMapOf<String,StructureLightingReport>()
        fun stage(name:String,action:()->List<Diagnostic>) {
            val start=System.nanoTime()
            val diagnostics=try {action()}catch(e:StructureBuildException){listOf(e.diagnostic)}catch(e:IllegalArgumentException){listOf(Diagnostic(name,"INVALID_STRUCTURE_DATA",DiagnosticSeverity.ERROR,e.message ?: "Invalid structure"))}
            val positioned=diagnostics.map {d->d.copy(stage=name,structureId=definition.id,componentId=d.componentId ?: d.position?.let {p->components.entries.firstOrNull {contains(it.value,p)}?.key})}
            stages[name]=StructureCheckStage(if(diagnostics.any {it.severity==DiagnosticSeverity.ERROR})"FAILED" else "PASSED",diagnostics.size,positioned.take(128),(System.nanoTime()-start)/1_000_000)
        }
        val blueprints=(listOf(definition.blueprint)+definition.assembly?.pieces.orEmpty().values).associateBy {it.id}
        stage("geometry") {
            val problems=mutableListOf<Diagnostic>()
            for((id,b)in blueprints)try {
                require((b.drawing?.variants?.size ?: b.variation.count) in 1..8) {"Use 1..8 geometry variants"}
                geometries[id]=(0 until (b.drawing?.variants?.size ?: b.variation.count)).map {StructureGeometryCompiler.compile(b,it,drawings,false)}
            } catch(e:StructureBuildException){problems+=e.diagnostic.copy(path="blueprints.$id.${e.diagnostic.path}")}
            problems
        }
        stage("semantics") {
            val problems=mutableListOf<Diagnostic>()
            for((id,variants)in geometries)for((variant,g)in variants.withIndex()) {
                val b=blueprints.getValue(id);val metadata=if(g.drawingSource)StructureDrawCompiler.metadata(b,g)else b
                val prefix="blueprints.$id.variants[$variant]"
                fun original(d:Diagnostic)=d.copy(path="$prefix.${d.path}",position=d.position?.let {p->BuildPos(p.x+g.sourceMin.x,p.y+g.sourceMin.y,p.z+g.sourceMin.z)},region=d.region?.let {v->BuildBox(BuildPos(v.from.x+g.sourceMin.x,v.from.y+g.sourceMin.y,v.from.z+g.sourceMin.z),BuildPos(v.to.x+g.sourceMin.x,v.to.y+g.sourceMin.y,v.to.z+g.sourceMin.z))})
                problems+=g.diagnostics.map(::original)
                if(g.origin.y!=0||g.origin.x !in 0 until g.size.x||g.origin.z !in 0 until g.size.z)
                    problems+=original(Diagnostic("origin","DRAWING_ORIGIN_DATUM",DiagnosticSeverity.ERROR,"Origin is outside the bottom support datum",position=g.origin,expected="inside bounds at normalized Y=0",actual=g.origin.toString(),hint="Use the original drawing coordinates, not half of the exported size"))
                fun validBox(v:BuildBox)=v.from.x>=0&&v.from.y>=0&&v.from.z>=0&&v.to.x<g.size.x&&v.to.y<g.size.y&&v.to.z<g.size.z&&v.from.x<=v.to.x&&v.from.y<=v.to.y&&v.from.z<=v.to.z
                if(g.keepClear.size>32||g.keepClear.any {!validBox(it)})problems+=original(Diagnostic("keepClear","DRAWING_CLEARANCE_BOUNDS",DiagnosticSeverity.ERROR,"Invalid clearance volume"))
                if(g.protectedAreas.size>32||g.protectedAreas.any {!validBox(it)})problems+=original(Diagnostic("protectedAreas","DRAWING_PROTECTED_BOUNDS",DiagnosticSeverity.ERROR,"Invalid protected volume"))
                val report=if(estimateLighting)StructureLightingChecker.analyze(metadata,g.voxels)else StructureLightingChecker.validate(metadata,g.voxels)
                light["$id:$variant"]=report;problems+=report.diagnostics.map(::original)
            }
            problems
        }
        if(geometries.isEmpty())stages["semantics"]=StructureCheckStage("NOT_RUN")
        if(native!=null && geometries.isNotEmpty())stage("native") {
            geometries.flatMap {(_,variants)->variants.flatMap {g->native.inspect(g).map {d->d.copy(position=d.position?.let {p->BuildPos(p.x+g.sourceMin.x,p.y+g.sourceMin.y,p.z+g.sourceMin.z)})}}}
        } else stages["native"]=StructureCheckStage("NOT_RUN")
        stage("deployment") {
            val problems=mutableListOf<Diagnostic>()
            for((id,variants)in geometries)for(g in variants) {
                val columns=g.voxels.map {it.position.x to it.position.z}.distinct().size
                val fragments=if(g.drawingSource)g.voxels.map {Triple(it.position.x/32,it.position.y/32,it.position.z/32)}.distinct().size else 1
                val metrics=mapOf("width" to g.size.x,"height" to g.size.y,"depth" to g.size.z,"authoredCells" to g.voxels.size,"columns" to columns,"fragments" to fragments)
                if(g.size.x>193||g.size.z>193||g.size.y>128||g.voxels.size>262144||columns>8192||fragments>128)
                    problems+=Diagnostic("blueprints.$id","DRAWING_DEPLOYMENT_BUDGET",DiagnosticSeverity.ERROR,"Drawing exceeds a deployment budget; geometry remains previewable",metrics=metrics,hint="Reduce the named metric or deploy as separately distributed logical buildings")
            }
            problems
        }
        if(!assemblyContext||stages["geometry"]?.state=="FAILED" || stages["semantics"]?.state=="FAILED" || stages["deployment"]?.state=="FAILED")stages["assembly"]=StructureCheckStage("NOT_RUN")
        else stage("assembly") {StructureCatalogCompiler.compile(StructureLibrary(structures=listOf(definition),drawingAssets=drawings));emptyList()}
        val errors=stages.values.flatMap {it.diagnostics}.filter {it.severity==DiagnosticSeverity.ERROR}
        val fingerprint=errors.joinToString {"${it.code}:${it.position}:${it.metrics}"}
        var count=0
        // Preview and drawing-only preflight omit world placement. That is not
        // a new creative revision and must not count as another failed repair.
        val revision=com.wjz.worldsmith.core.draw.DrawSnapshotCodec.hash((WorldsmithJson.encode(definition.blueprint)+definition.assembly?.let {WorldsmithJson.encode(it)}.orEmpty()+drawings.keys.sorted().joinToString()).toByteArray())
        if(session.isNotEmpty()&&fingerprint.isNotEmpty())repetitions.compute("$session:${definition.id}") {_,old->
            count=if(old?.fingerprint==fingerprint){if(old.revision==revision)old.count else old.count+1}else 1
            Repetition(fingerprint,revision,count)
        }
        else if(session.isNotEmpty())repetitions.remove("$session:${definition.id}")
        val valid=stages.values.none {it.state=="FAILED"};return StructureInspection(StructureCheckReport(definition.id,stages,valid,false,count),geometries,light)
    }
    companion object {fun contains(box:BuildBox,p:BuildPos)=p.x in box.from.x..box.to.x&&p.y in box.from.y..box.to.y&&p.z in box.from.z..box.to.z}
}
