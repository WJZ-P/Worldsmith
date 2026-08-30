package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wjz.worldsmith.core.model.HydrologyIntent;
import com.wjz.worldsmith.core.model.ReliefDistribution;
import com.wjz.worldsmith.core.model.RiverFill;
import com.wjz.worldsmith.core.model.TerrainPlan;
import com.wjz.worldsmith.core.model.TerrainShape;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.model.WorldsmithPackManifest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Samples Minecraft's wired density graph to verify every hydrology control. */
final class WorldsmithHydrologySamplingTest {
	private static final long SEED = 0x485944524F4C4F47L;
	private static final int SEA_LEVEL = 63;
	private static HolderLookup.Provider activeWorldgen;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		activeWorldgen = WorldsmithPackExporter.compilePatch(
			WorldsmithPacks.builtinCompiled(),
			VanillaRegistries.createLookup()
		).full();
	}

	@Test
	void riverCoverageAndMeanderChangeTheBiomeVisibleNetwork() {
		RandomState baseline = state(hydrology(0.0, 1.0, 1.0, 0.0, RiverFill.FLUID, 0.0, 1.0, 1.0, 1.0), '8');
		RandomState sparse = state(hydrology(0.04, 1.0, 1.0, 0.0, RiverFill.FLUID, 0.0, 1.0, 1.0, 1.0), '9');
		RandomState dense = state(hydrology(0.12, 1.0, 1.0, 0.0, RiverFill.FLUID, 0.0, 1.0, 1.0, 1.0), 'a');
		RandomState meandering = state(hydrology(0.12, 1.0, 1.0, 1.0, RiverFill.FLUID, 0.0, 1.0, 1.0, 1.0), 'b');

		double sparseShare = inlandWaterShare(baseline, sparse, 35_000);
		double denseShare = inlandWaterShare(baseline, dense, 35_000);
		double meanderShare = inlandWaterShare(baseline, meandering, 35_000);
		double changedRoute = routeDifference(baseline, dense, meandering, 35_000);

		assertTrue(denseShare > sparseShare * 1.8, "riverCoverage should increase biome-visible inland water");
		assertTrue(sparseShare > 0.001, "a non-zero river network should reach inland samples");
		assertTrue(changedRoute > 0.01, "riverMeander should move a visible part of the route");
		assertTrue(Math.abs(meanderShare - denseShare) < 0.04, "meander should move rivers rather than erase them");
	}

	@Test
	void riverWidthAndDepthControlChannelGeometry() {
		RandomState baseline = state(hydrology(0.0, 1.0, 1.0, 0.7, RiverFill.FLUID, 0.0, 1.0, 1.0, 1.0), 'c');
		RandomState narrow = state(hydrology(0.08, 0.5, 0.35, 0.7, RiverFill.FLUID, 0.0, 1.0, 1.0, 1.0), 'd');
		RandomState wide = state(hydrology(0.08, 2.0, 0.35, 0.7, RiverFill.FLUID, 0.0, 1.0, 1.0, 1.0), 'e');
		RandomState deep = state(hydrology(0.08, 0.5, 2.0, 0.7, RiverFill.FLUID, 0.0, 1.0, 1.0, 1.0), 'f');

		double narrowRun = averageWaterRunBlocks(baseline, narrow);
		double wideRun = averageWaterRunBlocks(baseline, wide);
		double narrowCorrelation = waterCorrelation(baseline, narrow, 32, 30_000);
		double wideCorrelation = waterCorrelation(baseline, wide, 32, 30_000);
		double shallowFloor = averageChannelFloor(baseline, narrow, narrow, 500);
		double deepFloor = averageChannelFloor(baseline, narrow, deep, 500);

		assertTrue(wideRun > narrowRun * 1.6, "riverWidth should produce physically wider water runs");
		assertTrue(wideCorrelation > narrowCorrelation + 0.20, "wide rivers should remain coherent over longer distances");
		assertTrue(deepFloor < shallowFloor - 8.0, "riverDepth should lower the solid channel floor");
	}

	@Test
	void dryRiversCarveLandWithoutPublishingAnAquaticBiomeSignal() {
		RandomState baseline = state(hydrology(0.0, 1.0, 1.0, 0.65, RiverFill.FLUID, 0.0, 1.0, 1.0, 1.0), '1');
		RandomState wet = state(hydrology(0.10, 1.0, 1.0, 0.65, RiverFill.FLUID, 0.0, 1.0, 1.0, 1.0), '2');
		RandomState dry = state(hydrology(0.10, 1.0, 1.0, 0.65, RiverFill.DRY, 0.0, 1.0, 1.0, 1.0), '3');

		DryMetrics metrics = dryMetrics(baseline, wet, dry, 600);

		assertTrue(metrics.loweredSamples() > 100, "dry routes should carve many sampled land columns");
		assertTrue(metrics.averageDrop() > 5.0, "dry river depth should be visible in the surface height");
		assertTrue(metrics.minimumFloor() >= SEA_LEVEL - 2, "dry channels should stay above global fluid level");
		assertTrue(metrics.maximumContinentsDifference() < 1.0E-9, "dry routes should retain their land biome signal");
	}

	@Test
	void lakeDensityAndScaleControlBasinCoverageAndSize() {
		RandomState baseline = state(hydrology(0.0, 1.0, 1.0, 0.5, RiverFill.FLUID, 0.0, 1.0, 1.0, 1.0), '4');
		RandomState sparse = state(hydrology(0.0, 1.0, 1.0, 0.5, RiverFill.FLUID, 0.04, 0.7, 0.8, 1.0), '5');
		RandomState dense = state(hydrology(0.0, 1.0, 1.0, 0.5, RiverFill.FLUID, 0.14, 0.7, 0.8, 1.0), '6');
		RandomState large = state(hydrology(0.0, 1.0, 1.0, 0.5, RiverFill.FLUID, 0.14, 3.0, 0.8, 1.0), '7');
		RandomState deep = state(hydrology(0.0, 1.0, 1.0, 0.5, RiverFill.FLUID, 0.14, 0.7, 2.2, 1.0), 'a');

		double sparseShare = inlandWaterShare(baseline, sparse, 35_000);
		double denseShare = inlandWaterShare(baseline, dense, 35_000);
		double smallRun = averageWaterRunBlocks(baseline, dense);
		double largeRun = averageWaterRunBlocks(baseline, large);
		double smallCorrelation = waterCorrelation(baseline, dense, 32, 30_000);
		double largeCorrelation = waterCorrelation(baseline, large, 32, 30_000);
		double ordinaryFloor = averageChannelFloor(baseline, dense, dense, 500);
		double deepFloor = averageChannelFloor(baseline, dense, deep, 500);

		assertTrue(denseShare > sparseShare * 1.7, "lakeDensity should increase inland basin coverage");
		assertTrue(sparseShare > 0.001, "non-zero lake density should create visible basins");
		assertTrue(largeRun > smallRun * 1.5, "lakeScale should create broader connected basins");
		assertTrue(largeCorrelation > smallCorrelation + 0.15, "large lakes should remain coherent over longer distances");
		assertTrue(deepFloor < ordinaryFloor - 10.0, "lakeDepth should lower the basin floor");
	}

	@Test
	void oceanDepthOnlyMovesTheOceanFloor() {
		RandomState shallow = state(hydrology(0.0, 1.0, 1.0, 0.5, RiverFill.FLUID, 0.0, 1.0, 1.0, 0.45), '8');
		RandomState deep = state(hydrology(0.0, 1.0, 1.0, 0.5, RiverFill.FLUID, 0.0, 1.0, 1.0, 2.2), '9');

		OceanMetrics metrics = oceanMetrics(shallow, deep, 700);

		assertTrue(metrics.deepFloor() < metrics.shallowFloor() - 25.0, "oceanDepth should lower deep-ocean terrain");
		assertTrue(metrics.averageLandDelta() < 1.0, "oceanDepth should leave inland surface heights stable");
	}

	private static HydrologyIntent hydrology(
		double riverCoverage,
		double riverWidth,
		double riverDepth,
		double riverMeander,
		RiverFill riverFill,
		double lakeDensity,
		double lakeScale,
		double lakeDepth,
		double oceanDepth
	) {
		return new HydrologyIntent(
			riverCoverage,
			riverWidth,
			riverDepth,
			riverMeander,
			riverFill,
			lakeDensity,
			lakeScale,
			lakeDepth,
			oceanDepth
		);
	}

	private static RandomState state(HydrologyIntent hydrology, char idCharacter) {
		WorldsmithPack source = WorldsmithPacks.builtin();
		TerrainPlan template = source.getTerrain();
		TerrainShape.Procedural shape = new TerrainShape.Procedural(
			0.80,
			1.0,
			0.35,
			new ReliefDistribution(1.0, 0.0, 0.0),
			1.0,
			0.0,
			hydrology
		);
		TerrainPlan terrain = new TerrainPlan(
			template.getSchemaVersion(),
			template.getSeed(),
			template.getMinY(),
			template.getHeight(),
			template.getHorizontalNoiseSize(),
			template.getVerticalNoiseSize(),
			template.getSeaLevel(),
			template.getDefaultBlock(),
			template.getDefaultFluid(),
			shape,
			false,
			template.getOreVeinsEnabled(),
			template.getLegacyRandomSource(),
			template.getSpawnTargets()
		);
		String id = String.valueOf(idCharacter).repeat(64);
		WorldsmithPackManifest oldManifest = source.getManifest();
		WorldsmithPackManifest manifest = new WorldsmithPackManifest(
			oldManifest.getFormatVersion(), id, "Hydrology sample", "Compiler fixture", oldManifest.getFiles()
		);
		CompiledPack compiledPack = CompiledPack.scoped(new WorldsmithPack(
			manifest, terrain, source.getBiomes(), source.getFeatures(), id
		));
		HolderLookup.Provider registries = WorldsmithPackExporter.compilePatch(compiledPack, activeWorldgen).full();
		return RandomState.create(registries, compiledPack.noiseSettingsKey(), SEED);
	}

	private static double inlandWaterShare(RandomState baseline, RandomState hydrology, int sampleCount) {
		Random random = new Random(0x5249564552L);
		int inland = 0;
		int water = 0;
		for (int i = 0; i < sampleCount; i++) {
			int x = random.nextInt(-120_000, 120_001);
			int z = random.nextInt(-120_000, 120_001);
			DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, 0, z);
			if (baseline.router().continents().compute(point) <= 0.12) {
				continue;
			}
			inland++;
			if (hydrology.router().continents().compute(point) < -0.19) {
				water++;
			}
		}
		assertTrue(inland > sampleCount / 3, "expected enough inland points");
		return (double) water / inland;
	}

	private static double routeDifference(RandomState baseline, RandomState first, RandomState second, int sampleCount) {
		Random random = new Random(0x4D45414E444552L);
		int inland = 0;
		int different = 0;
		for (int i = 0; i < sampleCount; i++) {
			int x = random.nextInt(-120_000, 120_001);
			int z = random.nextInt(-120_000, 120_001);
			DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, 0, z);
			if (baseline.router().continents().compute(point) <= 0.12) {
				continue;
			}
			inland++;
			boolean firstWater = first.router().continents().compute(point) < -0.19;
			boolean secondWater = second.router().continents().compute(point) < -0.19;
			if (firstWater != secondWater) {
				different++;
			}
		}
		return (double) different / inland;
	}

	private static double averageWaterRunBlocks(RandomState baseline, RandomState hydrology) {
		List<Integer> runs = new ArrayList<>();
		for (int z = -18_000; z <= 18_000; z += 1_200) {
			int run = 0;
			for (int x = -24_000; x <= 24_000; x += 4) {
				DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, 0, z);
				boolean water = baseline.router().continents().compute(point) > 0.12
					&& hydrology.router().continents().compute(point) < -0.19;
				if (water) {
					run += 4;
				} else if (run > 0) {
					runs.add(run);
					run = 0;
				}
			}
			if (run > 0) {
				runs.add(run);
			}
		}
		assertTrue(runs.size() > 10, "expected enough water runs, got " + runs.size());
		return runs.stream().mapToInt(Integer::intValue).average().orElseThrow();
	}

	private static double waterCorrelation(RandomState baseline, RandomState hydrology, int offset, int samples) {
		Random random = new Random(0x434F5252454C4154L + offset);
		int water = 0;
		int stillWater = 0;
		for (int i = 0; i < samples; i++) {
			int x = random.nextInt(-100_000, 100_001);
			int z = random.nextInt(-100_000, 100_001);
			DensityFunction.SinglePointContext first = new DensityFunction.SinglePointContext(x, 0, z);
			if (baseline.router().continents().compute(first) <= 0.12
				|| hydrology.router().continents().compute(first) >= -0.19) {
				continue;
			}
			water++;
			DensityFunction.SinglePointContext second = new DensityFunction.SinglePointContext(x + offset, 0, z);
			if (baseline.router().continents().compute(second) > 0.12
				&& hydrology.router().continents().compute(second) < -0.19) {
				stillWater++;
			}
		}
		assertTrue(water > 100, "expected enough water points for correlation");
		return (double) stillWater / water;
	}

	private static double averageChannelFloor(
		RandomState baseline,
		RandomState route,
		RandomState measured,
		int requestedSamples
	) {
		Random random = new Random(0x4445505448L);
		long sum = 0;
		int samples = 0;
		for (int attempt = 0; attempt < 300_000 && samples < requestedSamples; attempt++) {
			int x = random.nextInt(-80_000, 80_001);
			int z = random.nextInt(-80_000, 80_001);
			DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, 0, z);
			if (baseline.router().continents().compute(point) > 0.12
				&& route.router().continents().compute(point) < -0.24) {
				sum += surfaceY(measured, x, z);
				samples++;
			}
		}
		assertTrue(samples >= requestedSamples / 2, "expected channel floor samples, got " + samples);
		return (double) sum / samples;
	}

	private static DryMetrics dryMetrics(RandomState baseline, RandomState wet, RandomState dry, int requestedSamples) {
		Random random = new Random(0x4452595249564552L);
		int lowered = 0;
		long totalDrop = 0;
		int minimumFloor = Integer.MAX_VALUE;
		double maxContinentsDifference = 0.0;
		for (int attempt = 0; attempt < 400_000 && lowered < requestedSamples; attempt++) {
			int x = random.nextInt(-80_000, 80_001);
			int z = random.nextInt(-80_000, 80_001);
			DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, 0, z);
			double baseContinents = baseline.router().continents().compute(point);
			if (baseContinents <= 0.12 || wet.router().continents().compute(point) >= -0.24) {
				continue;
			}
			int baseSurface = surfaceY(baseline, x, z);
			int drySurface = surfaceY(dry, x, z);
			if (drySurface < baseSurface) {
				lowered++;
				totalDrop += baseSurface - drySurface;
				minimumFloor = Math.min(minimumFloor, drySurface);
			}
			maxContinentsDifference = Math.max(
				maxContinentsDifference,
				Math.abs(baseContinents - dry.router().continents().compute(point))
			);
		}
		return new DryMetrics(
			lowered,
			lowered == 0 ? 0.0 : (double) totalDrop / lowered,
			minimumFloor,
			maxContinentsDifference
		);
	}

	private static OceanMetrics oceanMetrics(RandomState shallow, RandomState deep, int requestedSamples) {
		Random random = new Random(0x4F4345414EL);
		long shallowOcean = 0;
		long deepOcean = 0;
		long landDelta = 0;
		int oceanSamples = 0;
		int landSamples = 0;
		for (int attempt = 0; attempt < 300_000 && (oceanSamples < requestedSamples || landSamples < requestedSamples); attempt++) {
			int x = random.nextInt(-100_000, 100_001);
			int z = random.nextInt(-100_000, 100_001);
			DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, 0, z);
			double continents = shallow.router().continents().compute(point);
			if (continents < -0.55 && oceanSamples < requestedSamples) {
				shallowOcean += surfaceY(shallow, x, z);
				deepOcean += surfaceY(deep, x, z);
				oceanSamples++;
			} else if (continents > 0.20 && landSamples < requestedSamples) {
				landDelta += Math.abs(surfaceY(shallow, x, z) - surfaceY(deep, x, z));
				landSamples++;
			}
		}
		assertTrue(oceanSamples > requestedSamples / 2, "expected ocean samples, got " + oceanSamples);
		assertTrue(landSamples > requestedSamples / 2, "expected land samples, got " + landSamples);
		return new OceanMetrics(
			(double) shallowOcean / oceanSamples,
			(double) deepOcean / oceanSamples,
			(double) landDelta / landSamples
		);
	}

	private static int surfaceY(RandomState state, int x, int z) {
		DensityFunction density = state.router().finalDensity();
		for (int y = 319; y >= -64; y -= 2) {
			if (density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0) {
				return y;
			}
		}
		return -64;
	}

	private record DryMetrics(
		int loweredSamples,
		double averageDrop,
		int minimumFloor,
		double maximumContinentsDifference
	) {
	}

	private record OceanMetrics(double shallowFloor, double deepFloor, double averageLandDelta) {
	}
}
