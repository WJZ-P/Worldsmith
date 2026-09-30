package com.wjz.worldsmith.core.creatureauthoring

import com.wjz.worldsmith.core.content.CreatureAttributes
import com.wjz.worldsmith.core.content.CreatureBehavior
import com.wjz.worldsmith.core.content.CreatureBoneRole
import com.wjz.worldsmith.core.content.CreatureCategory
import com.wjz.worldsmith.core.content.CreatureDrives
import com.wjz.worldsmith.core.content.CreatureMovement
import kotlinx.serialization.Serializable

@Serializable enum class CreatureBodyPlan { QUADRUPED, BIPED, BIRD, SERPENT }

/**
 * Starting rigs for the common body plans. What a model most often gets wrong
 * is not the silhouette but the joints: a leg whose pivot is not at the hip
 * swings from its middle, a jaw hinged at its tip, hind legs in step with the
 * front ones, a wing that cannot fold. These rigs put every pivot at its joint,
 * give each bone the role its motion needs, stand the feet on the ground line
 * and name material roles for the skin painter. `_l` bones lie on -X and are
 * mirrored to `_r`. Reshape the cubes freely; keep the joints where they are.
 */
object CreatureTemplates {
    @JvmStatic @JvmOverloads
    fun recipe(plan: CreatureBodyPlan, id: String, displayName: String, scale: Int = 1): CreatureRecipe {
        require(scale in 1..3) { "Template scale is 1..3" }
        val b = CreatureBuilder.create(id, displayName, if (plan == CreatureBodyPlan.BIPED) CreatureCategory.HOSTILE else CreatureCategory.PASSIVE)
            .atlas(when (scale) { 1 -> 64; 2 -> 128; else -> 256 }, when (scale) { 1 -> 64; 2 -> 128; else -> 256 }, 1)
        val s = scale.toFloat()
        fun root(id: String, x: Float, height: Float, z: Float) = b.bone(id, null, x * s, 24f - height * s, z * s)
        fun child(id: String, parent: String, x: Float, y: Float, z: Float) = b.bone(id, parent, x * s, y * s, z * s)
        fun CreatureBuilder.BoneBuilder.box(cube: String, x: Float, y: Float, z: Float, w: Int, h: Int, d: Int, material: String) =
            cube(cube, x * s, y * s, z * s, w * scale, h * scale, d * scale, material)
        when (plan) {
            CreatureBodyPlan.QUADRUPED -> {
                root("body", 0f, 10f, 0f).role(CreatureBoneRole.BODY).box("torso", -3f, -3f, -6f, 6, 6, 12, "fur").end()
                child("head", "body", 0f, -2f, -6f).role(CreatureBoneRole.HEAD)
                    .box("skull", -3f, -4f, -5f, 6, 5, 5, "fur").box("muzzle", -2f, -1f, -8f, 4, 3, 3, "muzzle")
                    .box("ear_l", -3f, -6f, -3f, 2, 2, 1, "fur").box("ear_r", 1f, -6f, -3f, 2, 2, 1, "fur").end()
                child("leg_fl", "body", -2f, 3f, -4f).role(CreatureBoneRole.LEG_LEFT).box("leg", -1f, 0f, -1f, 2, 7, 2, "leg").end()
                child("leg_hl", "body", -2f, 3f, 5f).role(CreatureBoneRole.LEG_LEFT).gaitPhase(180f).box("leg", -1f, 0f, -1f, 2, 7, 2, "leg").end()
                child("tail", "body", 0f, -2f, 6f).role(CreatureBoneRole.TAIL).rotation(-30f, 0f, 0f).box("root", -1f, -1f, 0f, 2, 2, 4, "fur").end()
                child("tail_tip", "tail", 0f, 0f, 4f).role(CreatureBoneRole.TAIL).rotation(10f, 0f, 0f).box("tip", -1f, -1f, 0f, 2, 2, 4, "fur").end()
                b.mirrorSubtree("leg_fl", "leg_fr").mirrorSubtree("leg_hl", "leg_hr")
                b.attributes(CreatureAttributes(width = 0.6f * s, height = 0.9f * s))
            }
            CreatureBodyPlan.BIPED -> {
                root("body", 0f, 12f, 0f).role(CreatureBoneRole.BODY).box("torso", -4f, -12f, -2f, 8, 12, 4, "clothes").end()
                child("head", "body", 0f, -12f, 0f).role(CreatureBoneRole.HEAD).box("skull", -4f, -8f, -4f, 8, 8, 8, "skin").end()
                child("arm_l", "body", -5f, -10f, 0f).role(CreatureBoneRole.ARM_LEFT).box("arm", -3f, -2f, -2f, 4, 12, 4, "sleeve").end()
                child("leg_l", "body", -2f, 0f, 0f).role(CreatureBoneRole.LEG_LEFT).box("leg", -2f, 0f, -2f, 4, 12, 4, "legs").end()
                b.mirrorSubtree("arm_l", "arm_r").mirrorSubtree("leg_l", "leg_r")
                b.attributes(CreatureAttributes(width = 0.6f * s, height = 1.9f * s))
            }
            CreatureBodyPlan.BIRD -> {
                root("body", 0f, 7f, 0f).role(CreatureBoneRole.BODY).box("torso", -3f, -3f, -4f, 6, 5, 8, "plume").end()
                child("head", "body", 0f, -2f, -4f).role(CreatureBoneRole.HEAD).box("skull", -2f, -5f, -3f, 4, 4, 4, "head").end()
                child("beak", "head", 0f, -3f, -3f).role(CreatureBoneRole.JAW).box("bill", -1f, 0f, -3f, 2, 1, 3, "beak").end()
                // Spread flat from the shoulder: it beats about Z in flight and sweeps back to rest.
                child("wing_l", "body", -3f, -2f, -2f).role(CreatureBoneRole.WING_LEFT).box("feathers", -7f, 0f, -1f, 7, 1, 6, "wing").end()
                child("tail", "body", 0f, -2f, 4f).role(CreatureBoneRole.TAIL).rotation(-15f, 0f, 0f).box("fan", -2f, 0f, 0f, 4, 1, 4, "wing").end()
                child("leg_l", "body", -1f, 2f, 0f).role(CreatureBoneRole.LEG_LEFT).box("shank", -1f, 0f, 0f, 1, 5, 1, "leg").end()
                b.mirrorSubtree("wing_l", "wing_r").mirrorSubtree("leg_l", "leg_r")
                b.attributes(CreatureAttributes(width = 0.5f * s, height = 0.7f * s))
                b.drives(CreatureDrives(movement = CreatureMovement.FLY))
            }
            CreatureBodyPlan.SERPENT -> {
                root("body", 0f, 2f, -6f).role(CreatureBoneRole.BODY).box("neck", -2f, -2f, 0f, 4, 4, 6, "scale").end()
                child("head", "body", 0f, -1f, 0f).role(CreatureBoneRole.HEAD).box("skull", -3f, -3f, -6f, 6, 4, 6, "scale").end()
                child("jaw", "head", 0f, 1f, -1f).role(CreatureBoneRole.JAW).box("mandible", -3f, 0f, -5f, 6, 1, 5, "belly").end()
                // The body is a tail chain: each segment lags the last, so the whole length undulates.
                var parent = "body"
                listOf(Triple(4, 4, 6), Triple(4, 4, 6), Triple(4, 3, 6), Triple(2, 2, 5)).forEachIndexed { i, (w, h, d) ->
                    val id = "segment_${i + 1}"
                    child(id, parent, 0f, 0f, 6f).role(CreatureBoneRole.TAIL)
                        .box("coil", -w / 2f, 2f - h, 0f, w, h, d, "scale").end()
                    parent = id
                }
                b.attributes(CreatureAttributes(width = 0.6f * s, height = 0.5f * s))
            }
        }
        return b.recipe()
    }
}
