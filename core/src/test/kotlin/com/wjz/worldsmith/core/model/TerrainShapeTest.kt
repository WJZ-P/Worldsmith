package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerrainShapeTest {
    @Test
    fun `the built in pack demonstrates prompt-facing procedural terrain`() {
        val terrain = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").terrain

        val shape = assertInstanceOf(TerrainShape.Procedural::class.java, terrain.shape)
        assertEquals(0.55, shape.landRatio)
        assertEquals(ReliefDistribution(0.65, 0.25, 0.10), shape.relief)
        assertEquals(RiverFill.DRY, shape.hydrology.riverFill)
        assertEquals(1.25, shape.hydrology.oceanDepth)
    }

    /**
     * The discriminator is the whole point of the shape being a closed set
     * rather than an enum: each current terrain model has an unambiguous JSON
     * contract and future models can receive their own contract.
     */
    @Test
    fun `a shape is written with the tag that lets a second variant be added later`() {
        val encoded = WorldsmithJson.encode<TerrainShape>(TerrainShape.Vanilla(VanillaNoisePreset.AMPLIFIED))

        assertTrue("\"kind\": \"vanilla\"" in encoded, "the variant tag should be on disk, got: $encoded")

        val decoded = WorldsmithJson.decode<TerrainShape>(encoded)
        assertEquals(TerrainShape.Vanilla(VanillaNoisePreset.AMPLIFIED), decoded)
    }

    @Test
    fun `procedural terrain intent round trips without Minecraft implementation details`() {
        val shape = TerrainShape.Procedural(
            landRatio = 0.82,
            continentScale = 2.4,
            coastRoughness = 0.7,
            relief = ReliefDistribution(flats = 0.2, highlands = 0.3, peaks = 0.5),
            verticalScale = 1.8,
            caveDensity = 0.15,
            hydrology = HydrologyIntent(
                riverCoverage = 0.08,
                riverWidth = 1.7,
                riverDepth = 1.2,
                riverMeander = 0.9,
                riverFill = RiverFill.DRY,
                lakeDensity = 0.05,
                lakeScale = 2.0,
                lakeDepth = 0.6,
                oceanDepth = 1.4,
            ),
        )

        val encoded = WorldsmithJson.encode<TerrainShape>(shape)

        assertTrue("\"kind\": \"procedural\"" in encoded)
        assertTrue("\"riverFill\": \"DRY\"" in encoded)
        assertTrue("NoiseRouter" !in encoded)
        assertEquals(shape, WorldsmithJson.decode<TerrainShape>(encoded))
    }

    @Test
    fun `procedural terrain rejects an omitted hydrology block`() {
        val incomplete = """
            {
              "kind": "procedural",
              "landRatio": 0.7,
              "continentScale": 1.4,
              "coastRoughness": 0.2,
              "relief": { "flats": 0.8, "highlands": 0.15, "peaks": 0.05 },
              "verticalScale": 0.9,
              "caveDensity": 0.3
            }
        """.trimIndent()

        assertThrows(SerializationException::class.java) {
            WorldsmithJson.decode<TerrainShape>(incomplete)
        }
    }

    @Test
    fun `terrain round trips through the pack format`() {
        val terrain = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").terrain

        assertEquals(terrain, WorldsmithJson.decode<TerrainPlan>(WorldsmithJson.encode(terrain)))
    }
}
