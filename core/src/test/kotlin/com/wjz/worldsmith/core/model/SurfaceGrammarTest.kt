package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SurfaceGrammarTest {
    @Test
    fun `built in surfaces round trip as ordered semantic grammar`() {
        val surface = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
            .biomes.biomes.first { it.id == "rime_peaks" }
            .surface

        assertTrue(surface.base.layers.size >= 2)
        assertEquals("dry_riverbed", surface.rules.first().id)
        assertTrue(surface.rules.any { it.conditions.altitude != null })
        assertTrue(surface.rules.any { it.conditions.slope == SurfaceSlope.STEEP })
        assertEquals(surface, WorldsmithJson.decode<SurfaceDefinition>(WorldsmithJson.encode(surface)))
    }

    @Test
    fun `superseded flat surface object is rejected`() {
        val superseded = """
            {
              "top": { "semanticRole": "top", "preferredIds": ["minecraft:gravel"] },
              "under": { "semanticRole": "under", "preferredIds": ["minecraft:dirt"] },
              "deep": { "semanticRole": "deep", "preferredIds": ["minecraft:stone"] }
            }
        """.trimIndent()

        assertThrows(SerializationException::class.java) {
            WorldsmithJson.decode<SurfaceDefinition>(superseded)
        }
    }
}
