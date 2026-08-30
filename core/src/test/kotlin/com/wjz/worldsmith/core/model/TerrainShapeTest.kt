package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerrainShapeTest {
    @Test
    fun `the built in pack borrows vanilla's overworld router`() {
        val terrain = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").terrain

        val shape = assertInstanceOf(TerrainShape.Vanilla::class.java, terrain.shape)
        assertEquals(VanillaNoisePreset.OVERWORLD, shape.preset)
    }

    /**
     * The discriminator is the whole point of the shape being a closed set
     * rather than an enum. Without it on disk, adding a second variant later
     * would make every pack written before that day unreadable.
     */
    @Test
    fun `a shape is written with the tag that lets a second variant be added later`() {
        val encoded = WorldsmithJson.encode<TerrainShape>(TerrainShape.Vanilla(VanillaNoisePreset.AMPLIFIED))

        assertTrue("\"kind\": \"vanilla\"" in encoded, "the variant tag should be on disk, got: $encoded")

        val decoded = WorldsmithJson.decode<TerrainShape>(encoded)
        assertEquals(TerrainShape.Vanilla(VanillaNoisePreset.AMPLIFIED), decoded)
    }

    @Test
    fun `terrain round trips through the pack format`() {
        val terrain = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").terrain

        assertEquals(terrain, WorldsmithJson.decode<TerrainPlan>(WorldsmithJson.encode(terrain)))
    }
}
