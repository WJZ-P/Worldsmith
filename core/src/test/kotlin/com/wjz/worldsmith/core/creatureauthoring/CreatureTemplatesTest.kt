package com.wjz.worldsmith.core.creatureauthoring

import com.wjz.worldsmith.core.content.CreatureBoneRole
import com.wjz.worldsmith.core.content.CreatureLibrary
import com.wjz.worldsmith.core.content.CreatureMovement
import com.wjz.worldsmith.core.content.CreaturePose
import com.wjz.worldsmith.core.content.CustomCreatureValidator
import org.junit.jupiter.api.Assertions.assertEquals
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
            // Legs and bodies are unrotated, so summing pivots finds how low the model reaches.
            val bones = definition.model.bones.associateBy { it.id }
            fun pivotY(id: String?): Float = id?.let { bones.getValue(it).pivot.y + pivotY(bones.getValue(it).parent) } ?: 0f
            val lowest = definition.model.bones.filter { it.rotation.x == 0f }.flatMap { b -> b.cubes.map { pivotY(b.id) + it.origin.y + it.size.y } }.max()
            assertEquals(24f, lowest, "$plan x$scale reaches the ground line")
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

        val serpent = roles(CreatureBodyPlan.SERPENT)
        val chains = CreaturePose.chains(serpent.model.bones)
        assertEquals(listOf(0, 1, 2, 3), (1..4).map { chains.getValue("segment_$it") }, "the body is one jointed chain")
    }
}
