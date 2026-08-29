package com.wjz.worldsmith.datagen;

import com.wjz.worldsmith.worldgen.BiomeCompiler;
import com.wjz.worldsmith.worldgen.WorldsmithNoiseSettings;
import com.wjz.worldsmith.worldgen.WorldsmithVegetation;
import com.wjz.worldsmith.worldgen.WorldsmithWorldPresets;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;

public final class WorldsmithDataGenerator implements DataGeneratorEntrypoint {
	@Override
	public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
		FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
		pack.addProvider(WorldsmithRegistryProvider::new);
		pack.addProvider(WorldsmithBiomeTagProvider::new);
		pack.addProvider(WorldsmithWorldPresetTagProvider::new);
		pack.addProvider(WorldsmithLangProvider::new);
	}

	/**
	 * Registration order matters: each bootstrap looks up the registries above it.
	 * Configured features have no dependencies, placed features reference them,
	 * biomes reference placed features, the noise settings reference biomes from
	 * inside the surface rules, and the world preset references everything.
	 */
	@Override
	public void buildRegistry(RegistrySetBuilder registryBuilder) {
		registryBuilder.add(Registries.CONFIGURED_FEATURE, WorldsmithVegetation::bootstrapConfigured);
		registryBuilder.add(Registries.PLACED_FEATURE, WorldsmithVegetation::bootstrapPlaced);
		registryBuilder.add(Registries.BIOME, BiomeCompiler::bootstrap);
		registryBuilder.add(Registries.NOISE_SETTINGS, WorldsmithNoiseSettings::bootstrap);
		registryBuilder.add(Registries.WORLD_PRESET, WorldsmithWorldPresets::bootstrap);
	}
}
