package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wjz.worldsmith.core.model.HydrologyIntent;
import com.wjz.worldsmith.core.model.ReliefDistribution;
import com.wjz.worldsmith.core.model.RiverFill;
import com.wjz.worldsmith.core.model.Anchor;
import com.wjz.worldsmith.core.model.AnchorClimateBias;
import com.wjz.worldsmith.core.model.AnchorPlacement;
import com.wjz.worldsmith.core.model.BandEffect;
import com.wjz.worldsmith.core.model.BandRegion;
import com.wjz.worldsmith.core.model.BiomePlan;
import com.wjz.worldsmith.core.model.BiomeSpatialSettings;
import com.wjz.worldsmith.core.model.CaveIntent;
import com.wjz.worldsmith.core.model.CaveVerticalRange;
import com.wjz.worldsmith.core.model.TerrainBand;
import com.wjz.worldsmith.core.model.TerrainPlan;
import com.wjz.worldsmith.core.model.TerrainShape;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.model.WorldsmithPackManifest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Random;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
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
	/** Where the relief bands cut erosion between peaks and highland. */
	private static final double PEAK_BAND_EDGE = -0.375;
	private static final long SEED = 0x574F524C44534D49L;
	private static HolderLookup.Provider activeWorldgen;

	@BeforeAll
	static void bootstrapMinecraft() {
		activeWorldgen();
	}

	/**
	 * Built on demand rather than in {@code @BeforeAll}, because another test
	 * class reuses {@link #state} and would otherwise get a null provider
	 * depending on which class JUnit happened to run first.
	 */
	static synchronized HolderLookup.Provider activeWorldgen() {
		if (activeWorldgen == null) {
			WorldsmithTestBootstrap.bootStrap();
			activeWorldgen = WorldsmithPackExporter.compilePatch(
				WorldsmithPacks.builtinCompiled(),
				VanillaRegistries.createLookup()
			).full();
		}
		return activeWorldgen;
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

	/**
	 * The ground field is a height function, so no combination of the other
	 * knobs can put stone in the air with nothing beneath it. This is the test
	 * that separates real islands from tall spires that merely look like them.
	 */
	@Test
	void skyIslandsPutSolidGroundWithNothingBeneathIt() {
		RandomState grounded = state(bandShape(), '0');
		RandomState floating = state(
			bandShape(island(0.35, 180, 250, BandRegion.ANYWHERE)), '8'
		);

		int groundedIslands = detachedColumns(grounded, 180, 250, 40);
		int floatingIslands = detachedColumns(floating, 180, 250, 40);

		assertTrue(
			groundedIslands == 0,
			() -> "a height field cannot detach anything, yet found " + groundedIslands
		);
		assertTrue(
			floatingIslands > 10,
			() -> "sky coverage should float real ground, found " + floatingIslands
		);

		double groundedFill = bandSolidShare(grounded, 180, 250);
		double floatingFill = bandSolidShare(floating, 180, 250);
		assertTrue(
			floatingFill > groundedFill + 0.10,
			() -> "the band should gain real volume: grounded=" + groundedFill + ", floating=" + floatingFill
		);
	}

	/** Fraction of sampled points inside the band that are solid, over land or over ocean. */
	private static double bandSolidShare(RandomState state, int bandMin, int bandMax, boolean overLand) {
		DensityFunction density = state.router().finalDensity();
		DensityFunction continents = state.router().continents();
		Random random = new Random(0xC0A57L);
		int solid = 0;
		int counted = 0;
		while (counted < 2_000) {
			int x = random.nextInt(-4_000, 4_001);
			int z = random.nextInt(-4_000, 4_001);
			boolean land = continents.compute(new DensityFunction.SinglePointContext(x, 0, z)) > -0.11;
			if (land != overLand) {
				continue;
			}
			int y = random.nextInt(bandMin, bandMax + 1);
			if (density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0) {
				solid++;
			}
			counted++;
		}
		return (double) solid / counted;
	}

	/** Fraction of sampled points inside the band that are solid. */
	private static double bandSolidShare(RandomState state, int bandMin, int bandMax) {
		DensityFunction density = state.router().finalDensity();
		Random random = new Random(0xBA5EL);
		int solid = 0;
		int samples = 4_000;
		for (int i = 0; i < samples; i++) {
			int x = random.nextInt(-4_000, 4_001);
			int y = random.nextInt(bandMin, bandMax + 1);
			int z = random.nextInt(-4_000, 4_001);
			if (density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0) {
				solid++;
			}
		}
		return (double) solid / samples;
	}

	@Test
	void skyIslandsStayInsideTheirBand() {
		RandomState floating = state(bandShape(island(0.35, 180, 250, BandRegion.ANYWHERE)), '9');

		assertTrue(
			detachedColumns(floating, 260, 310, 40) == 0,
			"nothing should float above the band it was given"
		);
	}

	/**
	 * Union has a mirror. Without subtraction a band can only add rock, so a
	 * hollowed world or a canyon that is not merely a low surface is
	 * unreachable no matter how the other knobs are set.
	 */
	@Test
	void aCarvingBandRemovesGroundThatWasThere() {
		RandomState solid = state(bandShape(), '4');
		RandomState hollowed = state(
			bandShape(new TerrainBand(0.45, -40, 30, BandEffect.CARVE, BandRegion.ANYWHERE, null, 2.0, 1.2)),
			'5'
		);

		double solidFill = bandSolidShare(solid, -40, 30);
		double hollowedFill = bandSolidShare(hollowed, -40, 30);

		assertTrue(
			hollowedFill < solidFill - 0.15,
			() -> "a carving band should remove real volume: solid=" + solidFill + ", hollowed=" + hollowedFill
		);
	}

	/**
	 * A band that ignores geography spreads evenly over ocean and continent
	 * alike, which reads as scattered rather than designed.
	 */
	@Test
	void aRegionBoundBandFollowsTheCoastline() {
		RandomState overLand = state(bandShape(island(0.40, 180, 250, BandRegion.OVER_LAND)), 'd');

		double aboveLand = bandSolidShare(overLand, 180, 250, true);
		double aboveOcean = bandSolidShare(overLand, 180, 250, false);

		assertTrue(
			aboveLand > aboveOcean * 4.0 + 0.05,
			() -> "an OVER_LAND band should stay over land: land=" + aboveLand + ", ocean=" + aboveOcean
		);
	}

	@Test
	void bandsStackIntoSeparateLayers() {
		RandomState twoLayers = state(
			bandShape(
				island(0.40, 150, 190, BandRegion.ANYWHERE),
				island(0.40, 240, 280, BandRegion.ANYWHERE)
			),
			'e'
		);

		double lower = bandSolidShare(twoLayers, 150, 190);
		double gap = bandSolidShare(twoLayers, 205, 225);
		double upper = bandSolidShare(twoLayers, 240, 280);

		assertTrue(lower > 0.10, () -> "lower layer was " + lower);
		assertTrue(upper > 0.10, () -> "upper layer was " + upper);
		assertTrue(gap < lower / 2.0, () -> "the sky between layers should stay open, was " + gap);
	}

	@Test
	void anAnchorBoundBandStaysInsideTheLandmark() {
		Anchor anchor = new Anchor("sky_focus", new AnchorPlacement.Fixed(0, 0), 700, 0.0, 1.0, null);
		TerrainBand band = new TerrainBand(
			0.45, 180, 240, BandEffect.ADD, BandRegion.ANYWHERE, "sky_focus", 1.4, 1.0
		);
		RandomState focused = state(anchoredBandShape(anchor, band), '1');

		double near = localBandSolidShare(focused, 180, 240, 0, 0, 450, 2_000);
		double far = localBandSolidShare(focused, 180, 240, 3_000, 0, 450, 2_000);

		assertTrue(near > far + 0.08, () -> "anchor-bound band leaked: near=" + near + ", far=" + far);
	}

	/**
	 * Noise says "this kind of thing, everywhere, in this proportion". An anchor
	 * says "here". The test for that is not that a mountain exists but that it
	 * exists in one place and leaves the rest of the world alone.
	 */
	@Test
	void aFixedAnchorRaisesGroundOnlyWhereItWasPlaced() {
		RandomState plain = state(anchorShape(), '2');
		RandomState peaked = state(
			anchorShape(new Anchor("holy_peak", new AnchorPlacement.Fixed(600, -400), 500, 150.0, 1.0, null)),
			'3'
		);

		double atCentre = surfaceHeight(peaked, 600, -400) - surfaceHeight(plain, 600, -400);
		double atRim = surfaceHeight(peaked, 600 + 480, -400) - surfaceHeight(plain, 600 + 480, -400);
		double faraway = surfaceHeight(peaked, 600 + 3_000, -400) - surfaceHeight(plain, 600 + 3_000, -400);

		assertTrue(atCentre > 100.0, () -> "the anchor should raise its centre, gained " + atCentre);
		assertTrue(atRim < atCentre / 3.0, () -> "the rise should fall off by the rim, rim=" + atRim);
		assertTrue(Math.abs(faraway) < 1.0, () -> "distant terrain must be untouched, moved " + faraway);
	}

	/** A negative amplitude is the same field, sunk instead of raised. */
	@Test
	void aNegativeAnchorSinksGroundIntoACrater() {
		RandomState plain = state(anchorShape(), '4');
		RandomState cratered = state(
			anchorShape(new Anchor("basin", new AnchorPlacement.Fixed(0, 0), 400, -120.0, 1.0, null)),
			'5'
		);

		double atCentre = surfaceHeight(cratered, 0, 0) - surfaceHeight(plain, 0, 0);

		assertTrue(atCentre < -80.0, () -> "a negative amplitude should sink the ground, moved " + atCentre);
	}

	/**
	 * Nothing is unique in an endless world, so rarity is a spacing rather than
	 * a promise: instances have to recur, and recur far apart.
	 */
	@Test
	void aScatteredAnchorRecursAcrossTheWorld() {
		RandomState plain = state(anchorShape(), '6');
		RandomState spires = state(
			anchorShape(new Anchor("spires", new AnchorPlacement.Scattered(4_000, 0.7), 500, 140.0, 1.0, null)),
			'7'
		);

		// Instances jitter on both axes, so a single transect can miss every one
		// of them by construction. What "recurs" means is that raised ground
		// turns up in many different lattice cells, which is what this counts.
		// Sampled at random rather than on a grid. A regular scan whose step
		// divides the spacing lands on the same relative position in every cell,
		// so it can miss every instance in the world and report nothing.
		Set<Long> cellsHit = new HashSet<>();
		Random random = new Random(0xA9C40FL);
		for (int sample = 0; sample < 2_000; sample++) {
			int x = random.nextInt(-20_000, 20_001);
			int z = random.nextInt(-20_000, 20_001);
			if (surfaceHeight(spires, x, z) - surfaceHeight(plain, x, z) > 60.0) {
				cellsHit.add(((long) Math.floorDiv(x, 4_000) << 32) ^ (Math.floorDiv(z, 4_000) & 0xFFFFFFFFL));
			}
		}
		int distinctCells = cellsHit.size();

		assertTrue(distinctCells > 8, () -> "a scattered anchor should recur, distinct cells hit " + distinctCells);
	}

	@Test
	void aLineAnchorBuildsOneFiniteCorridor() {
		assertTrue(Math.abs(WorldsmithAnchorFields.lineDistance(0, 40, -100, 0, 100, 0) - 40.0) < 1.0E-9);
		assertTrue(Math.abs(WorldsmithAnchorFields.lineDistance(160, 0, -100, 0, 100, 0) - 60.0) < 1.0E-9);
		RandomState plain = state(anchorShape(), '1');
		RandomState ridge = state(
			anchorShape(new Anchor(
				"ridge",
				new AnchorPlacement.Line(-1_200, 300, 1_200, 300),
				240,
				90.0,
				1.0,
				null
			)),
			'2'
		);

		double start = surfaceHeight(ridge, -1_000, 300) - surfaceHeight(plain, -1_000, 300);
		double middle = surfaceHeight(ridge, 0, 300) - surfaceHeight(plain, 0, 300);
		double farSide = surfaceHeight(ridge, 0, 1_000) - surfaceHeight(plain, 0, 1_000);
		double pastEnd = surfaceHeight(ridge, 2_000, 300) - surfaceHeight(plain, 2_000, 300);

		assertTrue(start > 55.0, () -> "line should raise its first half, gained " + start);
		assertTrue(middle > 55.0, () -> "line should raise its midpoint, gained " + middle);
		assertTrue(Math.abs(farSide) < 1.0, () -> "line leaked sideways by " + farSide);
		assertTrue(Math.abs(pastEnd) < 1.0, () -> "line leaked past its endpoint by " + pastEnd);
	}

	/**
	 * Raising the ground is not enough. Biomes are chosen from climate
	 * parameters rather than from how high the ground turned out, so without
	 * this an anchor builds a mountain that still carries the plain's biome.
	 */
	@Test
	void anAnchorPullsBiomeSelectionTowardItsOwnLandform() {
		RandomState plain = state(anchorShape(), '8');
		RandomState peaked = state(
			anchorShape(new Anchor(
				"peak",
				new AnchorPlacement.Fixed(0, 0),
				500,
				150.0,
				1.0,
				new AnchorClimateBias(1.0, null, null, 0.55, -0.8, null)
			)),
			'9'
		);

		double plainErosion = plain.router().erosion().compute(new DensityFunction.SinglePointContext(0, 0, 0));
		double peakErosion = peaked.router().erosion().compute(new DensityFunction.SinglePointContext(0, 0, 0));
		double faraway = peaked.router().erosion().compute(new DensityFunction.SinglePointContext(9_000, 0, 0));
		double plainFaraway = plain.router().erosion().compute(new DensityFunction.SinglePointContext(9_000, 0, 0));

		assertTrue(
			peakErosion < plainErosion - 0.3,
			() -> "erosion should be pulled toward peaks: plain=" + plainErosion + ", peak=" + peakErosion
		);
		assertTrue(
			Math.abs(faraway - plainFaraway) < 0.001,
			() -> "biome selection away from the anchor must be untouched, moved " + (faraway - plainFaraway)
		);
	}

	@Test
	void skyIslandEdgesDoNotShareOneHorizontalCutPlane() {
		RandomState floating = state(bandShape(island(0.55, 160, 240, BandRegion.ANYWHERE)), '0');
		DensityFunction density = floating.router().finalDensity();
		Set<Integer> lowerEdges = new HashSet<>();
		int columns = 0;

		for (int z = -3_000; z <= 3_000; z += 64) {
			for (int x = -3_000; x <= 3_000; x += 64) {
				if (density.compute(new DensityFunction.SinglePointContext(x, 152, z)) > 0.0
					|| density.compute(new DensityFunction.SinglePointContext(x, 200, z)) <= 0.0) {
					continue;
				}
				for (int y = 160; y <= 200; y++) {
					if (density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0) {
						lowerEdges.add(y);
						columns++;
						break;
					}
				}
			}
		}

		assertTrue(columns > 20, "fixture found only " + columns + " detached island columns");
		int range = lowerEdges.stream().mapToInt(Integer::intValue).max().orElseThrow()
			- lowerEdges.stream().mapToInt(Integer::intValue).min().orElseThrow();
		assertTrue(
			lowerEdges.size() >= 6 && range >= 8,
			() -> "island bottoms still share a plane: edges=" + lowerEdges + ", range=" + range
		);
	}

	@Test
	void geometryAloneDoesNotInventABiomeMeaning() {
		RandomState plain = state(anchorShape(), 'a');
		RandomState mound = state(
			anchorShape(new Anchor("mound", new AnchorPlacement.Fixed(0, 0), 300, 1.0, 1.0, null)),
			'b'
		);

		double plainErosion = plain.router().erosion().compute(new DensityFunction.SinglePointContext(0, 0, 0));
		double moundErosion = mound.router().erosion().compute(new DensityFunction.SinglePointContext(0, 0, 0));

		assertTrue(
			Math.abs(moundErosion - plainErosion) < 0.001,
			() -> "an unlabelled one-block mound changed biome erosion by " + (moundErosion - plainErosion)
		);
	}

	@Test
	void anOceanLandmarkCanAuthorItsOwnContinentalClimate() {
		RandomState ocean = state(anchorShape(0.05), 'c');
		int[] point = findOceanPoint(ocean);
		Anchor islandPeak = new Anchor(
			"island_peak",
			new AnchorPlacement.Fixed(point[0], point[1]),
			600,
			180.0,
			1.0,
			new AnchorClimateBias(1.0, null, null, 0.45, -0.8, null)
		);
		RandomState raised = state(anchorShape(0.05, islandPeak), 'd');
		DensityFunction.SinglePointContext centre = new DensityFunction.SinglePointContext(point[0], 0, point[1]);

		double before = ocean.router().continents().compute(centre);
		double after = raised.router().continents().compute(centre);

		assertTrue(before < -0.19, () -> "fixture should start in ocean, got " + before);
		assertTrue(after > -0.11, () -> "landmark should publish an inland climate, got " + after);
	}

	@Test
	void climateBiasTargetsAllBiomeAxesWithOneStrength() {
		RandomState plain = state(anchorShape(), 'e');
		AnchorClimateBias bias = new AnchorClimateBias(0.5, 1.0, -0.8, 0.45, -0.7, 0.6);
		RandomState authored = state(
			anchorShape(new Anchor("climate", new AnchorPlacement.Fixed(0, 0), 400, 0.0, 1.0, bias)),
			'f'
		);
		DensityFunction.SinglePointContext centre = new DensityFunction.SinglePointContext(0, 0, 0);

		assertHalfway(plain.router().temperature(), authored.router().temperature(), centre, 1.0, "temperature");
		assertHalfway(plain.router().vegetation(), authored.router().vegetation(), centre, -0.8, "humidity");
		assertHalfway(plain.router().continents(), authored.router().continents(), centre, 0.45, "continentalness");
		assertHalfway(plain.router().erosion(), authored.router().erosion(), centre, -0.7, "erosion");
		assertHalfway(plain.router().ridges(), authored.router().ridges(), centre, 0.6, "weirdness");
	}

	/**
	 * A climate target must not draw its biome border as a circle.
	 *
	 * <p>A border lies where a field crosses a threshold. Interpolating toward a
	 * target makes the field a function of the raw influence, and the raw
	 * influence is a function of distance alone, so every border around a
	 * landmark comes out a perfect ring. A flat core is fine - it is one biome,
	 * with no border inside it - so what this measures is the crossing itself:
	 * the radius at which erosion leaves the peak band, taken all the way round.
	 */
	@Test
	void aClimateTargetDoesNotDrawCircularBiomeBorders() {
		RandomState biased = state(
			anchorShape(new Anchor(
				"peak",
				new AnchorPlacement.Fixed(0, 0),
				2_000,
				150.0,
				1.0,
				new AnchorClimateBias(1.0, null, null, null, -0.8, null)
			)),
			'a'
		);

		DensityFunction erosion = biased.router().erosion();
		double closest = Double.MAX_VALUE;
		double furthest = -Double.MAX_VALUE;
		for (int step = 0; step < 24; step++) {
			double angle = step * Math.PI / 12.0;
			for (int radius = 100; radius <= 2_400; radius += 25) {
				int x = (int) Math.round(Math.cos(angle) * radius);
				int z = (int) Math.round(Math.sin(angle) * radius);
				if (erosion.compute(new DensityFunction.SinglePointContext(x, 0, z)) > PEAK_BAND_EDGE) {
					closest = Math.min(closest, radius);
					furthest = Math.max(furthest, radius);
					break;
				}
			}
		}
		double nearest = closest;
		double outermost = furthest;

		assertTrue(
			outermost - nearest > 200.0,
			() -> "the peak band border should wander, but ran from " + nearest + " to " + outermost
		);
	}

	/**
	 * The outline of a landmark must not be a circle either.
	 *
	 * <p>A radial profile draws one, which reads as machined rather than as a
	 * place. The distortion is applied to the distance rather than to the
	 * profile, so this walks outward at many angles and asks where the ground
	 * stops being raised: a circle answers with the same number every time.
	 */
	@Test
	void anAnchorOutlineIsNotACircle() {
		RandomState plain = state(anchorShape(), 'b');
		RandomState peaked = state(
			anchorShape(new Anchor("peak", new AnchorPlacement.Fixed(0, 0), 1_200, 150.0, 1.0, null)),
			'c'
		);

		double closest = Double.MAX_VALUE;
		double furthest = -Double.MAX_VALUE;
		for (int step = 0; step < 24; step++) {
			double angle = step * Math.PI / 12.0;
			for (int radius = 100; radius <= 1_400; radius += 25) {
				int x = (int) Math.round(Math.cos(angle) * radius);
				int z = (int) Math.round(Math.sin(angle) * radius);
				if (surfaceHeight(peaked, x, z) - surfaceHeight(plain, x, z) < 8.0) {
					closest = Math.min(closest, radius);
					furthest = Math.max(furthest, radius);
					break;
				}
			}
		}
		double nearest = closest;
		double outermost = furthest;

		assertTrue(
			outermost - nearest > 200.0,
			() -> "the outline should wander, but ran from " + nearest + " to " + outermost
		);
		// Bitten into, never bulging: the extent has to stay inside the declared
		// radius or a scattered lattice could reach past its neighbouring cell.
		assertTrue(outermost <= 1_200.0, () -> "the outline left its radius at " + outermost);
	}

	@Test
	void caveFamiliesChangeHowMuchSolidTerrainIsCarved() {
		TerrainShape.Procedural solidShape = shape(0.82, 1.0, 0.3, 0.5, 0.35, 0.15, 1.0, 0.0);
		TerrainShape.Procedural caveShape = shape(0.82, 1.0, 0.3, 0.5, 0.35, 0.15, 1.0, 1.0);
		RandomState solid = state(solidShape, '6');
		RandomState caves = state(caveShape, '7');

		int carved = carvedSamples(solid, caves);

		assertTrue(carved > 150, () -> "full cave density should carve sampled solid points, got " + carved);
	}

	@Test
	void biomeSpatialControlsWarpBordersWithoutChangingClimateShares() {
		TerrainShape.Procedural shape = anchorShape();
		RandomState smooth = state(shape, new BiomeSpatialSettings(1.0, 0.0), '8');
		RandomState rough = state(shape, new BiomeSpatialSettings(1.0, 1.0), '9');
		RandomState broad = state(shape, new BiomeSpatialSettings(4.0, 0.0), 'a');
		RandomState fine = state(shape, new BiomeSpatialSettings(0.5, 0.0), 'b');
		Random random = new Random(0xB10E5A1EL);
		double smoothSum = 0.0;
		double smoothSquares = 0.0;
		double roughSum = 0.0;
		double roughSquares = 0.0;
		double broadDelta = 0.0;
		double fineDelta = 0.0;
		double roughDelta = 0.0;
		double maxRoughDelta = 0.0;
		int changed = 0;
		int samples = 12_000;
		for (int i = 0; i < samples; i++) {
			int x = random.nextInt(-120_000, 120_001);
			int z = random.nextInt(-120_000, 120_001);
			DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, 0, z);
			DensityFunction.SinglePointContext neighbour = new DensityFunction.SinglePointContext(x + 256, 0, z);
			double smoothValue = smooth.router().temperature().compute(point);
			double roughValue = rough.router().temperature().compute(point);
			smoothSum += smoothValue;
			smoothSquares += smoothValue * smoothValue;
			roughSum += roughValue;
			roughSquares += roughValue * roughValue;
			double moved = Math.abs(smoothValue - roughValue);
			roughDelta += moved;
			maxRoughDelta = Math.max(maxRoughDelta, moved);
			if (moved > 0.02) {
				changed++;
			}
			broadDelta += Math.abs(
				broad.router().temperature().compute(point) - broad.router().temperature().compute(neighbour)
			);
			fineDelta += Math.abs(
				fine.router().temperature().compute(point) - fine.router().temperature().compute(neighbour)
			);
		}
		double smoothMean = smoothSum / samples;
		double roughMean = roughSum / samples;
		double smoothSigma = Math.sqrt(smoothSquares / samples - smoothMean * smoothMean);
		double roughSigma = Math.sqrt(roughSquares / samples - roughMean * roughMean);
		double broadNeighbourDelta = broadDelta;
		double fineNeighbourDelta = fineDelta;
		double meanRoughDelta = roughDelta / samples;
		double largestRoughDelta = maxRoughDelta;
		int changedSamples = changed;

		assertTrue(meanRoughDelta > 0.002,
			() -> "boundary roughness mean/max movement was " + meanRoughDelta + "/" + largestRoughDelta
				+ " (" + changedSamples + " samples exceeded 0.02)");
		assertTrue(Math.abs(smoothSigma - roughSigma) < 0.025,
			() -> "coordinate warp changed temperature share: " + smoothSigma + " vs " + roughSigma);
		assertTrue(broadNeighbourDelta < fineNeighbourDelta,
			() -> "large regionScale should make nearer samples more alike: "
				+ broadNeighbourDelta + " vs " + fineNeighbourDelta);
	}

	@Test
	void landformAndWeirdnessAreIndependentBiomeAxes() {
		RandomState state = state(shape(0.75, 1.0, 0.3, 0.34, 0.33, 0.33, 1.0, 0.0), 'c');
		Random random = new Random(0x1A2E5L);
		double sumX = 0.0, sumY = 0.0, sumXX = 0.0, sumYY = 0.0, sumXY = 0.0;
		int samples = 16_000;
		for (int i = 0; i < samples; i++) {
			int x = random.nextInt(-160_000, 160_001);
			int z = random.nextInt(-160_000, 160_001);
			DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, 0, z);
			double landform = state.router().erosion().compute(point);
			double weirdness = state.router().ridges().compute(point);
			sumX += landform;
			sumY += weirdness;
			sumXX += landform * landform;
			sumYY += weirdness * weirdness;
			sumXY += landform * weirdness;
		}
		double covariance = sumXY / samples - (sumX / samples) * (sumY / samples);
		double varianceX = sumXX / samples - Math.pow(sumX / samples, 2.0);
		double varianceY = sumYY / samples - Math.pow(sumY / samples, 2.0);
		double correlation = covariance / Math.sqrt(varianceX * varianceY);

		assertTrue(Math.abs(correlation) < 0.12, () -> "landform and weirdness correlation was " + correlation);
	}

	@Test
	void caveVerticalRangeAndFloodingAreRealRouterInputs() {
		CaveIntent none = new CaveIntent(0.0, 0.0, 0.0, 0.0, new CaveVerticalRange(-56, 32), 0.0);
		CaveIntent deep = new CaveIntent(1.0, 1.0, 1.0, 1.0, new CaveVerticalRange(-56, 32), 1.0);
		RandomState solid = state(shapeWithCaves(none), '3');
		RandomState caves = state(shapeWithCaves(deep), '4');

		DensityFunction.SinglePointContext high = new DensityFunction.SinglePointContext(320, 220, -480);
		double solidHigh = solid.router().finalDensity().compute(high);
		double cavesHigh = caves.router().finalDensity().compute(high);
		assertTrue(Math.abs(solidHigh - cavesHigh) < 1.0E-9, "caves changed density outside verticalRange");

		double dry = solid.router().fluidLevelFloodednessNoise().compute(high);
		double flooded = caves.router().fluidLevelFloodednessNoise().compute(high);
		assertTrue(dry <= -0.99, () -> "zero floodedChance should produce a dry bias, got " + dry);
		assertTrue(flooded >= 0.99, () -> "full floodedChance should produce a wet bias, got " + flooded);
	}

	@Test
	void biomeReliefSignalMatchesTheReliefThatWasBuilt() {
		RandomState terrain = state(shape(0.92, 1.0, 0.2, 0.34, 0.33, 0.33, 1.0, 0.0), '5');
		double flatHeights = 0.0;
		double highlandHeights = 0.0;
		double peakHeights = 0.0;
		int flats = 0;
		int highlands = 0;
		int peaks = 0;
		Random random = new Random(0x1A4DF04DL);
		for (int sample = 0; sample < 8_000; sample++) {
			int x = random.nextInt(-30_000, 30_001);
			int z = random.nextInt(-30_000, 30_001);
			DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, 0, z);
			if (terrain.router().continents().compute(point) < 0.25) {
				continue;
			}
			double erosion = terrain.router().erosion().compute(point);
			double height = surfaceHeight(terrain, x, z);
			if (erosion < -0.375) {
				peakHeights += height;
				peaks++;
			} else if (erosion < 0.05) {
				highlandHeights += height;
				highlands++;
			} else {
				flatHeights += height;
				flats++;
			}
		}
		double flatMean = flatHeights / flats;
		double highlandMean = highlandHeights / highlands;
		double peakMean = peakHeights / peaks;
		assertTrue(flats > 100 && highlands > 100 && peaks > 100, "fixture must sample every relief band");
		assertTrue(highlandMean > flatMean + 12.0, "highland biome signal did not identify higher terrain");
		assertTrue(peakMean > highlandMean + 20.0, "peak biome signal did not identify peak terrain");
	}

	static TerrainShape.Procedural shape(
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
			new CaveIntent(
				caveDensity,
				caveDensity,
				caveDensity,
				caveDensity,
				new CaveVerticalRange(-56, 192),
				0.35
			),
			new HydrologyIntent(0.0, 1.0, 0.8, 0.65, RiverFill.FLUID, 0.0, 1.0, 0.8, 1.0),
			List.of(),
			List.of()
		);
	}

	private static TerrainShape.Procedural anchorShape(Anchor... anchors) {
		return anchorShape(0.95, anchors);
	}

	private static TerrainShape.Procedural shapeWithCaves(CaveIntent caves) {
		TerrainShape.Procedural base = shape(0.92, 1.0, 0.2, 0.6, 0.3, 0.1, 1.0, 0.0);
		return new TerrainShape.Procedural(
			base.getLandRatio(),
			base.getContinentScale(),
			base.getCoastRoughness(),
			base.getRelief(),
			base.getVerticalScale(),
			caves,
			base.getHydrology(),
			base.getBands(),
			base.getAnchors()
		);
	}

	@Test
	void aSkyIslandDoesNotHideTheGroundFromSurfaceRules() {
		RandomState ground = state(bandShape(), 'a');
		RandomState floating = state(bandShape(island(0.55, 180, 250, BandRegion.ANYWHERE)), 'b');
		DensityFunction floatingDensity = floating.router().finalDensity();
		for (int z = -3_000; z <= 3_000; z += 64) {
			for (int x = -3_000; x <= 3_000; x += 64) {
				DensityFunction.SinglePointContext islandLevel = new DensityFunction.SinglePointContext(x, 210, z);
				if (floatingDensity.compute(islandLevel) <= 0.0
					|| ground.router().finalDensity().compute(islandLevel) > 0.0) {
					continue;
				}
				DensityFunction.SinglePointContext column = new DensityFunction.SinglePointContext(x, 0, z);
				double groundEstimate = ground.router().preliminarySurfaceLevel().compute(column);
				double floatingEstimate = floating.router().preliminarySurfaceLevel().compute(column);
				assertTrue(
					floatingEstimate <= groundEstimate + 8.0,
					() -> "top island raised the preliminary surface from " + groundEstimate + " to " + floatingEstimate
				);
				return;
			}
		}
		throw new AssertionError("fixture found no floating island column");
	}

	private static TerrainShape.Procedural anchorShape(double landRatio, Anchor... anchors) {
		TerrainShape.Procedural flat = shape(landRatio, 1.0, 0.2, 1.0, 0.0, 0.0, 0.4, 0.0);
		return new TerrainShape.Procedural(
			flat.getLandRatio(),
			flat.getContinentScale(),
			flat.getCoastRoughness(),
			flat.getRelief(),
			flat.getVerticalScale(),
			flat.getCaves(),
			flat.getHydrology(),
			List.of(),
			List.of(anchors)
		);
	}

	private static int[] findOceanPoint(RandomState state) {
		DensityFunction continents = state.router().continents();
		for (int z = -8_000; z <= 8_000; z += 128) {
			for (int x = -8_000; x <= 8_000; x += 128) {
				if (continents.compute(new DensityFunction.SinglePointContext(x, 0, z)) < -0.55) {
					return new int[] {x, z};
				}
			}
		}
		throw new AssertionError("ocean fixture contained no deep-water point");
	}

	private static void assertHalfway(
		DensityFunction before,
		DensityFunction after,
		DensityFunction.FunctionContext point,
		double target,
		String axis
	) {
		double initial = before.compute(point);
		double expected = (initial + target) * 0.5;
		double actual = after.compute(point);
		assertTrue(
			Math.abs(actual - expected) < 1.0E-6,
			() -> axis + " expected " + expected + " but was " + actual
		);
	}

	/** Highest Y whose density is still solid, which is the ground the player walks. */
	private static double surfaceHeight(RandomState state, int x, int z) {
		DensityFunction density = state.router().finalDensity();
		for (int y = 300; y >= -64; y -= 1) {
			if (density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0) {
				return y;
			}
		}
		return -64;
	}

	private static TerrainBand island(double coverage, int minY, int maxY, BandRegion region) {
		return new TerrainBand(coverage, minY, maxY, BandEffect.ADD, region, null, 1.4, 1.0);
	}

	private static TerrainShape.Procedural bandShape(TerrainBand... bands) {
		TerrainShape.Procedural flat = shape(0.82, 1.0, 0.3, 0.9, 0.08, 0.02, 0.6, 0.0);
		return new TerrainShape.Procedural(
			flat.getLandRatio(),
			flat.getContinentScale(),
			flat.getCoastRoughness(),
			flat.getRelief(),
			flat.getVerticalScale(),
			flat.getCaves(),
			flat.getHydrology(),
			List.of(bands),
			List.of()
		);
	}

	private static TerrainShape.Procedural anchoredBandShape(Anchor anchor, TerrainBand band) {
		TerrainShape.Procedural flat = shape(0.82, 1.0, 0.3, 0.9, 0.08, 0.02, 0.6, 0.0);
		return new TerrainShape.Procedural(
			flat.getLandRatio(),
			flat.getContinentScale(),
			flat.getCoastRoughness(),
			flat.getRelief(),
			flat.getVerticalScale(),
			flat.getCaves(),
			flat.getHydrology(),
			List.of(band),
			List.of(anchor)
		);
	}

	private static double localBandSolidShare(
		RandomState state,
		int minY,
		int maxY,
		int centreX,
		int centreZ,
		int radius,
		int samples
	) {
		DensityFunction density = state.router().finalDensity();
		Random random = new Random(0xA11C0FL + centreX);
		int solid = 0;
		for (int sample = 0; sample < samples; sample++) {
			int x = centreX + random.nextInt(-radius, radius + 1);
			int y = random.nextInt(minY, maxY + 1);
			int z = centreZ + random.nextInt(-radius, radius + 1);
			if (density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0) {
				solid++;
			}
		}
		return (double)solid / samples;
	}

	/**
	 * Columns holding solid ground inside the band with a clear drop beneath it.
	 *
	 * <p>This is the whole claim: not that the band contains stone, which a tall
	 * mountain would also satisfy, but that the stone has nothing under it.
	 */
	private static int detachedColumns(RandomState state, int bandMin, int bandMax, int gap) {
		DensityFunction density = state.router().finalDensity();
		Random random = new Random(0x15A4D5L);
		int detached = 0;
		for (int sample = 0; sample < 400; sample++) {
			int x = random.nextInt(-4_000, 4_001);
			int z = random.nextInt(-4_000, 4_001);
			for (int y = bandMax; y >= bandMin; y -= 2) {
				if (density.compute(new DensityFunction.SinglePointContext(x, y, z)) <= 0.0) {
					continue;
				}
				boolean clearBelow = true;
				for (int below = y - 4; below >= y - 4 - gap; below -= 2) {
					if (density.compute(new DensityFunction.SinglePointContext(x, below, z)) > 0.0) {
						clearBelow = false;
						break;
					}
				}
				if (clearBelow) {
					detached++;
				}
				break;
			}
		}
		return detached;
	}

	static RandomState state(TerrainShape.Procedural shape, char idCharacter) {
		return state(shape, WorldsmithPacks.builtin().getBiomes().getSpatial(), idCharacter);
	}

	static RandomState state(
		TerrainShape.Procedural shape,
		BiomeSpatialSettings spatial,
		char idCharacter
	) {
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
		BiomePlan biomes = new BiomePlan(
			source.getBiomes().getSchemaVersion(), source.getBiomes().getBiomes(), spatial
		);
		CompiledPack compiledPack = CompiledPack.scoped(new WorldsmithPack(
			manifest, terrain, biomes, source.getFeatures(), id, source.getStructures()
		));
		HolderLookup.Provider registries = WorldsmithPackExporter.compilePatch(compiledPack, activeWorldgen()).full();
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
