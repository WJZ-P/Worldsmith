package com.wjz.worldsmith.core.mcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Templates are only a good start if every one renders in any colour, block faces are solid and icons stand on clear ground. */
class TextureTemplatesTest {
    private fun image(recipe: TextureRecipe) = ImageIO.read(ByteArrayInputStream(TextureRecipes.render(recipe).bytes))

    @Test fun `every template renders as a 16 x 16 texture in its own and other colours`() {
        for (template in TextureTemplate.entries) for ((base, accent) in listOf(template.base to template.accent, "#d64545" to "#8a8780", "#101010" to "#f0f0f0", "#ffffff" to "#000000")) {
            val png = image(TextureTemplates.recipe(template, base, accent, 7))
            assertEquals(16, png.width, "$template"); assertEquals(16, png.height, "$template")
            val opaque = (0 until 16).sumOf { y -> (0 until 16).count { x -> png.getRGB(x, y) ushr 24 == 255 } }
            when {
                template == TextureTemplate.GLASS -> assertTrue(opaque in 16..200, "glass keeps a clear pane: $opaque")
                template.block -> assertEquals(256, opaque, "$template is a solid face in $base")
                else -> {
                    assertTrue(opaque in 30..200, "$template is an icon: $opaque opaque pixels")
                    for ((x, y) in listOf(0 to 15, 15 to 15, 0 to 0)) if (template != TextureTemplate.SWORD || y != 0)
                        assertEquals(0, png.getRGB(x, y) ushr 24, "$template stands on clear ground at $x,$y")
                }
            }
        }
    }

    @Test fun `a ramp steps up in value from outline to highlight`() {
        for (hex in listOf("#3fb3d6", "#a2784a", "#8e8d88", "#d64545", "#4f8a34")) {
            val ramp = TextureTemplates.ramp(hex)
            val values = ramp.map { val c = Color.decode(it); Color.RGBtoHSB(c.red, c.green, c.blue, null)[2] }
            // Bright bases reach full value at the light step; there the highlight differs by saturation.
            assertTrue(values[0] < values[1] && values[1] < values[2] && values[2] <= values[3] && values[3] <= values[4], "$hex: $values")
            assertEquals(5, ramp.toSet().size, "$hex: five distinct tones $ramp")
            assertEquals(hex, ramp[2], "the base is kept exactly")
        }
    }

    @Test fun `a seed varies the pattern and repeats it exactly`() {
        fun pixels(seed: Long) = TextureRecipes.render(TextureTemplates.recipe(TextureTemplate.COBBLESTONE, seed = seed)).bytes.toList()
        assertEquals(pixels(3), pixels(3))
        assertNotEquals(pixels(3), pixels(4))
        assertThrows(IllegalArgumentException::class.java) { TextureTemplates.recipe(TextureTemplate.GEM, "red", "#000000") }
    }
}
