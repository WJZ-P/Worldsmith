package com.wjz.worldsmith.core.creatureauthoring

import com.wjz.worldsmith.core.content.CreatureBoneRole
import com.wjz.worldsmith.core.content.CreatureLibrary
import com.wjz.worldsmith.core.content.CreatureMovement
import com.wjz.worldsmith.core.content.CreaturePose
import com.wjz.worldsmith.core.content.CustomCreatureValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A template is only worth starting from if it compiles, validates and stands on the ground at every scale. */
class CreatureTemplatesTest {
    @Test
    fun `every body plan compiles, validates and stands on the ground line at every scale`() {
        for (plan in CreatureBodyPlan.entries) for (scale in 1..3) {
            val recipe = CreatureTemplates.recipe(plan, "t_${plan.name.lowercase()}", plan.name, scale)
            val definition = CreatureAuthoring.compile(recipe, "a".repeat(64)).definition
            val problems = CustomCreatureValidator.validate(CreatureLibrary(recipe.schemaVersion, listOf(definition)))
            assertTrue(problems.isEmpty(), "$plan x$scale: $problems")
            assertEquals(24.0, lowest(definition.model.bones), 0.02, "$plan x$scale stands on the ground line")
        }
    }

    @Test
    fun `every starter skin covers its template's roles and puts two eyes on the face`() {
        for (plan in CreatureBodyPlan.entries) for (scale in 1..3) {
            val guide = CreatureAuthoring.guide(CreatureTemplates.recipe(plan, "t", "T", scale))
            val skin = CreatureTemplates.skin(plan, scale)
            assertEquals(emptyList<String>(), CreatureSkins.validate(guide.uvLayout, skin), "$plan x$scale")
            assertEquals(2, skin.decals.count { it.cube == "skull" && it.face == "front" }, "$plan x$scale")
            CreatureSkins.paint(guide.uvLayout, skin)
        }
    }

    /** The lowest point of the rest pose: each bone placed at its pivot in its parent's frame and turned Rz * Ry * Rx. */
    private fun lowest(bones: List<com.wjz.worldsmith.core.content.CreatureBone>): Double {
        val byId = bones.associateBy { it.id }
        fun rotate(r: com.wjz.worldsmith.core.content.CreatureVector, p: DoubleArray): DoubleArray {
            val (ax, ay, az) = listOf(r.x, r.y, r.z).map { Math.toRadians(it.toDouble()) }
            var (x, y, z) = Triple(p[0], p[1], p[2])
            run { val ny = y * Math.cos(ax) - z * Math.sin(ax); val nz = y * Math.sin(ax) + z * Math.cos(ax); y = ny; z = nz }
            run { val nx = x * Math.cos(ay) + z * Math.sin(ay); val nz = -x * Math.sin(ay) + z * Math.cos(ay); x = nx; z = nz }
            run { val nx = x * Math.cos(az) - y * Math.sin(az); val ny = x * Math.sin(az) + y * Math.cos(az); x = nx; y = ny }
            return doubleArrayOf(x, y, z)
        }
        fun world(boneId: String, p: DoubleArray): DoubleArray {
            val bone = byId.getValue(boneId)
            val turned = rotate(bone.rotation, p)
            val placed = doubleArrayOf(turned[0] + bone.pivot.x, turned[1] + bone.pivot.y, turned[2] + bone.pivot.z)
            return bone.parent?.let { world(it, placed) } ?: placed
        }
        return bones.flatMap { bone -> bone.cubes.flatMap { c ->
            listOf(0f, c.size.x).flatMap { dx -> listOf(0f, c.size.y).flatMap { dy -> listOf(0f, c.size.z).map { dz ->
                world(bone.id, doubleArrayOf((c.origin.x + dx).toDouble(), (c.origin.y + dy).toDouble(), (c.origin.z + dz).toDouble()))[1]
            } } }
        } }.max()
    }

    @Test
    fun `each plan carries the joints its motion needs`() {
        fun roles(plan: CreatureBodyPlan) = CreatureAuthoring.compile(CreatureTemplates.recipe(plan, "t", "T"), "a".repeat(64)).definition
        val quadruped = roles(CreatureBodyPlan.QUADRUPED)
        assertEquals(2, quadruped.model.bones.count { it.role == CreatureBoneRole.LEG_LEFT })
        assertEquals(2, quadruped.model.bones.count { it.role == CreatureBoneRole.LEG_RIGHT })
        assertEquals(2, quadruped.model.bones.count { it.gaitPhase == 180f }, "the hind pair walks in counter-phase")

        val bird = roles(CreatureBodyPlan.BIRD)
        assertTrue(bird.model.bones.any { it.role == CreatureBoneRole.WING_LEFT } && bird.model.bones.any { it.role == CreatureBoneRole.WING_RIGHT })
        assertTrue(bird.model.bones.any { it.role == CreatureBoneRole.JAW })
        assertEquals(CreatureMovement.FLY, bird.behavior.drives.movement)

        val arthropod = roles(CreatureBodyPlan.ARTHROPOD)
        assertEquals(8, arthropod.model.bones.count { it.role == CreatureBoneRole.LEG_LEFT || it.role == CreatureBoneRole.LEG_RIGHT })
        fun phase(id: String) = arthropod.model.bones.single { it.id == id }.gaitPhase
        assertEquals(phase("leg_l1"), phase("leg_l3")); assertNotEquals(phase("leg_l1"), phase("leg_l2"), "alternate legs step together")

        val serpent = roles(CreatureBodyPlan.SERPENT)
        val chains = CreaturePose.chains(serpent.model.bones)
        assertEquals(listOf(0, 1, 2, 3), (1..4).map { chains.getValue("segment_$it") }, "the body is one jointed chain")
    }
}
