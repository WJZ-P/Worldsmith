package com.wjz.worldsmith.core.hash

import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorldsmithHashUtilTest {
    private val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
    private val files = WorldContentBundleIO.encode(base)

    @Test fun `whitespace and object key order do not change id`() {
        val compact = files.texts.mapValues { (_, text) -> Json.parseToJsonElement(text).toString() }
        val reversed = files.texts.mapValues { (_, text) ->
            JsonObject(Json.parseToJsonElement(text).jsonObject.entries.reversed().associate { it.toPair() }).toString()
        }
        assertEquals(WorldsmithHashUtil.computeGenerationId(files.manifest, compact), WorldsmithHashUtil.computeGenerationId(files.manifest, reversed))
    }

    @Test fun `fixed seed changes id while missing seed remains a random recipe`() {
        val terrain = Json.parseToJsonElement(files.texts.getValue("terrain.json")).jsonObject
        fun content(seed: JsonElement?) = files.texts + ("terrain.json" to JsonObject((terrain - "seed") + (seed?.let { mapOf("seed" to it) } ?: emptyMap())).toString())
        val random = content(null)
        val explicitRandom = content(JsonNull)
        val fixed = content(JsonPrimitive(42))
        assertEquals(WorldsmithHashUtil.computeGenerationId(files.manifest, random), WorldsmithHashUtil.computeGenerationId(files.manifest, explicitRandom))
        assertNotEquals(WorldsmithHashUtil.computeGenerationId(files.manifest, random), WorldsmithHashUtil.computeGenerationId(files.manifest, fixed))
        assertEquals(WorldsmithHashUtil.computeGenerationId(files.manifest, fixed), WorldsmithHashUtil.finalizeManifest(files.manifest, fixed).id)
    }

    @Test fun `explicit default fields and omitted defaults have one semantic identity`() {
        val emptyBlocks = files.texts + ("blocks.json" to "{\"schemaVersion\":1}")
        assertEquals(files.manifest.id, WorldsmithHashUtil.computeGenerationId(files.manifest, emptyBlocks))
    }
}
