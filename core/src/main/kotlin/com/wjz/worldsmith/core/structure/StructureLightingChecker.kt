package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import java.util.ArrayDeque

/** Declaration checks are mandatory; the bounded, non-blocking voxel estimate is explicitly opt-in. */
object StructureLightingChecker {
    private const val MAX_WORK = 1_000_000
    @JvmStatic
    fun inspect(blueprint: StructureBlueprint, voxels: Collection<StructureVoxel>): StructureLightingReport {
        val report=validate(blueprint,voxels)
        report.diagnostics.firstOrNull {it.severity==DiagnosticSeverity.ERROR}?.let {throw StructureBuildException(it)}
        return report
    }
    @JvmStatic fun validate(blueprint:StructureBlueprint,voxels:Collection<StructureVoxel>):StructureLightingReport = try {compute(blueprint,voxels,false)}catch(e:StructureBuildException){StructureLightingReport(0,null,listOf(e.diagnostic))}
    /** Optional authoring feedback, never a publication brightness gate. No native light-engine claim. */
    @JvmStatic fun analyze(blueprint:StructureBlueprint,voxels:Collection<StructureVoxel>):StructureLightingReport {
        val declarations=validate(blueprint,voxels)
        if(declarations.diagnostics.isNotEmpty())return declarations
        return try {compute(blueprint,voxels,true)}catch(e:StructureBuildException){StructureLightingReport(0,null,listOf(e.diagnostic.copy(severity=DiagnosticSeverity.WARNING)))}
    }
    private fun compute(blueprint:StructureBlueprint,voxels:Collection<StructureVoxel>,estimate:Boolean):StructureLightingReport {
        val occupied=blueprint.rooms+blueprint.indoorPassages
        val policy = blueprint.lighting ?: if(occupied.isEmpty())return StructureLightingReport(0, null) else
            throw StructureBuildException(Diagnostic("lighting","ROOMS_REQUIRE_LIGHTING",DiagnosticSeverity.ERROR,"Declare READABLE lighting, or explicit INTENTIONALLY_DARK intent for a deliberately dark interior"))
        fun need(ok: Boolean, path: String, code: String, message: String) {
            if (!ok) throw StructureBuildException(Diagnostic("lighting.$path", code, DiagnosticSeverity.ERROR, message))
        }
        fun inside(p: BuildPos) = p.x in 0 until blueprint.size.x && p.y in 0 until blueprint.size.y && p.z in 0 until blueprint.size.z
        need(policy.minimum in 8..15 && policy.spaces.size <= 32 && policy.sources.size <= 128, "", "INVALID_STRUCTURE_LIGHTING", "minimum is 8..15; use at most 32 spaces and 128 sources")
        need(policy.sources.map { it.at }.distinct().size == policy.sources.size, "sources", "DUPLICATE_LIGHT_SOURCE", "Declare each source position once")
        val cells = voxels.associateBy { it.position }
        need(occupied.size<=32,"rooms","INVALID_OCCUPIED_SPACES","At most 32 declared rooms and indoor passages combined")
        for(room in occupied)need(inside(room.from)&&inside(room.to)&&room.from.x<=room.to.x&&room.from.y<=room.to.y&&room.from.z<=room.to.z,
            "rooms","INVALID_OCCUPIED_SPACES","Declared rooms must be ordered and inside the building")
        for ((i, source) in policy.sources.withIndex()) {
            need(inside(source.at) && source.level in 1..15, "sources[$i]", "INVALID_LIGHT_SOURCE", "Source positions must be inside the blueprint with level 1..15")
            need(cells[source.at]?.material?.isAir() == false, "sources[$i]", "LIGHT_SOURCE_MISSING_BLOCK", "Place an actual light-emitting block at the source position; MC export verifies its emission")
        }
        if (policy.mode == StructureLightingMode.EXTERIOR_ONLY) {
            need(occupied.isEmpty(),"rooms","INTERIOR_LIGHTING_REQUIRED","Declared indoor rooms/passages require READABLE or INTENTIONALLY_DARK")
            need(policy.spaces.isEmpty(), "spaces", "EXTERIOR_LIGHTING_SPACES", "Use READABLE when declaring occupied spaces")
            return StructureLightingReport(0, null)
        }
        for((i,box)in policy.spaces.withIndex())need(inside(box.from)&&inside(box.to)&&box.from.x<=box.to.x&&box.from.y<=box.to.y&&box.from.z<=box.to.z,
            "spaces[$i]","INVALID_LIGHTING_SPACE","Space bounds must be ordered and inside the blueprint")
        if(policy.mode==StructureLightingMode.INTENTIONALLY_DARK)return StructureLightingReport(0,null)
        need(policy.spaces.isNotEmpty() && policy.sources.isNotEmpty(), "", "ROOM_LIGHTING_REQUIRED", "Readable interiors need occupied space bounds and authored light sources")
        if(!estimate)return StructureLightingReport(0,null)
        val samples = linkedSetOf<BuildPos>()
        fun clear(p: BuildPos) = cells[p]?.let { it.material.isAir() || it.passable } == true
        need(occupied.sumOf { (it.to.x-it.from.x+1L)*(it.to.y-it.from.y+1L)*(it.to.z-it.from.z+1L) }<=MAX_WORK,"rooms","LIGHTING_WORK_BUDGET","Declared room volume exceeds the bounded check budget")
        for(room in occupied)for(y in room.from.y..room.to.y)for(z in room.from.z..room.to.z)for(x in room.from.x..room.to.x) {
            val point=BuildPos(x,y,z)
            if(clear(point)&&clear(point.copy(y=y+1))&&cells[point.copy(y=y-1)]?.let(StructureNavigation::supports)==true)
                need(policy.spaces.any { point.x in it.from.x..it.to.x && point.y in it.from.y..it.to.y && point.z in it.from.z..it.to.z },
                    "rooms","UNCOVERED_OCCUPIED_SPACE","Occupied walking point $point is outside all lighting spaces")
        }
        var spaceWork = 0L
        for ((i, box) in policy.spaces.withIndex()) {
            need(inside(box.from) && inside(box.to) && box.from.x <= box.to.x && box.from.y <= box.to.y && box.from.z <= box.to.z,
                "spaces[$i]", "INVALID_LIGHTING_SPACE", "Space bounds must be ordered and inside the blueprint")
            spaceWork += (box.to.x - box.from.x + 1L) * (box.to.y - box.from.y + 1L) * (box.to.z - box.from.z + 1L)
            need(spaceWork <= MAX_WORK, "spaces", "LIGHTING_WORK_BUDGET", "Occupied-space sampling exceeded its work budget")
            var feet = 0
            for (y in box.from.y..box.to.y) for (z in box.from.z..box.to.z) for (x in box.from.x..box.to.x) {
                val p = BuildPos(x, y, z)
                if (clear(p) && clear(p.copy(y = y + 1)) && cells[p.copy(y = y - 1)]?.let(StructureNavigation::supports) == true) {
                    samples += p; feet++
                }
            }
            need(feet > 0, "spaces[$i]", "LIGHTING_SPACE_NO_FLOOR", "Each occupied space needs authored walkable floor/feet/headroom samples")
        }
        val levels = HashMap<BuildPos, Int>(); val queue = ArrayDeque<BuildPos>()
        for (source in policy.sources.sortedByDescending { it.level }) { levels[source.at] = source.level; queue.add(source.at) }
        var work = 0
        val offsets = listOf(BuildPos(1,0,0), BuildPos(-1,0,0), BuildPos(0,1,0), BuildPos(0,-1,0), BuildPos(0,0,1), BuildPos(0,0,-1))
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst(); val next = levels.getValue(p) - 1
            if (next <= 0) continue
            for (d in offsets) {
                need(++work <= MAX_WORK, "", "LIGHTING_WORK_BUDGET", "Lighting estimate exceeded its work budget; simplify the blueprint")
                val q = BuildPos(p.x + d.x, p.y + d.y, p.z + d.z)
                if (clear(q) && next > (levels[q] ?: 0)) { levels[q] = next; queue.add(q) }
            }
        }
        val sampled = samples.map { p -> p to minOf(levels[p] ?: 0, levels[p.copy(y = p.y + 1)] ?: 0) }
        val dark = sampled.filter { it.second < policy.minimum }
        val bounds=if(dark.isEmpty())null else BuildBox(BuildPos(dark.minOf {it.first.x},dark.minOf {it.first.y},dark.minOf {it.first.z}),BuildPos(dark.maxOf {it.first.x},dark.maxOf {it.first.y},dark.maxOf {it.first.z}))
        val diagnostics=if(dark.isEmpty())emptyList()else listOf(Diagnostic("lighting.spaces","ROOM_TOO_DARK",DiagnosticSeverity.WARNING,
            "${dark.size}/${samples.size} walking samples fall below block-light ${policy.minimum}",position=dark.first().first,region=bounds,
            expected="feet/head block light >= ${policy.minimum}",actual="minimum=${sampled.minOf {it.second}}",hint="Add distributed fixtures near the dark region or repair blocked light paths; skylight is not counted",metrics=mapOf("darkSamples" to dark.size,"walkingSamples" to samples.size)))
        val stride=maxOf(1,(sampled.size+2047)/2048)
        return StructureLightingReport(samples.size,sampled.minOfOrNull {it.second},diagnostics,sampled.filterIndexed {i,_->i%stride==0}.map {StructureLightSample(it.first,it.second)},dark.size,bounds)
    }
}
