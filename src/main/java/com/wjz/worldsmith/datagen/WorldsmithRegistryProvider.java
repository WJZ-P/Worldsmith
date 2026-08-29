package com.wjz.worldsmith.datagen;

import com.wjz.worldsmith.core.model.BiomeSkin;
import com.wjz.worldsmith.worldgen.BiomeSkeleton;
import com.wjz.worldsmith.worldgen.BiomeSkeletons;
import com.wjz.worldsmith.worldgen.WorldsmithNoiseSettings;
import com.wjz.worldsmith.worldgen.WorldsmithSkins;
import com.wjz.worldsmith.worldgen.WorldsmithVegetation;
import com.wjz.worldsmith.worldgen.WorldsmithWorldPresets;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricDynamicRegistryProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Writes Worldsmith's worldgen entries into the generated data pack.
 *
 * <p>Only entries this mod owns are emitted. The bootstraps themselves run from
 * {@link WorldsmithDataGenerator#buildRegistry}, which is what lets a biome
 * reference a placed feature that is also being generated in the same pass.
 */
public final class WorldsmithRegistryProvider extends FabricDynamicRegistryProvider {
	public WorldsmithRegistryProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	protected void configure(HolderLookup.Provider registries, Entries entries) {
		HolderLookup.RegistryLookup<ConfiguredFeature<?, ?>> configuredFeatures =
			registries.lookupOrThrow(Registries.CONFIGURED_FEATURE);
		HolderLookup.RegistryLookup<PlacedFeature> placedFeatures = registries.lookupOrThrow(Registries.PLACED_FEATURE);

		for (BiomeSkin skin : WorldsmithSkins.load().getSkins()) {
			for (int index = 0; index < skin.getVegetation().size(); index++) {
				entries.add(configuredFeatures, WorldsmithVegetation.configuredKey(skin.getSkeletonId(), index));
				entries.add(placedFeatures, WorldsmithVegetation.placedKey(skin.getSkeletonId(), index));
			}
		}

		HolderLookup.RegistryLookup<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
		for (BiomeSkeleton skeleton : BiomeSkeletons.ALL) {
			entries.add(biomes, skeleton.biome());
		}

		entries.add(registries.lookupOrThrow(Registries.NOISE_SETTINGS), WorldsmithNoiseSettings.WASTELAND);
		entries.add(registries.lookupOrThrow(Registries.WORLD_PRESET), WorldsmithWorldPresets.WASTELAND);
	}

	@Override
	public String getName() {
		return "Worldsmith Worldgen";
	}
}
