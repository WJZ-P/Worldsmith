package com.wjz.worldsmith.core.analysis

import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Reproducible diagrams for human inspection; not a map of a seeded or running game world. */
class LandformVisualStudyTest {
    @Test fun `emit explicit same-base profile studies for mound plateau and caldera`() {
        val output = Path.of(System.getProperty("worldsmith.projectRoot"), "build/landform-quality-verification")
        Files.createDirectories(output)
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").terrain
        val profiles = listOf(
            "offset_mound" to AnchorRelief.Offset(64.0),
            "mesa_sanctuary" to AnchorRelief.Mesa(128, .6, 2.0),
            "caldera_refuge" to AnchorRelief.Caldera(76, 146, .25, .65, 3.0),
        )
        for ((id, relief) in profiles) {
            val plan = base.copy(shape = (base.shape as TerrainShape.Procedural).copy(
                anchors = listOf(Anchor(id, AnchorPlacement.Fixed(0, 0), 240, relief))))
            val report = LandformPreview.analyze(plan, id, 80.0)
            assertEquals(80.0, report.samples.first().surfaceY)
            assertEquals(80.0, report.samples.last().surfaceY)
            assertFalse(report.actualTerrainSampled)
            val png = LandformPreview.png(report)
            assertTrue(png.size > 10_000)
            Files.write(output.resolve("$id.png"), png)
            Files.writeString(output.resolve("$id.json"), WorldsmithJson.encode(report))
            Files.writeString(output.resolve("$id-terrain.json"), WorldsmithJson.encode(plan))
        }
    }
}
