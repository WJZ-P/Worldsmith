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
    data class Roof(
        override val id: String,
        val from: BuildPos,
        val to: BuildPos,
        val material: String,
        val style: RoofStyle = RoofStyle.GABLE,
        val ridgeAxis: RoofAxis = RoofAxis.Z,
        /** Optional stair palette entry; facing/half/shape are generated, ridge uses material. */
        val stairMaterial: String? = null,
    ) : BuildOperation

    @Serializable @SerialName("REPEAT")
    data class Repeat(override val id: String, val count: Int, val step: BuildPos, val build: List<BuildOperation>) : BuildOperation

    /** Rotation is around the module's origin. Translation is applied afterwards. */
    @Serializable @SerialName("INSTANCE")
    data class Instance(override val id: String, val module: String, val at: BuildPos, val rotation: BuildRotation = BuildRotation.NONE) : BuildOperation
}

@Serializable
data class StructureBlueprint(
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
)

@Serializable
enum class StructureSurface { LAND_SURFACE, OCEAN_FLOOR }

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
data class StructureTerrainFit(
    val surface: StructureSurface = StructureSurface.LAND_SURFACE,
    val maxHeightDifference: Int = 3,
    val foundation: StructureFoundation = StructureFoundation(),
)

/** Spacing/separation are in CHUNKS, not blocks and not guaranteed pairwise distances. */
@Serializable
data class StructurePlacement(
    val biomes: List<String>,
    val spacingChunks: Int = 24,
    val separationChunks: Int = 8,
    val rotations: List<BuildRotation> = BuildRotation.entries,
    val terrainFit: StructureTerrainFit = StructureTerrainFit(),
    /** Padding reserved around every allowed rotation during deterministic site arbitration. */
    val clearanceBlocks: Int = 2,
)

@Serializable
data class WorldStructureDefinition(val id: String, val blueprint: StructureBlueprint, val placement: StructurePlacement)

/** In-memory/MCP document; portable disk storage keeps each blueprint in its own file. */
@Serializable
data class StructureLibrary(val schemaVersion: Int = 1, val structures: List<WorldStructureDefinition> = emptyList())

@Serializable
data class StructureIndexEntry(val id: String, val blueprint: String, val placement: StructurePlacement)

@Serializable
data class StructureIndex(val schemaVersion: Int = 1, val structures: List<StructureIndexEntry> = emptyList())

/** AIR is explicit. Missing coordinates mean KEEP, never implicit air. */
data class StructureVoxel(val position: BuildPos, val material: BuildMaterial, val quarterTurns: Int = 0)

data class CompiledStructure(
    val id: String,
    val size: BuildPos,
    val origin: BuildPos,
    val voxels: List<StructureVoxel>,
    val keepClear: List<BuildBox>,
    val expandedWork: Int,
    val diagnostics: List<com.wjz.worldsmith.core.validation.Diagnostic> = emptyList(),
)
