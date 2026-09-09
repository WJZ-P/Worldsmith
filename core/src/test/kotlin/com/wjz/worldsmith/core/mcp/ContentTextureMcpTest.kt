package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentAssetValidation
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

class ContentTextureMcpTest {
    private fun fixture() = ContentTextureMcp.pixels(listOf("#FF0000", "#00FF0080", "#0000ffFF"), listOf(listOf(0, 1, 2), listOf(2, 0, 1)))

    @Test fun `pixel grid produces a real bounded PNG with exact RGB and RGBA channels`() {
        val asset = fixture()
        val image = ImageIO.read(ByteArrayInputStream(asset.bytes))
        assertEquals(3, asset.width); assertEquals(2, asset.height)
        assertEquals(0xffff0000.toInt(), image.getRGB(0, 0))
        assertEquals(0x8000ff00.toInt(), image.getRGB(1, 0))
        assertEquals(0xff0000ff.toInt(), image.getRGB(2, 0))
        assertEquals(asset.descriptor.sha256, asset.descriptor.id)
        assertEquals(ContentAssetValidation.hash(asset.bytes), asset.descriptor.id)
        assertEquals("assets/${asset.descriptor.id}.png", asset.descriptor.path)
        assertEquals(asset.bytes.size.toLong(), asset.descriptor.byteLength)
    }

    @Test fun `uploaded and authored bytes share exactly one content addressed identity`() {
        val made = fixture()
        val uploaded = ContentTextureMcp.upload(Base64.getEncoder().encodeToString(made.bytes))
        assertEquals(made.descriptor, uploaded.descriptor)
        assertArrayEquals(made.bytes, uploaded.bytes)
        assertEquals(made.width, uploaded.width)
        assertEquals(made.height, uploaded.height)
        assertEquals(made.descriptor, fixture().descriptor, "Pixel authoring must be deterministic")
    }

    @Test fun `preview returns MCP image content and truthful transient metadata`() {
        val asset = fixture()
        val result = ContentTextureMcp.result(asset)
        assertFalse(result.isError)
        assertEquals(1, result.images.size)
        assertEquals("image/png", result.images.single().mimeType)
        assertArrayEquals(asset.bytes, Base64.getDecoder().decode(result.images.single().data))
        assertEquals(3, result.structuredContent.getValue("width").jsonPrimitive.int)
        assertTrue(result.structuredContent.getValue("verified").jsonPrimitive.boolean)
        assertFalse(result.structuredContent.getValue("persisted").jsonPrimitive.boolean)
        assertFalse(result.structuredContent.getValue("nativeUsageValidated").jsonPrimitive.boolean)
    }

    @Test fun `preview verifies bytes and dimensions instead of trusting a caller constructed result`() {
        val asset = fixture()
        assertThrows(IllegalArgumentException::class.java) { ContentTextureMcp.preview(asset.copy(width = 100)) }
        assertThrows(IllegalArgumentException::class.java) { ContentTextureMcp.describe(asset.copy(descriptor = asset.descriptor.copy(id = "a".repeat(64)))) }
        asset.bytes[asset.bytes.lastIndex] = (asset.bytes.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { ContentTextureMcp.preview(asset) }
    }

    @Test fun `base64 upload rejects disguised nonimages corrupt PNGs and bad transport spelling`() {
        val png = fixture().bytes
        val encoded = Base64.getEncoder().encodeToString(png)
        for (value in listOf("", "%%%", "data:image/png;base64,$encoded", "$encoded\n", Base64.getEncoder().encodeToString("not an image".toByteArray())))
            assertThrows(IllegalArgumentException::class.java) { ContentTextureMcp.upload(value) }
        png[png.lastIndex] = (png.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { ContentTextureMcp.upload(Base64.getEncoder().encodeToString(png)) }
    }

    @Test fun `upload checks encoded length before decoding oversized data`() {
        val oversized = "A".repeat(((ContentAssetValidation.MAX_ASSET_BYTES + 2) / 3) * 4 + 1)
        val error = assertThrows(IllegalArgumentException::class.java) { ContentTextureMcp.upload(oversized) }
        assertTrue(error.message!!.contains("4 MiB"))
    }

    @Test fun `pixel dimensions are authoring bounds rather than implicit block profile rules`() {
        val tiny = ContentTextureMcp.pixels(listOf("#abcdef"), listOf(listOf(0)))
        assertEquals(1, tiny.width); assertEquals(1, tiny.height)
        val max = ContentTextureMcp.pixels(listOf("#abcdef"), List(256) { List(256) { 0 } })
        assertEquals(256, max.width); assertEquals(256, max.height)
        assertThrows(IllegalArgumentException::class.java) { ContentTextureMcp.pixels(listOf("#ffffff"), List(257) { listOf(0) }) }
        assertThrows(IllegalArgumentException::class.java) { ContentTextureMcp.pixels(listOf("#ffffff"), listOf(List(257) { 0 })) }
    }

    @Test fun `empty ragged and out of palette grids fail before rendering`() {
        val palette = listOf("#ffffff")
        for (rows in listOf(emptyList(), listOf(emptyList()), listOf(listOf(0), listOf(0, 0)), listOf(listOf(-1)), listOf(listOf(1))))
            assertThrows(IllegalArgumentException::class.java) { ContentTextureMcp.pixels(palette, rows) }
    }

    @Test fun `palette accepts only exact six or eight channel digits and bounded entries`() {
        for (palette in listOf(emptyList(), listOf("red"), listOf("ffffff"), listOf("#fff"), listOf("#gg0000"), listOf(" #ffffff"), List(257) { "#ffffff" }))
            assertThrows(IllegalArgumentException::class.java) { ContentTextureMcp.pixels(palette, listOf(listOf(0))) }
    }
}
