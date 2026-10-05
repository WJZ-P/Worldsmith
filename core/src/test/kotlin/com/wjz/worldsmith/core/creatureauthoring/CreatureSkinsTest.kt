package com.wjz.worldsmith.core.creatureauthoring

import com.wjz.worldsmith.core.content.CreatureBoneRole
import com.wjz.worldsmith.core.content.CreatureCategory
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * A skin is described by material and painted onto the UV layout, so an author
 * never addresses atlas pixels. These pin the parts a viewer notices: light from
 * above, decals exactly where the face says, and limbs that share their pixels.
 */
class CreatureSkinsTest {
    private val builder = CreatureBuilder.create("stag", "Stag", CreatureCategory.PASSIVE).atlas(64, 64, 1).also { b ->
        b.bone("body", null, 0f, 14f, 0f).cube("torso", -3f, -3f, -6f, 6, 6, 12, "hide").cube("belly", -2f, 3f, -5f, 4, 1, 10, "cream").end()
        b.bone("head", "body", 0f, -3f, -6f).role(CreatureBoneRole.HEAD).cube("skull", -2f, -4f, -4f, 4, 4, 4, "hide").end()
        b.bone("leg", "body", -2f, 3f, -4f).role(CreatureBoneRole.LEG_LEFT).cube("shin", -1f, 0f, -1f, 2, 7, 2, "hoof").end()
        b.mirrorSubtree("leg", "leg_r")
    }
    private val layout = builder.guide().uvLayout
    private val skin = CreatureSkin(mapOf(
        "hide" to SkinMaterial("#8A5A3C", shade = 0.3f, grain = 0f),
        "cream" to SkinMaterial("#E8DCC8"),
        "hoof" to SkinMaterial("#2E2420"),
    ), listOf(SkinDecal("head", "skull", "front", 0, 1, listOf("K..K"), mapOf("K" to "#101010"))))

    private fun image(png: ByteArray) = ImageIO.read(ByteArrayInputStream(png))
    private fun face(bone: String, cube: String, face: String) = layout.islands.first { it.boneId == bone && it.cubeId == cube }.faces.first { it.face == face }
    private fun brightness(rgb: Int) = ((rgb shr 16) and 0xff) + ((rgb shr 8) and 0xff) + (rgb and 0xff)

    @Test
    fun `light comes from above and decals land exactly on their face`() {
        val painted = image(CreatureSkins.paint(layout, skin))
        val top = face("body", "torso", "top"); val bottom = face("body", "torso", "bottom"); val side = face("body", "torso", "left")
        assertTrue(brightness(painted.getRGB(top.x + 2, top.y + 2)) > brightness(painted.getRGB(side.x + 1, side.y + 2)))
        assertTrue(brightness(painted.getRGB(side.x + 1, side.y)) > brightness(painted.getRGB(side.x + 1, side.y + side.height - 1)),
            "a side grades from its lit shoulder to its shadowed underside")
        assertTrue(brightness(painted.getRGB(side.x + 1, side.y + side.height - 1)) > brightness(painted.getRGB(bottom.x + 1, bottom.y + 1)))

        val front = face("head", "skull", "front")
        assertEquals(0xff101010.toInt(), painted.getRGB(front.x, front.y + 1))
        assertEquals(0xff101010.toInt(), painted.getRGB(front.x + 3, front.y + 1))
        assertTrue(painted.getRGB(front.x + 1, front.y + 1) != 0xff101010.toInt(), "'.' keeps the skin beneath")
    }

    @Test
    fun `a belly colour countershades the underside and the foot of each side`() {
        val pale = skin.copy(materials = skin.materials + ("hide" to SkinMaterial("#8A5A3C", shade = 0f, grain = 0f, pattern = SkinPattern.SPOTS, patternDensity = 1f, belly = "#E0D0B0")))
        val painted = image(CreatureSkins.paint(layout, pale))
        val bottom = face("body", "torso", "bottom"); val side = face("body", "torso", "left")
        assertEquals(0xffE0D0B0.toInt(), painted.getRGB(bottom.x + 2, bottom.y + 2), "the underside is the belly, without spots")
        assertEquals(0xffE0D0B0.toInt(), painted.getRGB(side.x + 1, side.y + side.height - 1), "the foot of a side")
        val first = side.height - 1 - (0 until side.height).reversed().takeWhile { painted.getRGB(side.x + 1, side.y + it) == 0xffE0D0B0.toInt() }.count()
        assertTrue(painted.getRGB(side.x + 1, side.y + first) != 0xff8A5A3C.toInt(), "a softened edge row between")
        val top = face("body", "torso", "top")
        assertTrue(painted.getRGB(top.x + 1, top.y + 1) != 0xffE0D0B0.toInt(), "the back stays dark")
        assertTrue(CreatureSkins.validate(layout, pale.copy(materials = pale.materials + ("hide" to SkinMaterial("#8A5A3C", belly = "cream")))).any { "belly" in it })
    }

    @Test
    fun `painting is deterministic and a mirrored limb shares its source's pixels`() {
        assertArrayEquals(CreatureSkins.paint(layout, skin), CreatureSkins.paint(layout, skin))
        val grainy = skin.copy(materials = skin.materials + ("hide" to SkinMaterial("#8A5A3C", grain = 0.1f)))
        assertFalse(CreatureSkins.paint(layout, grainy).contentEquals(CreatureSkins.paint(layout, grainy.copy(seed = 7))))

        val mirrored = layout.islands.first { it.boneId == "leg_r" }
        assertEquals(layout.islands.first { it.boneId == "leg" }.id, mirrored.sharedWith)
        val marked = skin.copy(decals = listOf(SkinDecal("leg_r", "shin", "front", 0, 0, listOf("X"), mapOf("X" to "#FF0000"))))
        val front = face("leg", "shin", "front")
        assertEquals(0xffff0000.toInt(), image(CreatureSkins.paint(layout, marked)).getRGB(front.x, front.y))
    }

    @Test
    fun `a skin that does not fit its model is refused with the reason`() {
        val problems = CreatureSkins.validate(layout, CreatureSkin(
            mapOf("hide" to SkinMaterial("brown"), "fins" to SkinMaterial("#000000")),
            listOf(SkinDecal("head", "skull", "front", 2, 0, listOf("KKK"), mapOf("K" to "#000000")),
                SkinDecal("head", "horn", "front", 0, 0, listOf("K"), mapOf("K" to "#000000")),
                SkinDecal("head", "skull", "front", 0, 0, listOf("Q"), mapOf()))))
        for (fragment in listOf("'cream' is used by the model", "'fins' is not a material role", "hide.base",
            "does not fit the 4x4 front face", "no cube 'head/horn'", "glyph 'Q' has no colour"))
            assertTrue(problems.any { fragment in it }, "$fragment not in $problems")
    }

    @Test
    fun `the contract's skin example decodes through the DTO`() {
        val contract = requireNotNull(javaClass.getResourceAsStream("/prompts/contract/creature_authoring.system.md")).bufferedReader().use { it.readText() }
        val json = Regex("```json\\s*\\n([\\s\\S]*?)\\n```").findAll(contract).map { it.groupValues[1] }.single { "\"materials\"" in it }
        val decoded = WorldsmithJson.decode<CreatureSkin>(json)
        assertEquals(SkinPattern.FUR, decoded.materials.getValue("fur").pattern)
        assertEquals("front", decoded.decals.single().face)
    }
}
