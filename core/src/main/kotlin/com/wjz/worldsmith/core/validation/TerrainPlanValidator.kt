package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.SkyIntent
import com.wjz.worldsmith.core.model.TerrainPlan
import com.wjz.worldsmith.core.model.TerrainShape

object TerrainPlanValidator {
    private const val MIN_SKY_BAND = 24
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
                addAll(validateSky(shape.sky, plan))
                val hydrology = shape.hydrology
                if (hydrology.riverCoverage !in 0.0..0.35) {
                    add(error("shape.hydrology.riverCoverage", "RIVER_COVERAGE_OUT_OF_RANGE", "River coverage must be between 0 and 0.35"))
                }
                if (hydrology.riverWidth !in 0.25..4.0) {
                    add(error("shape.hydrology.riverWidth", "RIVER_WIDTH_OUT_OF_RANGE", "River width must be between 0.25 and 4"))
                }
                if (hydrology.riverDepth !in 0.0..4.0) {
                    add(error("shape.hydrology.riverDepth", "RIVER_DEPTH_OUT_OF_RANGE", "River depth must be between 0 and 4"))
                }
                if (hydrology.riverMeander !in 0.0..1.0) {
                    add(error("shape.hydrology.riverMeander", "RIVER_MEANDER_OUT_OF_RANGE", "River meander must be between 0 and 1"))
                }
                if (hydrology.lakeDensity !in 0.0..0.35) {
                    add(error("shape.hydrology.lakeDensity", "LAKE_DENSITY_OUT_OF_RANGE", "Lake density must be between 0 and 0.35"))
                }
                if (hydrology.lakeScale !in 0.25..8.0) {
                    add(error("shape.hydrology.lakeScale", "LAKE_SCALE_OUT_OF_RANGE", "Lake scale must be between 0.25 and 8"))
                }
                if (hydrology.lakeDepth !in 0.0..4.0) {
                    add(error("shape.hydrology.lakeDepth", "LAKE_DEPTH_OUT_OF_RANGE", "Lake depth must be between 0 and 4"))
                }
                if (hydrology.oceanDepth !in 0.25..4.0) {
                    add(error("shape.hydrology.oceanDepth", "OCEAN_DEPTH_OUT_OF_RANGE", "Ocean depth must be between 0.25 and 4"))
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

    /**
     * Islands only exist between [SkyIntent.minY] and [SkyIntent.maxY], so a
     * band outside the world, or inverted, or under the sea would silently
     * produce nothing at all rather than the world the prompt asked for.
     */
    private fun validateSky(sky: SkyIntent, plan: TerrainPlan): List<Diagnostic> = buildList {
        if (sky.coverage !in 0.0..1.0) {
            add(error("shape.sky.coverage", "SKY_COVERAGE_OUT_OF_RANGE", "Sky coverage must be between 0 and 1"))
        }
        if (sky.scale !in 0.1..8.0) {
            add(error("shape.sky.scale", "SKY_SCALE_OUT_OF_RANGE", "Sky island scale must be between 0.1 and 8"))
        }
        if (sky.thickness !in 0.1..8.0) {
            add(error("shape.sky.thickness", "SKY_THICKNESS_OUT_OF_RANGE", "Sky island thickness must be between 0.1 and 8"))
        }
        if (sky.coverage <= 0.0) {
            return@buildList
        }

        val worldTop = plan.minY + plan.height
        if (sky.minY >= sky.maxY) {
            add(error("shape.sky", "REVERSED_SKY_BAND", "Sky islands must start below where they end"))
        }
        if (sky.minY < plan.minY || sky.maxY > worldTop) {
            add(
                error(
                    "shape.sky",
                    "SKY_BAND_OUTSIDE_WORLD",
                    "Sky band " + sky.minY + ".." + sky.maxY + " leaves the world height " + plan.minY + ".." + worldTop,
                ),
            )
        }
        if (sky.maxY - sky.minY < MIN_SKY_BAND) {
            add(
                warning(
                    "shape.sky",
                    "SKY_BAND_TOO_THIN",
                    "A band under " + MIN_SKY_BAND + " blocks tall leaves room for slivers rather than islands",
                ),
            )
        }
        if (sky.minY < plan.seaLevel) {
            add(
                warning(
                    "shape.sky",
                    "SKY_BAND_BELOW_SEA_LEVEL",
                    "Islands starting below sea level " + plan.seaLevel + " will merge into the ground and the sea",
                ),
            )
        }
    }

    private fun warning(path: String, code: String, message: String) =
        Diagnostic(path, code, DiagnosticSeverity.WARNING, message)

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
