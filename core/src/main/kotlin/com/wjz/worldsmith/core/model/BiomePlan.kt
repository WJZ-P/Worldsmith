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

/**
 * Where a biome sits along the land-shape axis.
 *
 * <p>Terrain height is driven by continentalness and erosion, so selecting
 * biomes with the same two values is what keeps a swamp off a mountain peak.
 * The bands are fixed and adjacent, which is what makes coverage provable.
 */
@Serializable
enum class ReliefBand {
    DEEP_WATER,
    SHALLOW_WATER,
    COAST,
    PEAKS,
    HIGHLAND,
    FLATS,
}

@Serializable
enum class TemperatureBand {
    COLD,
    TEMPERATE,
    HOT,
}

@Serializable
enum class HumidityBand {
    ARID,
    HUMID,
}

/**
 * The preferred way to place a biome: name bands instead of writing raw numbers.
 *
 * An empty list on an axis claims that whole axis, so a slot naming only a
 * relief takes every temperature and humidity within it. Listing several bands
 * is allowed as long as they are adjacent; a gap would make the resulting box
 * quietly swallow the band in between.
 */
@Serializable
data class ClimateSlot(
    val relief: ReliefBand,
    val temperature: List<TemperatureBand> = emptyList(),
    val humidity: List<HumidityBand> = emptyList(),
)

@Serializable
data class BiomeBehavior(
    val temperature: Float,
    val downfall: Float,
    val hasPrecipitation: Boolean,
)

@Serializable
data class SurfaceLayers(
    val top: MaterialSelector,
    val under: MaterialSelector,
    val deep: MaterialSelector,
    val steepOverride: MaterialSelector? = null,
)

@Serializable
data class WaterFog(
    val color: String,
    val startDistance: Float,
    val endDistance: Float,
)

@Serializable
data class AmbientParticleSpec(
    val particle: String,
    val probability: Float,
)

/**
 * How a biome looks.
 *
 * <p>These fields land in two different places in Minecraft: the grass, foliage
 * and water colours become `BiomeSpecialEffects` overrides, while everything
 * fog, particle and sky related becomes an environment attribute. Authors do not
 * need to care, but the compiler does.
 */
@Serializable
data class BiomeEnvironment(
    val grassColor: String,
    val foliageColor: String,
    val waterColor: String,
    val skyColor: String,
    val fogColor: String,
    val fogEndDistance: Float = DEFAULT_FOG_END_DISTANCE,
    val waterFog: WaterFog? = null,
    val ambientParticles: List<AmbientParticleSpec> = emptyList(),
) {
    companion object {
        const val DEFAULT_FOG_END_DISTANCE: Float = 192.0f
    }
}

/**
 * Adjustments to the tag set the archetype already implies.
 *
 * <p>[remove] subtracts from those defaults. It cannot take a biome out of a
 * vanilla tag it was never in, because a Worldsmith biome only joins a tag when
 * this pack puts it there.
 */
@Serializable
data class BiomeTagOverrides(
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
)

/** A reference to a feature in the pack's feature library. */
@Serializable
data class BiomeFeatureRef(
    val feature: String,
    val density: Double? = null,
)

@Serializable
data class BiomeDefinition(
    val id: String,
    val displayName: String,
    val archetype: BiomeArchetypeRole,
    val slot: ClimateSlot? = null,
    val climate: ClimateBox? = null,
    val behavior: BiomeBehavior,
    val surface: SurfaceLayers,
    val environment: BiomeEnvironment,
    val tags: BiomeTagOverrides = BiomeTagOverrides(),
    val features: List<BiomeFeatureRef> = emptyList(),
)

@Serializable
data class BiomePlan(
    val schemaVersion: Int = WorldsmithCore.BLUEPRINT_SCHEMA_VERSION,
    val biomes: List<BiomeDefinition>,
)
