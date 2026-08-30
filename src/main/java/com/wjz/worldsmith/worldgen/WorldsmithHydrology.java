package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.HydrologyIntent;
import com.wjz.worldsmith.core.model.RiverFill;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Compiles prompt-facing hydrology into horizontal density fields. */
final class WorldsmithHydrology {
	private static final double RIVER_ROUTE_SCALE = 0.032;
	private static final double LAKE_FIELD_SCALE = 0.018;
	private static final double HORIZONTAL_NOISE_SIGMA = 0.34;
	private static final double LOGISTIC_NORMAL_SCALE = 1.702;

	private WorldsmithHydrology() {
	}

	/**
	 * The two fields consumed by the terrain compiler plus masks retained for
	 * outcome-level tests. All values are ordinary serializable density
	 * functions; no runtime callback or mutable generator state is introduced.
	 */
	record Fields(
		DensityFunction continents,
		DensityFunction horizontalHeightBlocks,
		DensityFunction riverStrength,
		DensityFunction lakeStrength
	) {
	}

	static Fields compile(
		HydrologyIntent intent,
		DensityFunction baseContinents,
		DensityFunction landInput,
		DensityFunction baseHeightBlocks,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		DensityFunction inlandGate = DensityFunctions.rangeChoice(
			landInput,
			0.02,
			1_000_000.0,
			DensityFunctions.constant(1.0),
			DensityFunctions.zero()
		);
		DensityFunction river = effectStrength(riverStrength(intent, inlandGate, noises));
		DensityFunction lake = effectStrength(lakeStrength(intent, inlandGate, noises));

		DensityFunction continents = baseContinents;
		DensityFunction height = baseHeightBlocks;
		if (intent.getRiverCoverage() > 0.0) {
			if (intent.getRiverFill() == RiverFill.FLUID) {
				continents = waterBiomeSignal(continents, river, -0.30);
				double riverBed = -2.0 - 10.0 * intent.getRiverDepth();
				height = DensityFunctions.lerp(river, height, DensityFunctions.constant(riverBed));
			} else {
				double carveDepth = 4.0 + 10.0 * intent.getRiverDepth();
				DensityFunction lowered = DensityFunctions.add(height, DensityFunctions.constant(-carveDepth));
				DensityFunction dryBed = DensityFunctions.max(lowered, DensityFunctions.constant(1.5));
				height = DensityFunctions.lerp(river, height, dryBed);
			}
		}

		if (intent.getLakeDensity() > 0.0) {
			continents = waterBiomeSignal(continents, lake, -0.38);
			double lakeBed = -3.0 - 12.0 * intent.getLakeDepth();
			height = DensityFunctions.lerp(lake, height, DensityFunctions.constant(lakeBed));
		}
		return new Fields(continents, height, river, lake);
	}

	private static DensityFunction riverStrength(
		HydrologyIntent intent,
		DensityFunction inlandGate,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		if (intent.getRiverCoverage() <= 0.0) {
			return DensityFunctions.zero();
		}
		double scale = RIVER_ROUTE_SCALE / intent.getRiverWidth();
		DensityFunction route = DensityFunctions.shiftedNoise2d(
			DensityFunctions.zero(), DensityFunctions.zero(), scale, noises.getOrThrow(Noises.BADLANDS_SURFACE)
		);
		DensityFunction bend = DensityFunctions.shiftedNoise2d(
			DensityFunctions.zero(), DensityFunctions.zero(), scale * 0.47, noises.getOrThrow(Noises.ICEBERG_SURFACE)
		);
		double bendWeight = intent.getRiverMeander() * 0.55;
		DensityFunction routeField = DensityFunctions.add(
			route,
			DensityFunctions.mul(bend, DensityFunctions.constant(bendWeight))
		);
		double sigma = HORIZONTAL_NOISE_SIGMA * Math.sqrt(1.0 + bendWeight * bendWeight);
		double threshold = centralProbabilityThreshold(intent.getRiverCoverage(), sigma);
		DensityFunction corridor = normalizedPositive(
			DensityFunctions.add(
				DensityFunctions.constant(threshold),
				DensityFunctions.mul(routeField.abs(), DensityFunctions.constant(-1.0))
			),
			threshold
		);
		return DensityFunctions.mul(corridor, inlandGate);
	}

	private static DensityFunction lakeStrength(
		HydrologyIntent intent,
		DensityFunction inlandGate,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		if (intent.getLakeDensity() <= 0.0) {
			return DensityFunctions.zero();
		}
		DensityFunction field = DensityFunctions.shiftedNoise2d(
			DensityFunctions.zero(),
			DensityFunctions.zero(),
			LAKE_FIELD_SCALE / intent.getLakeScale(),
			noises.getOrThrow(Noises.SWAMP)
		);
		double threshold = upperTailThreshold(intent.getLakeDensity(), HORIZONTAL_NOISE_SIGMA);
		DensityFunction basin = normalizedPositive(
			DensityFunctions.add(field, DensityFunctions.constant(-threshold)),
			0.18
		);
		return DensityFunctions.mul(basin, inlandGate);
	}

	private static DensityFunction normalizedPositive(DensityFunction input, double width) {
		if (width <= 0.0) {
			return DensityFunctions.zero();
		}
		return DensityFunctions.mul(input.clamp(0.0, width), DensityFunctions.constant(1.0 / width));
	}

	/**
	 * A triangular contour mask spends very little area near one. Terrain and
	 * biome interpolation both need a substantial core, so promote the inner
	 * third to full effect while retaining a smooth bank from zero to one.
	 */
	private static DensityFunction effectStrength(DensityFunction raw) {
		return DensityFunctions.mul(raw, DensityFunctions.constant(3.0)).clamp(0.0, 1.0);
	}

	/**
	 * Publish stable coast and water bands instead of blending them with the
	 * fine continental-detail noise. The matching terrain mask remains smooth;
	 * this only prevents a wide channel from flickering between land and water
	 * biomes every few blocks.
	 */
	private static DensityFunction waterBiomeSignal(
		DensityFunction baseContinents,
		DensityFunction strength,
		double waterContinentalness
	) {
		DensityFunction bank = DensityFunctions.rangeChoice(
			strength,
			0.45,
			0.72,
			DensityFunctions.constant(-0.15),
			baseContinents
		);
		return DensityFunctions.rangeChoice(
			strength,
			0.72,
			1.000_001,
			DensityFunctions.constant(waterContinentalness),
			bank
		);
	}

	/** Threshold whose central interval contains approximately {@code probability}. */
	static double centralProbabilityThreshold(double probability, double sigma) {
		if (probability <= 0.0) {
			return 0.0;
		}
		double cumulative = 0.5 + Math.min(probability, 0.999_999) * 0.5;
		return normalQuantile(cumulative, sigma);
	}

	/** Threshold exceeded by approximately {@code probability} of samples. */
	static double upperTailThreshold(double probability, double sigma) {
		if (probability >= 1.0) {
			return -1.0;
		}
		return normalQuantile(1.0 - Math.max(probability, 0.000_001), sigma);
	}

	private static double normalQuantile(double cumulative, double sigma) {
		double bounded = Math.max(0.000_001, Math.min(0.999_999, cumulative));
		return (sigma / LOGISTIC_NORMAL_SCALE) * Math.log(bounded / (1.0 - bounded));
	}
}
