package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.WorldsmithCore
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

@Serializable
enum class NoiseTemplate {
    VANILLA_OVERWORLD,
    VANILLA_LARGE_BIOMES,
    VANILLA_AMPLIFIED,
}

@Serializable
data class TerrainPlan(
    val schemaVersion: Int = WorldsmithCore.BLUEPRINT_SCHEMA_VERSION,
    val worldId: String,
    val minY: Int,
    val height: Int,
    val horizontalNoiseSize: Int,
    val verticalNoiseSize: Int,
    val seaLevel: Int,
    val defaultBlock: MaterialSelector,
    val defaultFluid: MaterialSelector,
    val noiseTemplate: NoiseTemplate,
    val aquifersEnabled: Boolean,
    val oreVeinsEnabled: Boolean,
    val legacyRandomSource: Boolean,
    val spawnTargets: List<ClimateBox>,
)
