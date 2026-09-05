package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

object StructureValidator {
    const val MAX_STRUCTURES = 48
    private val ID = Regex("^[a-z0-9_][a-z0-9_-]{0,63}$")
    private val ANCHOR_ID = Regex("^[a-z0-9_]+$")

    @JvmStatic
    fun validateBlueprint(blueprint: StructureBlueprint): List<Diagnostic> = try {
        StructureGeometryCompiler.compileVariants(blueprint).flatMapIndexed {i,g->g.diagnostics.map {it.copy(path="variants[$i].${it.path}")}}
    } catch (failure: StructureBuildException) {
        listOf(failure.diagnostic)
    }

    @JvmStatic fun validateDefinition(definition:WorldStructureDefinition):List<Diagnostic> = try {
        val compiled=StructureCatalogCompiler.compile(StructureLibrary(structures=listOf(definition)))
        compiled.templates.flatMap {(id,variants)->variants.flatMapIndexed {i,g->g.diagnostics.map {it.copy(path="blueprints.$id.variants[$i].${it.path}")}}}
    } catch(failure:StructureBuildException) {listOf(failure.diagnostic)}

    @JvmStatic
    fun validate(library: StructureLibrary, biomes: BiomePlan): List<Diagnostic> = buildList {
        if(library.schemaVersion!=1)add(error("schemaVersion","UNSUPPORTED_SCHEMA","Structure library schema must be 1"))
        if(library.structures.size>MAX_STRUCTURES){add(error("structures","TOO_MANY_STRUCTURES","At most $MAX_STRUCTURES structure definitions per pack"));return@buildList}
        val catalog=try {StructureCatalogCompiler.compile(library)}catch(failure:StructureBuildException){add(failure.diagnostic);null}
        if(catalog!=null)addAll(catalog.templates.flatMap {(id,variants)->variants.flatMapIndexed {i,g->g.diagnostics.map {it.copy(path="blueprints.$id.variants[$i].${it.path}")}}})
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
            val variants=catalog?.templates?.get(blueprint.id)
            val geometry=variants?.firstOrNull()
            if(variants!=null && variants.any {g->g.voxels.none { it.position.y==0 && !it.material.isAir() }}) {
                add(error("$path.blueprint.build","MISSING_STRUCTURE_FLOOR","A placed structure needs solid authored floor cells at local Y=0"))
            }
            val p=structure.placement
            p.anchor?.let { target ->
                if (!ANCHOR_ID.matches(target.id)) add(error("$path.placement.anchor.id", "INVALID_ANCHOR_ID", "Use an existing terrain anchor id"))
                if (target.offsetX !in -4096..4096 || target.offsetZ !in -4096..4096) add(error("$path.placement.anchor", "STRUCTURE_ANCHOR_OFFSET_OUT_OF_RANGE", "Anchor offsets must stay within -4096..4096 blocks"))
                if (target.along !in 0.0..1.0) add(error("$path.placement.anchor.along", "STRUCTURE_ANCHOR_ALONG_OUT_OF_RANGE", "Line position must be between 0 and 1"))
                if (p.spacingChunks != 24 || p.separationChunks != 8) add(error("$path.placement", "UNUSED_STRUCTURE_SPACING", "Anchor placement does not consume random-spread spacing; omit spacingChunks and separationChunks"))
            }
            if (p.clearanceBlocks !in 0..16) add(error("$path.placement.clearanceBlocks", "STRUCTURE_CLEARANCE_OUT_OF_RANGE", "Structure clearance must be 0..16 blocks"))
            if(p.biomes.isEmpty())add(error("$path.placement.biomes","EMPTY_STRUCTURE_BIOMES","Declare at least one eligible biome id"))
            p.biomes.forEach { if(it !in biomeIds)add(error("$path.placement.biomes","UNKNOWN_STRUCTURE_BIOME","Unknown biome '$it'")) }
            if(p.biomes.distinct().size!=p.biomes.size)add(error("$path.placement.biomes","DUPLICATE_STRUCTURE_BIOME","Biome ids must be distinct"))
            if(p.spacingChunks !in 2..4096 || p.separationChunks !in 1 until p.spacingChunks)add(error("$path.placement","INVALID_STRUCTURE_SPACING","Require 2 <= spacingChunks <= 4096 and 1 <= separationChunks < spacingChunks"))
            if(p.rotations.isEmpty() || p.rotations.distinct().size!=p.rotations.size)add(error("$path.placement.rotations","INVALID_STRUCTURE_ROTATIONS","Choose distinct rotations, at least one"))
            val fit=p.terrainFit
            val special=fit.surface in setOf(StructureSurface.SKY_SURFACE,StructureSurface.CAVE_FLOOR,StructureSurface.CAVE_CEILING)
            if(special && fit.verticalRange==null)add(error("$path.placement.terrainFit.verticalRange","MISSING_STRUCTURE_HEIGHT_RANGE","Sky/cave placement needs an explicit height window"))
            if(fit.verticalRange?.let {it.minY>it.maxY || it.minY !in -2032..2031 || it.maxY !in -2032..2031}==true)add(error("$path.placement.terrainFit.verticalRange","INVALID_STRUCTURE_HEIGHT_RANGE","Choose an ordered height window within -2032..2031"))
            if(fit.layer !in 0..15 || !special && fit.layer!=0)add(error("$path.placement.terrainFit.layer","INVALID_STRUCTURE_LAYER","Layer is a top-down candidate index 0..15, only for sky/cave modes"))
            if(fit.searchRadius !in 0..16 || fit.minAirBelow !in 1..64 || fit.surface!=StructureSurface.SKY_SURFACE && fit.minAirBelow!=8)add(error("$path.placement.terrainFit","INVALID_STRUCTURE_SEARCH","Search radius is 0..16; minAirBelow 1..64 applies only to sky surfaces"))
            fit.earthwork?.let {e->
                if(fit.surface!=StructureSurface.LAND_SURFACE || fit.foundation.mode!=FoundationMode.FILL || e.maxCut !in 1..8 || e.maxBlocks !in 1..8192)add(error("$path.placement.terrainFit.earthwork","INVALID_STRUCTURE_EARTHWORK","Earthwork needs LAND_SURFACE + FILL, maxCut 1..8 and maxBlocks 1..8192"))
            }
            if(fit.surface==StructureSurface.CAVE_CEILING && fit.foundation.mode!=FoundationMode.NONE)add(error("$path.placement.terrainFit.foundation","CEILING_FOUNDATION_UNUSED","Ceiling-hung structures use NONE; no columns are filled beneath them"))
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
                    if(variants!=null && variants.any {g->g.voxels.none { it.position==pos && !it.material.isAir() }})add(error("$fp.supports","FOUNDATION_SUPPORT_HAS_NO_FLOOR","Each pillar must meet an authored solid floor cell in every variant"))
                }
            }
        }
    }

    private fun error(path:String,code:String,message:String)=Diagnostic(path,code,DiagnosticSeverity.ERROR,message)
}
