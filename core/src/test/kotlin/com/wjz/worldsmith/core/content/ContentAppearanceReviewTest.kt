package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.mcp.TextureRecipes
import com.wjz.worldsmith.core.mcp.TextureTemplate
import com.wjz.worldsmith.core.mcp.TextureTemplates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Random
import javax.imageio.ImageIO

/** Review hints read from texture metrics: pointed at the common mistakes, quiet on textures that tile and read. */
class ContentAppearanceReviewTest {
    @Suppress("UNCHECKED_CAST")
    private fun codes(image: BufferedImage, icon: Boolean) =
        (ContentAppearancePreview.metrics(image, icon)["review"] as List<Map<String, Any>>).map { it["code"] }

    private fun template(t: TextureTemplate) = ImageIO.read(ByteArrayInputStream(TextureRecipes.render(TextureTemplates.recipe(t, seed = 3)).bytes))
    private fun image(f: (Int, Int) -> Int) = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { for (y in 0 until 16) for (x in 0 until 16) it.setRGB(x, y, f(x, y)) }

    @Test fun `seamless templates and clear icons raise nothing`() {
        for (t in listOf(TextureTemplate.PLANKS, TextureTemplate.STONE_BRICKS, TextureTemplate.COBBLESTONE, TextureTemplate.SAND, TextureTemplate.LEAVES, TextureTemplate.LOG_SIDE))
            assertEquals(emptyList<Any>(), codes(template(t), icon = false), "$t")
        for (t in TextureTemplate.entries.filterNot { it.block }) assertEquals(emptyList<Any>(), codes(template(t), icon = true), "$t")
    }

    @Test fun `a square icon, a face that does not wrap and a noisy texture are pointed out`() {
        assertEquals(listOf("ICON_FILLS_TILE"), codes(image { _, _ -> 0xff8a5a3c.toInt() }, icon = true))
        // A shade ramp across the face meets its opposite edge with a jump no neighbouring columns make.
        assertEquals(listOf("FACE_EDGE_SEAM"), codes(image { x, _ -> 0xff000000.toInt() or (0x30 + x * 10) * 0x010101 }, icon = false))
        val random = Random(1)
        assertEquals(listOf("TEXTURE_MANY_COLOURS"), codes(image { _, _ -> 0xff000000.toInt() or random.nextInt(0xffffff) }, icon = false))
    }
}
