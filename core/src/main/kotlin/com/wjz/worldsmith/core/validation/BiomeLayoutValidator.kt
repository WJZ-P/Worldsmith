package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.model.BiomeLayoutPlan
import com.wjz.worldsmith.core.model.ClimateBox
import com.wjz.worldsmith.core.model.NumericRange

object BiomeLayoutValidator {
    private val ID = Regex("^[a-z0-9_.-]+$")

    fun validate(layout: BiomeLayoutPlan): List<Diagnostic> = buildList {
        if (layout.schemaVersion != WorldsmithCore.BLUEPRINT_SCHEMA_VERSION) {
            add(error("schemaVersion", "UNSUPPORTED_SCHEMA", "Unsupported biome layout schema ${layout.schemaVersion}"))
        }
        if (layout.worldId.isBlank()) {
            add(error("worldId", "EMPTY_WORLD_ID", "World id must not be blank"))
        }
        if (layout.skeletons.isEmpty()) {
            add(error("skeletons", "EMPTY_BIOME_LAYOUT", "Biome layout must contain at least one skeleton"))
        }

        val duplicateIds = layout.skeletons.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        duplicateIds.sorted().forEach { id ->
            add(error("skeletons[$id]", "DUPLICATE_SKELETON", "Biome skeleton id must be unique"))
        }

        layout.skeletons.forEachIndexed { index, skeleton ->
            val path = "skeletons[$index]"
            if (!ID.matches(skeleton.id)) {
                add(error("$path.id", "INVALID_SKELETON_ID", "Skeleton id must match ${ID.pattern}"))
            }
            addAll(validateClimate("$path.climate", skeleton.climate))
            if (skeleton.behavior.temperature !in -2.0f..2.0f) {
                add(error("$path.behavior.temperature", "TEMPERATURE_OUT_OF_RANGE", "Temperature must be between -2 and 2"))
            }
            if (skeleton.behavior.downfall !in 0.0f..1.0f) {
                add(error("$path.behavior.downfall", "DOWNFALL_OUT_OF_RANGE", "Downfall must be between 0 and 1"))
            }
        }
    }

    fun validateClimate(path: String, climate: ClimateBox): List<Diagnostic> = buildList {
        listOf(
            "temperature" to climate.temperature,
            "humidity" to climate.humidity,
            "continentalness" to climate.continentalness,
            "erosion" to climate.erosion,
            "depth" to climate.depth,
            "weirdness" to climate.weirdness,
        ).forEach { (name, range) -> addAll(validateRange("$path.$name", range)) }
        if (climate.offset !in 0.0f..1.0f) {
            add(error("$path.offset", "OFFSET_OUT_OF_RANGE", "Climate offset must be between 0 and 1"))
        }
    }

    private fun validateRange(path: String, range: NumericRange): List<Diagnostic> = buildList {
        if (range.min > range.max) {
            add(error(path, "REVERSED_RANGE", "Range minimum must not exceed its maximum"))
        }
        if (range.min !in -2.0f..2.0f || range.max !in -2.0f..2.0f) {
            add(error(path, "CLIMATE_RANGE_OUT_OF_BOUNDS", "Climate ranges must remain between -2 and 2"))
        }
    }

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
