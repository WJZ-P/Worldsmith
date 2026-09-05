package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import java.util.ArrayDeque
import kotlin.math.abs

data class CompiledStructurePart(val blueprintId:String,val variant:Int,val offset:BuildPos,val rotation:BuildRotation,val geometry:CompiledStructure)
data class StructureConnection(val fromPart:Int,val fromPort:String,val toPart:Int,val toPort:String)
data class CompiledStructurePlan(val parts:List<CompiledStructurePart>,val connections:List<StructureConnection>,val bounds:BuildBox)
data class CompiledStructureCatalog(
    val blueprints:Map<String,StructureBlueprint>, val templates:Map<String,List<CompiledStructure>>, val plans:Map<String,List<CompiledStructurePlan>>,
)

/** A bounded palette of multi-piece layouts, precompiled before worldgen; no runtime recursive assembly. */
object StructureCatalogCompiler {
    const val MAX_BLUEPRINTS=64
    const val MAX_TOTAL_VOXELS=1_000_000
    const val MAX_TOTAL_WORK=4_000_000
    const val MAX_PLAN_VOXELS=262144

    @JvmStatic fun compile(library:StructureLibrary):CompiledStructureCatalog {
        need(library.schemaVersion==1 && library.structures.size<=48,"structures","STRUCTURE_CATALOG_LIMIT","Use schema 1 and at most 48 definitions")
        val sources=linkedMapOf<String,StructureBlueprint>();val templates=linkedMapOf<String,List<CompiledStructure>>()
        var voxels=0;var work=0
        fun add(b:StructureBlueprint) {
            val previous=sources.putIfAbsent(b.id,b)
            need(previous==null || previous==b,"blueprints.${b.id}","CONFLICTING_BLUEPRINT","Blueprint ids must resolve to identical source content")
            if(previous!=null)return
            need(sources.size<=MAX_BLUEPRINTS,"structures","STRUCTURE_CATALOG_LIMIT","At most $MAX_BLUEPRINTS distinct blueprints per pack")
            val variants=StructureGeometryCompiler.compileVariants(b)
            voxels+=variants.sumOf {it.voxels.size};work+=variants.sumOf {it.expandedWork}
            need(voxels<=MAX_TOTAL_VOXELS && work<=MAX_TOTAL_WORK,"structures","STRUCTURE_CATALOG_BUDGET","Compiled templates exceed the pack-wide voxel/work budget")
            templates[b.id]=variants
        }
        for(definition in library.structures) {
            add(definition.blueprint)
            definition.assembly?.let {assembly->
                need(assembly.pieces.size in 1..16,"assembly.pieces","ASSEMBLY_PIECE_LIMIT","Declare 1..16 reusable piece blueprints")
                assembly.pieces.forEach {(id,b)->need(id==b.id,"assembly.pieces.$id","ASSEMBLY_PIECE_ID","Piece map key must equal its blueprint id");add(b)}
            }
        }
        val plans=linkedMapOf<String,List<CompiledStructurePlan>>()
        for(d in library.structures) {
            need(d.id !in plans,"structures.${d.id}","DUPLICATE_STRUCTURE_ID","Structure ids must be distinct")
            plans[d.id]=buildPlans(d,sources,templates)
        }
        return CompiledStructureCatalog(sources,templates,plans)
    }

    private fun buildPlans(d:WorldStructureDefinition,sources:Map<String,StructureBlueprint>,templates:Map<String,List<CompiledStructure>>):List<CompiledStructurePlan> {
        val a=d.assembly
        if(a!=null) {
            need(a.variants in d.blueprint.variation.count..8 && a.maxPieces in 1..16 && a.maxDepth in 0..8 && a.maxRadius in 16..96,"assembly","INVALID_ASSEMBLY_LIMITS","Use enough variants for the root (at most 8), maxPieces 1..16, depth 0..8 and radius 16..96")
            need(a.pools.size in 1..32,"assembly.pools","INVALID_ASSEMBLY_POOLS","Declare 1..32 pools")
            for((pool,choices) in a.pools)need(pool.matches(Regex("[a-z0-9_][a-z0-9_-]{0,63}")) && choices.size in 1..16 && choices.map {it.piece}.distinct().size==choices.size && choices.all {it.piece in a.pieces && it.weight in 1..10000},"assembly.pools.$pool","INVALID_ASSEMBLY_POOL","Pools need distinct existing pieces and positive bounded weights")
            for(b in listOf(d.blueprint)+a.pieces.values) for(p in b.ports)need(p.pool==null || p.pool in a.pools,"assembly.ports.${b.id}.${p.id}","UNKNOWN_ASSEMBLY_POOL","Port refers to an unknown pool")
        } else need(d.blueprint.ports.none {it.required},"ports","REQUIRED_PORT_WITHOUT_ASSEMBLY","Required ports need an assembly")
        return (0 until (a?.variants ?: d.blueprint.variation.count)).map {variant->
            val root=templates.getValue(d.blueprint.id)[variant % d.blueprint.variation.count]
            val parts=mutableListOf(CompiledStructurePart(d.blueprint.id,variant % d.blueprint.variation.count,BuildPos(-root.origin.x,0,-root.origin.z),BuildRotation.NONE,root))
            val connections=mutableListOf<StructureConnection>()
            val used=mutableSetOf<Pair<Int,String>>()
            val pending=ArrayDeque<Triple<Int,StructurePort,Int>>()
            d.blueprint.ports.forEach {pending.add(Triple(0,it,0))}
            var attempts=0
            while(a!=null && pending.isNotEmpty()) {
                val (parentIndex,port,depth)=pending.removeFirst()
                if((parentIndex to port.id) in used)continue
                val parent=parts[parentIndex]
                val facing=port.facing.rotate(parent.rotation.ordinal)
                val at=transform(port.at,parent)
                val target=BuildPos(at.x+facing.dx,at.y+facing.dy,at.z+facing.dz)
                var attached=false
                if(port.pool!=null && parts.size<a.maxPieces && depth<a.maxDepth) {
                    val choices=a.pools.getValue(port.pool).sortedBy {choice->
                        // Weighted deterministic permutation, without replacement.
                        -kotlin.math.ln(maxOf(1e-12,StructureVariationCompiler.unit(variant.toLong(),"${d.id}:$parentIndex:${port.id}:${choice.piece}")))/choice.weight
                    }
                    search@ for(choice in choices) {
                        val b=sources.getValue(choice.piece)
                        val geometries=templates.getValue(b.id)
                        val chosen=(StructureVariationCompiler.unit(variant.toLong(),"$parentIndex:${port.id}:${b.id}")*geometries.size).toInt()
                        val geometry=geometries[chosen]
                        for(childPort in b.ports.filter {it.type==port.type && it.passage==port.passage})for(rotation in BuildRotation.entries) {
                            need(++attempts<=2048,"assembly","ASSEMBLY_WORK_BUDGET","Assembly exceeded 2048 candidate connections")
                            if(childPort.facing.rotate(rotation.ordinal)!=facing.opposite())continue
                            val requiredChildren=b.ports.count {it.id!=childPort.id && it.required}
                            val reservedForPending=pending.count {(index,p,_)->p.required && (index to p.id) !in used}
                            if(depth+1>=a.maxDepth && requiredChildren>0 || parts.size+1+requiredChildren+reservedForPending>a.maxPieces)continue
                            val p=StructureGeometryCompiler.rotate(childPort.at,rotation.ordinal)
                            val child=CompiledStructurePart(b.id,chosen,BuildPos(target.x-p.x,target.y-p.y,target.z-p.z),rotation,geometry)
                            val bounds=box(child)
                            if(maxOf(abs(bounds.from.x),abs(bounds.to.x),abs(bounds.from.z),abs(bounds.to.z))>a.maxRadius)continue
                            if(parts.any {intersects(box(it),bounds)})continue
                            val all=parts.map(::box)+bounds
                            if(all.maxOf {it.to.y}-all.minOf {it.from.y}+1>128)continue
                            if(parts.sumOf {it.geometry.voxels.size}+geometry.voxels.size>MAX_PLAN_VOXELS)continue
                            val childIndex=parts.size;parts+=child
                            used+=(parentIndex to port.id);used+=(childIndex to childPort.id)
                            connections+=StructureConnection(parentIndex,port.id,childIndex,childPort.id)
                            b.ports.filter {it.id!=childPort.id}.forEach {pending.add(Triple(childIndex,it,depth+1))}
                            attached=true;break@search
                        }
                    }
                }
                need(!port.required||attached,"assembly.variant[$variant].${parent.blueprintId}.${port.id}","REQUIRED_PORT_UNCONNECTED","Required port did not connect within the piece, depth and radius budgets; add a compatible terminal/cap piece or adjust the graph")
            }
            val minY=parts.minOf {box(it).from.y}
            val normalized=parts.map {it.copy(offset=it.offset.copy(y=it.offset.y-minY))}
            val boxes=normalized.map(::box)
            val bounds=BuildBox(BuildPos(boxes.minOf {it.from.x},0,boxes.minOf {it.from.z}),BuildPos(boxes.maxOf {it.to.x},boxes.maxOf {it.to.y},boxes.maxOf {it.to.z}))
            need(a==null || maxOf(abs(bounds.from.x),abs(bounds.to.x),abs(bounds.from.z),abs(bounds.to.z))<=a.maxRadius,"assembly","ROOT_OUTSIDE_ASSEMBLY_RADIUS","The root also needs to fit the declared assembly radius")
            CompiledStructurePlan(normalized,connections,bounds)
        }
    }

    @JvmStatic fun transform(p:BuildPos,part:CompiledStructurePart):BuildPos {
        val r=StructureGeometryCompiler.rotate(p,part.rotation.ordinal)
        return BuildPos(r.x+part.offset.x,r.y+part.offset.y,r.z+part.offset.z)
    }
    private fun box(p:CompiledStructurePart):BuildBox {
        val a=transform(BuildPos(0,0,0),p);val b=transform(BuildPos(p.geometry.size.x-1,p.geometry.size.y-1,p.geometry.size.z-1),p)
        return BuildBox(BuildPos(minOf(a.x,b.x),minOf(a.y,b.y),minOf(a.z,b.z)),BuildPos(maxOf(a.x,b.x),maxOf(a.y,b.y),maxOf(a.z,b.z)))
    }
    private fun intersects(a:BuildBox,b:BuildBox)=a.from.x<=b.to.x&&a.to.x>=b.from.x&&a.from.y<=b.to.y&&a.to.y>=b.from.y&&a.from.z<=b.to.z&&a.to.z>=b.from.z
    private fun need(ok:Boolean,path:String,code:String,message:String) {if(!ok)throw StructureBuildException(Diagnostic(path,code,DiagnosticSeverity.ERROR,message))}

    /** Shared compiled geometry for a multi-piece schematic, not a giant runtime template. */
    @JvmStatic fun preview(id:String,plan:CompiledStructurePlan):CompiledStructure {
        val min=plan.bounds.from;val max=plan.bounds.to
        val voxels=plan.parts.flatMap {part->part.geometry.voxels.map {v->val p=transform(v.position,part);v.copy(position=BuildPos(p.x-min.x,p.y-min.y,p.z-min.z),quarterTurns=(v.quarterTurns+part.rotation.ordinal)%4)}}
        return CompiledStructure(id,BuildPos(max.x-min.x+1,max.y-min.y+1,max.z-min.z+1),BuildPos(-min.x,0,-min.z),voxels,emptyList(),voxels.size)
    }
}
