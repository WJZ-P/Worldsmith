package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wjz.worldsmith.core.model.ReliefDistribution;
import com.wjz.worldsmith.core.model.HydrologyIntent;
import com.wjz.worldsmith.core.model.TerrainPlan;
import com.wjz.worldsmith.core.model.TerrainShape;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.model.WorldsmithPackManifest;
import java.util.ArrayList;
import java.util.Collections;
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

/**
 * Outcome-level checks for the semantic terrain controls.
 *
 * <p>These tests wire Minecraft's real noises with a fixed seed and sample the
 * resulting density functions. They therefore catch a field that is accepted
 * by JSON but accidentally ignored by the target-version compiler.
 */
final class WorldsmithTerrainSamplingTest {
	private static final long SEED = 0x574F524C44534D49L;
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
	void landRatioChangesTheSampledLandShare() {
		RandomState oceanic = state(shape(0.20, 1.0, 0.45, 0.65, 0.25, 0.10, 1.0, 0.0), 'b');
		RandomState continental = state(shape(0.82, 1.0, 0.45, 0.65, 0.25, 0.10, 1.0, 0.0), 'c');

		double oceanicLand = landShare(oceanic, 12_000);
		double continentalLand = landShare(continental, 12_000);

		assertTrue(
			continentalLand > oceanicLand + 0.35,
			() -> "landRatio should strongly change land share: low=" + oceanicLand + ", high=" + continentalLand
		);
		assertTrue(Math.abs(oceanicLand - 0.20) < 0.06, () -> "sampled low ratio was " + oceanicLand);
		assertTrue(Math.abs(continentalLand - 0.82) < 0.06, () -> "sampled high ratio was " + continentalLand);
	}

	@Test
	void continentScaleAndCoastRoughnessChangeHorizontalShape() {
		RandomState archipelago = state(shape(0.55, 0.30, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0), 'd');
		RandomState supercontinents = state(shape(0.55, 4.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0), 'e');
		RandomState smooth = state(shape(0.55, 1.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0), 'f');
		RandomState rough = state(shape(0.55, 1.0, 1.0, 1.0, 0.0, 0.0, 1.0, 0.0), '1');

		int smallScaleTransitions = coastlineTransitions(archipelago, 64);
		int largeScaleTransitions = coastlineTransitions(supercontinents, 64);
		int smoothTransitions = coastlineTransitions(smooth, 16);
		int roughTransitions = coastlineTransitions(rough, 16);

		assertTrue(
			smallScaleTransitions > largeScaleTransitions * 2,
			() -> "larger continentScale should create broader regions: small=" + smallScaleTransitions
				+ ", large=" + largeScaleTransitions
		);
		assertTrue(
			roughTransitions > smoothTransitions,
			() -> "coastRoughness should add shoreline detail: smooth=" + smoothTransitions
				+ ", rough=" + roughTransitions
		);
	}

	@Test
	void reliefWeightsAndVerticalScaleChangeSurfaceHeights() {
		RandomState flats = state(shape(0.78, 1.0, 0.3, 1.0, 0.0, 0.0, 1.0, 0.0), '2');
		RandomState peaks = state(shape(0.78, 1.0, 0.3, 0.0, 0.0, 1.0, 1.0, 0.0), '3');
		RandomState low = state(shape(0.78, 1.0, 0.3, 0.2, 0.3, 0.5, 0.45, 0.0), '4');
		RandomState tall = state(shape(0.78, 1.0, 0.3, 0.2, 0.3, 0.5, 2.2, 0.0), '5');
		assertReliefShares(flats, 0.20, 0.30, 0.50);

		double flatP90 = surfacePercentile(flats, 0.90);
		double peakP90 = surfacePercentile(peaks, 0.90);
		double lowP90 = surfacePercentile(low, 0.90);
		double tallP90 = surfacePercentile(tall, 0.90);

		assertTrue(
			peakP90 > flatP90 + 35.0,
			() -> "peak relief should raise the upper surface: flats=" + flatP90 + ", peaks=" + peakP90
		);
		assertTrue(
			tallP90 > lowP90 + 35.0,
			() -> "verticalScale should change height amplitude: low=" + lowP90 + ", tall=" + tallP90
		);
	}

	@Test
	void caveDensityChangesHowMuchSolidTerrainIsCarved() {
		TerrainShape.Procedural solidShape = shape(0.82, 1.0, 0.3, 0.5, 0.35, 0.15, 1.0, 0.0);
		TerrainShape.Procedural caveShape = shape(0.82, 1.0, 0.3, 0.5, 0.35, 0.15, 1.0, 1.0);
		RandomState solid = state(solidShape, '6');
		RandomState caves = state(caveShape, '7');

		int carved = carvedSamples(solid, caves);

		assertTrue(carved > 150, () -> "full cave density should carve sampled solid points, got " + carved);
	}

	private static TerrainShape.Procedural shape(
		double landRatio,
		double continentScale,
		double coastRoughness,
		double flats,
		double highlands,
		double peaks,
		double verticalScale,
		double caveDensity
	) {
		return new TerrainShape.Procedural(
			landRatio,
			continentScale,
			coastRoughness,
			new ReliefDistribution(flats, highlands, peaks),
			verticalScale,
			caveDensity,
			new HydrologyIntent()
		);
	}

	private static RandomState state(TerrainShape.Procedural shape, char idCharacter) {
		WorldsmithPack source = WorldsmithPacks.builtin();
		TerrainPlan template = source.getTerrain();
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
			template.getAquifersEnabled(),
			template.getOreVeinsEnabled(),
			template.getLegacyRandomSource(),
			template.getSpawnTargets()
		);
		String id = String.valueOf(idCharacter).repeat(64);
		WorldsmithPackManifest oldManifest = source.getManifest();
		WorldsmithPackManifest manifest = new WorldsmithPackManifest(
			oldManifest.getFormatVersion(), id, "Terrain sample", "Compiler fixture", oldManifest.getFiles()
		);
		CompiledPack compiledPack = CompiledPack.scoped(new WorldsmithPack(
			manifest, terrain, source.getBiomes(), source.getFeatures(), id
		));
		HolderLookup.Provider registries = WorldsmithPackExporter.compilePatch(compiledPack, activeWorldgen).full();
		return RandomState.create(registries, compiledPack.noiseSettingsKey(), SEED);
	}

	private static double landShare(RandomState state, int samples) {
		Random random = new Random(0x534D495448L);
		DensityFunction continents = state.router().continents();
		int land = 0;
		for (int i = 0; i < samples; i++) {
			int x = random.nextInt(-100_000, 100_001);
			int z = random.nextInt(-100_000, 100_001);
			if (continents.compute(new DensityFunction.SinglePointContext(x, 0, z)) > -0.11) {
				land++;
			}
		}
		return (double) land / samples;
	}

	private static int coastlineTransitions(RandomState state, int step) {
		DensityFunction continents = state.router().continents();
		int transitions = 0;
		for (int z = -12_000; z <= 12_000; z += 1_500) {
			boolean previous = false;
			boolean hasPrevious = false;
			for (int x = -24_000; x <= 24_000; x += step) {
				boolean land = continents.compute(new DensityFunction.SinglePointContext(x, 0, z)) > -0.11;
				if (hasPrevious && land != previous) {
					transitions++;
				}
				previous = land;
				hasPrevious = true;
			}
		}
		return transitions;
	}

	private static double surfacePercentile(RandomState state, double percentile) {
		DensityFunction continents = state.router().continents();
		DensityFunction density = state.router().finalDensity();
		List<Integer> heights = new ArrayList<>();
		for (int z = -3_200; z <= 3_200; z += 160) {
			for (int x = -3_200; x <= 3_200; x += 160) {
				DensityFunction.SinglePointContext horizontal = new DensityFunction.SinglePointContext(x, 0, z);
				if (continents.compute(horizontal) <= 0.20) {
					continue;
				}
				for (int y = 319; y >= -64; y -= 4) {
					if (density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0) {
						heights.add(y);
						break;
					}
				}
			}
		}
		assertTrue(heights.size() > 100, () -> "expected enough inland samples, got " + heights.size());
		Collections.sort(heights);
		return heights.get((int) Math.floor((heights.size() - 1) * percentile));
	}

	private static int carvedSamples(RandomState solid, RandomState caves) {
		DensityFunction land = solid.router().continents();
		DensityFunction solidDensity = solid.router().finalDensity();
		DensityFunction caveDensity = caves.router().finalDensity();
		int carved = 0;
		for (int z = -4_000; z <= 4_000; z += 96) {
			for (int x = -4_000; x <= 4_000; x += 96) {
				if (land.compute(new DensityFunction.SinglePointContext(x, 0, z)) <= 0.10) {
					continue;
				}
				for (int y = -48; y <= 32; y += 8) {
					DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, y, z);
					if (solidDensity.compute(point) > 0.0 && caveDensity.compute(point) <= 0.0) {
						carved++;
					}
				}
			}
		}
		return carved;
	}

	private static void assertReliefShares(RandomState state, double flats, double highlands, double peaks) {
		Random random = new Random(1234L);
		double flatThreshold = WorldsmithNoiseSettings.reliefThreshold(flats);
		double highlandThreshold = WorldsmithNoiseSettings.reliefThreshold(flats + highlands);
		int flatSamples = 0;
		int highlandSamples = 0;
		int peakSamples = 0;
		for (int i = 0; i < 40_000; i++) {
			double value = state.router().ridges().compute(new DensityFunction.SinglePointContext(
				random.nextInt(-100_000, 100_001), 0, random.nextInt(-100_000, 100_001)
			));
			if (value < flatThreshold) {
				flatSamples++;
			} else if (value < highlandThreshold) {
				highlandSamples++;
			} else {
				peakSamples++;
			}
		}
		double count = flatSamples + highlandSamples + peakSamples;
		assertTrue(Math.abs(flatSamples / count - flats) < 0.035, "flat share should track its requested weight");
		assertTrue(Math.abs(highlandSamples / count - highlands) < 0.035, "highland share should track its requested weight");
		assertTrue(Math.abs(peakSamples / count - peaks) < 0.035, "peak share should track its requested weight");
	}
}
