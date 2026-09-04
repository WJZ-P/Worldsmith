package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.model.Anchor
import com.wjz.worldsmith.core.model.AnchorClimateBias
import com.wjz.worldsmith.core.model.AnchorPlacement
import com.wjz.worldsmith.core.model.CaveIntent
import com.wjz.worldsmith.core.model.CaveVerticalRange
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
                caves = CaveIntent(0.0, 1.0, 0.5, 0.25, CaveVerticalRange(-59, 319), 0.0),
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
                caves = CaveIntent(-0.1, 1.1, -0.2, 1.2, CaveVerticalRange(100, 20), 1.1),
                hydrology = HydrologyIntent(
                    riverCoverage = 0.36,
                    riverWidth = 0.24,
                    riverDepth = -0.1,
                    riverMeander = 1.1,
                    riverFill = RiverFill.FLUID,
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
        assertTrue("CAVE_CONTROL_OUT_OF_RANGE" in codes)
        assertTrue("REVERSED_CAVE_RANGE" in codes)
        assertTrue("RIVER_COVERAGE_OUT_OF_RANGE" in codes)
        assertTrue("RIVER_WIDTH_OUT_OF_RANGE" in codes)
        assertTrue("RIVER_DEPTH_OUT_OF_RANGE" in codes)
        assertTrue("RIVER_MEANDER_OUT_OF_RANGE" in codes)
        assertTrue("LAKE_DENSITY_OUT_OF_RANGE" in codes)
        assertTrue("LAKE_SCALE_OUT_OF_RANGE" in codes)
        assertTrue("LAKE_DEPTH_OUT_OF_RANGE" in codes)
        assertTrue("OCEAN_DEPTH_OUT_OF_RANGE" in codes)
    }

    @Test
    fun `anchor climate bias is explicit and bounded`() {
        val shape = template.shape as TerrainShape.Procedural
        val plan = template.copy(
            shape = shape.copy(
                anchors = listOf(
                    Anchor(
                        id = "empty_bias",
                        placement = AnchorPlacement.Fixed(0, 0),
                        radius = 200,
                        amplitude = 0.0,
                        climateBias = AnchorClimateBias(strength = 1.2),
                    ),
                    Anchor(
                        id = "bad_target",
                        placement = AnchorPlacement.Fixed(400, 0),
                        radius = 200,
                        amplitude = 20.0,
                        climateBias = AnchorClimateBias(erosion = -3.0),
                    ),
                ),
            ),
        )

        val codes = TerrainPlanValidator.validate(plan).map { it.code }.toSet()

        assertTrue("ANCHOR_CLIMATE_STRENGTH_OUT_OF_RANGE" in codes)
        assertTrue("EMPTY_ANCHOR_CLIMATE_BIAS" in codes)
        assertTrue("ANCHOR_CLIMATE_TARGET_OUT_OF_RANGE" in codes)
    }

    @Test
    fun `technical height is the same envelope as the overworld dimension type`() {
        val diagnostics = TerrainPlanValidator.validate(template.copy(minY = -80, height = 400))
        val codes = diagnostics.map { it.code }.toSet()

        assertTrue("OVERWORLD_MIN_Y_REQUIRED" in codes)
        assertTrue("OVERWORLD_HEIGHT_REQUIRED" in codes)
    }

    @Test
    fun `line and scattered anchor bounds are validated before codec compilation`() {
        val shape = template.shape as TerrainShape.Procedural
        val plan = template.copy(
            shape = shape.copy(
                anchors = listOf(
                    Anchor("flat_line", AnchorPlacement.Line(0, 0, 0, 0), 100, 0.0),
                    Anchor("huge", AnchorPlacement.Fixed(0, 0), 100, 500.0),
                    Anchor("spacing", AnchorPlacement.Scattered(1_000_001, 0.5), 100, 0.0),
                ),
            ),
        )
        val codes = TerrainPlanValidator.validate(plan).map { it.code }.toSet()

        assertTrue("ANCHOR_LINE_HAS_NO_LENGTH" in codes)
        assertTrue("ANCHOR_AMPLITUDE_OUT_OF_RANGE" in codes)
        assertTrue("ANCHOR_SPACING_OUT_OF_RANGE" in codes)
    }
}
