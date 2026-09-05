package com.wjz.worldsmith.core.structure

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Coordinates are feet positions. KEEP is unknown, not assumed to be walkable. */
@Serializable data class StructureAccess(
    val entrances:List<BuildPos>, val destinations:List<BuildPos>,
    val requiredClear:List<BuildBox> = emptyList(), val headroom:Int=2,
)
@Serializable data class WeightedMaterial(val material:String, val weight:Int=1)
@Serializable data class WeightedModule(val module:String, val weight:Int=1)
@Serializable data class StructureDecay(
    val materials:List<String>, val probability:Double, val replacement:String?=null,
    val region:BuildBox?=null, val exposedOnly:Boolean=true,
)
@Serializable data class StructureVariation(
    val count:Int=1, val seed:Long=0,
    val materials:Map<String,List<WeightedMaterial>> = emptyMap(),
    val decay:List<StructureDecay> = emptyList(), val protectedAreas:List<BuildBox> = emptyList(),
)

@Serializable enum class PortFacing(val dx:Int,val dy:Int,val dz:Int) {
    NORTH(0,0,-1),EAST(1,0,0),SOUTH(0,0,1),WEST(-1,0,0),UP(0,1,0),DOWN(0,-1,0);
    fun rotate(turns:Int)=if(dy!=0)this else entries[Math.floorMod(ordinal+turns,4)]
    fun opposite()=when(this){UP->DOWN;DOWN->UP;else->rotate(2)}
}
/** Optional walkable passage, or a solid attachment socket for towers, branches and roofs. */
@Serializable data class StructurePort(
    val id:String, val at:BuildPos, val facing:PortFacing, val type:String,
    val pool:String?=null, val required:Boolean=false,
    val passage:Boolean=true,
)
@Serializable data class AssemblyChoice(val piece:String, val weight:Int=1)
@Serializable data class StructureAssembly(
    val pieces:Map<String,StructureBlueprint>, val pools:Map<String,List<AssemblyChoice>>,
    val variants:Int=4, val maxPieces:Int=12, val maxDepth:Int=5, val maxRadius:Int=80,
)
@Serializable data class StructureAssemblyIndex(
    val pieces:Map<String,String>, val pools:Map<String,List<AssemblyChoice>>,
    val variants:Int=4, val maxPieces:Int=12, val maxDepth:Int=5, val maxRadius:Int=80,
)

/** Typed content, never arbitrary NBT or executable commands. */
@Serializable sealed interface StructureInteraction {
    val at:BuildPos
    @Serializable @SerialName("container")
    data class Container @JvmOverloads constructor(override val at:BuildPos, val lootTable:String?=null, val items:List<StructureItem> = emptyList(), val loot:StructureLoot?=null) : StructureInteraction
    @Serializable @SerialName("sign")
    data class Sign(override val at:BuildPos, val front:List<String>, val back:List<String> = emptyList(), val color:String="black", val glowing:Boolean=false) : StructureInteraction
    @Serializable @SerialName("banner")
    data class Banner(override val at:BuildPos, val patterns:List<StructureBannerLayer>) : StructureInteraction
}
@Serializable data class StructureItem(val slot:Int, val item:String, val count:Int=1)
@Serializable data class StructureBannerLayer(val pattern:String, val color:String)
@Serializable data class StructureLoot(val entries:List<StructureLootEntry>,val minRolls:Int=1,val maxRolls:Int=3)
@Serializable data class StructureLootEntry(val item:String,val weight:Int=1,val minCount:Int=1,val maxCount:Int=1)
