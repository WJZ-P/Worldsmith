package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.WorldsmithCore
import kotlinx.serialization.Serializable

@Serializable
enum class BiomeArchetypeRole {
    DEEP_OCEAN,
    OCEAN,
    BEACH,
    MOUNTAIN,
    HILL,
    LOWLAND,
}

@Serializable
data class BiomeBehavior(
    val temperature: Float,
    val downfall: Float,
    val hasPrecipitation: Boolean,
)

@Serializable
data class BiomeSkeletonDefinition(
    val id: String,
    val archetype: BiomeArchetypeRole,
    val climate: ClimateBox,
    val behavior: BiomeBehavior,
)

@Serializable
data class BiomeLayoutPlan(
    val schemaVersion: Int = WorldsmithCore.BLUEPRINT_SCHEMA_VERSION,
    val worldId: String,
    val skeletons: List<BiomeSkeletonDefinition>,
)
