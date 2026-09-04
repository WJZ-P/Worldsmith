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
 * <p>The analyzer predicts how much of a world each biome covers from measured
 * continuous-axis spreads and from the authored three-part landform mixture.
 * Nothing else connects the two: change the wired noise and the report keeps
 * answering confidently about a world that no longer exists, which is worse
 * than having no report at all.
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
	void continuousAxesStillHaveTheSpreadTheAnalyzerAssumes() {
		double[] sigma = measure(0.5);
		DepthFit fit = measureDepthFit(0.5);

		assertClose("temperature", BiomeDistributionAnalyzer.TEMPERATURE_SIGMA, sigma[0]);
		assertClose("humidity", BiomeDistributionAnalyzer.HUMIDITY_SIGMA, sigma[1]);
		assertClose("continentalness", BiomeDistributionAnalyzer.CONTINENTALNESS_SIGMA, sigma[2]);
		assertClose("depth", BiomeDistributionAnalyzer.DEPTH_SIGMA, sigma[4]);
		assertClose("weirdness", BiomeDistributionAnalyzer.WEIRDNESS_SIGMA, sigma[5]);
		assertClose("depth regression slope", BiomeDistributionAnalyzer.DEPTH_CONTINENTALNESS_SLOPE, fit.slope());
		assertClose("depth regression intercept", BiomeDistributionAnalyzer.DEPTH_INTERCEPT, fit.intercept());
		assertClose("depth residual sigma", BiomeDistributionAnalyzer.DEPTH_RESIDUAL_SIGMA, fit.residualSigma());
	}

	private static DepthFit measureDepthFit(double landRatio) {
		Climate.Sampler sampler = sampler(landRatio);
		Random random = new Random(9876L);
		double sumX = 0.0, sumY = 0.0, sumXX = 0.0, sumXY = 0.0, sumYY = 0.0;
		for (int i = 0; i < SAMPLES; i++) {
			double[] point = sample(sampler, random);
			double x = point[2], y = point[4];
			sumX += x; sumY += y; sumXX += x * x; sumXY += x * y; sumYY += y * y;
		}
		double meanX = sumX / SAMPLES, meanY = sumY / SAMPLES;
		double varianceX = sumXX / SAMPLES - meanX * meanX;
		double covariance = sumXY / SAMPLES - meanX * meanY;
		double slope = covariance / varianceX;
		double intercept = meanY - slope * meanX;
		double varianceY = sumYY / SAMPLES - meanY * meanY;
		double residual = Math.sqrt(Math.max(0.0, varianceY - slope * covariance));
		return new DepthFit(slope, intercept, residual);
	}

	private record DepthFit(double slope, double intercept, double residualSigma) {
	}

	@Test
	void proceduralLandformBandsFollowTheAuthoredReliefWeights() {
		LandformStats measured = measureLandform(0.20, 0.30, 0.50);

		assertShare("flats", 0.20, measured.share()[0]);
		assertShare("highlands", 0.30, measured.share()[1]);
		assertShare("peaks", 0.50, measured.share()[2]);
	}

	@Test
	void everyLandformBandKeepsTheCompilerCenterAndTextureSpread() {
		LandformStats measured = measureLandform(0.34, 0.33, 0.33);
		double[] centers = {
			BiomeDistributionAnalyzer.FLATS_LANDFORM_CENTER,
			BiomeDistributionAnalyzer.HIGHLANDS_LANDFORM_CENTER,
			BiomeDistributionAnalyzer.PEAKS_LANDFORM_CENTER
		};
		double[] spreads = {
			BiomeDistributionAnalyzer.LANDFORM_TEXTURE_SIGMA
				* BiomeDistributionAnalyzer.FLATS_LANDFORM_TEXTURE,
			BiomeDistributionAnalyzer.LANDFORM_TEXTURE_SIGMA
				* BiomeDistributionAnalyzer.HIGHLANDS_LANDFORM_TEXTURE,
			BiomeDistributionAnalyzer.LANDFORM_TEXTURE_SIGMA
				* BiomeDistributionAnalyzer.PEAKS_LANDFORM_TEXTURE
		};

		for (int band = 0; band < 3; band++) {
			assertTrue(
				Math.abs(measured.mean()[band] - centers[band]) < 0.02,
				"landform " + band + " mean was " + measured.mean()[band] + " instead of " + centers[band]
			);
			assertTrue(
				Math.abs(measured.sigma()[band] - spreads[band]) < 0.015,
				"landform " + band + " sigma was " + measured.sigma()[band] + " instead of " + spreads[band]
			);
		}
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
		return sampler(landRatio, 0.65, 0.25, 0.10);
	}

	private static Climate.Sampler sampler(double landRatio, double flats, double highlands, double peaks) {
		RandomState state = WorldsmithTerrainSamplingTest.state(
			WorldsmithTerrainSamplingTest.shape(landRatio, 1.0, 0.45, flats, highlands, peaks, 1.0, 0.0),
			"abcdef".charAt((int) (landRatio * 10) % 6)
		);
		return state.sampler();
	}

	private static void assertShare(String band, double expected, double measured) {
		assertTrue(
			Math.abs(expected - measured) < 0.04,
			band + " share was " + measured + " but the terrain asks for " + expected
		);
	}

	private static LandformStats measureLandform(double flats, double highlands, double peaks) {
		Climate.Sampler sampler = sampler(0.5, flats, highlands, peaks);
		Random random = new Random(2468L);
		int[] count = new int[3];
		double[] sum = new double[3];
		double[] sumSquares = new double[3];
		for (int i = 0; i < SAMPLES; i++) {
			double erosion = sample(sampler, random)[3];
			int band = erosion >= 0.05 ? 0 : erosion >= -0.375 ? 1 : 2;
			count[band]++;
			sum[band] += erosion;
			sumSquares[band] += erosion * erosion;
		}

		double[] share = new double[3];
		double[] mean = new double[3];
		double[] sigma = new double[3];
		for (int band = 0; band < 3; band++) {
			share[band] = count[band] / (double) SAMPLES;
			mean[band] = sum[band] / count[band];
			sigma[band] = Math.sqrt(sumSquares[band] / count[band] - mean[band] * mean[band]);
		}
		return new LandformStats(share, mean, sigma);
	}

	private record LandformStats(double[] share, double[] mean, double[] sigma) {
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
