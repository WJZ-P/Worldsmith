package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

object StructureValidator {
    const val MAX_STRUCTURES = 48
    private val ID = Regex("^[a-z0-9_][a-z0-9_-]{0,63}$")

    @JvmStatic
    fun validateBlueprint(blueprint: StructureBlueprint): List<Diagnostic> = try {
        StructureGeometryCompiler.compile(blueprint).diagnostics
    } catch (failure: StructureBuildException) {
        listOf(failure.diagnostic)
    }

    @JvmStatic
    fun validate(library: StructureLibrary, biomes: BiomePlan): List<Diagnostic> = buildList {
        if(library.schemaVersion!=1)add(error("schemaVersion","UNSUPPORTED_SCHEMA","Structure library schema must be 1"))
        if(library.structures.size>MAX_STRUCTURES)add(error("structures","TOO_MANY_STRUCTURES","At most $MAX_STRUCTURES structure definitions per pack"))
        val biomeIds=biomes.biomes.map { it.id }.toSet()
        val seen=mutableSetOf<String>()
        val blueprints=mutableMapOf<String,StructureBlueprint>()
        for((index,structure) in library.structures.withIndex()) {
            val path="structures[$index]"
            if(!ID.matches(structure.id))add(error("$path.id","INVALID_STRUCTURE_ID","Structure id must use lowercase letters, digits, underscores or dashes"))
            if(!seen.add(structure.id))add(error("$path.id","DUPLICATE_STRUCTURE_ID","Structure id '${structure.id}' is repeated"))
            val blueprint=structure.blueprint
            if(!ID.matches(blueprint.id))add(error("$path.blueprint.id","INVALID_BLUEPRINT_ID","Blueprint id must use lowercase letters, digits, underscores or dashes"))
            val previous=blueprints.putIfAbsent(blueprint.id,blueprint)
            if(previous!=null && previous!=blueprint)add(error("$path.blueprint.id","CONFLICTING_BLUEPRINT","Two different blueprints share id '${blueprint.id}'"))
            val geometry = try { StructureGeometryCompiler.compile(blueprint) } catch (failure: StructureBuildException) {
                add(failure.diagnostic.copy(path="$path.blueprint.${failure.diagnostic.path}")); null
            }
            if (geometry != null) addAll(geometry.diagnostics.map { it.copy(path = "$path.blueprint.${it.path}") })
            if(geometry!=null && geometry.voxels.none { it.position.y==0 && !it.material.isAir() }) {
                add(error("$path.blueprint.build","MISSING_STRUCTURE_FLOOR","A placed structure needs solid authored floor cells at local Y=0"))
            }
            val p=structure.placement
            if (p.clearanceBlocks !in 0..16) add(error("$path.placement.clearanceBlocks", "STRUCTURE_CLEARANCE_OUT_OF_RANGE", "Structure clearance must be 0..16 blocks"))
            if(p.biomes.isEmpty())add(error("$path.placement.biomes","EMPTY_STRUCTURE_BIOMES","Declare at least one eligible biome id"))
            p.biomes.forEach { if(it !in biomeIds)add(error("$path.placement.biomes","UNKNOWN_STRUCTURE_BIOME","Unknown biome '$it'")) }
            if(p.biomes.distinct().size!=p.biomes.size)add(error("$path.placement.biomes","DUPLICATE_STRUCTURE_BIOME","Biome ids must be distinct"))
            if(p.spacingChunks !in 2..4096 || p.separationChunks !in 1 until p.spacingChunks)add(error("$path.placement","INVALID_STRUCTURE_SPACING","Require 2 <= spacingChunks <= 4096 and 1 <= separationChunks < spacingChunks"))
            if(p.rotations.isEmpty() || p.rotations.distinct().size!=p.rotations.size)add(error("$path.placement.rotations","INVALID_STRUCTURE_ROTATIONS","Choose distinct rotations, at least one"))
            val fit=p.terrainFit
            if(fit.maxHeightDifference !in 0..12)add(error("$path.placement.terrainFit.maxHeightDifference","STRUCTURE_SLOPE_LIMIT","Maximum height difference must be 0..12 blocks"))
            val foundation=fit.foundation
            val fp="$path.placement.terrainFit.foundation"
            if(foundation.mode==FoundationMode.NONE) {
                if(foundation.material!=null || foundation.maxDepth!=0 || foundation.supports.isNotEmpty())add(error(fp,"UNUSED_FOUNDATION_FIELDS","NONE has no material, depth or support points"))
            } else {
                if(foundation.material !in blueprint.palette)add(error("$fp.material","UNKNOWN_STRUCTURE_MATERIAL","Foundation must name a palette entry"))
                if(foundation.maxDepth !in 1..16)add(error("$fp.maxDepth","FOUNDATION_DEPTH_OUT_OF_RANGE","Foundation depth must be 1..16"))
                if(foundation.mode==FoundationMode.PILLARS && foundation.supports.isEmpty())add(error("$fp.supports","MISSING_FOUNDATION_SUPPORTS","PILLARS needs explicit support points"))
                if(foundation.mode!=FoundationMode.PILLARS && foundation.supports.isNotEmpty())add(error("$fp.supports","UNUSED_FOUNDATION_SUPPORTS","Only PILLARS consumes support points"))
                if(foundation.supports.size>64 || foundation.supports.distinct().size!=foundation.supports.size)add(error("$fp.supports","INVALID_FOUNDATION_SUPPORTS","Use at most 64 distinct support points"))
                foundation.supports.forEach { pos ->
                    if(pos.y!=0 || pos.x !in 0 until blueprint.size.x || pos.z !in 0 until blueprint.size.z)add(error("$fp.supports","FOUNDATION_SUPPORT_OUT_OF_BOUNDS","Support must be at Y=0 inside blueprint size"))
                    if(geometry!=null && geometry.voxels.none { it.position==pos && !it.material.isAir() })add(error("$fp.supports","FOUNDATION_SUPPORT_HAS_NO_FLOOR","Each pillar must meet an authored solid floor cell at Y=0"))
                }
            }
        }
    }

    private fun error(path:String,code:String,message:String)=Diagnostic(path,code,DiagnosticSeverity.ERROR,message)
}
