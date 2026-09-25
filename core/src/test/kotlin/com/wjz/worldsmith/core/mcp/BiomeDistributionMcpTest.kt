package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BiomeDistributionMcpTest {
    @TempDir lateinit var root: Path

    @Test fun `distribution tools expose finite statistical estimates instead of world absence proofs`() {
        val tools = WorldsmithMcpTools(root.resolve("packs"))
        val tool = tools.all().single { it.name == WorldsmithWorkflow.ANALYZE_TOOL }
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val result = tool.handler(buildJsonObject { put("terrain", McpJson.encode(pack.terrain)); put("biomes", McpJson.encode(pack.biomes)) })
        assertTrue(tool.readOnly)
        assertFalse(result.isError, result.text)
        assertEquals(20_000, result.structuredContent.getValue("samples").jsonPrimitive.int)
        assertFalse(result.structuredContent.getValue("actualWorldSampled").jsonPrimitive.boolean)
        assertFalse(result.structuredContent.getValue("absenceProven").jsonPrimitive.boolean)
        assertTrue(result.text.startsWith("Statistical climate-space estimate"))
        assertTrue("not a world map" in result.text)
    }

    @Test fun `saved pack analysis only accepts a managed content identity`() {
        val tool = WorldsmithMcpTools(root.resolve("packs")).all().single { it.name == WorldsmithWorkflow.ANALYZE_TOOL }
        for (id in listOf("../elsewhere", root.toString(), "unknown", "f".repeat(64))) {
            val result = tool.handler(buildJsonObject { put("id", id) })
            assertTrue(result.isError, id)
            assertTrue(result.text.startsWith("No managed pack"))
        }
    }
}
