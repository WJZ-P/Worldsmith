package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.WorldsmithCore
import kotlinx.serialization.Serializable

/**
 * Vegetation shapes the target compiler knows how to build. The model picks a
 * recipe and a material; it never describes placement geometry itself.
 */
@Serializable
enum class VegetationRecipe {
    GROUND_PATCH,
    DEAD_TREE,
    BOULDER,
}

@Serializable
data class BiomeColors(
    val grass: String,
    val foliage: String,
    val water: String,
    val sky: String,
    val fog: String,
    val fogEndDistance: Float = DEFAULT_FOG_END_DISTANCE,
) {
    companion object {
        const val DEFAULT_FOG_END_DISTANCE: Float = 192.0f
    }
}

@Serializable
data class SurfaceLayers(
    val top: MaterialSelector,
    val under: MaterialSelector,
    val deep: MaterialSelector,
    val steepOverride: MaterialSelector? = null,
)

@Serializable
data class VegetationSlot(
    val recipe: VegetationRecipe,
    val block: MaterialSelector,
    val density: Double,
)

/**
 * Everything the model is allowed to decide about one skeleton in stage one:
 * how it looks, what it is made of, and what grows on it.
 */
@Serializable
data class BiomeSkin(
    val skeletonId: String,
    val displayName: String,
    val colors: BiomeColors,
    val surface: SurfaceLayers,
    val vegetation: List<VegetationSlot> = emptyList(),
)

@Serializable
data class BiomeSkinSet(
    val schemaVersion: Int = WorldsmithCore.BLUEPRINT_SCHEMA_VERSION,
    val worldId: String,
    val skins: List<BiomeSkin>,
)
