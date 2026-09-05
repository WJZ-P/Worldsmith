package com.wjz.worldsmith.core.structure

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.ExperimentalSerializationApi

/** Local integer coordinates. Boxes use inclusive endpoints; +Y is up, +Z is south. */
@Serializable
data class BuildPos(val x: Int, val y: Int, val z: Int)

@Serializable
data class BuildBox(val from: BuildPos, val to: BuildPos)

/** Exact, version-neutral block-state description. The MC adapter checks the live registry. */
@Serializable
data class BuildMaterial(val block: String, val properties: Map<String, String> = emptyMap()) {
    fun isAir(): Boolean = block == "minecraft:air" || block == "minecraft:cave_air" || block == "minecraft:void_air"
}

@Serializable
enum class BuildRotation { NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90 }

@Serializable
enum class RoofStyle { FLAT, GABLE, HIP }

@Serializable
enum class RoofAxis { X, Z }

@Serializable enum class BuildFacing(val dx: Int, val dz: Int) {
    NORTH(0,-1), EAST(1,0), SOUTH(0,1), WEST(-1,0);
    fun rotate(turns: Int) = entries[Math.floorMod(ordinal + turns, 4)]
}
@Serializable data class RoofKnot(val at: Double, val height: Double)
@Serializable data class BuildPoint2(val x: Int, val z: Int)

/** A bounded building language, not executable code. Array order is explicit overwrite order. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("op")
sealed interface BuildOperation {
    val id: String

    @Serializable @SerialName("SET")
    data class SetBlock(override val id: String, val at: BuildPos, val material: String) : BuildOperation

    @Serializable @SerialName("FILL")
    data class Fill(override val id: String, val from: BuildPos, val to: BuildPos, val material: String) : BuildOperation

    /** Writes the walls AND explicit interior air; use FILL faces to leave the interior untouched. */
    @Serializable @SerialName("SHELL")
    data class Shell(override val id: String, val from: BuildPos, val to: BuildPos, val material: String, val thickness: Int = 1) : BuildOperation

    @Serializable @SerialName("CLEAR")
    data class Clear(override val id: String, val from: BuildPos, val to: BuildPos) : BuildOperation

    /** Six-connected line; diagonal coordinates are joined by face-adjacent blocks. */
    @Serializable @SerialName("LINE")
    data class Line(override val id: String, val from: BuildPos, val to: BuildPos, val material: String) : BuildOperation

    @Serializable @SerialName("ROOF")
    data class Roof @JvmOverloads constructor(
        override val id: String,
        val from: BuildPos,
        val to: BuildPos,
        val material: String,
        val style: RoofStyle = RoofStyle.GABLE,
        val ridgeAxis: RoofAxis = RoofAxis.Z,
        /** Optional stair palette entry; facing/half/shape are generated, ridge uses material. */
        val stairMaterial: String? = null,
        val profile: List<RoofKnot> = emptyList(),
    ) : BuildOperation

    @Serializable @SerialName("ELLIPSOID")
    data class Ellipsoid(override val id:String, val from:BuildPos, val to:BuildPos, val material:String, val thickness:Int=0) : BuildOperation

    /** topScale=1 is a cylinder; 0 is a cone. The box determines the elliptical base. */
    @Serializable @SerialName("CYLINDER")
    data class Cylinder(override val id:String, val from:BuildPos, val to:BuildPos, val material:String, val topScale:Double=1.0, val thickness:Int=0) : BuildOperation

    @Serializable @SerialName("POLYGON")
    data class Polygon(override val id:String, val points:List<BuildPoint2>, val minY:Int, val maxY:Int, val material:String) : BuildOperation

    @Serializable @SerialName("ARCH")
    data class Arch(override val id:String, val from:BuildPos, val to:BuildPos, val springY:Int, val material:String, val thickness:Int=1, val spanAxis:RoofAxis=RoofAxis.X) : BuildOperation

    /** Quadratic/cubic Bezier, with a radius of zero producing a six-connected single-voxel beam. */
    @Serializable @SerialName("CURVE")
    data class Curve(override val id:String, val points:List<BuildPos>, val material:String, val radius:Double=0.0) : BuildOperation

    @Serializable @SerialName("DOOR")
    data class Door(override val id:String, val at:BuildPos, val material:String, val facing:BuildFacing, val hinge:String="left", val open:Boolean=false) : BuildOperation

    /** at is the first step block; facing points uphill, width grows to its right. */
    @Serializable @SerialName("STAIRCASE")
    data class Staircase(override val id:String, val at:BuildPos, val facing:BuildFacing, val steps:Int, val material:String, val width:Int=1, val headroom:Int=3, val fillMaterial:String?=null) : BuildOperation

    @Serializable @SerialName("CHOOSE")
    data class Choose(override val id:String, val choices:List<WeightedModule>, val at:BuildPos=BuildPos(0,0,0), val rotation:BuildRotation=BuildRotation.NONE) : BuildOperation

    @Serializable @SerialName("REPEAT")
    data class Repeat(override val id: String, val count: Int, val step: BuildPos, val build: List<BuildOperation>) : BuildOperation

    /** Rotation is around the module's origin. Translation is applied afterwards. */
    @Serializable @SerialName("INSTANCE")
    data class Instance(override val id: String, val module: String, val at: BuildPos, val rotation: BuildRotation = BuildRotation.NONE) : BuildOperation
}

@Serializable
data class StructureBlueprint @JvmOverloads constructor(
    val schemaVersion: Int = 1,
    val id: String,
    val size: BuildPos,
    /** Local horizontal anchor used when placing this template. Y must be zero (foundation datum). */
    val origin: BuildPos = BuildPos(0, 0, 0),
    val palette: Map<String, BuildMaterial>,
    val build: List<BuildOperation>,
    val modules: Map<String, List<BuildOperation>> = emptyMap(),
    /** Volumes kept clear of Worldsmith vegetation, including entrances and yards. */
    val keepClear: List<BuildBox> = emptyList(),
    val access: StructureAccess? = null,
    val variation: StructureVariation = StructureVariation(),
    val ports: List<StructurePort> = emptyList(),
    val interactions: List<StructureInteraction> = emptyList(),
)

@Serializable
enum class StructureSurface { LAND_SURFACE, OCEAN_FLOOR, WATER_SURFACE, SKY_SURFACE, CAVE_FLOOR, CAVE_CEILING }

@Serializable data class StructureHeightRange(val minY:Int,val maxY:Int)
@Serializable data class StructureEarthwork(val maxCut:Int=2,val maxBlocks:Int=4096)

@Serializable
enum class FoundationMode { NONE, FILL, PILLARS }

@Serializable
data class StructureFoundation(
    val mode: FoundationMode = FoundationMode.NONE,
    val material: String? = null,
    val maxDepth: Int = 0,
    /** Local points on Y=0, used only by PILLARS. */
    val supports: List<BuildPos> = emptyList(),
)

@Serializable
data class StructureTerrainFit @JvmOverloads constructor(
    val surface: StructureSurface = StructureSurface.LAND_SURFACE,
    val maxHeightDifference: Int = 3,
    val foundation: StructureFoundation = StructureFoundation(),
    val verticalRange:StructureHeightRange?=null,
    val layer:Int=0,
    val searchRadius:Int=0,
    val earthwork:StructureEarthwork?=null,
    val minAirBelow:Int=8,
)

/** Optional link to a terrain anchor; never implies a required landmark or a forced successful start. */
@Serializable
data class StructureAnchorTarget(
    val id: String,
    val offsetX: Int = 0,
    val offsetZ: Int = 0,
    /** LINE only: 0 is the start, 1 the end; default is the midpoint. */
    val along: Double = 0.5,
)

/** Without anchor, spacing/separation retain vanilla random-spread semantics, in CHUNKS. */
@Serializable
data class StructurePlacement(
    val biomes: List<String>,
    val spacingChunks: Int = 24,
    val separationChunks: Int = 8,
    val rotations: List<BuildRotation> = BuildRotation.entries,
    val terrainFit: StructureTerrainFit = StructureTerrainFit(),
    /** Padding reserved around every allowed rotation during deterministic site arbitration. */
    val clearanceBlocks: Int = 2,
    val anchor: StructureAnchorTarget? = null,
)

@Serializable
data class WorldStructureDefinition @JvmOverloads constructor(val id: String, val blueprint: StructureBlueprint, val placement: StructurePlacement, val assembly:StructureAssembly?=null)

/** In-memory/MCP document; portable disk storage keeps each blueprint in its own file. */
@Serializable
data class StructureLibrary(val schemaVersion: Int = 1, val structures: List<WorldStructureDefinition> = emptyList())

@Serializable
data class StructureIndexEntry(val id: String, val blueprint: String, val placement: StructurePlacement, val assembly:StructureAssemblyIndex?=null)

@Serializable
data class StructureIndex(val schemaVersion: Int = 1, val structures: List<StructureIndexEntry> = emptyList())

/** AIR is explicit. Missing coordinates mean KEEP, never implicit air. */
data class StructureVoxel(val position: BuildPos, val material: BuildMaterial, val quarterTurns: Int = 0, val passable:Boolean=false)

data class CompiledStructure(
    val id: String,
    val size: BuildPos,
    val origin: BuildPos,
    val voxels: List<StructureVoxel>,
    val keepClear: List<BuildBox>,
    val expandedWork: Int,
    val diagnostics: List<com.wjz.worldsmith.core.validation.Diagnostic> = emptyList(),
    val interactions: List<StructureInteraction> = emptyList(),
)
