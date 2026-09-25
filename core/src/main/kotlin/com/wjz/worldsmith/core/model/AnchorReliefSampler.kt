package com.wjz.worldsmith.core.model

import kotlin.math.pow

/**
 * Offline reference for an anchor's uncarved surface, with no game dependency.
 *
 * [sample] takes the already-warped distance divided by anchor radius. The
 * native compiler uses the same squared-radius smoothstep construction with
 * density nodes, so inspection and generation share explicit boundary rules.
 * This predicts the body before caves or additive/carving bands, not a promise
 * that a building site is clear of those later operations.
 */
object AnchorReliefSampler {
    @JvmStatic
    fun sample(
        relief: AnchorRelief,
        normalizedDistance: Double,
        incomingSurfaceY: Double,
        localTexture: Double,
        falloff: Double,
    ): Double {
        require(normalizedDistance.isFinite() && normalizedDistance >= 0.0)
        require(incomingSurfaceY.isFinite() && localTexture.isFinite())
        require(falloff.isFinite() && falloff > 0.0)
        if (normalizedDistance >= 1.0) return incomingSurfaceY
        val radiusSquared = normalizedDistance * normalizedDistance
        val footprint = 1.0 - radiusSquared
        val texture = localTexture.coerceIn(-1.0, 1.0)
        return when (relief) {
            is AnchorRelief.Offset -> incomingSurfaceY + relief.amplitude * footprint.pow(falloff)
            is AnchorRelief.Mesa -> {
                val blend = smoothstep(footprint / (1.0 - relief.topRadius * relief.topRadius))
                lerp(blend, incomingSurfaceY, relief.surfaceY + texture * relief.roughness)
            }
            is AnchorRelief.Caldera -> {
                val floorSquared = relief.floorRadius * relief.floorRadius
                val rimSquared = relief.rimRadius * relief.rimRadius
                val wall = smoothstep((radiusSquared - floorSquared) / (rimSquared - floorSquared))
                val target = lerp(wall, relief.floorY.toDouble(), relief.rimY.toDouble()) + texture * relief.roughness
                val blend = smoothstep(footprint / (1.0 - rimSquared))
                lerp(blend, incomingSurfaceY, target)
            }
        }
    }

    private fun smoothstep(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun lerp(amount: Double, from: Double, to: Double) = from + amount * (to - from)
}
