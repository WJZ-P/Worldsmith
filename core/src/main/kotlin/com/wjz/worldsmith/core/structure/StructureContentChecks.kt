package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

internal object StructureContentChecks {
    private val namespaced=Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
    private val id=Regex("^[a-z0-9_][a-z0-9_-]{0,63}$")
    private val colors=setOf("white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black")
    fun validate(b:StructureBlueprint,cells:Map<BuildPos,StructureVoxel>):List<Diagnostic> = buildList {
        fun error(path:String,code:String,message:String){ add(Diagnostic(path,code,DiagnosticSeverity.ERROR,message)) }
        fun inside(p:BuildPos)=p.x in 0 until b.size.x && p.y in 0 until b.size.y && p.z in 0 until b.size.z
        fun resource(s:String)=s.length<=160 && namespaced.matches(s)
        if(b.ports.size>32 || b.ports.map { it.id }.distinct().size!=b.ports.size)error("ports","INVALID_STRUCTURE_PORTS","Use at most 32 distinctly named ports")
        b.ports.forEachIndexed { i,p ->
            val at="ports[$i]"
            if(!id.matches(p.id)||!id.matches(p.type)||p.pool?.let { !id.matches(it) }==true)error(at,"INVALID_PORT_NAME","Use short lowercase port, type and pool names")
            val boundary=when(p.facing){PortFacing.NORTH->p.at.z==0;PortFacing.SOUTH->p.at.z==b.size.z-1;PortFacing.EAST->p.at.x==b.size.x-1;PortFacing.WEST->p.at.x==0;PortFacing.UP->p.at.y==b.size.y-1;PortFacing.DOWN->p.at.y==0}
            if(!inside(p.at)||!boundary)error(at,"PORT_OUTSIDE_BOUNDARY","Port must sit on its declared boundary face")
            else if(!p.passage) {
                if(cells[p.at]?.let(StructureNavigation::supports)!=true)error(at,"EMPTY_ATTACHMENT_PORT","A solid attachment socket needs an authored supporting block")
            } else if(p.facing.dy!=0 || p.at.y !in 1 until b.size.y-1)error(at,"INVALID_PASSAGE_PORT","Walkable ports need a horizontal facing, headroom and a floor; use passage=false for solid UP/DOWN joints")
            else {
                val foot=cells[p.at];val head=cells[p.at.copy(y=p.at.y+1)];val floor=cells[p.at.copy(y=p.at.y-1)]
                if(foot?.let { it.material.isAir()||it.passable }!=true || head?.let { it.material.isAir()||it.passable }!=true || floor==null || !StructureNavigation.supports(floor))
                    error(at,"BLOCKED_STRUCTURE_PORT","Port needs two authored traversable cells and an authored solid floor")
            }
        }
        if(b.interactions.size>128 || b.interactions.map { it.at }.distinct().size!=b.interactions.size)error("interactions","INVALID_INTERACTIONS","At most 128 interactions, one per block position")
        b.interactions.forEachIndexed { i,entry ->
            val path="interactions[$i]";val voxel=cells[entry.at]
            if(!inside(entry.at)||voxel==null||voxel.material.isAir())error(path,"INTERACTION_WITHOUT_BLOCK","Interaction must target a surviving authored block")
            when(entry) {
                is StructureInteraction.Container -> {
                    if(listOf(entry.lootTable!=null,entry.items.isNotEmpty(),entry.loot!=null).count {it}>1)error(path,"CONFLICTING_CONTAINER_CONTENT","Choose explicit items, an inline loot pool or a loot-table reference")
                    if(entry.lootTable!=null && (!resource(entry.lootTable)||entry.items.isNotEmpty()))error(path,"INVALID_STRUCTURE_LOOT","Use a namespaced loot table OR explicit items, not both")
                    if(entry.items.size>54 || entry.items.map {it.slot}.distinct().size!=entry.items.size)error(path,"INVALID_CONTAINER_SLOTS","Use at most 54 distinct item slots")
                    if(entry.items.any {it.slot !in 0..53||it.count !in 1..64||!resource(it.item)})error(path,"INVALID_CONTAINER_ITEM","Use slot 0..53, count 1..64 and namespaced item ids; MC validates actual capacity and stack limits")
                    entry.loot?.let {loot->if(loot.minRolls !in 0..8||loot.maxRolls !in loot.minRolls..8||loot.entries.size !in 1..32||loot.entries.any {!resource(it.item)||it.weight !in 1..10000||it.minCount !in 1..64||it.maxCount !in it.minCount..64})error(path,"INVALID_STRUCTURE_LOOT","Inline loot needs 1..32 weighted entries, bounded stack counts and 0..8 ordered rolls")}
                }
                is StructureInteraction.Sign -> if(entry.front.size>4||entry.back.size>4||(entry.front+entry.back).any {it.length>160}||entry.color !in colors)
                    error(path,"INVALID_SIGN_TEXT","Each side has at most four literal text lines of 160 characters; choose a dye color")
                is StructureInteraction.Banner -> if(entry.patterns.size>16||entry.patterns.any { !resource(it.pattern)||it.color !in colors })
                    error(path,"INVALID_BANNER_PATTERN","Use at most 16 namespaced patterns with dye colors")
            }
        }
    }
}
