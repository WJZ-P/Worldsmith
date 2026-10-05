package com.wjz.worldsmith.core.mcp

import kotlinx.serialization.Serializable
import java.awt.Color
import java.util.Random
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A starting texture: a tiling block face or a 16 x 16 item icon. */
@Serializable enum class TextureTemplate(val block: Boolean, val base: String, val accent: String) {
    PLANKS(true, "#a2784a", "#6b4a2b"),
    STONE_BRICKS(true, "#8e8d88", "#5e5d59"),
    COBBLESTONE(true, "#8a8780", "#5a5853"),
    SMOOTH_STONE(true, "#9d9d98", "#6d6d68"),
    ORE(true, "#3fb3d6", "#82817c"),
    LOG_SIDE(true, "#6b4a2b", "#4a3220"),
    LOG_TOP(true, "#b48d58", "#5c3f25"),
    LEAVES(true, "#4f8a34", "#2f5a20"),
    SAND(true, "#d8c88e", "#b8a46c"),
    METAL_BLOCK(true, "#b9bdc5", "#7d828c"),
    GLASS(true, "#c4e2ea", "#8fb8c4"),
    CRYSTAL(true, "#9c5bd6", "#5a2f8a"),
    GEM(false, "#3fb3d6", "#7a5230"),
    INGOT(false, "#e0b93a", "#7a5230"),
    SWORD(false, "#c9d1da", "#6b4a2b"),
    POTION(false, "#d64545", "#8a5a35"),
    ORB(false, "#9c5bd6", "#7a5230"),
    KEY(false, "#e0b93a", "#7a5230"),
    COIN(false, "#e0b93a", "#7a5230"),
    SHARD(false, "#3fb3d6", "#7a5230"),
}

/**
 * Templates for the textures a world needs most, as editable recipes. Drawing
 * a convincing block face or item icon from noise and hand-typed rows is where
 * models most often fall short: flat colour, no light direction, outlines that
 * fight the shape. A template starts from a five-step ramp of each colour,
 * shadows leaning cool and highlights warm as pixel artists shade, and lays a
 * shape that already reads: boards with seams and joints, stones lit from the
 * top left, an item silhouette with a dark rim. Block faces tile without a
 * seam. The palette is indices 0..4 for the base ramp (outline, dark, base,
 * light, highlight), 5..9 for the accent ramp and 10 for a half-step between
 * base and dark.
 */
object TextureTemplates {
    private val HEX = Regex("#[0-9a-fA-F]{6}")

    @JvmStatic @JvmOverloads
    fun recipe(template: TextureTemplate, base: String = template.base, accent: String = template.accent, seed: Long = 0): TextureRecipe {
        require(HEX.matches(base) && HEX.matches(accent)) { "Template colours are #RRGGBB" }
        val palette = ramp(base) + ramp(accent) + mix(base, 0.86)
        return TextureRecipe(width = 16, height = 16, palette = palette, seed = seed, operations = operations(template, Random(seed)))
    }

    /** Outline, dark, base, light and highlight: value steps with the hue leaning cool in shadow and warm in light. */
    @JvmStatic fun ramp(hex: String): List<String> {
        val rgb = hex.substring(1).toInt(16)
        val hsb = Color.RGBtoHSB(rgb shr 16 and 255, rgb shr 8 and 255, rgb and 255, null)
        fun toward(target: Float, amount: Float): Float {
            val delta = ((target - hsb[0] + 1.5f) % 1f) - 0.5f
            return ((hsb[0] + delta * amount) % 1f + 1f) % 1f
        }
        fun colour(h: Float, s: Float, b: Float) = "#%06x".format(Color.HSBtoRGB(h, s.coerceIn(0f, 1f), b.coerceIn(0f, 1f)) and 0xffffff)
        val (s, b) = hsb[1] to hsb[2]
        return listOf(
            colour(toward(0.66f, 0.12f), s * 1.05f + 0.05f, b * 0.35f),
            colour(toward(0.66f, 0.06f), s * 1.05f, b * 0.68f),
            hex.lowercase(),
            colour(toward(0.15f, 0.05f), s * 0.85f, b * 1.18f + 0.04f),
            colour(toward(0.15f, 0.10f), s * 0.55f, b * 1.35f + 0.15f),
        )
    }

    /**
     * The recipe's PNG enlarged for review: a block face tiled 3 x 3 so seams
     * show, an item icon alone. Each texel becomes a square of `scale` pixels.
     */
    @JvmStatic fun preview(template: TextureTemplate, png: ByteArray, scale: Int = 4): ByteArray {
        val tile = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png)) ?: error("PNG did not decode")
        val repeat = if (template.block) 3 else 1
        val image = java.awt.image.BufferedImage(tile.width * repeat * scale, tile.height * repeat * scale, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, tile.getRGB(x / scale % tile.width, y / scale % tile.height))
        return java.io.ByteArrayOutputStream().also { check(javax.imageio.ImageIO.write(image, "png", it)) }.toByteArray()
    }

    private fun mix(hex: String, factor: Double): String {
        val rgb = hex.substring(1).toInt(16)
        fun c(shift: Int) = ((rgb shr shift and 255) * factor).roundToInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(c(16), c(8), c(0))
    }

    private fun fill(color: Int, x: Int = 0, y: Int = 0, w: Int? = null, h: Int? = null) = TextureOperation("fill", x = x, y = y, width = w, height = h, color = color)
    private fun line(x: Int, y: Int, x2: Int, y2: Int, color: Int) = TextureOperation("line", x = x, y = y, x2 = x2, y2 = y2, color = color)
    private fun noise(probability: Double, vararg colors: Int) = TextureOperation("noise", colors = colors.toList(), probability = probability)
    private fun grain(amount: Double, cell: Int) = TextureOperation("grain", amount = amount, cellSize = cell)
    private fun stamp(rows: List<String>, glyphs: Map<String, Int>, x: Int = 0, y: Int = 0) = TextureOperation("stamp", x = x, y = y, rows = rows, glyphs = glyphs)

    /** Glyphs of item rows: the base ramp in lower case, the accent ramp in upper case. */
    private val ITEM_GLYPHS = mapOf("o" to 0, "d" to 1, "b" to 2, "l" to 3, "h" to 4, "O" to 5, "D" to 6, "B" to 7, "L" to 8, "H" to 9)

    private fun operations(template: TextureTemplate, random: Random): List<TextureOperation> = when (template) {
        TextureTemplate.PLANKS -> buildList {
            add(fill(2))
            // Four boards, each with a dark seam under it, a lit top edge and an end joint.
            val joints = (0 until 4).map { k -> (k * 5 + 2 + random.nextInt(3)) % 16 }
            for (k in 0 until 4) {
                add(line(0, 4 * k + 3, 15, 4 * k + 3, 1))
                val from = random.nextInt(6)
                add(line(from, 4 * k, from + 6 + random.nextInt(4), 4 * k, 3))
                val j = joints[k]
                add(line(j, 4 * k, j, 4 * k + 2, 1))
                add(line((j + 1) % 16, 4 * k, (j + 1) % 16, 4 * k + 2, 3))
            }
            add(grain(0.045, 2))
        }
        TextureTemplate.STONE_BRICKS -> listOf(
            TextureOperation("bricks", cellSize = 8, scale = 16, color = 1, colors = listOf(2, 2, 10), highlight = 3),
            grain(0.05, 2),
        )
        TextureTemplate.COBBLESTONE -> listOf(fill(1), stamp(cobbles(random), mapOf("m" to 1, "a" to 2, "c" to 10, "h" to 3)), grain(0.04, 1))
        TextureTemplate.SMOOTH_STONE -> listOf(fill(2), noise(0.08, 10), TextureOperation("bevel", highlight = 3, shadow = 1), grain(0.03, 2))
        TextureTemplate.ORE -> buildList {
            add(fill(7)); add(noise(0.22, 6, 8)); add(grain(0.04, 1))
            // Ore set into stone: dark-rimmed pieces with a lit facet, never touching the tile edge.
            val placed = mutableListOf<Pair<Int, Int>>()
            var tries = 0
            while (placed.size < 4 && tries++ < 64) {
                val x = 1 + random.nextInt(11); val y = 1 + random.nextInt(11)
                if (placed.any { (px, py) -> kotlin.math.abs(px - x) < 5 && kotlin.math.abs(py - y) < 5 }) continue
                placed += x to y
                val piece = if (random.nextBoolean()) listOf(".dd.", "dhbd", "dbbd", ".dd.") else listOf(".d..", "dhd.", "dbbd", ".dd.")
                add(stamp(piece, mapOf("d" to 1, "b" to 2, "h" to 4), x, y))
            }
        }
        TextureTemplate.LOG_SIDE -> buildList {
            add(fill(2))
            // Bark grooves: dark vertical runs, a lit ridge beside some.
            for (x in 0 until 16) {
                if (random.nextInt(3) == 0) continue
                val top = random.nextInt(8); val length = 4 + random.nextInt(8)
                add(line(x, top, x, minOf(15, top + length), if (x % 3 == 0) 0 else 1))
                if (random.nextInt(3) == 0 && x + 1 < 16) add(line(x + 1, top + 1, x + 1, minOf(15, top + 3), 3))
            }
            add(grain(0.05, 1))
        }
        TextureTemplate.LOG_TOP -> listOf(stamp(rings(), mapOf("o" to 6, "B" to 7, "d" to 1, "b" to 2, "l" to 3)), noise(0.05, 10), grain(0.03, 1))
        TextureTemplate.LEAVES -> listOf(fill(2), noise(0.24, 1), noise(0.18, 3), noise(0.06, 0), noise(0.03, 4), grain(0.05, 2))
        TextureTemplate.SAND -> listOf(fill(2), noise(0.18, 3), noise(0.12, 10), noise(0.04, 4), noise(0.03, 1), grain(0.02, 1))
        TextureTemplate.METAL_BLOCK -> buildList {
            add(fill(2)); add(TextureOperation("bevel", highlight = 3, shadow = 1))
            add(line(1, 7, 14, 7, 1)); add(line(1, 8, 14, 8, 3))
            for ((x, y) in listOf(2 to 2, 13 to 2, 2 to 12, 13 to 12)) { add(fill(4, x, y, 1, 1)); add(fill(1, x, y + 1, 1, 1)) }
            add(grain(0.02, 1))
        }
        TextureTemplate.GLASS -> listOf(
            // A lit frame and two streaks; the pane between stays clear, so it works as cutout glass.
            line(0, 0, 15, 0, 3), line(0, 0, 0, 15, 3), line(0, 15, 15, 15, 1), line(15, 0, 15, 15, 1),
            line(3, 6, 6, 3, 4), line(3, 9, 9, 3, 4), line(10, 13, 13, 10, 3),
        )
        TextureTemplate.CRYSTAL -> listOf(stamp(facets(), mapOf("o" to 0, "d" to 1, "b" to 2, "l" to 3, "h" to 4, "c" to 10)), grain(0.03, 1))
        else -> listOf(stamp(ITEMS.getValue(template), ITEM_GLYPHS))
    }

    /** Stones round toroidal Voronoi seeds: mortar between them, lit on the top left, shaded on the bottom right. */
    private fun cobbles(random: Random): List<String> {
        val seeds = List(8) { random.nextInt(16) to random.nextInt(16) }
        val tones = List(seeds.size) { if (random.nextInt(3) == 0) 'c' else 'a' }
        fun wrap(d: Int) = minOf(Math.floorMod(d, 16), 16 - Math.floorMod(d, 16))
        fun owner(x: Int, y: Int): Int {
            val px = Math.floorMod(x, 16); val py = Math.floorMod(y, 16)
            return seeds.indices.minBy { i -> val dx = wrap(px - seeds[i].first); val dy = wrap(py - seeds[i].second); sqrt((dx * dx + dy * dy).toDouble()) + i * 1e-6 }
        }
        val cells = Array(16) { y -> IntArray(16) { x -> owner(x, y) } }
        fun at(x: Int, y: Int) = cells[Math.floorMod(y, 16)][Math.floorMod(x, 16)]
        return List(16) { y ->
            String(CharArray(16) { x ->
                val c = at(x, y)
                when {
                    at(x + 1, y) != c || at(x, y + 1) != c -> 'm'
                    at(x - 1, y) != c || at(x, y - 1) != c -> 'h'
                    at(x + 2, y) != c || at(x, y + 2) != c -> 'c'
                    else -> tones[c]
                }
            })
        }
    }

    /** Growth rings round the pith, inside a ring of bark. */
    private fun rings(): List<String> = List(16) { y ->
        String(CharArray(16) { x ->
            val ring = floor(maxOf(kotlin.math.abs(x - 7.5), kotlin.math.abs(y - 7.5))).toInt()
            when {
                ring >= 7 -> 'o'
                ring == 6 -> 'B'
                ring <= 0 -> 'd'
                ring % 2 == 1 -> 'l'
                else -> 'b'
            }
        })
    }

    /** A cut face: a lit table in the middle, facets falling away from it, lit toward the top left. */
    private fun facets(): List<String> = List(16) { y ->
        String(CharArray(16) { x ->
            val u = x - 7.5; val v = y - 7.5
            val diamond = kotlin.math.abs(u) + kotlin.math.abs(v)
            when {
                diamond < 4.5 -> if (u + v < -2.5) 'h' else 'l'
                diamond < 5.5 -> 'd'
                x == 0 || y == 0 -> 'l'
                x == 15 || y == 15 -> 'd'
                u < 0 && v < 0 -> 'l'
                u > 0 && v > 0 -> 'c'
                else -> 'b'
            }
        })
    }

    private val ITEMS = mapOf(
        TextureTemplate.GEM to listOf(
            "................", "................", "....oooooooo....", "...ohhlllllbo...",
            "..ohlllllllbdo..", ".ollllllllbbbdo.", ".ollllllbbbbbdo.", ".odllllbbbbbddo.",
            "..odlllbbbbddo..", "...odllbbbddo...", "....odlbbddo....", ".....odbbdo.....",
            "......obdo......", ".......oo.......", "................", "................"),
        TextureTemplate.INGOT to listOf(
            "................", "................", "................", "................",
            "......oooooooo..", ".....ohhhhhhllo.", "....ohlllllllbo.", "...ooooooooooddo",
            "...obbbbbbbbbodo", "...obbbbbbbbbodo", "...oddddddddddoo", "...ooooooooooo..",
            "................", "................", "................", "................"),
        TextureTemplate.SWORD to listOf(
            ".............o..", "............oho.", "...........ohlbo", "..........ohlbo.",
            ".........ohlbo..", "........ohlbo...", ".......ohlbo....", "...oo.ohlbo.....",
            "..oLBohlbo......", "...oLBlbo.......", "....oLBo........", "...oDoLBo.......",
            ".ooDo.oLBo......", "oBDo...oo.......", "oLBo............", ".oo............."),
        TextureTemplate.POTION to listOf(
            "................", "......oooo......", "......oBBo......", "......oDDo......",
            ".......oo.......", "......ohho......", ".....oh..lo.....", "....oh....lo....",
            "...ohllllllbo...", "...ollllllbbo...", "..olbbbbbbbbdo..", "..olbbbbbbbbdo..",
            "..odbbbbbbbddo..", "...odddddddoo...", "....oooooooo....", "................"),
        TextureTemplate.ORB to listOf(
            "................", "................", ".....oooooo.....", "....ohhlllbo....",
            "...ohhllllbbo...", "..ohhllllllbdo..", "..ollllllllbdo..", "..olllllllbbdo..",
            "..olllllbbbbdo..", "..olllbbbbbddo..", "...odbbbbbddo...", "....oddddddo....",
            ".....oooooo.....", "................", "................", "................"),
        TextureTemplate.KEY to listOf(
            "................", "................", "...oooo.........", "..ohllbo........",
            ".ohooooho.......", ".oho..olo.......", ".obo..olo.......", ".obooooloooooo..",
            "..oddlllllllllo.", "...oooooolooblo.", "........obooobo.", "........oo..oo..",
            "................", "................", "................", "................"),
        TextureTemplate.COIN to listOf(
            "................", "................", ".....oooooo.....", "....ohhllllo....",
            "...ohlbbbbldo...", "..ohlbllllbbdo..", "..olbllllllbdo..", "..olbllbbllbdo..",
            "..olbllbbllbdo..", "..olbllllllbdo..", "..olbbllllbddo..", "...odbbbbbddo...",
            "....oddddddo....", ".....oooooo.....", "................", "................"),
        TextureTemplate.SHARD to listOf(
            "................", "..........oo....", ".........ohlo...", "........ohllo...",
            ".......ohhlbo...", "......ohhllbo...", ".....ohhllbdo...", "....ohhllbbdo...",
            "...ohhllbbddo...", "...ohllbbbdo....", "..ohllbbbddo....", "..olllbbddo.....",
            "..olbbbddo......", "...obbddo.......", "....oddo........", ".....oo........."),
    )
}
