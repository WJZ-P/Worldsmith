package com.wjz.worldsmith.core.creatureauthoring

import kotlinx.serialization.Serializable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** A surface texture laid over a material's base colour. */
@Serializable enum class SkinPattern { NONE, SPECKLE, SPOTS, STRIPES, SCALES, FUR }

/**
 * How one material role of a creature is painted. [shade] lightens tops and
 * darkens undersides and the lower part of each side, [grain] is a variation in
 * 2x2 clusters that keeps a flat colour from reading as plastic without
 * dithering it, and a [pattern] is drawn in [patternColor] - by default a tone
 * of the base, subtle for fur and scales, strong for spots and stripes. A
 * [belly] colour countershades: it fills the underside and the lowest
 * [bellyHeight] of each side, plain, the way most animals are paler beneath.
 */
@Serializable data class SkinMaterial @JvmOverloads constructor(
    val base: String,
    val shade: Float = 0.2f,
    val grain: Float = 0.035f,
    val pattern: SkinPattern = SkinPattern.NONE,
    val patternColor: String? = null,
    val patternDensity: Float = 0.25f,
    val belly: String? = null,
    val bellyHeight: Float = 0.35f,
)

/**
 * Pixel art stamped onto one face of one cube: eyes, a mouth, markings.
 * [x] and [y] are face-local, x to the right and y down, as the face is drawn in
 * the UV guide; '.' leaves the painted skin underneath.
 */
@Serializable data class SkinDecal(
    val bone: String,
    val cube: String,
    val face: String,
    val x: Int = 0,
    val y: Int = 0,
    val rows: List<String>,
    val colors: Map<String, String>,
)

/** A whole creature skin: one material per role used by the model, then decals. */
@Serializable data class CreatureSkin(
    val materials: Map<String, SkinMaterial>,
    val decals: List<SkinDecal> = emptyList(),
    val seed: Long = 0,
)

/**
 * Paints a creature atlas from its UV layout. The layout already knows where
 * every face of every cube lies, so an author says what each material looks
 * like and where the eyes go, rather than filling rectangles in atlas pixels.
 */
object CreatureSkins {
    const val MAX_DECALS = 64
    private val COLOR = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
    private val FACES = setOf("top", "bottom", "left", "front", "right", "back")

    @JvmStatic fun validate(layout: CreatureUvLayout, skin: CreatureSkin): List<String> = buildList {
        val roles = layout.islands.map { it.materialRole }.toSortedSet()
        (roles - skin.materials.keys).forEach { add("Material role '$it' is used by the model but has no skin material") }
        (skin.materials.keys - roles).forEach { add("Skin material '$it' is not a material role of this model") }
        skin.materials.forEach { (role, m) ->
            if (!COLOR.matches(m.base)) add("$role.base must be #RRGGBB")
            if (m.patternColor != null && !COLOR.matches(m.patternColor)) add("$role.patternColor must be #RRGGBB")
            if (m.belly != null && !COLOR.matches(m.belly)) add("$role.belly must be #RRGGBB")
            if (!m.bellyHeight.isFinite() || m.bellyHeight !in 0f..1f) add("$role.bellyHeight is 0..1")
            if (!m.shade.isFinite() || m.shade !in 0f..0.6f) add("$role.shade is 0..0.6")
            if (!m.grain.isFinite() || m.grain !in 0f..0.3f) add("$role.grain is 0..0.3")
            if (!m.patternDensity.isFinite() || m.patternDensity !in 0f..1f) add("$role.patternDensity is 0..1")
        }
        if (skin.decals.size > MAX_DECALS) add("At most $MAX_DECALS decals")
        skin.decals.forEachIndexed { i, d ->
            val face = face(layout, d)
            when {
                d.face !in FACES -> add("decals[$i].face is one of $FACES")
                face == null -> add("decals[$i] names no cube '${d.bone}/${d.cube}' in this model")
                d.rows.isEmpty() || d.rows.size > 64 || d.rows.any { it.length != d.rows.first().length } || d.rows.first().isEmpty() ->
                    add("decals[$i].rows are 1..64 rows of equal, non-empty length")
                d.x < 0 || d.y < 0 || d.x + d.rows.first().length > face.width || d.y + d.rows.size > face.height ->
                    add("decals[$i] does not fit the ${face.width}x${face.height} ${d.face} face at (${d.x},${d.y})")
            }
            d.colors.forEach { (glyph, color) ->
                if (glyph.length != 1 || glyph == ".") add("decals[$i] glyphs are single characters other than '.'")
                if (!COLOR.matches(color)) add("decals[$i] colour '$color' must be #RRGGBB or #RRGGBBAA")
            }
            d.rows.flatMap { it.toList() }.filter { it != '.' && it.toString() !in d.colors }.distinct()
                .forEach { add("decals[$i] glyph '$it' has no colour") }
        }
    }

    @JvmStatic fun paint(layout: CreatureUvLayout, skin: CreatureSkin): ByteArray {
        val problems = validate(layout, skin)
        require(problems.isEmpty()) { problems.take(12).joinToString("; ") }
        val image = BufferedImage(layout.textureWidth, layout.textureHeight, BufferedImage.TYPE_INT_ARGB)
        layout.islands.filter { it.sharedWith == null }.forEach { island ->
            val material = skin.materials.getValue(island.materialRole)
            val base = rgb(material.base)
            val accent = material.patternColor?.let(::rgb) ?: scale(base, when (material.pattern) {
                SkinPattern.FUR -> 0.84; SkinPattern.SCALES -> 0.78; SkinPattern.SPECKLE -> 0.74; else -> 0.6
            })
            val salt = skin.seed xor island.id.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong()
            val under = material.belly?.let(::rgb)
            island.faces.forEach { face ->
                // The first belly row of a side is half way between, so the change reads as a soft edge.
                val firstBelly = if (face.height <= 1) face.height else Math.ceil((1 - material.bellyHeight) * (face.height - 1).toDouble()).toInt()
                for (fy in 0 until face.height) for (fx in 0 until face.width) {
                    val px = face.x + fx; val py = face.y + fy
                    val belly = under != null && face.face != "top" && (face.face == "bottom" || fy >= firstBelly)
                    val marked = !belly && pattern(material, face.width, face.height, fx, fy, salt)
                    val light = light(face.face, fy, face.height, material.shade) + (noise(salt, px shr 1, py shr 1) - 0.5) * 2 * material.grain
                    val colour = when {
                        belly && face.face != "bottom" && fy == firstBelly -> blend(base, under!!)
                        belly -> under!!
                        marked -> accent
                        else -> base
                    }
                    image.setRGB(px, py, 0xff shl 24 or scale(colour, 1 + light))
                }
            }
        }
        skin.decals.forEach { d ->
            val face = requireNotNull(face(layout, d))
            d.rows.forEachIndexed { y, row -> row.forEachIndexed { x, glyph ->
                if (glyph != '.') image.setRGB(face.x + d.x + x, face.y + d.y + y, argb(d.colors.getValue(glyph.toString())))
            } }
        }
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()
    }

    /** Decals on a mirrored limb land on the island its source shares. */
    private fun face(layout: CreatureUvLayout, d: SkinDecal): UvFaceRect? {
        val island = layout.islands.firstOrNull { it.boneId == d.bone && it.cubeId == d.cube } ?: return null
        val source = island.sharedWith?.let { id -> layout.islands.firstOrNull { it.id == id } } ?: island
        return source.faces.firstOrNull { it.face == d.face }
    }

    /** Light from above: tops lift, undersides fall, sides grade from lit shoulder to shadowed belly. */
    private fun light(face: String, fy: Int, height: Int, shade: Float): Double = when (face) {
        "top" -> shade * 0.5
        "bottom" -> -shade.toDouble()
        else -> {
            val t = if (height <= 1) 0.5 else fy.toDouble() / (height - 1)
            shade * (0.35 * (1 - t) - 0.55 * t)
        }
    }

    private fun pattern(m: SkinMaterial, w: Int, h: Int, fx: Int, fy: Int, salt: Long): Boolean {
        val d = m.patternDensity.toDouble()
        return when (m.pattern) {
            SkinPattern.NONE -> false
            SkinPattern.SPECKLE -> noise(salt + 11, fx, fy) < d * 0.5
            SkinPattern.SPOTS -> {
                // One jittered disc per 4x4 cell, present with the density's chance.
                val cx = Math.floorDiv(fx, 4); val cy = Math.floorDiv(fy, 4)
                noise(salt + 23, cx, cy) < d && run {
                    val ox = cx * 4 + 1 + (noise(salt + 29, cx, cy) * 2).toInt(); val oy = cy * 4 + 1 + (noise(salt + 31, cx, cy) * 2).toInt()
                    (fx - ox) * (fx - ox) + (fy - oy) * (fy - oy) <= 1
                }
            }
            SkinPattern.STRIPES -> {
                // Bands across the face, wavering a pixel from row to row.
                val period = (2 + (1 - d) * 5).roundToInt()
                Math.floorMod(fx + (noise(salt + 37, 0, fy) * 2).toInt(), period) < maxOf(1, (period * 0.4).roundToInt())
            }
            SkinPattern.SCALES -> fy % 2 == 1 && Math.floorMod(fx + fy / 2, 2) == 0 && noise(salt + 41, fx, fy) < 0.4 + d * 0.6
            SkinPattern.FUR -> {
                // Short vertical strands, each column starting at its own height.
                val start = (noise(salt + 43, fx, 0) * maxOf(1, h)).toInt()
                val length = 1 + (noise(salt + 47, fx, 1) * 3).toInt()
                noise(salt + 53, fx, 2) < d && Math.floorMod(fy - start, maxOf(4, h)) < length
            }
        }
    }

    private fun noise(salt: Long, x: Int, y: Int): Double {
        var v = salt + x * 0x9E3779B97F4A7C15uL.toLong() + y * 0xC2B2AE3D27D4EB4FuL.toLong()
        v = (v xor (v ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        v = (v xor (v ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return ((v xor (v ushr 31)) ushr 11) / 9007199254740992.0
    }

    private fun rgb(text: String): Int = text.substring(1, 7).toInt(16)
    private fun blend(a: Int, b: Int): Int = (((a shr 16 and 0xff) + (b shr 16 and 0xff)) / 2 shl 16) or (((a shr 8 and 0xff) + (b shr 8 and 0xff)) / 2 shl 8) or (((a and 0xff) + (b and 0xff)) / 2)
    private fun argb(text: String): Int = if (text.length == 7) (0xff shl 24) or rgb(text)
        else (text.substring(7, 9).toInt(16) shl 24) or rgb(text)

    private fun scale(color: Int, factor: Double): Int {
        fun channel(shift: Int) = (((color shr shift) and 0xff) * factor).roundToInt().coerceIn(0, 255)
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
