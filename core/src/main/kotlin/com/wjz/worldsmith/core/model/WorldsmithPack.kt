package com.wjz.worldsmith.core.model

import kotlinx.serialization.Serializable
import com.wjz.worldsmith.core.structure.StructureLibrary

@Serializable
data class WorldsmithPackFiles(
    val terrain: String,
    val biomes: String,
    val features: String,
    val structures: String = "structures.json",
)

@Serializable
data class WorldsmithPackManifest(
    val formatVersion: Int,
    val id: String,
    val displayName: String,
    val description: String,
    val files: WorldsmithPackFiles,
)

data class WorldsmithPack(
    val manifest: WorldsmithPackManifest,
    val terrain: TerrainPlan,
    val biomes: BiomePlan,
    val features: FeatureLibrary,
    val computedId: String,
    val structures: StructureLibrary = StructureLibrary(),
)
