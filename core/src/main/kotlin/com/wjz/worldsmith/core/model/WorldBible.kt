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
    /**
     * Alternatives to pick between, block by block, instead of one material.
     *
     * <p>Empty is the ordinary case and means [preferredIds] alone. A non-empty
     * list replaces them: the compiler builds a weighted provider, so a patch of
     * "meadow flora" can be mostly grass with a scattering of flowers rather
     * than a field of one plant. Not every recipe can take one - an ore vein and
     * a boulder are handed a single block state by Minecraft's own config.
     */
    val weighted: List<WeightedMaterial> = emptyList(),
) {
    /** Every material this selector can produce, whichever form was written. */
    val alternatives: List<MaterialSelector>
        get() = if (weighted.isEmpty()) listOf(this) else weighted.map { it.material }
}

/** One choice inside a [MaterialSelector], with its share of the draw. */
@Serializable
data class WeightedMaterial(
    val material: MaterialSelector,
    val weight: Int = 1,
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
