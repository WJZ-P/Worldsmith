package com.wjz.worldsmith.core.analysis

import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class LandformPreviewTest {
    private fun terrain(relief: AnchorRelief, radius: Int = 120): TerrainPlan {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").terrain
        return base.copy(shape = (base.shape as TerrainShape.Procedural).copy(
            anchors = listOf(Anchor("focus", AnchorPlacement.Fixed(0, 0), radius, relief))))
    }

    @Test fun `mesa diagram uses absolute target and shows the bounded roughness envelope`() {
        val plan = terrain(AnchorRelief.Mesa(120, .6, 3.0))
        val report = LandformPreview.analyze(plan, "focus", 45.0)
        val center = report.samples[LandformPreview.SAMPLES / 2]
        assertEquals(120.0, center.surfaceY)
        assertEquals(117.0, center.lowerRoughnessY)
        assertEquals(123.0, center.upperRoughnessY)
        assertEquals(45.0, report.samples.first().surfaceY)
        assertEquals(45.0, report.samples.last().surfaceY)
        assertEquals(144.0, report.flatCoreWidthBlocks)
        assertFalse(report.actualTerrainSampled)
        assertFalse(report.minecraftStarted)
        assertFalse(report.buildingSiteVerified)
        val differentBase = LandformPreview.analyze(plan, "focus", 180.0)
        assertEquals(120.0, differentBase.samples[128].surfaceY)
        assertEquals(180.0, differentBase.samples.first().surfaceY)
    }

    @Test fun `caldera section includes floor raised rim and full return to the supplied terrain plane`() {
        val report = LandformPreview.analyze(terrain(AnchorRelief.Caldera(70, 150, .25, .65, 0.0)), "focus", 85.0)
        assertEquals(70.0, report.samples[128].surfaceY)
        assertTrue(report.maximumSurfaceY > 149.0)
        assertEquals(85.0, report.samples.first().surfaceY)
        assertTrue(report.maximumSampledGrade > 0.0)
        assertEquals(60.0, report.flatCoreWidthBlocks)
        for (i in 0..128) assertEquals(report.samples[i].surfaceY, report.samples[256 - i].surfaceY, 1e-10)
    }

    @Test fun `offset is relative and sampling work stays fixed even for the largest radius`() {
        val small = LandformPreview.analyze(terrain(AnchorRelief.Offset(-35.0), 32), "focus", 90.0)
        val large = LandformPreview.analyze(terrain(AnchorRelief.Offset(-35.0), 100_000), "focus", 90.0)
        assertEquals(LandformPreview.SAMPLES, small.samples.size)
        assertEquals(LandformPreview.SAMPLES, large.samples.size)
        assertEquals(55.0, large.samples[128].surfaceY)
        assertEquals(small.minimumSurfaceY, large.minimumSurfaceY)
        assertEquals(small.maximumSampledGrade / large.maximumSampledGrade, 100_000.0 / 32, 1e-7)
        assertNull(large.flatCoreWidthBlocks)
    }

    @Test fun `rendered PNG is deterministic and carries a visible two-dimensional profile`() {
        val report = LandformPreview.analyze(terrain(AnchorRelief.Caldera(70, 150, .25, .65, 3.0)), "focus", 80.0)
        val first = LandformPreview.png(report)
        assertArrayEquals(first, LandformPreview.png(report))
        val image = ImageIO.read(ByteArrayInputStream(first))
        assertEquals(LandformPreview.WIDTH, image.width)
        assertEquals(LandformPreview.HEIGHT, image.height)
        val colours = mutableSetOf<Int>()
        for (y in 140 until 590 step 5) for (x in 96 until 1200 step 5) colours += image.getRGB(x, y)
        assertTrue(colours.size > 10, "Profile must have curves/grid/envelope rather than a blank image")
    }

    @Test fun `malformed terrain unknown anchors and invented base values are rejected before rendering`() {
        val plan = terrain(AnchorRelief.Mesa(120, .6, 0.0))
        for (base in listOf(Double.NaN, Double.POSITIVE_INFINITY, -65.0, 320.0))
            assertThrows(IllegalArgumentException::class.java) { LandformPreview.analyze(plan, "focus", base) }
        assertThrows(IllegalArgumentException::class.java) { LandformPreview.analyze(plan, "missing", 80.0) }
        assertThrows(IllegalArgumentException::class.java) { LandformPreview.analyze(plan.copy(shape = TerrainShape.Vanilla()), "focus", 80.0) }
        assertThrows(IllegalArgumentException::class.java) { LandformPreview.analyze(terrain(AnchorRelief.Mesa(300, .6, 0.0)), "focus", 80.0) }
        assertThrows(IllegalArgumentException::class.java) { LandformPreview.analyze(terrain(AnchorRelief.Caldera(150, 70, .25, .65, 0.0)), "focus", 80.0) }
    }
}
