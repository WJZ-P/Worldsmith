package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.WorldsmithCore
import kotlinx.serialization.Serializable

/**
 * Vegetation shapes the target compiler knows how to build. The pack picks a
 * recipe and a material; it never describes placement geometry itself.
 */
@Serializable
enum class VegetationRecipe {
    GROUND_PATCH,
    DEAD_TREE,
    BOULDER,
    ORE_VEIN,
    CAVE_PATCH,
    SURFACE_LAYER,
}

/**
 * One reusable feature. Several biomes may reference the same definition, which
 * is the point: the shape and material are declared once and compiled once.
 */
@Serializable
data class FeatureDefinition(
    val id: String,
    val recipe: VegetationRecipe,
    val block: MaterialSelector,
    val density: Double,
)

@Serializable
data class FeatureLibrary(
    val schemaVersion: Int = WorldsmithCore.BLUEPRINT_SCHEMA_VERSION,
    val features: List<FeatureDefinition> = emptyList(),
)
