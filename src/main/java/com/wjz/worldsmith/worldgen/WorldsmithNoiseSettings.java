package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.BiomeSkinSet;
import java.util.List;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * The single noise settings entry for a Worldsmith world.
 *
 * <p>Stage one deliberately reuses vanilla's overworld noise router. Density
 * functions are the easiest part of world generation to get wrong and the
 * hardest to check automatically, so terrain shape is left alone and only the
 * three things that read as "somewhere else" are replaced: which biomes exist,
 * what the ground is made of, and where the player starts.
 */
public final class WorldsmithNoiseSettings {
	public static final ResourceKey<NoiseGeneratorSettings> WASTELAND =
		ResourceKey.create(Registries.NOISE_SETTINGS, Worldsmith.id("wasteland"));

	private static final int MIN_Y = -64;
	private static final int HEIGHT = 384;
	private static final int SEA_LEVEL = 63;

	private WorldsmithNoiseSettings() {
	}

	public static void bootstrap(BootstrapContext<NoiseGeneratorSettings> context) {
		HolderGetter<DensityFunction> functions = context.lookup(Registries.DENSITY_FUNCTION);
		HolderGetter<NormalNoise.NoiseParameters> noises = context.lookup(Registries.NOISE);
		HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);

		BiomeSkinSet skins = WorldsmithSkins.load();
		MaterialResolver resolver = new MaterialResolver();
		SurfaceRules.RuleSource surfaceRule = WorldsmithSurfaceRules.build(skins, biomes, resolver);
		resolver.report("surface rules");

		context.register(WASTELAND, new NoiseGeneratorSettings(
			NoiseSettings.create(MIN_Y, HEIGHT, 1, 2),
			Blocks.STONE.defaultBlockState(),
			Blocks.WATER.defaultBlockState(),
			NoiseRouterData.overworld(functions, noises, false, false),
			surfaceRule,
			spawnTarget(),
			SEA_LEVEL,
			false,
			true,
			true,
			false
		));
	}

	/**
	 * Where the player starts.
	 *
	 * <p>This field is mandatory in the codec and an empty list silently drops
	 * every spawn to (0, 0), which can be inside a mountain or under the sea. The
	 * two boxes below are vanilla's: inland, at surface depth, on either side of
	 * the river band so nobody wakes up in a river.
	 */
	private static List<Climate.ParameterPoint> spawnTarget() {
		Climate.Parameter full = Climate.Parameter.span(-1.0F, 1.0F);
		Climate.Parameter inland = Climate.Parameter.span(-0.11F, 1.0F);
		Climate.Parameter surfaceDepth = Climate.Parameter.point(0.0F);
		return List.of(
			new Climate.ParameterPoint(full, full, inland, full, surfaceDepth, Climate.Parameter.span(-1.0F, -0.16F), 0L),
			new Climate.ParameterPoint(full, full, inland, full, surfaceDepth, Climate.Parameter.span(0.16F, 1.0F), 0L)
		);
	}
}
