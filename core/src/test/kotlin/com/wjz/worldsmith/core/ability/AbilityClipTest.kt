package com.wjz.worldsmith.core.ability

import com.wjz.worldsmith.core.ability.visual.AbilityClip
import com.wjz.worldsmith.core.content.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AbilityClipTest {
    private fun v(x: Float = 0f, y: Float = 0f, z: Float = 0f) = AbilityClip.Vector(x, y, z)
    private fun transform(x: Float = 0f, angle: Float = 0f, scale: Float = 1f) = AbilityClip.Transform(v(x), v(angle), v(scale, scale, scale))
    private fun clip(blend: Int = 0) = AbilityClip(20, blend, listOf(AbilityClip.Track("arm", listOf(
        AbilityClip.Keyframe(0, transform()), AbilityClip.Keyframe(20, transform(8f, 120f, 2f))))))

    @Test fun `linear tracks interpolate translation rotation and scale without a named pose`() {
        val sample = clip().sample(10.0).getValue("arm")
        assertEquals(4f, sample.translation().x()); assertEquals(60f, sample.rotation().x()); assertEquals(1.5f, sample.scale().x())
        assertTrue(clip().sample(-1.0).isEmpty()); assertTrue(clip().sample(20.0).isEmpty())
    }
    @Test fun `blend enters and leaves procedural identity with bounded finite transforms`() {
        assertEquals(transform(), clip(4).sample(0.0).getValue("arm"))
        assertEquals(60f, clip(4).sample(10.0).getValue("arm").rotation().x())
        assertTrue(clip(4).sample(19.9).getValue("arm").rotation().x() < 1f)
        assertThrows(IllegalArgumentException::class.java) { clip().sample(Double.NaN) }
    }
    @Test fun `dynamic nested source lists become immutable validated tracks`() {
        val frame = AbilityValues.list(listOf(AbilityValues.number(0.0), AbilityValues.vector(0.0, 0.0, 0.0),
            AbilityValues.vector(-60.0, 0.0, 0.0), AbilityValues.vector(1.0, 1.0, 1.0)))
        val source = AbilityValues.list(listOf(AbilityValues.list(listOf(AbilityValues.text("arm"), AbilityValues.list(listOf(frame))))))
        val parsed = AbilityClip.parse(source, 20, 2)
        assertEquals(-60f, parsed.sample(5.0).getValue("arm").rotation().x())
        assertThrows(UnsupportedOperationException::class.java) { (parsed.tracks() as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (parsed.sample(5.0) as MutableMap<*, *>).clear() }
    }
    @Test fun `invalid timelines duplicate tracks and excessive values fail before playback`() {
        assertThrows(IllegalArgumentException::class.java) { AbilityClip(20, 0, listOf(clip().tracks()[0], clip().tracks()[0])) }
        assertThrows(IllegalArgumentException::class.java) { AbilityClip.Track("arm", listOf(AbilityClip.Keyframe(1, transform()))) }
        assertThrows(IllegalArgumentException::class.java) { AbilityClip.Track("arm", listOf(AbilityClip.Keyframe(0, transform()), AbilityClip.Keyframe(0, transform()))) }
        assertThrows(IllegalArgumentException::class.java) { transform(33f) }
        assertThrows(IllegalArgumentException::class.java) { transform(angle = Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { AbilityClip(10, 6, clip().tracks()) }
    }
    @Test fun `model validation checks real bone names and accumulated animated extents`() {
        val model = CreatureModel("a".repeat(64), bones = listOf(CreatureBone("arm", cubes = listOf(
            CreatureCube(CreatureVector(), CreatureVector(4f, 4f, 4f))))))
        val creature = CreatureDefinition("fixture", "Fixture", CreatureCategory.HOSTILE, model)
        clip().validateFor(creature)
        assertThrows(IllegalArgumentException::class.java) { clip().validateFor(creature.copy(model = model.copy(bones = listOf(CreatureBone("other"))))) }
        val deep = (0..5).map { i -> CreatureBone("b$i", parent = if (i == 0) null else "b${i - 1}",
            cubes = listOf(CreatureCube(CreatureVector(), CreatureVector(4f, 4f, 4f)))) }
        val large = AbilityClip(20, 0, deep.map { AbilityClip.Track(it.id, listOf(AbilityClip.Keyframe(0, transform(scale = 4f)))) })
        assertThrows(IllegalArgumentException::class.java) { large.validateFor(creature.copy(model = model.copy(bones = deep))) }
    }
}
