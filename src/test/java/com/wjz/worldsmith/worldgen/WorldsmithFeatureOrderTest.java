package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomeFeatureRef;
import com.wjz.worldsmith.core.model.BiomePlan;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.model.WorldsmithPackManifest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Regression coverage for Minecraft's cross-biome feature dependency graph. */
final class WorldsmithFeatureOrderTest {
	private static HolderLookup.Provider activeWorldgen;

	@BeforeAll
	static void bootstrapMinecraft() {
		WorldsmithTestBootstrap.bootStrap();
		activeWorldgen = WorldsmithPackExporter.compilePatch(
			WorldsmithPacks.builtinCompiled(),
			VanillaRegistries.createLookup()
		).full();
	}

	@Test
	void oppositePromptOrderDoesNotCreateAFeatureCycle() {
		WorldsmithPack source = WorldsmithPacks.builtin();
		List<FeatureDefinition> library = source.getFeatures().getFeatures();
		BiomeFeatureRef first = new BiomeFeatureRef(library.get(0).getId(), null);
		BiomeFeatureRef second = new BiomeFeatureRef(library.get(1).getId(), null);

		List<BiomeDefinition> definitions = new ArrayList<>(source.getBiomes().getBiomes());
		definitions.set(0, withFeatures(definitions.get(0), List.of(first, second)));
		definitions.set(1, withFeatures(definitions.get(1), List.of(second, first)));
		BiomePlan biomes = new BiomePlan(source.getBiomes().getSchemaVersion(), List.copyOf(definitions));

		String id = "f".repeat(64);
		WorldsmithPackManifest oldManifest = source.getManifest();
		WorldsmithPackManifest manifest = new WorldsmithPackManifest(
			oldManifest.getFormatVersion(), id, "Feature order fixture", "Compiler regression", oldManifest.getFiles()
		);
		CompiledPack pack = CompiledPack.scoped(new WorldsmithPack(
			manifest, source.getTerrain(), biomes, source.getFeatures(), id
		));
		RegistrySetBuilder.PatchedRegistries compiled = WorldsmithPackExporter.compilePatch(pack, activeWorldgen);
		HolderLookup.RegistryLookup<Biome> registry = compiled.full().lookupOrThrow(Registries.BIOME);
		List<Holder.Reference<Biome>> generated = pack.biomes().stream()
			.map(biome -> registry.getOrThrow(biome.key()))
			.toList();

		assertDoesNotThrow(() -> FeatureSorter.buildFeaturesPerStep(
			generated,
			biome -> biome.value().getGenerationSettings().features(),
			true
		));
	}

	private static BiomeDefinition withFeatures(BiomeDefinition biome, List<BiomeFeatureRef> features) {
		return new BiomeDefinition(
			biome.getId(),
			biome.getDisplayName(),
			biome.getArchetype(),
			biome.getSlot(),
			biome.getClimate(),
			biome.getBehavior(),
			biome.getSurface(),
			biome.getEnvironment(),
			biome.getTags(),
			features
		);
	}
}
