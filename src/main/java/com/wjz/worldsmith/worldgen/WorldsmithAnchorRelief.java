package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.AnchorRelief;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * Compiles explicit landmark cross-sections using ordinary serializable density
 * nodes. The radial footprint is {@code max(0, 1 - warpedRadiusSquared)};
 * climate/material influence remains separate and keeps the authored falloff.
 */
final class WorldsmithAnchorRelief {

	private WorldsmithAnchorRelief() {
	}

	static DensityFunction apply(
		AnchorRelief relief,
		DensityFunction influence,
		DensityFunction radialFootprint,
		DensityFunction incomingHeight,
		DensityFunction localTexture,
		int seaLevel
	) {
		if (relief instanceof AnchorRelief.Offset offset) {
			return DensityFunctions.add(incomingHeight, scale(influence, offset.getAmplitude()));
		}
		if (relief instanceof AnchorRelief.Mesa mesa) {
			DensityFunction blend = outerBlend(radialFootprint, mesa.getTopRadius());
			DensityFunction target = DensityFunctions.add(
				DensityFunctions.constant(mesa.getSurfaceY() - (double) seaLevel),
				scale(localTexture.clamp(-1.0, 1.0), mesa.getRoughness())
			);
			return DensityFunctions.lerp(blend, incomingHeight, target);
		}
		if (relief instanceof AnchorRelief.Caldera caldera) {
			double floorSquared = caldera.getFloorRadius() * caldera.getFloorRadius();
			double rimSquared = caldera.getRimRadius() * caldera.getRimRadius();
			DensityFunction wall = smoothstep(scale(
				DensityFunctions.add(DensityFunctions.constant(1.0 - floorSquared), scale(radialFootprint, -1.0)),
				1.0 / (rimSquared - floorSquared)
			));
			DensityFunction target = DensityFunctions.add(
				DensityFunctions.lerp(wall,
					DensityFunctions.constant(caldera.getFloorY() - (double) seaLevel),
					DensityFunctions.constant(caldera.getRimY() - (double) seaLevel)),
				scale(localTexture.clamp(-1.0, 1.0), caldera.getRoughness())
			);
			return DensityFunctions.lerp(outerBlend(radialFootprint, caldera.getRimRadius()), incomingHeight, target);
		}
		throw new IllegalArgumentException("Unknown anchor relief " + relief.getClass().getSimpleName());
	}

	private static DensityFunction outerBlend(DensityFunction footprint, double innerRadius) {
		return smoothstep(scale(footprint, 1.0 / (1.0 - innerRadius * innerRadius)));
	}

	/** Cubic easing has zero slope at both joins, including the outer footprint. */
	private static DensityFunction smoothstep(DensityFunction input) {
		DensityFunction t = input.clamp(0.0, 1.0);
		// Interval arithmetic sees independent [0,1] and [1,3] factors and
		// otherwise reports [0,3]. Publish the real mathematical bound so many
		// composed landmarks do not inflate downstream density bounds exponentially.
		return DensityFunctions.mul(t.square(), DensityFunctions.add(DensityFunctions.constant(3.0), scale(t, -2.0)))
			.clamp(0.0, 1.0);
	}

	private static DensityFunction scale(DensityFunction input, double amount) {
		return DensityFunctions.mul(input, DensityFunctions.constant(amount));
	}
}
