package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.TerrainPlan;
import com.wjz.worldsmith.core.model.TerrainShape;
import com.wjz.worldsmith.core.model.VanillaNoisePreset;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.List;
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
		DensityFunction finalDensity = NoiseRouterData.postProcess(slidTerrain);
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
			vanilla.temperature(),
			vanilla.vegetation(),
			hydrology.continents(),
			erosion,
			depth,
			reliefSelector,
			preliminarySurface,
			finalDensity,
			vanilla.veinToggle(),
			vanilla.veinRidged(),
			vanilla.veinGap()
		);
	}

	private static double landBias(double landRatio) {
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
