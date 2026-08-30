package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.model.ReliefDistribution
import com.wjz.worldsmith.core.model.HydrologyIntent
import com.wjz.worldsmith.core.model.RiverFill
import com.wjz.worldsmith.core.model.TerrainShape
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerrainPlanValidatorTest {
    private val template = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").terrain

    @Test
    fun `all supported procedural extremes validate`() {
        val plan = template.copy(
            shape = TerrainShape.Procedural(
                landRatio = 0.0,
                continentScale = 8.0,
                coastRoughness = 1.0,
                relief = ReliefDistribution(flats = 0.0, highlands = 0.0, peaks = 1.0),
                verticalScale = 4.0,
                caveDensity = 0.0,
                hydrology = HydrologyIntent(
                    riverCoverage = 0.35,
                    riverWidth = 4.0,
                    riverDepth = 4.0,
                    riverMeander = 1.0,
                    riverFill = RiverFill.DRY,
                    lakeDensity = 0.35,
                    lakeScale = 8.0,
                    lakeDepth = 4.0,
                    oceanDepth = 4.0,
                ),
            ),
        )

        assertEquals(emptyList<Diagnostic>(), TerrainPlanValidator.validate(plan))
    }

    @Test
    fun `each procedural control owns a precise diagnostic`() {
        val plan = template.copy(
            shape = TerrainShape.Procedural(
                landRatio = 1.1,
                continentScale = 0.05,
                coastRoughness = -0.1,
                relief = ReliefDistribution(flats = 0.0, highlands = 0.0, peaks = 0.0),
                verticalScale = 4.1,
                caveDensity = -0.1,
                hydrology = HydrologyIntent(
                    riverCoverage = 0.36,
                    riverWidth = 0.24,
                    riverDepth = -0.1,
                    riverMeander = 1.1,
                    lakeDensity = 0.36,
                    lakeScale = 0.24,
                    lakeDepth = -0.1,
                    oceanDepth = 0.24,
                ),
            ),
        )

        val diagnostics = TerrainPlanValidator.validate(plan)
        val codes = diagnostics.map { it.code }.toSet()

        assertTrue("LAND_RATIO_OUT_OF_RANGE" in codes)
        assertTrue("CONTINENT_SCALE_OUT_OF_RANGE" in codes)
        assertTrue("COAST_ROUGHNESS_OUT_OF_RANGE" in codes)
        assertTrue("EMPTY_RELIEF_DISTRIBUTION" in codes)
        assertTrue("VERTICAL_SCALE_OUT_OF_RANGE" in codes)
        assertTrue("CAVE_DENSITY_OUT_OF_RANGE" in codes)
        assertTrue("RIVER_COVERAGE_OUT_OF_RANGE" in codes)
        assertTrue("RIVER_WIDTH_OUT_OF_RANGE" in codes)
        assertTrue("RIVER_DEPTH_OUT_OF_RANGE" in codes)
        assertTrue("RIVER_MEANDER_OUT_OF_RANGE" in codes)
        assertTrue("LAKE_DENSITY_OUT_OF_RANGE" in codes)
        assertTrue("LAKE_SCALE_OUT_OF_RANGE" in codes)
        assertTrue("LAKE_DEPTH_OUT_OF_RANGE" in codes)
        assertTrue("OCEAN_DEPTH_OUT_OF_RANGE" in codes)
    }
}
