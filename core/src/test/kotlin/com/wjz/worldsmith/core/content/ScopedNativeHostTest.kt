package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.draw.DrawStructure
import com.wjz.worldsmith.core.draw.Box
import com.wjz.worldsmith.core.draw.Vec3i
import com.wjz.worldsmith.core.mcp.DrawingExportHost
import com.wjz.worldsmith.core.structure.CompiledStructure
import com.wjz.worldsmith.core.structure.StructureNativeHost
import com.wjz.worldsmith.core.validation.Diagnostic
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScopedNativeHostTest {
    private val blocks = CustomBlockLibrary(blocks = listOf(CustomBlockDefinition("moon", "Moon", textureAsset = "a".repeat(64))))

    @Test fun `legacy native inspection adapters accept empty content but never silently ignore custom blocks`() {
        val host = object : StructureNativeHost {
            override val identity = "fixture"
            override fun query(ids: List<String>, search: String, limit: Int) = JsonObject(emptyMap())
            override fun inspect(geometry: CompiledStructure): List<Diagnostic> = emptyList()
        }
        assertSame(host, host.forContent("realm", CustomBlockLibrary()))
        assertThrows(IllegalArgumentException::class.java) { host.forContent("realm", blocks) }
    }

    @Test fun `single-method drawing exporters retain compatibility but reject unsupported custom scope`() {
        var calls = 0
        val host = DrawingExportHost { calls++; byteArrayOf(1, 2, 3) }
        val drawing = DrawStructure(Box(Vec3i(0,0,0),Vec3i(0,0,0)),emptyList(),emptyMap())
        assertArrayEquals(byteArrayOf(1, 2, 3), host.exportContent(drawing, "realm", CustomBlockLibrary()))
        assertThrows(IllegalArgumentException::class.java) { host.exportContent(drawing, "realm", blocks) }
        assertEquals(1, calls)
    }
}
