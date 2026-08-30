package com.wjz.worldsmith.worldgen;

import com.mojang.datafixers.util.Pair;
import com.wjz.worldsmith.Worldsmith;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * The world preset the player picks on the world creation screen.
 *
 * <p>The overworld biome source is built with
 * {@link MultiNoiseBiomeSource#createFromList} rather than a vanilla preset,
 * which is the supported way to ship a biome set that contains no vanilla biomes
 * at all. The nether and the end stay vanilla in stage one; leaving them out
 * would remove two dimensions the game expects to exist.
 */
public final class WorldsmithWorldPresets {
	public static final ResourceKey<WorldPreset> WASTELAND =
		ResourceKey.create(Registries.WORLD_PRESET, Worldsmith.id("wasteland"));

	private WorldsmithWorldPresets() {
	}

	public static void bootstrap(CompiledPack pack, BootstrapContext<WorldPreset> context) {
		HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);
		HolderGetter<NoiseGeneratorSettings> noiseSettings = context.lookup(Registries.NOISE_SETTINGS);
		HolderGetter<DimensionType> dimensionTypes = context.lookup(Registries.DIMENSION_TYPE);
		HolderGetter<MultiNoiseBiomeSourceParameterList> multiNoisePresets =
			context.lookup(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);

		List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = pack.biomes().stream()
			.map(biome -> Pair.of(biome.climate(), (Holder<Biome>) biomes.getOrThrow(biome.key())))
			.toList();

		LevelStem overworld = new LevelStem(
			dimensionTypes.getOrThrow(BuiltinDimensionTypes.OVERWORLD),
			new NoiseBasedChunkGenerator(
				MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(entries)),
				noiseSettings.getOrThrow(pack.noiseSettingsKey())
			)
		);

		LevelStem nether = new LevelStem(
			dimensionTypes.getOrThrow(BuiltinDimensionTypes.NETHER),
			new NoiseBasedChunkGenerator(
				MultiNoiseBiomeSource.createFromPreset(multiNoisePresets.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)),
				noiseSettings.getOrThrow(NoiseGeneratorSettings.NETHER)
			)
		);

		LevelStem end = new LevelStem(
			dimensionTypes.getOrThrow(BuiltinDimensionTypes.END),
			new NoiseBasedChunkGenerator(
				TheEndBiomeSource.create(biomes),
				noiseSettings.getOrThrow(NoiseGeneratorSettings.END)
			)
		);

		context.register(pack.worldPresetKey(), new WorldPreset(Map.of(
			LevelStem.OVERWORLD, overworld,
			LevelStem.NETHER, nether,
			LevelStem.END, end
		)));
	}
}
