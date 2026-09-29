package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.creatureauthoring.CreatureBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural motion shared by the game and the offline preview. The roles that
 * existed before must move exactly as they did; the new ones must do what a
 * viewer expects of a wing, a jaw, a body and a jointed tail.
 */
class CreaturePoseTest {
    private fun bone(id: String, role: CreatureBoneRole, parent: String? = null) = CreatureBone(id, parent, role = role)
    private fun frame(age: Float, walk: Float = 0f, speed: Float = 0f, action: Int = CreatureCombatState.IDLE.ordinal, airborne: Boolean = false) =
        CreaturePose.Frame(age, walk, speed, 0f, 0f, 0L, action, airborne)

    @Test
    fun `a one-piece tail and walking legs move exactly as before`() {
        val tail = bone("tail", CreatureBoneRole.TAIL)
        val f = frame(age = 17f, walk = 3f, speed = 0.6f)
        val phase = 3f * 0.6662f
        val expected = sin(17f * 0.08f) * 0.16f + cos(phase) * (0.6f * 1.2f) * 0.15f
        assertEquals(expected, CreaturePose.rotation(tail, f).y(), 1e-5f)
        assertEquals(cos(phase) * 0.72f, CreaturePose.rotation(bone("leg", CreatureBoneRole.LEG_LEFT), f).x(), 1e-5f)
    }

    @Test
    fun `a jointed tail travels as a wave`() {
        val bones = listOf(bone("body", CreatureBoneRole.NONE), bone("t1", CreatureBoneRole.TAIL, "body"),
            bone("t2", CreatureBoneRole.TAIL, "t1"), bone("t3", CreatureBoneRole.TAIL, "t2"))
        val chains = CreaturePose.chains(bones)
        assertEquals(listOf(0, 1, 2), listOf("t1", "t2", "t3").map { chains.getValue(it) })
        val f = frame(age = 40f)
        val swings = (0..2).map { CreaturePose.rotation(bones[it + 1], f, it).y() }
        assertTrue(swings.distinct().size == 3, "each segment lags the one before it: $swings")
    }

    @Test
    fun `wings beat in flight and fold back along the body at rest`() {
        val left = bone("wl", CreatureBoneRole.WING_LEFT); val right = bone("wr", CreatureBoneRole.WING_RIGHT)
        val beats = (0..20).map { CreaturePose.rotation(left, frame(age = it * 0.3f, airborne = true)).z() }
        assertTrue(beats.max() - beats.min() > 1.4f, "a real wingbeat, not a twitch: $beats")
        val f = frame(age = 5f, airborne = true)
        assertEquals(-CreaturePose.rotation(left, f).z(), CreaturePose.rotation(right, f).z(), 1e-6f, "the two wings beat together, mirrored")

        val resting = frame(age = 5f)
        assertTrue(abs(CreaturePose.rotation(left, resting).y()) > 1.2f, "folded back along the body")
        assertEquals(-CreaturePose.rotation(left, resting).y(), CreaturePose.rotation(right, resting).y(), 1e-6f)
    }

    @Test
    fun `a flier tucks its legs instead of walking on air`() {
        val leg = bone("leg", CreatureBoneRole.LEG_RIGHT)
        val tucked = (0..8).map { CreaturePose.rotation(leg, frame(age = 3f, walk = it.toFloat(), speed = 1f, airborne = true)).x() }
        assertTrue(tucked.all { abs(it - 0.7f) < 1e-6f }, tucked.toString())
    }

    @Test
    fun `the jaw and body commit to a strike`() {
        val jaw = bone("jaw", CreatureBoneRole.JAW); val body = bone("body", CreatureBoneRole.BODY)
        fun x(b: CreatureBone, state: CreatureCombatState) = CreaturePose.rotation(b, frame(age = 0f, action = state.ordinal)).x()
        assertTrue(x(jaw, CreatureCombatState.IDLE) < x(jaw, CreatureCombatState.WINDUP))
        assertTrue(x(jaw, CreatureCombatState.WINDUP) < x(jaw, CreatureCombatState.STRIKE))
        assertTrue(x(body, CreatureCombatState.WINDUP) < x(body, CreatureCombatState.IDLE), "rears back on windup")
        assertTrue(x(body, CreatureCombatState.STRIKE) > x(body, CreatureCombatState.IDLE), "lunges on the strike")
        assertTrue("fly" in CreaturePose.POSES)
    }

    @Test
    fun `the new roles need schema 7 and mirror left to right`() {
        val builder = CreatureBuilder.create("heron", "Heron", CreatureCategory.PASSIVE)
        builder.bone("body", null, 0f, 14f, 0f).role(CreatureBoneRole.BODY).cube("torso", -3f, -3f, -4f, 6, 5, 8).end()
        builder.bone("wing", "body", -3f, -2f, 0f).role(CreatureBoneRole.WING_LEFT).cube("plane", -8f, 0f, -2f, 8, 1, 6).end()
        builder.mirrorSubtree("wing", "wing_r")
        assertEquals(7, builder.recipe().schemaVersion)
        val definition = builder.build("a".repeat(64)).definition
        assertEquals(CreatureBoneRole.WING_RIGHT, definition.model.bones.single { it.id == "wing_r" }.role)
        assertEquals(7, CreatureSounds.requiredSchema(definition))

        val old = CustomCreatureValidator.validate(CreatureLibrary(6, listOf(definition))).map { it.message }
        assertTrue(old.any { "Bone role BODY requires creature schemaVersion 7" in it }, old.toString())
        val drives = CreatureDefinition("walker", "Walker", CreatureCategory.PASSIVE, definition.model.copy(bones = listOf(definition.model.bones.first().copy(role = CreatureBoneRole.NONE))),
            behavior = CreatureBehavior(drives = CreatureDrives(herds = true)))
        assertEquals(7, CreatureSounds.requiredSchema(drives), "drives count toward the schema a creature needs")
    }
}
