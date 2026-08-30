package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.BandEffect
import com.wjz.worldsmith.core.model.TerrainBand
import com.wjz.worldsmith.core.model.TerrainPlan
import com.wjz.worldsmith.core.model.TerrainShape

object TerrainPlanValidator {
    private const val MIN_BAND_HEIGHT = 24
    private const val MAX_BANDS = 6
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
                addAll(validateBands(shape.bands, plan))
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
     * A band that can never do anything is worth catching here.
     *
     * Bands only act between their two heights, so one outside the world, or
     * inverted, or buried under the sea produces silence rather than the world
     * the prompt described - and silence is exactly what cannot be debugged
     * from in-game.
     */
    private fun validateBands(bands: List<TerrainBand>, plan: TerrainPlan): List<Diagnostic> = buildList {
        if (bands.size > MAX_BANDS) {
            add(error("shape.bands", "TOO_MANY_BANDS", "A world may layer at most $MAX_BANDS terrain bands"))
        }
        val worldTop = plan.minY + plan.height
        bands.forEachIndexed { index, band ->
            val path = "shape.bands[$index]"
            if (band.coverage !in 0.0..1.0) {
                add(error("$path.coverage", "BAND_COVERAGE_OUT_OF_RANGE", "Band coverage must be between 0 and 1"))
            }
            if (band.scale !in 0.1..8.0) {
                add(error("$path.scale", "BAND_SCALE_OUT_OF_RANGE", "Band scale must be between 0.1 and 8"))
            }
            if (band.thickness !in 0.1..8.0) {
                add(error("$path.thickness", "BAND_THICKNESS_OUT_OF_RANGE", "Band thickness must be between 0.1 and 8"))
            }
            if (band.minY >= band.maxY) {
                add(error(path, "REVERSED_BAND", "A band must start below where it ends"))
            }
            if (band.minY < plan.minY || band.maxY > worldTop) {
                add(
                    error(
                        path,
                        "BAND_OUTSIDE_WORLD",
                        "Band " + band.minY + ".." + band.maxY + " leaves the world height " + plan.minY + ".." + worldTop,
                    ),
                )
            }
            if (band.coverage <= 0.0) {
                add(warning(path, "BAND_HAS_NO_EFFECT", "A band with zero coverage changes nothing"))
                return@forEachIndexed
            }
            if (band.maxY - band.minY < MIN_BAND_HEIGHT) {
                add(
                    warning(
                        path,
                        "BAND_TOO_THIN",
                        "A band under " + MIN_BAND_HEIGHT + " blocks tall leaves room for slivers rather than shapes",
                    ),
                )
            }
            if (band.effect == BandEffect.ADD && band.minY < plan.seaLevel) {
                add(
                    warning(
                        path,
                        "BAND_MERGES_WITH_GROUND",
                        "An additive band starting below sea level " + plan.seaLevel +
                            " will merge into the ground and the sea rather than float over them",
                    ),
                )
            }
        }
    }

    private fun warning(path: String, code: String, message: String) =
        Diagnostic(path, code, DiagnosticSeverity.WARNING, message)

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
