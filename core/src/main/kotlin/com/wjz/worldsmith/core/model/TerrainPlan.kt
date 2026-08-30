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
/**
 * Land that floats free of the ground.
 *
 * Everything else in [TerrainShape.Procedural] describes a height field: for any
 * column there is one height, solid below and air above, which is why tall
 * spires and deep caves can imply islands but never actually detach one. This
 * block is the exception. It adds a second body of rock that is combined with
 * the ground by union rather than by sum, so a column may pass through air,
 * stone, air and stone again.
 *
 * [coverage] at zero means no islands, which is what almost every world wants
 * and therefore the default.
 */
@Serializable
data class SkyIntent(
    val coverage: Double = 0.0,
    val minY: Int = 160,
    val maxY: Int = 240,
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
        val sky: SkyIntent = SkyIntent(),
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
