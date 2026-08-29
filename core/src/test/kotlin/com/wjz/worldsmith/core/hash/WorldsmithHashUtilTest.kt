package com.wjz.worldsmith.core.hash

import com.wjz.worldsmith.core.model.WorldsmithPackFiles
import com.wjz.worldsmith.core.model.WorldsmithPackManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class WorldsmithHashUtilTest {
    private val manifest = WorldsmithPackManifest(
        formatVersion = 1,
        id = "0".repeat(64),
        displayName = "Ignored metadata",
        description = "Ignored metadata",
        files = WorldsmithPackFiles("terrain.json", "biomes/layout.json", "biomes/skins.json"),
    )

    @Test
    fun `whitespace and object key order do not change id`() {
        val compact = mapOf(
            "terrain.json" to "{\"height\":384,\"seaLevel\":63}",
            "biomes/layout.json" to "{\"skeletons\":[{\"id\":\"a\",\"weight\":1}]}",
            "biomes/skins.json" to "{\"colors\":{\"sky\":\"#ffffff\",\"fog\":\"#000000\"}}",
        )
        val formatted = mapOf(
            "terrain.json" to "{ \"seaLevel\": 63, \"height\": 384 }",
            "biomes/layout.json" to "{\n  \"skeletons\": [{\"weight\": 1, \"id\": \"a\"}]\n}",
            "biomes/skins.json" to "{\"colors\": {\"fog\": \"#000000\", \"sky\": \"#ffffff\"}}",
        )

        assertEquals(
            WorldsmithHashUtil.computeGenerationId(manifest, compact),
            WorldsmithHashUtil.computeGenerationId(manifest, formatted),
        )
    }

    @Test
    fun `fixed seed changes id while missing seed remains a random recipe`() {
        val randomSeed = contents("{\"height\":384}")
        val explicitRandomSeed = contents("{\"height\":384,\"seed\":null}")
        val fixedSeed = contents("{\"height\":384,\"seed\":42}")

        assertEquals(
            WorldsmithHashUtil.computeGenerationId(manifest, randomSeed),
            WorldsmithHashUtil.computeGenerationId(manifest, explicitRandomSeed),
        )
        assertNotEquals(
            WorldsmithHashUtil.computeGenerationId(manifest, randomSeed),
            WorldsmithHashUtil.computeGenerationId(manifest, fixedSeed),
        )
        assertEquals(
            WorldsmithHashUtil.computeGenerationId(manifest, fixedSeed),
            WorldsmithHashUtil.finalizeManifest(manifest, fixedSeed).id,
        )
    }

    private fun contents(terrain: String) = mapOf(
        "terrain.json" to terrain,
        "biomes/layout.json" to "{\"skeletons\":[]}",
        "biomes/skins.json" to "{\"skins\":[]}",
    )
}
