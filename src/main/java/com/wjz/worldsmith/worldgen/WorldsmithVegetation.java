package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.BiomeSkin;
import com.wjz.worldsmith.core.model.BiomeSkinSet;
import com.wjz.worldsmith.core.model.VegetationSlot;
import java.util.List;
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
 * Expands the three vegetation recipes into configured and placed features.
 *
 * <p>The model picks a recipe name and a density; the shape of the feature and
 * every placement modifier is decided here. Keeping the vocabulary closed is
 * what makes the output checkable: an unknown recipe cannot be serialised, so
 * it fails at compile time rather than producing a quietly empty world.
 *
 * <p>One feature pair is emitted per slot, keyed by skeleton id and slot index.
 * The index is positional and must stay aligned across the configured feature,
 * the placed feature, and the biome that references it.
 */
public final class WorldsmithVegetation {
	private static final int MAX_PATCH_COUNT = 24;
	private static final int MAX_RARITY = 32;

	private WorldsmithVegetation() {
	}

	public static ResourceKey<ConfiguredFeature<?, ?>> configuredKey(String skeletonId, int index) {
		return ResourceKey.create(Registries.CONFIGURED_FEATURE, Worldsmith.id("vegetation/" + skeletonId + "_" + index));
	}

	public static ResourceKey<PlacedFeature> placedKey(String skeletonId, int index) {
		return ResourceKey.create(Registries.PLACED_FEATURE, Worldsmith.id("vegetation/" + skeletonId + "_" + index));
	}

	public static void bootstrapConfigured(BootstrapContext<ConfiguredFeature<?, ?>> context) {
		BiomeSkinSet skins = WorldsmithPacks.builtin().getBiomeSkins();
		MaterialResolver resolver = new MaterialResolver();

		for (BiomeSkin skin : skins.getSkins()) {
			List<VegetationSlot> slots = skin.getVegetation();
			for (int index = 0; index < slots.size(); index++) {
				context.register(configuredKey(skin.getSkeletonId(), index), configure(slots.get(index), resolver));
			}
		}
		resolver.report("vegetation");
	}

	public static void bootstrapPlaced(BootstrapContext<PlacedFeature> context) {
		BiomeSkinSet skins = WorldsmithPacks.builtin().getBiomeSkins();
		HolderGetter<ConfiguredFeature<?, ?>> configured = context.lookup(Registries.CONFIGURED_FEATURE);

		for (BiomeSkin skin : skins.getSkins()) {
			List<VegetationSlot> slots = skin.getVegetation();
			for (int index = 0; index < slots.size(); index++) {
				ResourceKey<ConfiguredFeature<?, ?>> source = configuredKey(skin.getSkeletonId(), index);
				context.register(
					placedKey(skin.getSkeletonId(), index),
					new PlacedFeature(configured.getOrThrow(source), place(slots.get(index)))
				);
			}
		}
	}

	private static ConfiguredFeature<?, ?> configure(VegetationSlot slot, MaterialResolver resolver) {
		BlockState state = resolver.resolve(slot.getBlock(), Blocks.DEAD_BUSH);
		return switch (slot.getRecipe()) {
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

	private static List<PlacementModifier> place(VegetationSlot slot) {
		return switch (slot.getRecipe()) {
			case GROUND_PATCH -> List.of(
				CountPlacement.of(count(slot.getDensity())),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP_WORLD_SURFACE,
				BiomeFilter.biome(),
				BlockPredicateFilter.forPredicate(BlockPredicate.ONLY_IN_AIR_PREDICATE)
			);
			case DEAD_TREE, BOULDER -> List.of(
				RarityFilter.onAverageOnceEvery(rarity(slot.getDensity())),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP_WORLD_SURFACE,
				BiomeFilter.biome()
			);
		};
	}

	/** Dense ground cover is a per-chunk count. */
	private static int count(double density) {
		return Math.max(1, (int) Math.round(density * MAX_PATCH_COUNT));
	}

	/** Sparse props are a rarity: denser means a smaller "once every N chunks". */
	private static int rarity(double density) {
		return Math.max(1, (int) Math.round((1.0 - density) * MAX_RARITY));
	}
}
