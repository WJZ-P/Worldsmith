package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.draw.DrawStructure
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

/** Converts frozen SDK data into the shared logical-building representation. No code execution. */
object StructureDrawCompiler {
    fun metadata(b:StructureBlueprint,g:CompiledStructure):StructureBlueprint {
        fun p(v:BuildPos)=BuildPos(v.x-g.sourceMin.x,v.y-g.sourceMin.y,v.z-g.sourceMin.z)
        return b.copy(size=g.size,origin=g.origin,ports=g.ports,lighting=g.lighting,interactions=g.interactions,keepClear=g.keepClear,
            rooms=b.rooms.map { BuildBox(p(it.from),p(it.to)) },indoorPassages=b.indoorPassages.map { BuildBox(p(it.from),p(it.to)) },access=b.access?.let { it.copy(entrances=it.entrances.map(::p),destinations=it.destinations.map(::p),requiredClear=it.requiredClear.map { box->BuildBox(p(box.from),p(box.to)) }) })
    }
    fun compile(b: StructureBlueprint,drawing: DrawStructure,strict:Boolean=true): CompiledStructure {
        fun need(ok:Boolean,code:String,message:String) { if(!ok)throw StructureBuildException(Diagnostic("drawing",code,DiagnosticSeverity.ERROR,message)) }
        need(b.schemaVersion==1,"UNSUPPORTED_SCHEMA","Structure schema must be 1")
        need(b.id.matches(Regex("^[a-z0-9_][a-z0-9_-]{0,63}$")),"INVALID_STRUCTURE_ID","Use a short lowercase identifier without path separators")
        need(b.build.isEmpty() && b.modules.isEmpty(),"AMBIGUOUS_GEOMETRY_SOURCE","Choose drawing variants OR legacy build operations")
        need(b.variation.materials.isEmpty() && b.variation.decay.isEmpty(),"DRAWING_VARIATION_SOURCE","Generate geometry/material variants in Java; only instancePatches apply to frozen geometry")
        val bounds=drawing.bounds(); val min=BuildPos(bounds.min().x(),bounds.min().y(),bounds.min().z())
        fun p(p:BuildPos)=BuildPos(Math.subtractExact(p.x,min.x),Math.subtractExact(p.y,min.y),Math.subtractExact(p.z,min.z))
        fun box(v:BuildBox)=BuildBox(p(v.from),p(v.to))
        val size=BuildPos(bounds.width(),bounds.height(),bounds.depth())
        if(strict)need(size.x<=193 && size.z<=193 && size.y<=128,"DRAWING_DEPLOYMENT_EXTENT","Drawing remains previewable/exportable but exceeds the current 193x128x193 deployment envelope")
        if(strict)need(drawing.voxels().size<=StructureCatalogCompiler.MAX_PLAN_VOXELS,"DRAWING_DEPLOYMENT_BUDGET","Drawing exceeds the per-plan authored-cell budget including AIR; reduce authored clearance or deployment footprint")
        val normalized=b.copy(size=size,origin=p(b.origin),keepClear=b.keepClear.map(::box),rooms=b.rooms.map(::box),indoorPassages=b.indoorPassages.map(::box),ports=b.ports.map { it.copy(at=p(it.at)) },
            access=b.access?.let { it.copy(entrances=it.entrances.map(::p),destinations=it.destinations.map(::p),requiredClear=it.requiredClear.map(::box)) },
            lighting=b.lighting?.let { it.copy(spaces=it.spaces.map(::box),sources=it.sources.map { s->s.copy(at=p(s.at)) }) },
            interactions=b.interactions.map { when(it) {
                is StructureInteraction.Container->it.copy(at=p(it.at));is StructureInteraction.Sign->it.copy(at=p(it.at));is StructureInteraction.Banner->it.copy(at=p(it.at));is StructureInteraction.BossSpawner->it.copy(at=p(it.at))
            } })
        if(strict)need(normalized.origin.x in 0 until size.x && normalized.origin.z in 0 until size.z && normalized.origin.y==0,
            "DRAWING_ORIGIN_DATUM","Origin must be inside the drawing at its minimum-Y support datum; metadata uses original drawing coordinates")
        fun inside(p:BuildPos)=p.x in 0 until size.x && p.y in 0 until size.y && p.z in 0 until size.z
        fun validBox(v:BuildBox)=inside(v.from)&&inside(v.to)&&v.from.x<=v.to.x&&v.from.y<=v.to.y&&v.from.z<=v.to.z
        if(strict)need(normalized.keepClear.size<=32 && normalized.keepClear.all(::validBox),"DRAWING_CLEARANCE_BOUNDS","Invalid keepClear volume")
        val protected=b.variation.protectedAreas.map(::box)
        if(strict){need(protected.size<=32 && protected.all(::validBox),"DRAWING_PROTECTED_BOUNDS","Invalid protected volume")
            StructureVariationCompiler.validate(normalized.copy(variation=b.variation.copy(protectedAreas=protected)))}
        val voxels=drawing.voxels().map { v ->
            val s=v.block().state();val orientation=v.block().orientation()
            val passable=s.isAir() || s.id().endsWith("_door") && (s.id()!="minecraft:iron_door" || s.properties()["open"]=="true") || s.id().endsWith("_fence_gate") && s.properties()["open"]=="true"
            StructureVoxel(p(BuildPos(v.position().x(),v.position().y(),v.position().z())),BuildMaterial(s.id(),s.properties()),orientation.quarterTurns(),passable,orientation.mirrorX())
        }
        need(voxels.any { !it.material.isAir() },"EMPTY_DRAWING_STRUCTURE","A deployed drawing needs non-air geometry")
        val navigation=StructureNavigation.inspect(normalized,voxels)
        val diagnostics=navigation.diagnostics+StructureContentChecks.validate(normalized,voxels.associateBy { it.position })
        if(strict)diagnostics.firstOrNull { it.severity==DiagnosticSeverity.ERROR }?.let { throw StructureBuildException(it) }
        if(strict)StructureLightingChecker.inspect(normalized,voxels)
        return CompiledStructure(b.id,size,normalized.origin,voxels,normalized.keepClear,voxels.size,diagnostics,normalized.interactions,
            normalized.lighting,normalized.ports,protected,min,true,anchors=drawing.anchors().mapValues { (_,v)->p(BuildPos(v.x(),v.y(),v.z())) },reachableFeet=navigation.reachableFeet.toList())
    }
}
