package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wjz.worldsmith.core.analysis.BiomeDistributionAnalyzer;
import java.util.Random;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Holds the distribution analyzer to the noise it claims to describe.
 *
 * <p>The analyzer predicts how much of a world each biome covers by drawing
 * samples from a normal distribution per climate axis, and every one of those
 * sigmas was measured here rather than assumed. Nothing else connects the two:
 * change the wired noise and the report keeps answering confidently about a
 * world that no longer exists, which is worse than having no report at all.
 */
class ClimateAxisCalibrationTest {
	private static final int SAMPLES = 20_000;
	/** Sampling noise plus the spread across land ratios; measured drift is well inside this. */
	private static final double TOLERANCE = 0.03;

	@BeforeAll
	static void bootstrap() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void everyAxisStillHasTheSpreadTheAnalyzerAssumes() {
		double[] sigma = measure(0.5);

		assertClose("temperature", BiomeDistributionAnalyzer.TEMPERATURE_SIGMA, sigma[0]);
		assertClose("humidity", BiomeDistributionAnalyzer.HUMIDITY_SIGMA, sigma[1]);
		assertClose("continentalness", BiomeDistributionAnalyzer.CONTINENTALNESS_SIGMA, sigma[2]);
		assertClose("erosion", BiomeDistributionAnalyzer.EROSION_SIGMA, sigma[3]);
		assertClose("depth", BiomeDistributionAnalyzer.DEPTH_SIGMA, sigma[4]);
		assertClose("weirdness", BiomeDistributionAnalyzer.WEIRDNESS_SIGMA, sigma[5]);
	}

	@Test
	void continentalnessMovesWithTheRequestedLandRatio() {
		// The analyzer re-derives the coastline from landRatio with the same
		// log-odds calibration the compiler uses. If that ever stops tracking,
		// every land/water number the report prints is wrong.
		double oceanic = measureLandShare(0.20);
		double continental = measureLandShare(0.85);

		assertTrue(Math.abs(oceanic - 0.20) < 0.05, "oceanic land share was " + oceanic);
		assertTrue(Math.abs(continental - 0.85) < 0.05, "continental land share was " + continental);
	}

	private static void assertClose(String axis, double expected, double measured) {
		assertTrue(
			Math.abs(expected - measured) < TOLERANCE,
			axis + " sigma is " + measured + " but the analyzer assumes " + expected
		);
	}

	private static Climate.Sampler sampler(double landRatio) {
		RandomState state = WorldsmithTerrainSamplingTest.state(
			WorldsmithTerrainSamplingTest.shape(landRatio, 1.0, 0.45, 0.65, 0.25, 0.10, 1.0, 0.0),
			"abcdef".charAt((int) (landRatio * 10) % 6)
		);
		return state.sampler();
	}

	private static double[] measure(double landRatio) {
		Climate.Sampler sampler = sampler(landRatio);
		Random random = new Random(1234L);
		double[] sum = new double[6];
		double[] sumSquares = new double[6];

		for (int i = 0; i < SAMPLES; i++) {
			double[] point = sample(sampler, random);
			for (int axis = 0; axis < 6; axis++) {
				sum[axis] += point[axis];
				sumSquares[axis] += point[axis] * point[axis];
			}
		}

		double[] sigma = new double[6];
		for (int axis = 0; axis < 6; axis++) {
			double mean = sum[axis] / SAMPLES;
			sigma[axis] = Math.sqrt(sumSquares[axis] / SAMPLES - mean * mean);
		}
		return sigma;
	}

	private static double measureLandShare(double landRatio) {
		Climate.Sampler sampler = sampler(landRatio);
		Random random = new Random(4321L);
		int land = 0;
		for (int i = 0; i < SAMPLES; i++) {
			if (sample(sampler, random)[2] > -0.11) {
				land++;
			}
		}
		return land / (double) SAMPLES;
	}

	private static double[] sample(Climate.Sampler sampler, Random random) {
		int x = random.nextInt(400_000) - 200_000;
		int z = random.nextInt(400_000) - 200_000;
		Climate.TargetPoint point = sampler.sample(x >> 2, 16, z >> 2);
		return new double[] {
			Climate.unquantizeCoord(point.temperature()),
			Climate.unquantizeCoord(point.humidity()),
			Climate.unquantizeCoord(point.continentalness()),
			Climate.unquantizeCoord(point.erosion()),
			Climate.unquantizeCoord(point.depth()),
			Climate.unquantizeCoord(point.weirdness())
		};
	}
}
