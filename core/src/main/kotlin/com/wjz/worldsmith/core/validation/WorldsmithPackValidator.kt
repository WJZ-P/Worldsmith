package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.model.RiverFill
import com.wjz.worldsmith.core.model.SurfaceHydrology
import com.wjz.worldsmith.core.model.TerrainShape
import com.wjz.worldsmith.core.model.WorldsmithPack

object WorldsmithPackValidator {
    private const val FORMAT_VERSION = 1
    private val ID = Regex("^[0-9a-f]{64}$")

    fun validate(pack: WorldsmithPack): List<Diagnostic> = buildList {
        val manifest = pack.manifest
        if (manifest.formatVersion != FORMAT_VERSION) {
            add(error("manifest.formatVersion", "UNSUPPORTED_PACK_FORMAT", "Unsupported pack format ${manifest.formatVersion}"))
        }
        if (!ID.matches(manifest.id)) {
            add(error("manifest.id", "INVALID_PACK_ID", "Pack id must be a lowercase SHA-256"))
        } else if (!WorldsmithHashUtil.matches(manifest, pack.computedId)) {
            add(error("manifest.id", "PACK_HASH_MISMATCH", "Pack id does not match its generation content"))
        }
        if (manifest.displayName.isBlank()) {
            add(error("manifest.displayName", "EMPTY_DISPLAY_NAME", "Pack display name must not be blank"))
        }
        listOf(
            "terrain" to manifest.files.terrain,
            "biomes" to manifest.files.biomes,
            "features" to manifest.files.features,
        ).forEach { (name, path) ->
            if (path.startsWith('/') || path.split('/').any { it == ".." }) {
                add(error("manifest.files.$name", "UNSAFE_PACK_PATH", "Pack content paths must stay inside the pack"))
            }
        }

        addAll(TerrainPlanValidator.validate(pack.terrain).map { it.prefixed("terrain") })
        addAll(FeatureLibraryValidator.validate(pack.features).map { it.prefixed("features") })
        addAll(BiomePlanValidator.validate(pack.biomes, pack.features).map { it.prefixed("biomes") })
        addAll(validateBiomeTerrainLinks(pack))
        addAll(validateSurfaceTerrainLinks(pack))
        addAll(validateAnchorReferences(pack))
    }

    private fun validateBiomeTerrainLinks(pack: WorldsmithPack): List<Diagnostic> = buildList {
        val spatial = pack.biomes.spatial
        if (pack.terrain.shape !is TerrainShape.Procedural &&
            (spatial.regionScale != 1.0 || spatial.boundaryRoughness != 0.0)
        ) {
            add(
                error(
                    "biomes.spatial",
                    "BIOME_SPATIAL_REQUIRES_PROCEDURAL_TERRAIN",
                    "Non-default biome spatial controls require procedural terrain",
                ),
            )
        }
    }

    /**
     * Anchor references are resolved by name at compile time and throw when a
     * name is wrong, so an unknown one has to be caught here or it becomes a
     * crash while a world is being created.
     */
    private fun validateAnchorReferences(pack: WorldsmithPack): List<Diagnostic> = buildList {
        val shape = pack.terrain.shape
        val known = if (shape is TerrainShape.Procedural) shape.anchors.mapTo(mutableSetOf()) { it.id } else emptySet()

        if (shape is TerrainShape.Procedural) {
            shape.bands.forEachIndexed { index, band ->
                val name = band.anchor ?: return@forEachIndexed
                if (name !in known) {
                    add(
                        error(
                            "terrain.shape.bands[$index].anchor",
                            "UNKNOWN_ANCHOR",
                            "Band references anchor '" + name + "', which the terrain does not define",
                        ),
                    )
                }
            }
        }

        pack.biomes.biomes.forEachIndexed { biomeIndex, biome ->
            biome.surface.rules.forEachIndexed { ruleIndex, rule ->
                val band = rule.conditions.anchor ?: return@forEachIndexed
                val path = "biomes.biomes[$biomeIndex].surface.rules[$ruleIndex].conditions.anchor"
                if (shape !is TerrainShape.Procedural) {
                    add(error(path, "ANCHOR_REQUIRES_PROCEDURAL_TERRAIN", "Anchor surface conditions require procedural terrain"))
                    return@forEachIndexed
                }
                if (band.anchor !in known) {
                    add(
                        error(
                            path,
                            "UNKNOWN_ANCHOR",
                            "Surface rule references anchor '" + band.anchor + "', which the terrain does not define",
                        ),
                    )
                }
                if (band.min !in 0.0..1.0 || band.max !in 0.0..1.0) {
                    add(error(path, "ANCHOR_BAND_OUT_OF_RANGE", "Anchor influence bounds must be between 0 and 1"))
                } else if (band.min >= band.max) {
                    add(error(path, "REVERSED_RANGE", "Anchor influence must start below where it ends"))
                }
            }
        }
    }

    private fun validateSurfaceTerrainLinks(pack: WorldsmithPack): List<Diagnostic> = buildList {
        val shape = pack.terrain.shape
        val minY = pack.terrain.minY
        val maxY = minY + pack.terrain.height - 1
        pack.biomes.biomes.forEachIndexed { biomeIndex, biome ->
            biome.surface.rules.forEachIndexed { ruleIndex, rule ->
                val path = "biomes.biomes[$biomeIndex].surface.rules[$ruleIndex].conditions"
                rule.conditions.altitude?.let { altitude ->
                    if (altitude.min != null && altitude.min > maxY || altitude.max != null && altitude.max < minY) {
                        add(error("$path.altitude", "UNREACHABLE_ALTITUDE", "Altitude range does not intersect terrain height $minY..$maxY"))
                    }
                }
                val signal = rule.conditions.hydrology ?: return@forEachIndexed
                if (shape !is TerrainShape.Procedural) {
                    add(error("$path.hydrology", "HYDROLOGY_REQUIRES_PROCEDURAL_TERRAIN", "Hydrology surface conditions require procedural terrain"))
                    return@forEachIndexed
                }
                val hydrology = shape.hydrology
                when (signal) {
                    SurfaceHydrology.DRY_RIVERBED -> {
                        if (hydrology.riverCoverage == 0.0 || hydrology.riverFill != RiverFill.DRY) {
                            add(error("$path.hydrology", "UNREACHABLE_HYDROLOGY_SIGNAL", "DRY_RIVERBED requires non-zero DRY rivers"))
                        }
                    }
                    SurfaceHydrology.WET_RIVERBED -> {
                        if (hydrology.riverCoverage == 0.0 || hydrology.riverFill != RiverFill.FLUID) {
                            add(error("$path.hydrology", "UNREACHABLE_HYDROLOGY_SIGNAL", "WET_RIVERBED requires non-zero FLUID rivers"))
                        }
                    }
                    SurfaceHydrology.RIVER_BANK -> {
                        if (hydrology.riverCoverage == 0.0) {
                            add(error("$path.hydrology", "UNREACHABLE_HYDROLOGY_SIGNAL", "RIVER_BANK requires non-zero river coverage"))
                        }
                    }
                    SurfaceHydrology.LAKEBED -> {
                        if (hydrology.lakeDensity == 0.0) {
                            add(error("$path.hydrology", "UNREACHABLE_HYDROLOGY_SIGNAL", "LAKEBED requires non-zero lake density"))
                        }
                    }
                }
            }
        }
    }

    private fun Diagnostic.prefixed(prefix: String) = copy(path = "$prefix.$path")

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
