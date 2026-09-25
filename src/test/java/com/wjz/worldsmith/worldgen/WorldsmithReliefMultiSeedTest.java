package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wjz.worldsmith.core.model.ReliefDistribution;
import com.wjz.worldsmith.core.model.TerrainShape;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Bounded seeded field audit. No chunks, client, server, or game loop. */
class WorldsmithReliefMultiSeedTest {
	private static final long[] SEEDS = {0L, 1L, -1L, 0x574F524C44534D49L, 0x13579BDF2468ACE0L};
	private record Case(String id, double landRatio, double continentScale, double verticalScale,
		double flats, double highlands, double peaks) {}
	private static final List<Case> CASES = List.of(
		new Case("rolling_no_peaks", .92, 1.0, 1.0, .6, .4, 0),
		new Case("compact_no_highlands", .88, .45, 1.2, .45, 0, .55),
		new Case("broad_single_highland", .95, 2.4, .6, 0, 1, 0)
	);
	private record Point(int x, int z, double surfaceY, double erosion) {}
	private record Stats(double p50, double p90, double p99, double maximum,
		double minSurfaceY, double maxSurfaceY, int disabledFamilySamples, int comparisons) {}

	@BeforeAll static void bootstrapRegistries() { WorldsmithTestBootstrap.bootStrap(); }

	@Test
	void severalSeedsPreserveTerrainInvariantsWithoutPromisingOneSmoothingPercentage() throws Exception {
		JsonArray results = new JsonArray();
		StringBuilder csv = new StringBuilder("seed,case,mode,transition_width,samples,adjacent_pairs,p50_jump,p90_jump,p99_jump,max_jump,min_surface_y,max_surface_y,disabled_family_samples\n");
		int sampledColumns = 0;
		for (long seed : SEEDS) {
			for (Case fixture : CASES) {
				List<Point> sharp = sample(state(fixture, 0.0, seed, 'a'));
				List<Point> smooth = sample(state(fixture, .12, seed, 'b'));
				sampledColumns += sharp.size() + smooth.size();
				assertEquals(sharp.size(), smooth.size());
				for (int i = 0; i < sharp.size(); i++) {
					Point a = sharp.get(i), b = smooth.get(i);
					assertEquals(a.erosion, b.erosion, 1.0E-12, fixture.id + " biome identity changed at seed " + seed);
					if (fixture.highlands == 1.0) assertEquals(a.surfaceY, b.surfaceY, 1.0E-9,
						"single-family terrain changed when only transition width changed");
				}
				Stats sharpStats = stats(sharp, fixture), smoothStats = stats(smooth, fixture);
				assertEquals(0, sharpStats.disabledFamilySamples, fixture.id + " sharp disabled-family leak at seed " + seed);
				assertEquals(0, smoothStats.disabledFamilySamples, fixture.id + " smooth disabled-family leak at seed " + seed);
				JsonObject result = new JsonObject();
				result.addProperty("seed", Long.toString(seed));
				result.addProperty("case", fixture.id);
				result.addProperty("landRatio", fixture.landRatio);
				result.addProperty("continentScale", fixture.continentScale);
				result.addProperty("verticalScale", fixture.verticalScale);
				JsonArray weights = new JsonArray(); weights.add(fixture.flats); weights.add(fixture.highlands); weights.add(fixture.peaks);
				result.add("familyWeightsFlatsHighlandsPeaks", weights);
				result.addProperty("samplesPerMode", sharp.size());
				result.addProperty("erosionIdenticalAtEveryMatchedSample", true);
				result.add("sharp", json(sharpStats, 0.0)); result.add("smooth", json(smoothStats, .12));
				results.add(result);
				row(csv, seed, fixture.id, "sharp", 0.0, sharp.size(), sharpStats);
				row(csv, seed, fixture.id, "smooth", .12, smooth.size(), smoothStats);
			}
		}
		assertEquals(61_500, sampledColumns);
		JsonObject report = new JsonObject();
		report.addProperty("sampledColumnsIncludingBothModes", sampledColumns);
		report.addProperty("seedCount", SEEDS.length); report.addProperty("caseCount", CASES.size());
		report.addProperty("allSampledValuesFinite", true);
		report.addProperty("minecraftStarted", false); report.addProperty("chunksGenerated", false);
		report.addProperty("surfaceMethod", "Bracket the uncarved, uninterpolated density-field zero; interpolate only the final adjacent vertical samples.");
		report.addProperty("samplingWindow", "x=-512..512 step 1; z=-997 and 431; no caves, hydrology, anchors or bands");
		report.addProperty("limitations", "Finite transects, not a global statistical distribution or walkability/visual-quality proof. Quantiles use nearest rank over adjacent X pairs within each transect. No per-seed improvement percentage is required.");
		report.add("results", results);
		Path output = Path.of("build", "terrain-relief-audit").toAbsolutePath();
		Files.createDirectories(output);
		Files.writeString(output.resolve("multiseed-relief.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
		Files.writeString(output.resolve("multiseed-relief.csv"), csv, StandardCharsets.UTF_8);
		System.out.println("multiseed relief audit: seeds=" + SEEDS.length + ", cases=" + CASES.size() + ", columns=" + sampledColumns + ", files=" + output);
	}

	private static RandomState state(Case fixture, double width, long seed, char id) {
		TerrainShape.Procedural base = WorldsmithTerrainSamplingTest.shape(fixture.landRatio, fixture.continentScale,
			0.0, fixture.flats, fixture.highlands, fixture.peaks, fixture.verticalScale, 0.0);
		TerrainShape.Procedural shape = new TerrainShape.Procedural(base.getLandRatio(), base.getContinentScale(),
			base.getCoastRoughness(), new ReliefDistribution(fixture.flats, fixture.highlands, fixture.peaks, width),
			base.getVerticalScale(), base.getCaves(), base.getHydrology(), base.getBands(), base.getAnchors());
		return WorldsmithTerrainSamplingTest.state(shape, WorldsmithPacks.builtin().getBiomes().getSpatial(), id, seed);
	}

	private static List<Point> sample(RandomState state) {
		List<Point> samples = new ArrayList<>(2050);
		for (int z : new int[] {-997, 431}) for (int x = -512; x <= 512; x++) {
			int low = -64, high = 319;
			while (high - low > 1) {
				int middle = (low + high) >> 1;
				double depth = state.router().depth().compute(new DensityFunction.SinglePointContext(x, middle, z));
				assertTrue(Double.isFinite(depth), "non-finite density at " + x + "," + middle + "," + z);
				if (depth > 0) low = middle; else high = middle;
			}
			double below = state.router().depth().compute(new DensityFunction.SinglePointContext(x, low, z));
			double above = state.router().depth().compute(new DensityFunction.SinglePointContext(x, high, z));
			assertTrue(Double.isFinite(below) && Double.isFinite(above) && below > 0 && above <= 0);
			double surface = low + below / (below - above);
			double erosion = state.router().erosion().compute(new DensityFunction.SinglePointContext(x, low, z));
			assertTrue(Double.isFinite(surface) && Double.isFinite(erosion));
			assertTrue(surface >= -64 && surface <= 319, "surface escaped the configured terrain envelope");
			samples.add(new Point(x, z, surface, erosion));
		}
		return samples;
	}

	private static Stats stats(List<Point> points, Case fixture) {
		List<Double> jumps = new ArrayList<>();
		int disabled = 0;
		for (int i = 0; i < points.size(); i++) {
			Point p = points.get(i);
			if (p.erosion < -.375 ? fixture.peaks == 0 : p.erosion < .05 ? fixture.highlands == 0 : fixture.flats == 0) disabled++;
			if (i > 0 && points.get(i - 1).z == p.z) jumps.add(Math.abs(p.surfaceY - points.get(i - 1).surfaceY));
		}
		Collections.sort(jumps);
		return new Stats(percentile(jumps, .5), percentile(jumps, .9), percentile(jumps, .99), jumps.getLast(),
			points.stream().mapToDouble(Point::surfaceY).min().orElseThrow(), points.stream().mapToDouble(Point::surfaceY).max().orElseThrow(), disabled, jumps.size());
	}

	private static double percentile(List<Double> values, double q) {
		return values.get(Math.max(0, (int) Math.ceil(values.size() * q) - 1));
	}

	private static JsonObject json(Stats stats, double width) {
		JsonObject result = new JsonObject();
		result.addProperty("transitionWidth", width); result.addProperty("adjacentPairs", stats.comparisons);
		result.addProperty("p50Jump", stats.p50); result.addProperty("p90Jump", stats.p90);
		result.addProperty("p99Jump", stats.p99); result.addProperty("maxJump", stats.maximum);
		result.addProperty("minSurfaceY", stats.minSurfaceY); result.addProperty("maxSurfaceY", stats.maxSurfaceY);
		result.addProperty("disabledFamilySamples", stats.disabledFamilySamples);
		return result;
	}

	private static void row(StringBuilder csv, long seed, String id, String mode, double width, int samples, Stats stats) {
		csv.append(String.format(Locale.ROOT, "%d,%s,%s,%.2f,%d,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%d%n",
			seed, id, mode, width, samples, stats.comparisons, stats.p50, stats.p90, stats.p99, stats.maximum,
			stats.minSurfaceY, stats.maxSurfaceY, stats.disabledFamilySamples));
	}
}
