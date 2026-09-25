package com.wjz.worldsmith.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class AnchorReliefSamplerTest {
    @Test
    fun `valid extreme leveled profiles remain finite and inside the convex height envelope`() {
        val random = Random(0x5CA1A)
        val profiles = listOf(
            AnchorRelief.Mesa(-40, 0.05, 0.0),
            AnchorRelief.Mesa(224, 0.9, 16.0),
            AnchorRelief.Caldera(-24, 224, 0.05, 0.15, 16.0),
            AnchorRelief.Caldera(-40, 240, 0.8, 0.9, 0.0),
        )
        for (profile in profiles) repeat(2_000) {
            val radius = random.nextDouble(0.0, 1.5)
            val incoming = random.nextDouble(-64.0, 320.0)
            val texture = random.nextDouble(-4.0, 4.0)
            val falloff = random.nextDouble(0.05, 8.0)
            val sampled = AnchorReliefSampler.sample(profile, radius, incoming, texture, falloff)
            val bounds = when (profile) {
                is AnchorRelief.Mesa -> (profile.surfaceY - profile.roughness) to (profile.surfaceY + profile.roughness)
                is AnchorRelief.Caldera -> (profile.floorY - profile.roughness) to (profile.rimY + profile.roughness)
                else -> error("This property covers leveled profiles")
            }
            assertTrue(sampled.isFinite())
            assertTrue(sampled >= minOf(incoming, bounds.first) - 1.0e-9)
            assertTrue(sampled <= maxOf(incoming, bounds.second) + 1.0e-9)
            if (radius >= 1.0) assertEquals(incoming, sampled, 0.0)
        }
    }

    @Test
    fun `overlapping profiles compose in authored order rather than being summed or commuted`() {
        val terrace = AnchorRelief.Mesa(112, 0.6, 0.0)
        val mound = AnchorRelief.Offset(20.0)
        val mesaThenMound = sample(mound, 0.0, sample(terrace, 0.0))
        val moundThenMesa = sample(terrace, 0.0, sample(mound, 0.0))
        assertEquals(132.0, mesaThenMound, 0.0)
        assertEquals(112.0, moundThenMesa, 0.0)
        assertEquals(112.0, sample(mound, 1.01, sample(terrace, 0.0)), 0.0)
    }

    @Test
    fun `mesa top replaces hills instead of adding a flat offset to them`() {
        val mesa = AnchorRelief.Mesa(surfaceY = 112, topRadius = 0.6, roughness = 0.0)
        for (radius in listOf(0.0, 0.2, 0.59, 0.6)) {
            for (ground in listOf(-20.0, 80.0, 200.0)) {
                assertEquals(112.0, sample(mesa, radius, ground), 1.0e-9)
            }
        }
        assertEquals(80.0, sample(mesa, 1.0), 0.0)
        assertEquals(80.0, sample(mesa, 1.2), 0.0)
    }

    @Test
    fun `caldera has a level floor and a raised rim rather than just a negative dome`() {
        val caldera = AnchorRelief.Caldera(72, 148, 0.25, 0.65, 0.0)
        assertEquals(72.0, sample(caldera, 0.0), 0.0)
        assertEquals(72.0, sample(caldera, 0.25), 0.0)
        assertEquals(148.0, sample(caldera, 0.65), 1.0e-9)
        assertEquals(80.0, sample(caldera, 1.0), 0.0)
        assertTrue(sample(caldera, 0.45) in 72.0..148.0)
        assertTrue(sample(caldera, 0.85) in 80.0..148.0)
    }

    @Test
    fun `profile joins have no cliff discontinuity or seam slope`() {
        val cases = listOf(
            AnchorRelief.Mesa(112, 0.6, 0.0) to listOf(0.6, 1.0),
            AnchorRelief.Caldera(72, 148, 0.25, 0.65, 0.0) to listOf(0.25, 0.65, 1.0),
        )
        val delta = 1.0e-6
        for ((relief, joins) in cases) for (join in joins) {
            val left = sample(relief, join - delta)
            val at = sample(relief, join)
            val right = sample(relief, join + delta)
            assertEquals(at, left, 1.0e-7)
            assertEquals(at, right, 1.0e-7)
            assertTrue(kotlin.math.abs((right - left) / (2.0 * delta)) < 0.01)
        }
    }

    @Test
    fun `texture is bounded in blocks and influence falloff does not deform leveled surfaces`() {
        for (relief in listOf(AnchorRelief.Mesa(112, 0.6, 3.0), AnchorRelief.Caldera(72, 148, 0.25, 0.65, 3.0))) {
            val level = if (relief is AnchorRelief.Mesa) 112.0 else 72.0
            for (falloff in listOf(0.05, 1.0, 8.0)) {
                assertEquals(level + 3.0, AnchorReliefSampler.sample(relief, 0.0, 200.0, 7.0, falloff), 1.0e-9)
                assertEquals(level - 3.0, AnchorReliefSampler.sample(relief, 0.0, -20.0, -7.0, falloff), 1.0e-9)
                assertEquals(80.0, AnchorReliefSampler.sample(relief, 1.0, 80.0, 7.0, falloff), 0.0)
            }
        }
    }

    @Test
    fun `offset keeps the explicit signed amplitude and authored falloff`() {
        assertEquals(170.0, sample(AnchorRelief.Offset(90.0), 0.0), 0.0)
        assertEquals(-10.0, sample(AnchorRelief.Offset(-90.0), 0.0), 0.0)
        assertEquals(118.4, AnchorReliefSampler.sample(AnchorRelief.Offset(60.0), 0.6, 80.0, 0.0, 1.0), 1.0e-9)
        assertEquals(80.0, sample(AnchorRelief.Offset(90.0), 1.0), 0.0)
    }

    private fun sample(relief: AnchorRelief, radius: Double, ground: Double = 80.0) =
        AnchorReliefSampler.sample(relief, radius, ground, 0.0, 1.0)
}
