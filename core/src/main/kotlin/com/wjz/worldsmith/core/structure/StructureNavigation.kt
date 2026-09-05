package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import java.util.ArrayDeque

/** Two-block voxel approximation, not Minecraft collision/pathfinding or redstone simulation. */
object StructureNavigation {
    private val nonSupporting=setOf("minecraft:water","minecraft:lava","minecraft:bubble_column","minecraft:powder_snow")
    data class Report(val diagnostics:List<Diagnostic>, val protectedCells:Set<BuildPos>, val reachableFeet:Set<BuildPos>)
    @JvmStatic fun inspect(blueprint:StructureBlueprint, voxels:Collection<StructureVoxel>):Report {
        val ports=blueprint.ports.filter {it.passage}.map {it.at}
        val access=blueprint.access?.let {it.copy(destinations=(it.destinations+ports).distinct())}
            ?: if(ports.size>1)StructureAccess(listOf(ports.first()),ports.drop(1)) else return Report(emptyList(),emptySet(),emptySet())
        val errors=mutableListOf<Diagnostic>();val protected=mutableSetOf<BuildPos>()
        fun error(path:String,code:String,message:String) { errors+=Diagnostic("access.$path",code,DiagnosticSeverity.ERROR,message) }
        if(access.headroom !in 2..4 || access.entrances.size !in 1..32 || access.destinations.size !in 1..128 || access.requiredClear.size>32) {
            error("","INVALID_STRUCTURE_ACCESS","Use 1..32 entrances, 1..128 destinations, at most 32 clear boxes and headroom 2..4")
            return Report(errors,protected,emptySet())
        }
        val cells=voxels.associateBy { it.position }
        fun inside(p:BuildPos)=p.x in 0 until blueprint.size.x && p.y in 0 until blueprint.size.y && p.z in 0 until blueprint.size.z
        fun clear(p:BuildPos)=inside(p) && cells[p]?.let { it.material.isAir() || it.passable }==true
        fun walkable(p:BuildPos):Boolean {
            val floor=cells[p.copy(y=p.y-1)] ?: return false
            return supports(floor) && (0 until access.headroom).all { clear(p.copy(y=p.y+it)) }
        }
        for((i,box) in access.requiredClear.withIndex()) {
            if(!inside(box.from)||!inside(box.to)||box.from.x>box.to.x||box.from.y>box.to.y||box.from.z>box.to.z) {
                error("requiredClear[$i]","INVALID_CLEARANCE_BOX","Clear boxes must be ordered and inside the blueprint");continue
            }
            var blocked=0
            for(y in box.from.y..box.to.y)for(z in box.from.z..box.to.z)for(x in box.from.x..box.to.x) {
                val p=BuildPos(x,y,z);if(!clear(p))blocked++;protected+=p
            }
            if(blocked>0)error("requiredClear[$i]","BLOCKED_REQUIRED_CLEARANCE","$blocked cells are solid or KEEP; author air/doors explicitly")
        }
        (access.entrances.mapIndexed { i,p -> "entrances[$i]" to p }+access.destinations.mapIndexed { i,p -> "destinations[$i]" to p }).forEach { (path,p) ->
            if(!walkable(p))error(path,"UNWALKABLE_STRUCTURE_POINT","Point $p needs an authored supporting floor and ${access.headroom} traversable cells above it")
        }
        val queue=ArrayDeque<BuildPos>();val reached=mutableSetOf<BuildPos>()
        // Every declared destination AND every entrance must connect to the first
        // entrance; seeding every entrance would hide two disconnected wings.
        access.entrances.first().takeIf(::walkable)?.let { queue.add(it);reached.add(it) }
        while(queue.isNotEmpty()) {
            val p=queue.removeFirst()
            for(facing in BuildFacing.entries)for(dy in listOf(0,1,-1)) {
                val next=BuildPos(p.x+facing.dx,p.y+dy,p.z+facing.dz)
                if(next in reached || !walkable(next))continue
                // Stepping up also needs space above the source; stepping down
                // needs space above the lower destination during the transition.
                if(dy==1 && !clear(p.copy(y=p.y+access.headroom)))continue
                if(dy==-1 && !clear(next.copy(y=next.y+access.headroom)))continue
                reached+=next;queue.add(next)
            }
        }
        (access.entrances+access.destinations).forEachIndexed { i,p ->
            if(walkable(p) && p !in reached)error("routes[$i]","DISCONNECTED_STRUCTURE_ROUTE","Point $p is disconnected from the first entrance")
        }
        // Protect traversable volume AND its floor, so decay preserves routes.
        for(p in reached)for(dy in -1 until access.headroom)protected+=p.copy(y=p.y+dy)
        return Report(errors,protected,reached)
    }
    fun supports(voxel:StructureVoxel)=!voxel.material.isAir() && !voxel.passable && voxel.material.block !in nonSupporting
}
