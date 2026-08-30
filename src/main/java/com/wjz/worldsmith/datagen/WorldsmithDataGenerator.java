package com.wjz.worldsmith.datagen;

import com.wjz.worldsmith.worldgen.WorldsmithPackExporter;
import com.wjz.worldsmith.worldgen.WorldsmithPacks;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.minecraft.core.RegistrySetBuilder;

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
		WorldsmithPackExporter.addTo(registryBuilder, WorldsmithPacks.builtinCompiled());
	}
}
