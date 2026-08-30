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

/**
 * How the shape of the land is decided.
 *
 * This is the one part of a pack that is still described in Minecraft's terms
 * rather than Worldsmith's: today the only thing a pack can say is which vanilla
 * noise router to borrow. That is a deliberate limit on what the compiler
 * accepts, because density functions are the easiest part of world generation to
 * get wrong and the hardest to check automatically.
 *
 * It is written as a closed set with one member rather than as a bare enum so
 * that limit stays in the compiler instead of being burned into the format. A
 * pack's id is the hash of its generation content, so widening this field later
 * would rewrite the id of every pack in existence; widening the set of variants
 * does not.
 */
@Serializable
sealed interface TerrainShape {
    /** Borrow a vanilla router unchanged. */
    @Serializable
    @SerialName("vanilla")
    data class Vanilla(
        val preset: VanillaNoisePreset = VanillaNoisePreset.OVERWORLD,
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
