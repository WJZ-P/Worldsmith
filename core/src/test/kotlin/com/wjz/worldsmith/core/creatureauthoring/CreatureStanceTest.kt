package com.wjz.worldsmith.core.creatureauthoring

import com.wjz.worldsmith.core.content.CreatureBoneRole
import com.wjz.worldsmith.core.content.CreatureCategory
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreatureStanceTest {
    private fun hound(rootHeight: Float, hindLeg: Int = 7) = CreatureBuilder.create("hound", "Hound", CreatureCategory.PASSIVE).also { b ->
        b.bone("body", null, 0f, 24f - rootHeight, 0f).role(CreatureBoneRole.BODY).cube("torso", -3f, -3f, -6f, 6, 6, 12, "fur").end()
        b.bone("leg_f", "body", -2f, 3f, -4f).role(CreatureBoneRole.LEG_LEFT).cube("leg", -1f, 0f, -1f, 2, 7, 2, "fur").end()
        b.bone("leg_h", "body", -2f, 3f, 5f).role(CreatureBoneRole.LEG_LEFT).cube("leg", -1f, 0f, -1f, 2, hindLeg, 2, "fur").end()
        b.mirrorSubtree("leg_f", "leg_fr").mirrorSubtree("leg_h", "leg_hr")
    }.build("a".repeat(64)).definition

    @Test fun `every template stands on the ground line`() {
        for (plan in CreatureBodyPlan.entries) for (scale in 1..3)
            assertEquals(emptyList<String>(), CreatureStance.inspect(CreatureAuthoring.compile(CreatureTemplates.recipe(plan, "t", "T", scale), "a".repeat(64)).definition).map { it.code }, "$plan x$scale")
    }

    @Test fun `a hovering, a sinking and a short-legged model are each pointed out`() {
        assertEquals(emptyList<String>(), CreatureStance.inspect(hound(10f)).map { it.code })
        val floats = CreatureStance.inspect(hound(12f))
        assertEquals(listOf("CREATURE_FLOATS"), floats.map { it.code })
        assertEquals(2, floats.single().metrics["pixelsAbove"])
        assertTrue(floats.all { it.severity == DiagnosticSeverity.WARNING }, "a ghost may hover on purpose")
        assertEquals(listOf("CREATURE_SINKS"), CreatureStance.inspect(hound(8f)).map { it.code })
        val short = CreatureStance.inspect(hound(10f, hindLeg = 5))
        assertEquals(setOf("model.bones.leg_h", "model.bones.leg_hr"), short.filter { it.code == "CREATURE_LEG_SHORT" }.map { it.path }.toSet())
    }
}
