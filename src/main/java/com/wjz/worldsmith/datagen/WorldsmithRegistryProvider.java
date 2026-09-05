package com.wjz.worldsmith.datagen;

import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomeFeatureRef;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.worldgen.CompiledBiome;
import com.wjz.worldsmith.worldgen.CompiledPack;
import com.wjz.worldsmith.worldgen.WorldsmithNoiseSettings;
import com.wjz.worldsmith.worldgen.WorldsmithPacks;
import com.wjz.worldsmith.worldgen.WorldsmithVegetation;
import com.wjz.worldsmith.worldgen.WorldsmithWorldPresets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricDynamicRegistryProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
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
		CompiledPack pack = WorldsmithPacks.builtinCompiled();
		HolderLookup.RegistryLookup<ConfiguredFeature<?, ?>> configuredFeatures =
			registries.lookupOrThrow(Registries.CONFIGURED_FEATURE);
		HolderLookup.RegistryLookup<PlacedFeature> placedFeatures = registries.lookupOrThrow(Registries.PLACED_FEATURE);

		for (FeatureDefinition feature : pack.features().getFeatures()) {
			entries.add(configuredFeatures, WorldsmithVegetation.configuredKey(pack, feature.getId()));
		}

		// Biomes that take a feature's default density share one placed feature,
		// so collect the keys before emitting them.
		Set<ResourceKey<PlacedFeature>> placed = new LinkedHashSet<>();
		for (BiomeDefinition biome : pack.definitions()) {
			for (BiomeFeatureRef ref : biome.getFeatures()) {
				placed.add(WorldsmithVegetation.placedKeyFor(pack, biome, ref));
			}
		}
		placed.forEach(key -> entries.add(placedFeatures, key));

		HolderLookup.RegistryLookup<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
		for (CompiledBiome biome : pack.biomes()) {
			entries.add(biomes, biome.key());
		}

		entries.add(registries.lookupOrThrow(Registries.NOISE_SETTINGS), pack.noiseSettingsKey());
		entries.add(registries.lookupOrThrow(Registries.WORLD_PRESET), pack.worldPresetKey());
		for (var structure : pack.pack().getStructures().getStructures()) {
			entries.add(registries.lookupOrThrow(Registries.STRUCTURE), pack.structureKey(structure.getId()));
			entries.add(registries.lookupOrThrow(Registries.STRUCTURE_SET), pack.structureSetKey(structure.getId()));
		}
	}

	@Override
	public String getName() {
		return "Worldsmith Worldgen";
	}
}
