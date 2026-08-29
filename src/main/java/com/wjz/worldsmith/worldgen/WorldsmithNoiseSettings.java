package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.NoiseTemplate;
import com.wjz.worldsmith.core.model.TerrainPlan;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
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

	private WorldsmithNoiseSettings() {
	}

	public static void bootstrap(BootstrapContext<NoiseGeneratorSettings> context) {
		HolderGetter<DensityFunction> functions = context.lookup(Registries.DENSITY_FUNCTION);
		HolderGetter<NormalNoise.NoiseParameters> noises = context.lookup(Registries.NOISE);
		HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);

		TerrainPlan terrain = WorldsmithPacks.builtin().getTerrain();
		MaterialResolver resolver = new MaterialResolver();
		SurfaceRules.RuleSource surfaceRule = WorldsmithSurfaceRules.build(biomes, resolver);
		boolean largeBiomes = terrain.getNoiseTemplate() == NoiseTemplate.VANILLA_LARGE_BIOMES;
		boolean amplified = terrain.getNoiseTemplate() == NoiseTemplate.VANILLA_AMPLIFIED;

		context.register(WASTELAND, new NoiseGeneratorSettings(
			NoiseSettings.create(
				terrain.getMinY(), terrain.getHeight(), terrain.getHorizontalNoiseSize(), terrain.getVerticalNoiseSize()
			),
			resolver.resolve(terrain.getDefaultBlock(), Blocks.STONE),
			resolver.resolve(terrain.getDefaultFluid(), Blocks.WATER),
			NoiseRouterData.overworld(functions, noises, largeBiomes, amplified),
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
}
