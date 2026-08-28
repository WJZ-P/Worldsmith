package com.wjz.worldsmith.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TerrainProfile {
    VANILLA_LIKE,
    WASTELAND,
    ARCHIPELAGO,
    FLOATING_ISLANDS,
    CAVERNOUS,
    CUSTOM,
}

@Serializable
data class MaterialSelector(
    val semanticRole: String,
    val preferredIds: List<String> = emptyList(),
    val requiredTags: List<String> = emptyList(),
)

@Serializable
data class TerrainBible(
    val profile: TerrainProfile,
    val description: String,
    val relief: String,
    val caveStyle: String,
    val waterLevelHint: Int? = null,
)

@Serializable
data class SurfacePalette(
    val surface: List<MaterialSelector>,
    val subsurface: List<MaterialSelector>,
    val fluid: List<MaterialSelector> = emptyList(),
    val accents: List<MaterialSelector> = emptyList(),
)

@Serializable
data class ArchitectureBible(
    val styleTags: List<String>,
    val primaryMaterials: List<MaterialSelector>,
    val shapeLanguage: List<String>,
    val decayLevel: Double,
)

@Serializable
data class AtmosphereBible(
    val sky: String,
    val fog: String,
    val weather: String,
    val ambientMood: String,
)

@Serializable
data class WorldBible(
    val id: String,
    val title: String,
    val summary: String,
    val themeTags: List<String>,
    val biomeThemes: List<String>,
    val terrain: TerrainBible,
    val surfacePalette: SurfacePalette,
    val architecture: ArchitectureBible,
    val atmosphere: AtmosphereBible,
    val globalRules: List<String>,
)
