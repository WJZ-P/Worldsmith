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
        files = WorldsmithPackFiles("terrain.json", "biomes.json", "features.json"),
    )

    @Test
    fun `whitespace and object key order do not change id`() {
        val compact = mapOf(
            "terrain.json" to "{\"height\":384,\"seaLevel\":63}",
            "biomes.json" to "{\"biomes\":[{\"id\":\"a\",\"weight\":1}]}",
            "features.json" to "{\"features\":[{\"id\":\"x\",\"density\":0.5}]}",
            "structures.json" to "{\"schemaVersion\":1,\"structures\":[]}",
        )
        val formatted = mapOf(
            "terrain.json" to "{ \"seaLevel\": 63, \"height\": 384 }",
            "biomes.json" to "{\n  \"biomes\": [{\"weight\": 1, \"id\": \"a\"}]\n}",
            "features.json" to "{\"features\": [{\"density\": 0.5, \"id\": \"x\"}]}",
            "structures.json" to "{\"schemaVersion\":1,\"structures\":[]}",
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
        "biomes.json" to "{\"biomes\":[]}",
        "features.json" to "{\"features\":[]}",
        "structures.json" to "{\"schemaVersion\":1,\"structures\":[]}",
    )
}
