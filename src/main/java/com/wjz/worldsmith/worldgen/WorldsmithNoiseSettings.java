package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.Anchor;
import com.wjz.worldsmith.core.model.AnchorClimateBias;
import com.wjz.worldsmith.core.model.AnchorPlacement;
import com.wjz.worldsmith.core.model.BandEffect;
import com.wjz.worldsmith.core.model.BandRegion;
import com.wjz.worldsmith.core.model.BiomeSpatialSettings;
import com.wjz.worldsmith.core.model.CaveIntent;
import com.wjz.worldsmith.core.model.CaveVerticalRange;
import com.wjz.worldsmith.core.model.TerrainBand;
import com.wjz.worldsmith.core.model.TerrainPlan;
import com.wjz.worldsmith.core.model.TerrainShape;
import com.wjz.worldsmith.core.model.VanillaNoisePreset;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.Holder;
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
	/** How hard an anchor's climate influence is distorted, and how broad the lobes are. */
	private static final double ANCHOR_CLIMATE_WARP = 0.55;
	private static final double ANCHOR_CLIMATE_WARP_SCALE = 220.0;
	/** Coordinate displacement for rough biome borders; warping preserves each field's marginal distribution. */
	private static final double BIOME_BOUNDARY_WARP = 16.0;
	private static final int OVERWORLD_MIN_Y = -64;
	private static final int OVERWORLD_HEIGHT = 384;

	private WorldsmithNoiseSettings() {
	}

	public static void bootstrap(CompiledPack pack, BootstrapContext<NoiseGeneratorSettings> context) {
		HolderGetter<DensityFunction> functions = context.lookup(Registries.DENSITY_FUNCTION);
		HolderGetter<NormalNoise.NoiseParameters> noises = context.lookup(Registries.NOISE);
		HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);

		TerrainPlan terrain = pack.terrain();
		requireOverworldEnvelope(terrain);
		MaterialResolver resolver = new MaterialResolver();
		SurfaceRules.RuleSource surfaceRule = WorldsmithSurfaceRules.build(pack, biomes, resolver);

		context.register(pack.noiseSettingsKey(), new NoiseGeneratorSettings(
			NoiseSettings.create(
				terrain.getMinY(), terrain.getHeight(), terrain.getHorizontalNoiseSize(), terrain.getVerticalNoiseSize()
			),
			resolver.resolve(terrain.getDefaultBlock(), Blocks.STONE),
			resolver.resolve(terrain.getDefaultFluid(), Blocks.WATER),
			router(pack, functions, noises),
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
		CompiledPack pack,
		HolderGetter<DensityFunction> functions,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		TerrainPlan terrain = pack.terrain();
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
			return proceduralRouter(pack, procedural, functions, noises);
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
		CompiledPack pack,
		TerrainShape.Procedural shape,
		HolderGetter<DensityFunction> functions,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		TerrainPlan terrain = pack.terrain();
		NoiseRouter vanilla = NoiseRouterData.overworld(functions, noises, false, false);
		DensityFunction shiftX = NoiseRouterData.getFunction(functions, NoiseRouterData.SHIFT_X);
		DensityFunction shiftZ = NoiseRouterData.getFunction(functions, NoiseRouterData.SHIFT_Z);
		BiomeSpatialSettings spatial = pack.pack().getBiomes().getSpatial();
		double climateScale = 0.25 / spatial.getRegionScale();
		DensityFunction temperatureShiftX = shiftX;
		DensityFunction temperatureShiftZ = shiftZ;
		DensityFunction humidityShiftX = shiftX;
		DensityFunction humidityShiftZ = shiftZ;
		if (spatial.getBoundaryRoughness() > 0.0) {
			double warpScale = climateScale * 4.0;
			double warpAmount = BIOME_BOUNDARY_WARP * spatial.getBoundaryRoughness();
			temperatureShiftX = DensityFunctions.add(shiftX, DensityFunctions.mul(
				DensityFunctions.shiftedNoise2d(
					DensityFunctions.zero(), DensityFunctions.zero(), warpScale, noises.getOrThrow(Noises.SURFACE)
				),
				DensityFunctions.constant(warpAmount)
			));
			temperatureShiftZ = DensityFunctions.add(shiftZ, DensityFunctions.mul(
				DensityFunctions.shiftedNoise2d(
					DensityFunctions.zero(), DensityFunctions.zero(), warpScale, noises.getOrThrow(Noises.SURFACE_SECONDARY)
				),
				DensityFunctions.constant(warpAmount)
			));
			humidityShiftX = DensityFunctions.add(shiftX, DensityFunctions.mul(
				DensityFunctions.shiftedNoise2d(
					DensityFunctions.zero(), DensityFunctions.zero(), warpScale, noises.getOrThrow(Noises.JAGGED)
				),
				DensityFunctions.constant(warpAmount)
			));
			humidityShiftZ = DensityFunctions.add(shiftZ, DensityFunctions.mul(
				DensityFunctions.shiftedNoise2d(
					DensityFunctions.zero(), DensityFunctions.zero(), warpScale, noises.getOrThrow(Noises.PATCH)
				),
				DensityFunctions.constant(warpAmount)
			));
		}
		DensityFunction temperatureField = DensityFunctions.shiftedNoise2d(
			temperatureShiftX, temperatureShiftZ, climateScale, noises.getOrThrow(Noises.TEMPERATURE)
		);
		DensityFunction humidityField = DensityFunctions.shiftedNoise2d(
			humidityShiftX, humidityShiftZ, climateScale, noises.getOrThrow(Noises.VEGETATION)
		);

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

		DensityFunction reliefTexture = DensityFunctions.shiftedNoise2d(
			shiftX, shiftZ, baseScale * 1.6, noises.getOrThrow(Noises.EROSION)
		);
		DensityFunction reliefSelector = DensityFunctions.shiftedNoise2d(
			shiftX, shiftZ, baseScale * 2.2, noises.getOrThrow(Noises.RIDGE)
		);
		// Weirdness is a separate biome-texture axis. Reusing reliefSelector here
		// would make every raw erosion+weirdness box describe a false Cartesian
		// product. Sampling the same stationary noise far away preserves the
		// calibrated marginal spread while decorrelating it from landform choice.
		DensityFunction weirdnessField = DensityFunctions.shiftedNoise2d(
			DensityFunctions.add(shiftX, DensityFunctions.constant(2_048.0)),
			DensityFunctions.add(shiftZ, DensityFunctions.constant(-2_048.0)),
			baseScale * 2.2,
			noises.getOrThrow(Noises.RIDGE)
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

		// The one landform signal consumed by both geometry and biome climate.
		// The raw selector is partitioned according to the authored shares, then
		// each partition is mapped safely inside the erosion interval used by the
		// corresponding biome slot. The small texture preserves variation without
		// ever letting a flat cell call itself a peak (or vice versa).
		DensityFunction flatsLandform = DensityFunctions.add(
			DensityFunctions.constant(0.45),
			DensityFunctions.mul(reliefTexture, DensityFunctions.constant(0.25))
		);
		DensityFunction highlandsLandform = DensityFunctions.add(
			DensityFunctions.constant(-0.16),
			DensityFunctions.mul(reliefTexture, DensityFunctions.constant(0.14))
		);
		DensityFunction peaksLandform = DensityFunctions.add(
			DensityFunctions.constant(-0.68),
			DensityFunctions.mul(reliefTexture, DensityFunctions.constant(0.18))
		);
		DensityFunction landform = DensityFunctions.intervalSelect(
			reliefSelector,
			thresholds,
			List.of(flatsLandform, highlandsLandform, peaksLandform)
		);

		Map<String, DensityFunction> anchorInfluence = new LinkedHashMap<>();
		for (Anchor anchor : shape.getAnchors()) {
			anchorInfluence.put(anchor.getId(), DensityFunctions.cache2d(anchorField(anchor, noises)));
		}
		// Erosion is Worldsmith's public landform axis. Applying its authored
		// anchor bias before either consumer means a landmark changes its real
		// relief and its biome identity together.
		landform = biasClimate(
			landform, shape.getAnchors(), anchorInfluence, AnchorClimateBias::getErosion, noises);
		DoubleArrayList landformEdges = new DoubleArrayList();
		landformEdges.add(-0.375);
		landformEdges.add(0.05);
		DensityFunction reliefHeight = DensityFunctions.intervalSelect(
			landform,
			landformEdges,
			List.of(peaks, highlands, flats)
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
			temperatureField, shape.getAnchors(), anchorInfluence, AnchorClimateBias::getTemperature, noises);
		DensityFunction humidity = biasClimate(
			humidityField, shape.getAnchors(), anchorInfluence, AnchorClimateBias::getHumidity, noises);
		DensityFunction biomeContinents = biasClimate(
			hydrology.continents(), shape.getAnchors(), anchorInfluence, AnchorClimateBias::getContinentalness, noises);
		DensityFunction weirdness = biasClimate(
			weirdnessField, shape.getAnchors(), anchorInfluence, AnchorClimateBias::getWeirdness, noises);

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

		DensityFunction slidTerrain = NoiseRouterData.slide(
			baseTerrain,
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
		DensityFunction authoredTerrain = slidTerrain;
		for (TerrainBand band : shape.getBands()) {
			authoredTerrain = applyBand(
				authoredTerrain, band, shiftX, shiftZ, biomeContinents, anchorInfluence, noises
			);
		}

		// Bands are part of the uncarved body, rather than an afterthought added
		// after caves. Every cave family therefore acts on ground, floating
		// islands and authored underground bodies in exactly the same order.
		CaveIntent caves = shape.getCaves();
		DensityFunction caveWindow = caveWindow(functions, caves.getVerticalRange());
		DensityFunction carvedTerrain = applyCaveFamily(
			authoredTerrain,
			entranceField(noises),
			caves.getEntranceDensity(),
			caveWindow
		);
		carvedTerrain = applyCaveFamily(
			carvedTerrain,
			tunnelField(functions, noises),
			caves.getTunnelDensity(),
			caveWindow
		);
		carvedTerrain = applyCaveFamily(
			carvedTerrain,
			cavernField(functions, noises, authoredTerrain),
			caves.getCavernDensity(),
			caveWindow
		);
		DensityFunction uncarvedDensity = NoiseRouterData.postProcess(authoredTerrain);
		DensityFunction finalDensity = NoiseRouterData.postProcess(carvedTerrain);
		// postProcess places interpolation markers around its input. A Y gate
		// inside those markers gets blended across cell boundaries and lets caves
		// leak several blocks beyond verticalRange. Select again outside the
		// interpolated graph so the authored range is a hard invariant and the
		// protected bedrock floor always receives the uncarved density.
		CaveVerticalRange caveRange = caves.getVerticalRange();
		finalDensity = DensityFunctions.rangeChoice(
			NoiseRouterData.getFunction(functions, NoiseRouterData.Y),
			caveRange.getMinY(),
			caveRange.getMaxY() + 1.0,
			finalDensity,
			uncarvedDensity
		);
		finalDensity = applyCaveFamily(
			finalDensity,
			NoiseRouterData.getFunction(functions, NoiseRouterData.NOODLE),
			caves.getNoodleDensity(),
			caveWindow
		);
		// Band windows also live inside interpolation and may move a fractional
		// zero crossing by part of one noise cell when their integer bounds are
		// not cell-aligned. The public envelope promises five sealed bottom
		// layers, so enforce solid density outside that envelope at the final,
		// per-block level. Surface rules then replace those blocks with the usual
		// randomized bedrock floor.
		finalDensity = DensityFunctions.rangeChoice(
			NoiseRouterData.getFunction(functions, NoiseRouterData.Y),
			minY + 5.0,
			maxY,
			finalDensity,
			DensityFunctions.constant(1.0)
		);

		DensityFunction basePreliminarySurface = DensityFunctions.findTopSurface(
			slidTerrain,
			DensityFunctions.constant(maxY),
			minY,
			Math.max(4, terrain.getVerticalNoiseSize() * 4)
		);
		DensityFunction authoredPreliminarySurface = DensityFunctions.findTopSurface(
			authoredTerrain,
			DensityFunctions.constant(maxY),
			minY,
			Math.max(4, terrain.getVerticalNoiseSize() * 4)
		);
		// SurfaceRules only evaluates rich top layers from the preliminary level
		// upward. Using the topmost floating island here would therefore hide the
		// ordinary ground below it. The lower estimate keeps both surfaces in the
		// evaluation window, while an authored chasm that removes the original top
		// can still lower the estimate and expose its floor.
		DensityFunction preliminarySurface = DensityFunctions.min(
			basePreliminarySurface,
			authoredPreliminarySurface
		);
		// Climate depth is position relative to the local surface, not absolute
		// world Y. Reusing the uncarved terrain field keeps it near zero at the
		// ground even when verticalScale moves that ground far above sea level.
		DensityFunction depth = authoredTerrain.clamp(-1.0, 1.0);

		return new NoiseRouter(
			vanilla.barrierNoise(),
			floodedness(vanilla.fluidLevelFloodednessNoise(), caves.getFloodedChance()),
			vanilla.fluidLevelSpreadNoise(),
			vanilla.lavaNoise(),
			temperature,
			humidity,
			biomeContinents,
			landform,
			depth,
			weirdness,
			preliminarySurface,
			finalDensity,
			vanilla.veinToggle(),
			vanilla.veinRidged(),
			vanilla.veinGap()
		);
	}

	private static void requireOverworldEnvelope(TerrainPlan terrain) {
		if (terrain.getMinY() != OVERWORLD_MIN_Y || terrain.getHeight() != OVERWORLD_HEIGHT) {
			throw new IllegalArgumentException(
				"Worldsmith uses the Overworld dimension type and requires minY " + OVERWORLD_MIN_Y
					+ " with height " + OVERWORLD_HEIGHT
			);
		}
	}

	/** A hard Y gate shared by every cave family; one authored interval, no silent exceptions. */
	private static DensityFunction caveWindow(
		HolderGetter<DensityFunction> functions,
		CaveVerticalRange range
	) {
		DensityFunction y = NoiseRouterData.getFunction(functions, NoiseRouterData.Y);
		return DensityFunctions.rangeChoice(
			y,
			range.getMinY(),
			range.getMaxY() + 1.0,
			DensityFunctions.constant(1.0),
			DensityFunctions.zero()
		);
	}

	/** Blends one carving family with the current body only inside the requested Y range. */
	private static DensityFunction applyCaveFamily(
		DensityFunction terrain,
		DensityFunction cave,
		double density,
		DensityFunction verticalWindow
	) {
		if (density <= 0.0) {
			return terrain;
		}
		DensityFunction amount = DensityFunctions.mul(
			verticalWindow,
			DensityFunctions.constant(Math.min(1.0, density))
		);
		return DensityFunctions.lerp(amount, terrain, DensityFunctions.min(terrain, cave));
	}

	/** Vanilla's traversable spaghetti family, isolated from its cavern and entrance siblings. */
	private static DensityFunction tunnelField(
		HolderGetter<DensityFunction> functions,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		DensityFunction roughness = NoiseRouterData.getFunction(functions, NoiseRouterData.SPAGHETTI_ROUGHNESS_FUNCTION);
		DensityFunction spaghetti2d = DensityFunctions.add(
			NoiseRouterData.getFunction(functions, NoiseRouterData.SPAGHETTI_2D),
			roughness
		);
		DensityFunction rarity = DensityFunctions.cacheOnce(
			DensityFunctions.noise(noises.getOrThrow(Noises.SPAGHETTI_3D_RARITY), 2.0, 1.0)
		);
		DensityFunction thickness = DensityFunctions.mappedNoise(
			noises.getOrThrow(Noises.SPAGHETTI_3D_THICKNESS), -0.065, -0.088
		);
		DensityFunction spaghetti3d = DensityFunctions.add(
			DensityFunctions.max(
				spaghettiRarity3d(rarity, noises.getOrThrow(Noises.SPAGHETTI_3D_1)),
				spaghettiRarity3d(rarity, noises.getOrThrow(Noises.SPAGHETTI_3D_2))
			),
			thickness
		).clamp(-1.0, 1.0);
		return DensityFunctions.min(spaghetti2d, DensityFunctions.add(spaghetti3d, roughness));
	}

	/** The broad surface-opening field without the 3D tunnel field vanilla folds into ENTRANCES. */
	private static DensityFunction entranceField(HolderGetter<NormalNoise.NoiseParameters> noises) {
		return DensityFunctions.cacheOnce(DensityFunctions.mul(
			DensityFunctions.constant(5.0),
			DensityFunctions.add(
				DensityFunctions.add(
					DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_ENTRANCE), 0.75, 0.5),
					DensityFunctions.constant(0.37)
				),
				DensityFunctions.yClampedGradient(-10, 30, 0.3, 0.0)
			)
		));
	}

	/** Vanilla's quantized 3D spaghetti scale, local so tunnels and entrances remain independent controls. */
	private static DensityFunction spaghettiRarity3d(
		DensityFunction rarity,
		Holder<NormalNoise.NoiseParameters> noise
	) {
		DoubleArrayList thresholds = new DoubleArrayList();
		thresholds.add(-0.5);
		thresholds.add(0.0);
		thresholds.add(0.5);
		return DensityFunctions.intervalSelect(
			rarity,
			thresholds,
			List.of(
				rarityNoise(noise, 0.75),
				rarityNoise(noise, 1.0),
				rarityNoise(noise, 1.5),
				rarityNoise(noise, 2.0)
			)
		).abs();
	}

	private static DensityFunction rarityNoise(Holder<NormalNoise.NoiseParameters> noise, double rarity) {
		return DensityFunctions.mul(
			DensityFunctions.constant(rarity),
			DensityFunctions.noise(noise, 1.0 / rarity, 1.0 / rarity)
		);
	}

	/** Vanilla's broad cheese/layer family, including its solid pillars, as one semantic cavern field. */
	private static DensityFunction cavernField(
		HolderGetter<DensityFunction> functions,
		HolderGetter<NormalNoise.NoiseParameters> noises,
		DensityFunction terrain
	) {
		DensityFunction layers = DensityFunctions.mul(
			DensityFunctions.constant(4.0),
			DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_LAYER), 8.0).square()
		);
		DensityFunction cheese = DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_CHEESE), 0.6666666666666666);
		DensityFunction solidifiedCheese = DensityFunctions.add(
			DensityFunctions.add(DensityFunctions.constant(0.27), cheese).clamp(-1.0, 1.0),
			DensityFunctions.add(
				DensityFunctions.constant(1.5),
				DensityFunctions.mul(DensityFunctions.constant(-0.64), terrain)
			).clamp(0.0, 0.5)
		);
		DensityFunction caverns = DensityFunctions.add(layers, solidifiedCheese);
		DensityFunction rawPillars = NoiseRouterData.getFunction(functions, NoiseRouterData.PILLARS);
		DensityFunction pillars = DensityFunctions.rangeChoice(
			rawPillars,
			-1_000_000.0,
			0.03,
			DensityFunctions.constant(-1_000_000.0),
			rawPillars
		);
		return DensityFunctions.max(caverns, pillars);
	}

	/** Biases the aquifer's floodedness field while retaining its spatial variation at intermediate values. */
	private static DensityFunction floodedness(DensityFunction vanilla, double chance) {
		if (chance <= 0.0) {
			return DensityFunctions.constant(-1.0);
		}
		if (chance >= 1.0) {
			return DensityFunctions.constant(1.0);
		}
		return DensityFunctions.add(
			DensityFunctions.mul(vanilla, DensityFunctions.constant(0.75)),
			DensityFunctions.constant((chance - 0.5) * 2.0)
		).clamp(-1.0, 1.0);
	}

	/** Applies one explicitly authored climate target through the shared influence field. */
	private static DensityFunction biasClimate(
		DensityFunction source,
		List<Anchor> anchors,
		Map<String, DensityFunction> influence,
		Function<AnchorClimateBias, Double> target,
		HolderGetter<NormalNoise.NoiseParameters> noises
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
			// The influence is warped before it is used, and that is what keeps a
			// landmark from wearing bullseye rings. A biome border is drawn where
			// a field crosses a threshold; interpolating toward a target makes
			// the field a function of the raw influence, and the raw influence is
			// a function of distance alone, so every such border comes out a
			// perfect circle. Distorting the influence with a low-frequency noise
			// leaves the target reached at the centre - which an ocean landmark
			// needs in order to publish an inland climate at all - while making
			// every contour around it as ragged as the world it sits in.
			DensityFunction strength = DensityFunctions.mul(
				warpedInfluence(influence.get(anchor.getId()), anchor.getRadius(), noises),
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

	/**
	 * The anchor influence with its contours broken up.
	 *
	 * <p>The distortion is scaled by how far the influence already is from full,
	 * so it vanishes at the centre and is strongest in between. That is where it
	 * is wanted: the summit must still reach the target exactly, because an
	 * ocean landmark cannot publish an inland climate otherwise, while every
	 * contour that a biome border could follow lies at partial influence.
	 *
	 * <p>Its wavelength is scaled by the anchor's own radius so a large landmark
	 * undulates broadly rather than being fringed.
	 */
	private static DensityFunction warpedInfluence(
		DensityFunction influence,
		int radius,
		HolderGetter<NormalNoise.NoiseParameters> noises
	) {
		DensityFunction warp = DensityFunctions.shiftedNoise2d(
			DensityFunctions.zero(),
			DensityFunctions.zero(),
			ANCHOR_CLIMATE_WARP_SCALE / Math.max(64.0, radius),
			noises.getOrThrow(Noises.SURFACE_SECONDARY)
		).clamp(-1.0, 1.0);
		DensityFunction fade = DensityFunctions.add(
			DensityFunctions.constant(1.0),
			DensityFunctions.mul(influence, DensityFunctions.constant(-1.0))
		);
		return DensityFunctions.mul(
			influence,
			DensityFunctions.add(
				DensityFunctions.constant(1.0),
				DensityFunctions.mul(
					DensityFunctions.mul(warp, fade),
					DensityFunctions.constant(ANCHOR_CLIMATE_WARP)
				)
			)
		).clamp(0.0, 1.0);
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
				anchor.getFalloff(),
				new DensityFunction.NoiseHolder(noises.getOrThrow(WorldsmithAnchorFields.SILHOUETTE_NOISE))
			);
		}
		if (placement instanceof AnchorPlacement.Scattered scattered) {
			return new WorldsmithAnchorFields.Grid(
				scattered.getSpacing(),
				scattered.getJitter(),
				anchor.getRadius(),
				anchor.getFalloff(),
				new DensityFunction.NoiseHolder(noises.getOrThrow(WorldsmithAnchorFields.JITTER_NOISE)),
				new DensityFunction.NoiseHolder(noises.getOrThrow(WorldsmithAnchorFields.SILHOUETTE_NOISE))
			);
		}
		if (placement instanceof AnchorPlacement.Line line) {
			return new WorldsmithAnchorFields.Line(
				line.getStartX(),
				line.getStartZ(),
				line.getEndX(),
				line.getEndZ(),
				anchor.getRadius(),
				anchor.getFalloff(),
				new DensityFunction.NoiseHolder(noises.getOrThrow(WorldsmithAnchorFields.SILHOUETTE_NOISE))
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
