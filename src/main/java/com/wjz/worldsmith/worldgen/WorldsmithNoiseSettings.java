package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.Anchor;
import com.wjz.worldsmith.core.model.AnchorClimateBias;
import com.wjz.worldsmith.core.model.AnchorPlacement;
import com.wjz.worldsmith.core.model.BandEffect;
import com.wjz.worldsmith.core.model.BandRegion;
import com.wjz.worldsmith.core.model.TerrainBand;
import com.wjz.worldsmith.core.model.TerrainPlan;
import com.wjz.worldsmith.core.model.TerrainShape;
import com.wjz.worldsmith.core.model.VanillaNoisePreset;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * The single noise settings entry for a Worldsmith world.
 *
 * <p>Vanilla shapes are explicit passthrough presets. Procedural
 * shapes are compiled from version-independent, prompt-facing terrain intent
 * into the density functions used by this Minecraft version.
 */
public final class WorldsmithNoiseSettings {
	public static final ResourceKey<NoiseGeneratorSettings> WASTELAND =
		ResourceKey.create(Registries.NOISE_SETTINGS, Worldsmith.id("wasteland"));

	/** Maximum blocks of vertical fade at each edge of a band. */
	private static final int BAND_FADE = 24;
	/** Distorts a band's top and bottom without allowing either to leave its declared height range. */
	private static final double BAND_EDGE_WARP = 0.75;
	/** Low-frequency edge shape; divided by band scale so large islands undulate broadly. */
	private static final double BAND_EDGE_FREQUENCY = 0.18;
	/** Scales a carving band so the branch that is not carving cannot win a min. */
	private static final double CARVE_STRENGTH = 8.0;
	/** Turns the soft continentalness signal into a decisive region boundary. */
	private static final double REGION_EDGE = 6.0;
	/** How much anchor influence a band needs before it may act there. */
	private static final double ANCHOR_BAND_THRESHOLD = 0.2;
	/** How narrow the coastal strip is; larger keeps it closer to the shoreline. */
	private static final double COASTAL_EDGE = 8.0;

	private WorldsmithNoiseSettings() {
	}

	public static void bootstrap(CompiledPack pack, BootstrapContext<NoiseGeneratorSettings> context) {
		HolderGetter<DensityFunction> functions = context.lookup(Registries.DENSITY_FUNCTION);
		HolderGetter<NormalNoise.NoiseParameters> noises = context.lookup(Registries.NOISE);
		HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);

		TerrainPlan terrain = pack.terrain();
		MaterialResolver resolver = new MaterialResolver();
		SurfaceRules.RuleSource surfaceRule = WorldsmithSurfaceRules.build(pack, biomes, resolver);

		context.register(pack.noiseSettingsKey(), new NoiseGeneratorSettings(
			NoiseSettings.create(
				terrain.getMinY(), terrain.getHeight(), terrain.getHorizontalNoiseSize(), terrain.getVerticalNoiseSize()
			),
			resolver.resolve(terrain.getDefaultBlock(), Blocks.STONE),
			resolver.resolve(terrain.getDefaultFluid(), Blocks.WATER),
			router(terrain, functions, noises),
			surfaceRule,
			terrain.getSpawnTargets().stream().map(CompiledBiomes::climate).toList(),
			terrain.getSeaLevel(),
			false,
			terrain.getAquifersEnabled(),
			terrain.getOreVeinsEnabled(),
			terrain.getLegacyRandomSource()
		));
		resolver.report("noise settings");
	}

	/**
	 * Builds the noise router the pack asked for.
	 *
	 * <p>The format can describe terrain shapes this compiler cannot build yet.
	 * Failing loudly on one is deliberate: quietly falling back to vanilla
	 * terrain would produce a world that looks finished and is not the one the
	 * pack describes.
	 */
	private static NoiseRouter router(
		TerrainPlan terrain,
		HolderGetter<DensityFunction> functions,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		TerrainShape shape = terrain.getShape();
		if (shape instanceof TerrainShape.Vanilla vanilla) {
			VanillaNoisePreset preset = vanilla.getPreset();
			return NoiseRouterData.overworld(
				functions,
				noises,
				preset == VanillaNoisePreset.LARGE_BIOMES,
				preset == VanillaNoisePreset.AMPLIFIED
			);
		}
		if (shape instanceof TerrainShape.Procedural procedural) {
			return proceduralRouter(terrain, procedural, functions, noises);
		}
		throw new IllegalStateException(
			"Unknown Worldsmith terrain shape " + shape.getClass().getSimpleName()
		);
	}

	/**
	 * Compiles semantic terrain controls into a complete overworld router.
	 *
	 * <p>The horizontal fields are deliberately independent: continentalness
	 * decides water versus land, relief decides flat/highland/peak character,
	 * and a finer detail field roughens coastlines and local elevation. Vanilla
	 * supplies aquifers, climate noise, cave primitives, and ore veins so the
	 * result remains compatible with the rest of overworld generation.
	 */
	private static NoiseRouter proceduralRouter(
		TerrainPlan terrain,
		TerrainShape.Procedural shape,
		HolderGetter<DensityFunction> functions,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		NoiseRouter vanilla = NoiseRouterData.overworld(functions, noises, false, false);
		DensityFunction shiftX = NoiseRouterData.getFunction(functions, NoiseRouterData.SHIFT_X);
		DensityFunction shiftZ = NoiseRouterData.getFunction(functions, NoiseRouterData.SHIFT_Z);

		double baseScale = 0.25 / shape.getContinentScale();
		DensityFunction continentBase = DensityFunctions.shiftedNoise2d(
			shiftX, shiftZ, baseScale, noises.getOrThrow(Noises.CONTINENTALNESS)
		);
		DensityFunction coastlineDetail = DensityFunctions.shiftedNoise2d(
			shiftX, shiftZ, baseScale * 5.0, noises.getOrThrow(Noises.SURFACE)
		);
		// Vanilla's ocean/coast boundary is near continentalness -0.11. The
		// underlying noise is bell-shaped rather than uniform, so a log-odds
		// quantile approximation makes the semantic ratio track sampled area;
		// a linear bias would make moderate values almost all ocean or all land.
		double landBias = landBias(shape.getLandRatio());
		DensityFunction continents = DensityFunctions.add(
			DensityFunctions.add(continentBase, DensityFunctions.constant(landBias)),
			DensityFunctions.mul(coastlineDetail, DensityFunctions.constant(shape.getCoastRoughness() * 0.18))
		).clamp(-1.2, 1.0);

		DensityFunction erosion = DensityFunctions.shiftedNoise2d(
			shiftX, shiftZ, baseScale * 1.6, noises.getOrThrow(Noises.EROSION)
		);
		DensityFunction reliefSelector = DensityFunctions.shiftedNoise2d(
			shiftX, shiftZ, baseScale * 2.2, noises.getOrThrow(Noises.RIDGE)
		);
		DensityFunction localDetail = DensityFunctions.shiftedNoise2d(
			shiftX, shiftZ, baseScale * 7.0, noises.getOrThrow(Noises.SURFACE_SECONDARY)
		);

		double reliefTotal = shape.getRelief().getFlats()
			+ shape.getRelief().getHighlands()
			+ shape.getRelief().getPeaks();
		double flatShare = shape.getRelief().getFlats() / reliefTotal;
		double highlandShare = shape.getRelief().getHighlands() / reliefTotal;
		DoubleArrayList thresholds = new DoubleArrayList();
		thresholds.add(reliefThreshold(flatShare));
		thresholds.add(reliefThreshold(flatShare + highlandShare));

		double verticalScale = shape.getVerticalScale();
		DensityFunction flats = DensityFunctions.mul(localDetail, DensityFunctions.constant(7.0 * verticalScale));
		DensityFunction highlands = DensityFunctions.add(
			DensityFunctions.constant(30.0 * verticalScale),
			DensityFunctions.mul(localDetail, DensityFunctions.constant(18.0 * verticalScale))
		);
		DensityFunction peaks = DensityFunctions.add(
			DensityFunctions.constant(72.0 * verticalScale),
			DensityFunctions.mul(localDetail.abs(), DensityFunctions.constant(52.0 * verticalScale))
		);
		DensityFunction reliefHeight = DensityFunctions.intervalSelect(
			reliefSelector,
			thresholds,
			List.of(flats, highlands, peaks)
		);

		DensityFunction landInput = DensityFunctions.add(continents, DensityFunctions.constant(0.11));
		DensityFunction inlandRamp = DensityFunctions.mul(
			landInput.clamp(0.0, 0.35),
			DensityFunctions.constant(1.0 / 0.35)
		);
		DensityFunction continentalHeight = DensityFunctions.rangeChoice(
			landInput,
			-1_000_000.0,
			0.0,
			DensityFunctions.mul(
				landInput,
				DensityFunctions.constant(42.0 * verticalScale * shape.getHydrology().getOceanDepth())
			),
			DensityFunctions.mul(landInput, DensityFunctions.constant(42.0 * verticalScale))
		);
		DensityFunction horizontalHeightBlocks = DensityFunctions.add(
			continentalHeight,
			DensityFunctions.mul(reliefHeight, inlandRamp)
		);
		WorldsmithHydrology.Fields hydrology = WorldsmithHydrology.compile(
			shape.getHydrology(),
			continents,
			landInput,
			horizontalHeightBlocks,
			noises
		);
		horizontalHeightBlocks = hydrology.horizontalHeightBlocks();

		// One influence field per anchor, built once and available four ways:
		// it raises the ground, carries any explicit climate bias, tells the
		// surface rules which ring they are painting, and bounds where a band acts.
		// Behind cache2d because it depends only on X and Z.
		Map<String, DensityFunction> anchorInfluence = new LinkedHashMap<>();
		for (Anchor anchor : shape.getAnchors()) {
			anchorInfluence.put(anchor.getId(), DensityFunctions.cache2d(anchorField(anchor, noises)));
		}
		// Anchors land after hydrology so a river cannot cut a landmark in half,
		// and before baseTerrain so the preliminary surface level and the biome
		// depth parameter both see the ground that was actually built.
		for (Anchor anchor : shape.getAnchors()) {
			horizontalHeightBlocks = DensityFunctions.add(
				horizontalHeightBlocks,
				DensityFunctions.mul(
					anchorInfluence.get(anchor.getId()),
					DensityFunctions.constant(anchor.getAmplitude())
				)
			);
		}
		DensityFunction temperature = biasClimate(
			vanilla.temperature(), shape.getAnchors(), anchorInfluence, AnchorClimateBias::getTemperature
		);
		DensityFunction humidity = biasClimate(
			vanilla.vegetation(), shape.getAnchors(), anchorInfluence, AnchorClimateBias::getHumidity
		);
		DensityFunction biomeContinents = biasClimate(
			hydrology.continents(), shape.getAnchors(), anchorInfluence, AnchorClimateBias::getContinentalness
		);
		erosion = biasClimate(erosion, shape.getAnchors(), anchorInfluence, AnchorClimateBias::getErosion);
		DensityFunction weirdness = biasClimate(
			reliefSelector, shape.getAnchors(), anchorInfluence, AnchorClimateBias::getWeirdness
		);

		int minY = terrain.getMinY();
		int maxY = minY + terrain.getHeight();
		int seaLevel = terrain.getSeaLevel();
		DensityFunction verticalGradient = DensityFunctions.yClampedGradient(
			minY,
			maxY,
			(seaLevel - minY) / 64.0,
			(seaLevel - maxY) / 64.0
		);
		DensityFunction baseTerrain = DensityFunctions.cacheOnce(
			DensityFunctions.add(
				verticalGradient,
				DensityFunctions.mul(horizontalHeightBlocks, DensityFunctions.constant(1.0 / 64.0))
			)
		);

		DensityFunction carvedTerrain = baseTerrain;
		if (shape.getCaveDensity() > 0.0) {
			DensityFunction surfaceWithEntrances = DensityFunctions.min(
				baseTerrain,
				DensityFunctions.mul(
					DensityFunctions.constant(5.0),
					NoiseRouterData.getFunction(functions, NoiseRouterData.ENTRANCES)
				)
			);
			DensityFunction vanillaCaves = DensityFunctions.rangeChoice(
				baseTerrain,
				-1_000_000.0,
				1.5625,
				surfaceWithEntrances,
				NoiseRouterData.underground(functions, noises, baseTerrain)
			);
			carvedTerrain = DensityFunctions.lerp(
				DensityFunctions.constant(shape.getCaveDensity()),
				baseTerrain,
				vanillaCaves
			);
		}

		DensityFunction slidTerrain = NoiseRouterData.slide(
			carvedTerrain,
			minY,
			terrain.getHeight(),
			80,
			64,
			-0.078125,
			0,
			24,
			0.1171875
		);
		// Islands join after the slide and before post-processing. The slide
		// pushes density negative near the world ceiling, which would erase
		// anything floating up there; post-processing is what interpolates and
		// squeezes the field, which islands need as much as the ground does.
		DensityFunction banded = slidTerrain;
		for (TerrainBand band : shape.getBands()) {
			banded = applyBand(banded, band, shiftX, shiftZ, biomeContinents, anchorInfluence, noises);
		}
		DensityFunction finalDensity = NoiseRouterData.postProcess(banded);
		if (shape.getCaveDensity() > 0.0) {
			DensityFunction noodleCarved = DensityFunctions.min(
				finalDensity,
				NoiseRouterData.getFunction(functions, NoiseRouterData.NOODLE)
			);
			finalDensity = DensityFunctions.lerp(
				DensityFunctions.constant(shape.getCaveDensity()),
				finalDensity,
				noodleCarved
			);
		}

		DensityFunction preliminarySurface = DensityFunctions.findTopSurface(
			baseTerrain,
			DensityFunctions.constant(maxY),
			minY,
			Math.max(4, terrain.getVerticalNoiseSize() * 4)
		);
		// Climate depth is position relative to the local surface, not absolute
		// world Y. Reusing the uncarved terrain field keeps it near zero at the
		// ground even when verticalScale moves that ground far above sea level.
		DensityFunction depth = baseTerrain.clamp(-1.0, 1.0);

		return new NoiseRouter(
			vanilla.barrierNoise(),
			vanilla.fluidLevelFloodednessNoise(),
			vanilla.fluidLevelSpreadNoise(),
			vanilla.lavaNoise(),
			temperature,
			humidity,
			biomeContinents,
			erosion,
			depth,
			weirdness,
			preliminarySurface,
			finalDensity,
			vanilla.veinToggle(),
			vanilla.veinRidged(),
			vanilla.veinGap()
		);
	}

	/** Applies one explicitly authored climate target through the shared influence field. */
	private static DensityFunction biasClimate(
		DensityFunction source,
		List<Anchor> anchors,
		Map<String, DensityFunction> influence,
		Function<AnchorClimateBias, Double> target
	) {
		DensityFunction biased = source;
		for (Anchor anchor : anchors) {
			AnchorClimateBias climate = anchor.getClimateBias();
			if (climate == null || climate.getStrength() <= 0.0) {
				continue;
			}
			Double targetValue = target.apply(climate);
			if (targetValue == null) {
				continue;
			}
			DensityFunction strength = DensityFunctions.mul(
				influence.get(anchor.getId()),
				DensityFunctions.constant(climate.getStrength())
			);
			biased = DensityFunctions.lerp(
				strength,
				biased,
				DensityFunctions.constant(targetValue)
			);
		}
		return biased;
	}

	private static DensityFunction anchorField(
		Anchor anchor,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		AnchorPlacement placement = anchor.getPlacement();
		if (placement instanceof AnchorPlacement.Fixed fixed) {
			return new WorldsmithAnchorFields.Point(
				fixed.getX(),
				fixed.getZ(),
				anchor.getRadius(),
				anchor.getFalloff()
			);
		}
		if (placement instanceof AnchorPlacement.Scattered scattered) {
			return new WorldsmithAnchorFields.Grid(
				scattered.getSpacing(),
				scattered.getJitter(),
				anchor.getRadius(),
				anchor.getFalloff(),
				new DensityFunction.NoiseHolder(noises.getOrThrow(Noises.SPAGHETTI_3D_RARITY))
			);
		}
		throw new IllegalStateException(
			"Worldsmith cannot compile anchor placement " + placement.getClass().getSimpleName() + " yet"
		);
	}

	/**
	 * Joins one band to the terrain, by union or by subtraction.
	 *
	 * <p>A band with no coverage emits nothing at all, so a world that does not
	 * use them has exactly the density graph it had before bands existed.
	 */
	private static DensityFunction applyBand(
		DensityFunction terrain,
		TerrainBand band,
		DensityFunction shiftX,
		DensityFunction shiftZ,
		DensityFunction continents,
		Map<String, DensityFunction> anchorInfluence,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		DensityFunction field = bandField(band, shiftX, shiftZ, continents, anchorInfluence, noises);
		if (field == null) {
			return terrain;
		}
		if (band.getEffect() == BandEffect.ADD) {
			return DensityFunctions.max(terrain, field);
		}
		// Subtraction is the mirror of union, but the inactive branch has to be
		// unable to win. The field saturates near -1.5 outside the band and the
		// slid terrain stays well inside +-5, so scaling by -CARVE_STRENGTH puts
		// "not carving here" far above any terrain value and "carving here" far
		// below zero.
		return DensityFunctions.min(
			terrain,
			DensityFunctions.mul(field, DensityFunctions.constant(-CARVE_STRENGTH))
		);
	}

	/**
	 * The three-dimensional field one band describes.
	 *
	 * <p>The ground field is {@code f(y) + g(x, z)} with f strictly decreasing,
	 * so its density crosses zero exactly once per column: solid below, air
	 * above, and no arrangement of the other knobs can float an island or hollow
	 * out a world. This field is genuinely three-dimensional, which is what lets
	 * the caller build a column that reads air, stone, air, stone.
	 */
	private static DensityFunction bandField(
		TerrainBand band,
		DensityFunction shiftX,
		DensityFunction shiftZ,
		DensityFunction continents,
		Map<String, DensityFunction> anchorInfluence,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		if (band.getCoverage() <= 0.0 || band.getMinY() >= band.getMaxY()) {
			return null;
		}

		double shapeScale = Math.max(0.05, band.getScale());
		// Keep a full-strength middle, but distort the two signed zero surfaces
		// independently. A flat y-gradient crosses zero at one world-wide height;
		// min(window, blobs) then exposes that plane on every island. Low-frequency
		// 2D warp moves each crossing while its bounded amplitude keeps both edges
		// strictly inside minY..maxY.
		int fade = Math.min(BAND_FADE, Math.max(1, (band.getMaxY() - band.getMinY()) / 3));
		double edgeFrequency = BAND_EDGE_FREQUENCY / shapeScale;
		DensityFunction lowerWarp = DensityFunctions.mul(
			DensityFunctions.shiftedNoise2d(
				DensityFunctions.zero(),
				DensityFunctions.zero(),
				edgeFrequency,
				noises.getOrThrow(Noises.SURFACE)
			).clamp(-1.0, 1.0),
			DensityFunctions.constant(BAND_EDGE_WARP)
		);
		DensityFunction upperWarp = DensityFunctions.mul(
			DensityFunctions.shiftedNoise2d(
				DensityFunctions.zero(),
				DensityFunctions.zero(),
				edgeFrequency,
				noises.getOrThrow(Noises.SURFACE_SECONDARY)
			).clamp(-1.0, 1.0),
			DensityFunctions.constant(BAND_EDGE_WARP)
		);
		DensityFunction window = DensityFunctions.min(
			DensityFunctions.add(
				DensityFunctions.yClampedGradient(band.getMinY(), band.getMinY() + fade, -1.0, 1.0),
				lowerWarp
			),
			DensityFunctions.add(
				DensityFunctions.yClampedGradient(band.getMaxY() - fade, band.getMaxY(), 1.0, -1.0),
				upperWarp
			)
		);

		DensityFunction region = regionMask(band.getRegion(), continents);
		if (region != null) {
			window = DensityFunctions.min(window, region);
		}
		// An anchor bound is ANDed with the region, so a band can be told to act
		// only near a landmark instead of everywhere that landform occurs.
		if (band.getAnchor() != null) {
			DensityFunction influence = anchorInfluence.get(band.getAnchor());
			if (influence == null) {
				throw new IllegalStateException("Band references unknown anchor '" + band.getAnchor() + "'");
			}
			window = DensityFunctions.min(
				window,
				DensityFunctions.add(
					influence,
					DensityFunctions.constant(-ANCHOR_BAND_THRESHOLD)
				)
			);
		}

		// Larger shapes mean lower frequency. Thickness squashes the vertical
		// axis independently, which is the difference between boulders and flat
		// shards.
		double xzFrequency = 1.0 / shapeScale;
		double yFrequency = xzFrequency / Math.max(0.05, band.getThickness());
		DensityFunction blobs = DensityFunctions.noise(
			noises.getOrThrow(Noises.CAVE_CHEESE), xzFrequency, yFrequency
		);

		// Active where the blob field clears the coverage threshold AND the
		// column is inside the window: min is the intersection of the two.
		DensityFunction dense = DensityFunctions.add(
			blobs,
			DensityFunctions.constant(-skyThreshold(band.getCoverage()))
		);
		return DensityFunctions.mul(
			DensityFunctions.min(window, dense),
			DensityFunctions.constant(1.5)
		);
	}

	/**
	 * A mask that is positive inside the named region and negative outside it.
	 *
	 * <p>Built from the same continentalness the biome source reads, so a band
	 * tied to land really does follow the coastline the player sees rather than
	 * an independently drawn one. The multipliers turn a soft signal into a
	 * decisive edge; without them the mask would only weight the band instead of
	 * bounding it.
	 */
	private static DensityFunction regionMask(BandRegion region, DensityFunction continents) {
		DensityFunction landInput = DensityFunctions.add(continents, DensityFunctions.constant(0.11));
		return switch (region) {
			case ANYWHERE -> null;
			case OVER_LAND -> DensityFunctions.mul(landInput, DensityFunctions.constant(REGION_EDGE));
			case OVER_OCEAN -> DensityFunctions.mul(landInput, DensityFunctions.constant(-REGION_EDGE));
			case INLAND -> DensityFunctions.mul(
				DensityFunctions.add(continents, DensityFunctions.constant(-0.3)),
				DensityFunctions.constant(REGION_EDGE)
			);
			// Positive only in the narrow strip where land meets water.
			case COASTAL -> DensityFunctions.add(
				DensityFunctions.constant(1.0),
				DensityFunctions.mul(landInput.abs(), DensityFunctions.constant(-COASTAL_EDGE))
			);
		};
	}

	/**
	 * Turns a requested solid fraction into a noise threshold.
	 *
	 * Same log-odds quantile as {@link #landBias}: the cave-cheese field is
	 * bell-shaped, so treating a requested share as a linear cut would make
	 * every moderate value either a solid ceiling or nothing at all.
	 *
	 * <p>The 0.25 is measured, not assumed. Sampling the wired cave-cheese noise
	 * across a sky band gives a standard deviation of 0.243 to 0.258 depending on
	 * island scale, and the resulting thresholds land within 0.005 of the
	 * sampled 65th, 80th and 90th percentiles.
	 */
	static double skyThreshold(double coverage) {
		if (coverage <= 0.0) {
			return 2.0;
		}
		if (coverage >= 1.0) {
			return -2.0;
		}
		return -(0.25 / 1.702) * Math.log(coverage / (1.0 - coverage));
	}

	static double landBias(double landRatio) {
		if (landRatio <= 0.0) {
			return -2.0;
		}
		if (landRatio >= 1.0) {
			return 2.0;
		}
		// logistic quantile / 1.702 approximates a normal quantile; sampled
		// overworld continentalness has a standard deviation close to 0.38.
		return -0.11 + (0.38 / 1.702) * Math.log(landRatio / (1.0 - landRatio));
	}

	/** Converts a requested cumulative relief share into ridge-noise space. */
	static double reliefThreshold(double cumulativeShare) {
		if (cumulativeShare <= 0.0) {
			return -1.0;
		}
		if (cumulativeShare >= 1.0) {
			return 1.0;
		}
		// The wired ridge field is centred near zero with sigma ~= 0.341.
		// Its log-odds quantile keeps requested weights statistical shares
		// instead of treating a bell-shaped noise as if it were uniform.
		double threshold = (0.341 / 1.702) * Math.log(cumulativeShare / (1.0 - cumulativeShare));
		return Math.max(-1.0, Math.min(1.0, threshold));
	}
}
