package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.WorldsmithCore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NumericRange(
    val min: Float,
    val max: Float,
) {
    companion object {
        val FULL = NumericRange(-1.0f, 1.0f)
    }
}

@Serializable
data class ClimateBox(
    val temperature: NumericRange = NumericRange.FULL,
    val humidity: NumericRange = NumericRange.FULL,
    val continentalness: NumericRange = NumericRange.FULL,
    val erosion: NumericRange = NumericRange.FULL,
    val depth: NumericRange = NumericRange.FULL,
    val weirdness: NumericRange = NumericRange.FULL,
    val offset: Float = 0.0f,
)

/** One of vanilla's three overworld noise routers. */
@Serializable
enum class VanillaNoisePreset {
    OVERWORLD,
    LARGE_BIOMES,
    AMPLIFIED,
}

/** Relative share of inland terrain characters; values are normalized by the compiler. */
@Serializable
data class ReliefDistribution(
    val flats: Double,
    val highlands: Double,
    val peaks: Double,
)

/** Whether a generated river channel is flooded to sea level or remains a dry valley. */
@Serializable
enum class RiverFill {
    FLUID,
    DRY,
}

/**
 * Version-independent inland-water and ocean-floor intent. Every field is
 * required so a generated pack records an explicit design decision.
 */
@Serializable
data class HydrologyIntent(
    val riverCoverage: Double,
    val riverWidth: Double,
    val riverDepth: Double,
    val riverMeander: Double,
    val riverFill: RiverFill,
    val lakeDensity: Double,
    val lakeScale: Double,
    val lakeDepth: Double,
    val oceanDepth: Double,
)

/**
 * How the shape of the land is decided.
 *
 * Prompt-generated packs use semantic [Procedural] intent; the target adapter
 * turns it into the density-function graph of its Minecraft version. [Vanilla]
 * is an explicit passthrough mode for worlds that intentionally want an
 * unchanged Mojang router. The closed tagged set also leaves room for future
 * terrain models.
 */
/** Whether a band puts rock where there was none, or takes it away. */
@Serializable
enum class BandEffect {
    ADD,
    CARVE,
}

/**
 * Where a band applies, named in terms of the world's own geography.
 *
 * A band that ignores geography spreads evenly over ocean and continent alike,
 * which reads as scattered rather than designed. Tying it to the same
 * continentalness signal that decides land and sea is what makes it look like
 * part of the world instead of a layer laid on top.
 */
@Serializable
enum class BandRegion {
    ANYWHERE,
    OVER_LAND,
    OVER_OCEAN,
    INLAND,
    COASTAL,
}

/**
 * One body of rock, or one absence of it, layered onto the height field.
 *
 * Everything else in [TerrainShape.Procedural] describes a height field: the
 * density is a decreasing function of Y plus a function of X and Z, so it
 * crosses zero exactly once per column - solid below, air above. No combination
 * of those controls can float an island, hollow out a world, or cut a canyon
 * that is not simply a low place in the surface.
 *
 * A band is the exception. It is an independent three-dimensional field joined
 * to the ground by union or subtraction rather than by sum, which is what lets
 * a column read air, stone, air, stone.
 */
@Serializable
data class TerrainBand(
    val coverage: Double,
    val minY: Int,
    val maxY: Int,
    val effect: BandEffect = BandEffect.ADD,
    val region: BandRegion = BandRegion.ANYWHERE,
    val scale: Double = 1.0,
    val thickness: Double = 1.0,
)

@Serializable
sealed interface TerrainShape {
    /** Borrow a vanilla router unchanged. */
    @Serializable
    @SerialName("vanilla")
    data class Vanilla(
        val preset: VanillaNoisePreset = VanillaNoisePreset.OVERWORLD,
    ) : TerrainShape

    /**
     * Version-independent terrain intent produced from the player's prompt.
     *
     * Values describe outcomes rather than Minecraft density-function nodes.
     * The target-version compiler owns the conversion into a NoiseRouter.
     */
    @Serializable
    @SerialName("procedural")
    data class Procedural(
        val landRatio: Double,
        val continentScale: Double,
        val coastRoughness: Double,
        val relief: ReliefDistribution,
        val verticalScale: Double,
        val caveDensity: Double,
        val hydrology: HydrologyIntent,
        val bands: List<TerrainBand> = emptyList(),
    ) : TerrainShape
}

@Serializable
data class TerrainPlan(
    val schemaVersion: Int = WorldsmithCore.BLUEPRINT_SCHEMA_VERSION,
    val seed: Long? = null,
    val minY: Int,
    val height: Int,
    val horizontalNoiseSize: Int,
    val verticalNoiseSize: Int,
    val seaLevel: Int,
    val defaultBlock: MaterialSelector,
    val defaultFluid: MaterialSelector,
    val shape: TerrainShape,
    val aquifersEnabled: Boolean,
    val oreVeinsEnabled: Boolean,
    val legacyRandomSource: Boolean,
    val spawnTargets: List<ClimateBox>,
)
