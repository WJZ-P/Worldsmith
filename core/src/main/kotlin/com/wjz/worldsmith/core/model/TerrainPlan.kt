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
    val flats: Double = 0.65,
    val highlands: Double = 0.25,
    val peaks: Double = 0.10,
)

/** Whether a generated river channel is flooded to sea level or remains a dry valley. */
@Serializable
enum class RiverFill {
    FLUID,
    DRY,
}

/**
 * Version-independent inland-water and ocean-floor intent.
 *
 * Defaults preserve Terrain V2 exactly: no generated inland water and the
 * original ocean depth. Prompt-generated packs write the fields explicitly.
 */
@Serializable
data class HydrologyIntent(
    val riverCoverage: Double = 0.0,
    val riverWidth: Double = 1.0,
    val riverDepth: Double = 0.8,
    val riverMeander: Double = 0.65,
    val riverFill: RiverFill = RiverFill.FLUID,
    val lakeDensity: Double = 0.0,
    val lakeScale: Double = 1.0,
    val lakeDepth: Double = 0.8,
    val oceanDepth: Double = 1.0,
)

/**
 * How the shape of the land is decided.
 *
 * Prompt-generated packs use semantic [Procedural] intent; the target adapter
 * turns it into the density-function graph of its Minecraft version. [Vanilla]
 * remains as a compatibility escape hatch for packs that intentionally want an
 * unchanged router. Keeping both as a closed tagged set lets future terrain
 * models be added without changing the meaning of either existing variant.
 */
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
        val landRatio: Double = 0.55,
        val continentScale: Double = 1.0,
        val coastRoughness: Double = 0.45,
        val relief: ReliefDistribution = ReliefDistribution(),
        val verticalScale: Double = 1.0,
        val caveDensity: Double = 0.65,
        val hydrology: HydrologyIntent = HydrologyIntent(),
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
