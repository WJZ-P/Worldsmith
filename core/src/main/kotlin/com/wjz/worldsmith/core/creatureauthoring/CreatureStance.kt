package com.wjz.worldsmith.core.creatureauthoring

import com.wjz.worldsmith.core.content.CreatureBone
import com.wjz.worldsmith.core.content.CreatureBoneRole
import com.wjz.worldsmith.core.content.CreatureDefinition
import com.wjz.worldsmith.core.content.CreatureVector
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlin.math.roundToInt

/**
 * Whether a model stands on the ground in its rest pose. A creature whose feet hover
 * a pixel above the grass or sink into it looks wrong at once in the world, and an
 * edited leg - longer, moved, rolled outward - is the easiest way to get there. The
 * rest pose places every cube corner through its bones' pivots and rotations
 * (Rz * Ry * Rx, as both renderers compose them) without procedural motion; the
 * ground line is model Y = 24. These are warnings: a wisp or a ghost may float on
 * purpose.
 */
object CreatureStance {
    const val GROUND = 24.0
    /** Half a model pixel either way is invisible in game. */
    const val TOLERANCE = 0.5

    @JvmStatic fun inspect(definition: CreatureDefinition): List<Diagnostic> {
        val bones = definition.model.bones
        if (bones.none { it.cubes.isNotEmpty() }) return emptyList()
        val lowest = lowest(bones, bones.map { it.id }.toSet())
        val problems = mutableListOf<Diagnostic>()
        val offset = lowest - GROUND
        if (offset < -TOLERANCE) problems += Diagnostic("model.bones", "CREATURE_FLOATS", DiagnosticSeverity.WARNING,
            "In its rest pose the model's lowest point is ${px(-offset)} px above the ground line (Y=24): it will hover",
            metrics = mapOf("pixelsAbove" to (-offset).roundToInt()),
            hint = "Lengthen the legs or lower the root pivot so the feet reach Y=24, or leave it if this creature floats on purpose")
        if (offset > TOLERANCE) problems += Diagnostic("model.bones", "CREATURE_SINKS", DiagnosticSeverity.WARNING,
            "In its rest pose the model reaches ${px(offset)} px below the ground line (Y=24): its feet will sink into the ground",
            metrics = mapOf("pixelsBelow" to offset.roundToInt()),
            hint = "Shorten the legs, raise the root pivot or reduce a leg's roll so the lowest point sits at Y=24")
        // Each leg with everything it carries; legs that stop short of the lowest foot do not touch down.
        val legs = bones.filter { it.role == CreatureBoneRole.LEG_LEFT || it.role == CreatureBoneRole.LEG_RIGHT }
        if (legs.size >= 2) {
            val feet = legs.associate { leg -> leg.id to lowest(bones, subtree(bones, leg.id)) }.filterValues { it.isFinite() }
            val ground = feet.values.maxOrNull() ?: return problems
            feet.filter { (_, y) -> ground - y > TOLERANCE }.forEach { (id, y) ->
                problems += Diagnostic("model.bones.$id", "CREATURE_LEG_SHORT", DiagnosticSeverity.WARNING,
                    "Leg '$id' stops ${px(ground - y)} px above the lowest foot in the rest pose: it will not touch the ground",
                    metrics = mapOf("pixelsShort" to (ground - y).roundToInt()),
                    hint = "Give legs that stand on the ground the same reach; a leg held up on purpose can stay")
            }
        }
        return problems
    }

    private fun px(value: Double) = "%.1f".format(java.util.Locale.ROOT, value)

    private fun subtree(bones: List<CreatureBone>, root: String): Set<String> {
        val result = mutableSetOf(root)
        var grew = true
        while (grew) { grew = bones.any { it.parent in result && result.add(it.id) } }
        return result
    }

    /** The greatest model Y of any cube corner of the named bones, in the rest pose. */
    private fun lowest(bones: List<CreatureBone>, of: Set<String>): Double {
        val byId = bones.associateBy { it.id }
        fun world(boneId: String, p: DoubleArray, depth: Int = 0): DoubleArray {
            val bone = byId.getValue(boneId)
            val turned = rotate(bone.rotation, p)
            val placed = doubleArrayOf(turned[0] + bone.pivot.x, turned[1] + bone.pivot.y, turned[2] + bone.pivot.z)
            val parent = bone.parent?.let(byId::get)
            return if (parent == null || depth > 64) placed else world(parent.id, placed, depth + 1)
        }
        var lowest = Double.NEGATIVE_INFINITY
        for (bone in bones) if (bone.id in of) for (c in bone.cubes) for (dx in listOf(0f, c.size.x)) for (dy in listOf(0f, c.size.y)) for (dz in listOf(0f, c.size.z))
            lowest = maxOf(lowest, world(bone.id, doubleArrayOf((c.origin.x + dx).toDouble(), (c.origin.y + dy).toDouble(), (c.origin.z + dz).toDouble()))[1])
        return lowest
    }

    private fun rotate(r: CreatureVector, p: DoubleArray): DoubleArray {
        val ax = Math.toRadians(r.x.toDouble()); val ay = Math.toRadians(r.y.toDouble()); val az = Math.toRadians(r.z.toDouble())
        var x = p[0]; var y = p[1]; var z = p[2]
        if (ax != 0.0) { val ny = y * Math.cos(ax) - z * Math.sin(ax); z = y * Math.sin(ax) + z * Math.cos(ax); y = ny }
        if (ay != 0.0) { val nx = x * Math.cos(ay) + z * Math.sin(ay); z = -x * Math.sin(ay) + z * Math.cos(ay); x = nx }
        if (az != 0.0) { val nx = x * Math.cos(az) - y * Math.sin(az); y = x * Math.sin(az) + y * Math.cos(az); x = nx }
        return doubleArrayOf(x, y, z)
    }
}
