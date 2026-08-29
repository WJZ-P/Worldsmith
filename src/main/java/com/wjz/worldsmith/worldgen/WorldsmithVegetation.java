package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.feature.VegetationBudget;
import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomeFeatureRef;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.FeatureLibrary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.placement.PlacementUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.BlockBlobConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.BlockColumnConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

/**
 * Expands the pack's feature library into configured and placed features.
 *
 * <p>A feature is declared once and compiled once, however many biomes use it.
 * Only a biome that overrides the density needs its own placed feature, and that
 * one is keyed by feature and biome rather than by list position, so reordering
 * a biome's feature list cannot silently repoint it at something else.
 *
 * <p>The pack picks a recipe name and a density; the shape of the feature and
 * every placement modifier is decided here. Keeping the vocabulary closed is
 * what makes the output checkable: an unknown recipe cannot be deserialised, so
 * it fails while loading rather than producing a quietly empty world.
 */
public final class WorldsmithVegetation {
	private WorldsmithVegetation() {
	}

	public static ResourceKey<ConfiguredFeature<?, ?>> configuredKey(String featureId) {
		return ResourceKey.create(Registries.CONFIGURED_FEATURE, Worldsmith.id("vegetation/" + featureId));
	}

	public static ResourceKey<PlacedFeature> placedKey(String featureId) {
		return ResourceKey.create(Registries.PLACED_FEATURE, Worldsmith.id("vegetation/" + featureId));
	}

	public static ResourceKey<PlacedFeature> placedKey(String featureId, String biomeId) {
		return ResourceKey.create(Registries.PLACED_FEATURE, Worldsmith.id("vegetation/" + featureId + "/" + biomeId));
	}

	/** The placed feature a biome should reference for one of its entries. */
	public static ResourceKey<PlacedFeature> placedKeyFor(BiomeDefinition biome, BiomeFeatureRef ref) {
		return ref.getDensity() == null ? placedKey(ref.getFeature()) : placedKey(ref.getFeature(), biome.getId());
	}

	public static void bootstrapConfigured(BootstrapContext<ConfiguredFeature<?, ?>> context) {
		FeatureLibrary library = WorldsmithPacks.builtin().getFeatures();
		MaterialResolver resolver = new MaterialResolver();

		for (FeatureDefinition feature : library.getFeatures()) {
			context.register(configuredKey(feature.getId()), configure(feature, resolver));
		}
		resolver.report("vegetation");
	}

	public static void bootstrapPlaced(BootstrapContext<PlacedFeature> context) {
		HolderGetter<ConfiguredFeature<?, ?>> configured = context.lookup(Registries.CONFIGURED_FEATURE);
		Map<String, FeatureDefinition> library = byId(WorldsmithPacks.builtin().getFeatures());

		// Several biomes may resolve to the same key when they all take the
		// default density, so collect before registering.
		Map<ResourceKey<PlacedFeature>, PlacedFeature> placed = new LinkedHashMap<>();
		for (BiomeDefinition biome : WorldsmithPacks.builtin().getBiomes().getBiomes()) {
			for (BiomeFeatureRef ref : biome.getFeatures()) {
				FeatureDefinition definition = library.get(ref.getFeature());
				if (definition == null) {
					throw new IllegalStateException("Biome '" + biome.getId() + "' references unknown feature '" + ref.getFeature() + "'");
				}
				double density = ref.getDensity() == null ? definition.getDensity() : ref.getDensity();
				placed.putIfAbsent(
					placedKeyFor(biome, ref),
					new PlacedFeature(configured.getOrThrow(configuredKey(definition.getId())), place(definition, density))
				);
			}
		}
		placed.forEach(context::register);
	}

	private static Map<String, FeatureDefinition> byId(FeatureLibrary library) {
		Map<String, FeatureDefinition> byId = new LinkedHashMap<>();
		library.getFeatures().forEach(feature -> byId.put(feature.getId(), feature));
		return byId;
	}

	private static ConfiguredFeature<?, ?> configure(FeatureDefinition feature, MaterialResolver resolver) {
		BlockState state = resolver.resolve(feature.getBlock(), Blocks.DEAD_BUSH);
		return switch (feature.getRecipe()) {
			case GROUND_PATCH -> new ConfiguredFeature<>(
				Feature.SIMPLE_BLOCK,
				new SimpleBlockConfiguration(BlockStateProvider.simple(state))
			);
			case DEAD_TREE -> new ConfiguredFeature<>(
				Feature.BLOCK_COLUMN,
				BlockColumnConfiguration.simple(UniformInt.of(2, 5), BlockStateProvider.simple(state))
			);
			case BOULDER -> new ConfiguredFeature<>(
				Feature.BLOCK_BLOB,
				new BlockBlobConfiguration(state, BlockPredicate.matchesTag(BlockTags.FOREST_ROCK_CAN_PLACE_ON))
			);
		};
	}

	private static List<PlacementModifier> place(FeatureDefinition feature, double density) {
		return switch (feature.getRecipe()) {
			case GROUND_PATCH -> List.of(
				CountPlacement.of(VegetationBudget.patchCount(density)),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP_WORLD_SURFACE,
				BiomeFilter.biome(),
				BlockPredicateFilter.forPredicate(BlockPredicate.ONLY_IN_AIR_PREDICATE)
			);
			case DEAD_TREE, BOULDER -> List.of(
				RarityFilter.onAverageOnceEvery(VegetationBudget.rarity(density)),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP_WORLD_SURFACE,
				BiomeFilter.biome()
			);
		};
	}
}
