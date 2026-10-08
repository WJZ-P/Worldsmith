package com.wjz.worldsmith.core.creatureauthoring

import com.wjz.worldsmith.core.content.CreatureAttributes
import com.wjz.worldsmith.core.content.CreatureBehavior
import com.wjz.worldsmith.core.content.CreatureBoneRole
import com.wjz.worldsmith.core.content.CreatureCategory
import com.wjz.worldsmith.core.content.CreatureDrives
import com.wjz.worldsmith.core.content.CreatureMovement
import kotlinx.serialization.Serializable

@Serializable enum class CreatureBodyPlan { QUADRUPED, BIPED, BIRD, SERPENT, ARTHROPOD }

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
        val b = CreatureBuilder.create(id, displayName, if (plan == CreatureBodyPlan.BIPED || plan == CreatureBodyPlan.ARTHROPOD) CreatureCategory.HOSTILE else CreatureCategory.PASSIVE)
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
                    .box("ear_l", -3f, -6f, -3f, 2, 2, 1, "ear").box("ear_r", 1f, -6f, -3f, 2, 2, 1, "ear").end()
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
            CreatureBodyPlan.ARTHROPOD -> {
                root("body", 0f, 7f, 0f).role(CreatureBoneRole.BODY).box("thorax", -3f, -2f, -4f, 6, 4, 7, "carapace").end()
                child("abdomen", "body", 0f, -1f, 3f).box("abdomen", -4f, -3f, 0f, 8, 6, 9, "abdomen").end()
                child("head", "body", 0f, 0f, -4f).role(CreatureBoneRole.HEAD).box("skull", -2.5f, -2f, -4f, 5, 4, 4, "carapace").end()
                child("fangs", "head", 0f, 2f, -4f).role(CreatureBoneRole.JAW)
                    .box("fang_l", -2f, 0f, -1f, 1, 2, 1, "fang").box("fang_r", 1f, 0f, -1f, 1, 2, 1, "fang").end()
                // Eight straight legs splayed out and down, as a vanilla spider's are, each leaning
                // toward its end of the body; the splay is solved so every foot meets the ground
                // line. Alternate legs step together, the walk of a spider or a beetle.
                listOf(-30f, -10f, 10f, 30f).forEachIndexed { k, lean ->
                    val leg = "leg_l${k + 1}"
                    child(leg, "body", -3f, 1f, -3f + 2f * k).role(CreatureBoneRole.LEG_LEFT).gaitPhase(if (k % 2 == 0) 0f else 180f)
                        .rotation(lean, 0f, splay(lean, hip = 6f, length = 12f)).box("leg", -0.5f, 0f, -0.5f, 1, 12, 1, "leg").end()
                    b.mirrorSubtree(leg, "leg_r${k + 1}")
                }
                b.attributes(CreatureAttributes(width = 1.3f * s, height = 0.9f * s))
            }
        }
        return b.recipe()
    }

    /**
     * The outward roll, in degrees, that sets the foot of a straight one-block-thick leg
     * hanging `length` from a hip `hip` above the ground, leant `lean` degrees fore or aft,
     * exactly on the ground line. Rotations compose as Rz * Ry * Rx, as both renderers do.
     */
    private fun splay(lean: Float, hip: Float, length: Float): Float {
        val a = Math.toRadians(lean.toDouble())
        fun lowest(roll: Double) = listOf(-0.5, 0.5).maxOf { x -> listOf(0.0, length.toDouble()).maxOf { y -> listOf(-0.5, 0.5).maxOf { z ->
            val y1 = y * Math.cos(a) - z * Math.sin(a)
            x * Math.sin(roll) + y1 * Math.cos(roll)
        } } }
        var low = 0.0; var high = Math.PI / 2
        repeat(60) { val mid = (low + high) / 2; if (lowest(mid) > hip) low = mid else high = mid }
        return Math.toDegrees((low + high) / 2).toFloat()
    }

    /**
     * A first skin for a template: a material per role in a believable palette and
     * eyes where a viewer looks for them, so the first textured preview reads as an
     * animal rather than a lump. Recolour, repattern and add markings to make the
     * species; eyes are what give a creature a face, so keep some.
     */
    @JvmStatic @JvmOverloads
    fun skin(plan: CreatureBodyPlan, scale: Int = 1): CreatureSkin {
        require(scale in 1..3) { "Template scale is 1..3" }
        val s = scale
        // An eye is an s x s dark block; from scale 2 its top-left pixel catches the light.
        fun eye(bone: String, cube: String, x: Int, y: Int) = SkinDecal(bone, cube, "front", x * s, y * s,
            List(s) { r -> String(CharArray(s) { c -> if (s > 1 && r == 0 && c == 0) 'h' else 'e' }) }, mapOf("e" to "#17151a", "h" to "#f2efe6"))
        return when (plan) {
            CreatureBodyPlan.QUADRUPED -> CreatureSkin(mapOf(
                "fur" to SkinMaterial("#8a6a4a", pattern = SkinPattern.FUR, belly = "#c2a37c"),
                // Ears stay the back's colour: a belly band would paint a pale stripe across them.
                "ear" to SkinMaterial("#7d5f42", pattern = SkinPattern.FUR, patternDensity = 0.15f),
                "muzzle" to SkinMaterial("#c9ab86", shade = 0.15f),
                "leg" to SkinMaterial("#6e533a", pattern = SkinPattern.FUR, patternDensity = 0.15f),
            ), listOf(eye("head", "skull", 1, 1), eye("head", "skull", 4, 1),
                SkinDecal("head", "muzzle", "front", s, 0, List(s) { "n".repeat(2 * s) }, mapOf("n" to "#2a1f1a"))))
            CreatureBodyPlan.BIPED -> CreatureSkin(mapOf(
                "skin" to SkinMaterial("#c8a484", shade = 0.15f),
                "clothes" to SkinMaterial("#4a5a7a", pattern = SkinPattern.SPECKLE, patternDensity = 0.2f),
                "sleeve" to SkinMaterial("#3f4d69"),
                "legs" to SkinMaterial("#3a3a44"),
            ), listOf(false, true).map { right ->
                // Two-pixel eyes, white toward the temples: the humanoid face every player knows.
                SkinDecal("head", "skull", "front", (if (right) 5 else 1) * s, 4 * s,
                    List(s) { if (right) "e".repeat(s) + "w".repeat(s) else "w".repeat(s) + "e".repeat(s) }, mapOf("w" to "#f2efe6", "e" to "#2d3b5a"))
            })
            CreatureBodyPlan.BIRD -> CreatureSkin(mapOf(
                "plume" to SkinMaterial("#9aa6b0", pattern = SkinPattern.SCALES, patternDensity = 0.35f, belly = "#dde3e8"),
                "head" to SkinMaterial("#6f7d89"),
                "beak" to SkinMaterial("#e0a030", shade = 0.1f, grain = 0f),
                "wing" to SkinMaterial("#6e7c88", pattern = SkinPattern.STRIPES, patternDensity = 0.3f),
                "leg" to SkinMaterial("#d08a2a", grain = 0f),
            ), listOf(eye("head", "skull", 0, 1), eye("head", "skull", 3, 1)))
            CreatureBodyPlan.ARTHROPOD -> CreatureSkin(mapOf(
                "carapace" to SkinMaterial("#3b3530", shade = 0.25f),
                "abdomen" to SkinMaterial("#4a3d33", pattern = SkinPattern.STRIPES, patternColor = "#2a2420", patternDensity = 0.35f),
                "leg" to SkinMaterial("#2e2925", pattern = SkinPattern.STRIPES, patternColor = "#5a4a3c", patternDensity = 0.2f),
                "fang" to SkinMaterial("#cdb48a", shade = 0.1f, grain = 0f),
            ), listOf("e.e.e", ".e.e.").mapIndexed { row, pattern ->
                // Many small eyes in two rows, the way a spider looks back.
                SkinDecal("head", "skull", "front", 0, (row + 1) * s, List(s) { pattern.map { c -> c.toString().repeat(s) }.joinToString("") }, mapOf("e" to "#d22b20"))
            })
            CreatureBodyPlan.SERPENT -> CreatureSkin(mapOf(
                "scale" to SkinMaterial("#5f7a3a", pattern = SkinPattern.SCALES, patternDensity = 0.5f),
                "belly" to SkinMaterial("#cfc08a", shade = 0.1f),
            ), listOf(eye("head", "skull", 0, 1), eye("head", "skull", 5, 1)))
        }
    }
}
