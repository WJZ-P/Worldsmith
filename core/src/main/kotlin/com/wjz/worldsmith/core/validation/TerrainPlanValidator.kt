package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.TerrainPlan
import com.wjz.worldsmith.core.model.TerrainShape

object TerrainPlanValidator {
    fun validate(plan: TerrainPlan): List<Diagnostic> = buildList {
        if (plan.schemaVersion != WorldsmithCore.BLUEPRINT_SCHEMA_VERSION) {
            add(error("schemaVersion", "UNSUPPORTED_SCHEMA", "Unsupported terrain schema ${plan.schemaVersion}"))
        }
        if (plan.minY % 16 != 0) {
            add(error("minY", "MIN_Y_ALIGNMENT", "Minimum Y must be divisible by 16"))
        }
        if (plan.height <= 0 || plan.height % 16 != 0) {
            add(error("height", "HEIGHT_ALIGNMENT", "Height must be positive and divisible by 16"))
        }
        if (plan.horizontalNoiseSize !in 1..4 || plan.verticalNoiseSize !in 1..4) {
            add(error("noiseSize", "NOISE_SIZE_OUT_OF_RANGE", "Noise sizes must be between 1 and 4"))
        }
        if (plan.seaLevel !in plan.minY until (plan.minY + plan.height)) {
            add(error("seaLevel", "SEA_LEVEL_OUT_OF_RANGE", "Sea level must be inside the terrain height range"))
        }
        when (val shape = plan.shape) {
            is TerrainShape.Vanilla -> Unit
            is TerrainShape.Procedural -> {
                if (shape.landRatio !in 0.0..1.0) {
                    add(error("shape.landRatio", "LAND_RATIO_OUT_OF_RANGE", "Land ratio must be between 0 and 1"))
                }
                if (shape.continentScale !in 0.1..8.0) {
                    add(
                        error(
                            "shape.continentScale",
                            "CONTINENT_SCALE_OUT_OF_RANGE",
                            "Continent scale must be between 0.1 and 8",
                        ),
                    )
                }
                if (shape.coastRoughness !in 0.0..1.0) {
                    add(error("shape.coastRoughness", "COAST_ROUGHNESS_OUT_OF_RANGE", "Coast roughness must be between 0 and 1"))
                }
                listOf(
                    "flats" to shape.relief.flats,
                    "highlands" to shape.relief.highlands,
                    "peaks" to shape.relief.peaks,
                ).forEach { (name, value) ->
                    if (value !in 0.0..1.0) {
                        add(error("shape.relief.$name", "RELIEF_WEIGHT_OUT_OF_RANGE", "Relief weights must be between 0 and 1"))
                    }
                }
                if (shape.relief.flats + shape.relief.highlands + shape.relief.peaks <= 0.0) {
                    add(error("shape.relief", "EMPTY_RELIEF_DISTRIBUTION", "At least one relief weight must be positive"))
                }
                if (shape.verticalScale !in 0.1..4.0) {
                    add(error("shape.verticalScale", "VERTICAL_SCALE_OUT_OF_RANGE", "Vertical scale must be between 0.1 and 4"))
                }
                if (shape.caveDensity !in 0.0..1.0) {
                    add(error("shape.caveDensity", "CAVE_DENSITY_OUT_OF_RANGE", "Cave density must be between 0 and 1"))
                }
            }
        }
        addAll(validateMaterial("defaultBlock", plan.defaultBlock))
        addAll(validateMaterial("defaultFluid", plan.defaultFluid))
        if (plan.spawnTargets.isEmpty()) {
            add(error("spawnTargets", "EMPTY_SPAWN_TARGETS", "At least one spawn target is required"))
        }
        plan.spawnTargets.forEachIndexed { index, target ->
            addAll(BiomePlanValidator.validateClimate("spawnTargets[$index]", target))
        }
    }

    private fun validateMaterial(path: String, selector: MaterialSelector): List<Diagnostic> = buildList {
        if (selector.semanticRole.isBlank()) {
            add(error("$path.semanticRole", "EMPTY_SEMANTIC_ROLE", "Material selector must name a semantic role"))
        }
        if (selector.preferredIds.isEmpty() && selector.requiredTags.isEmpty()) {
            add(error(path, "EMPTY_MATERIAL", "Material selector must list preferred ids or required tags"))
        }
    }

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
