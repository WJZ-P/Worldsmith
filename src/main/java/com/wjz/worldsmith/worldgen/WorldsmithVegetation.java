package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.feature.VegetationBudget;
import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomeFeatureRef;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.FeatureLibrary;
import com.wjz.worldsmith.core.model.VegetationRecipe;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.placement.PlacementUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.BlockBlobConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.BlockColumnConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
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
	/** Vanilla's common ore size; a vein of roughly this many blocks. */
	private static final int ORE_VEIN_SIZE = 33;
	/** Ore and cave decoration stay below the ordinary surface. */
	private static final int ORE_VEIN_CEILING = 64;
	/** How far down a cave patch looks for a floor before giving up. */
	private static final int CAVE_SCAN_DEPTH = 12;

	private WorldsmithVegetation() {
	}

	public static ResourceKey<ConfiguredFeature<?, ?>> configuredKey(CompiledPack pack, String featureId) {
		return pack.configuredFeatureKey(featureId);
	}

	public static ResourceKey<PlacedFeature> placedKey(CompiledPack pack, String featureId) {
		return pack.placedFeatureKey(featureId);
	}

	public static ResourceKey<PlacedFeature> placedKey(CompiledPack pack, String featureId, String biomeId) {
		return pack.placedFeatureKey(featureId, biomeId);
	}

	/** The placed feature a biome should reference for one of its entries. */
	public static ResourceKey<PlacedFeature> placedKeyFor(CompiledPack pack, BiomeDefinition biome, BiomeFeatureRef ref) {
		return ref.getDensity() == null
			? placedKey(pack, ref.getFeature())
			: placedKey(pack, ref.getFeature(), biome.getId());
	}

	/**
	 * Gives every biome the same relative order for shared placed features.
	 *
	 * <p>Minecraft does not treat a biome's feature array as local decoration
	 * priority. {@code FeatureSorter} combines every biome's array into one
	 * global dependency graph. If one biome says A then B and another says B
	 * then A, world creation reaches chunk generation and fails with a feature
	 * order cycle. Feature order is not part of Worldsmith's prompt vocabulary,
	 * so the feature-library declaration order is the single canonical order.
	 */
	static List<BiomeFeatureRef> orderedRefs(FeatureLibrary library, List<BiomeFeatureRef> refs) {
		Map<String, Integer> order = new LinkedHashMap<>();
		for (int index = 0; index < library.getFeatures().size(); index++) {
			order.put(library.getFeatures().get(index).getId(), index);
		}

		return refs.stream()
			.sorted(Comparator.comparingInt(ref -> {
				Integer index = order.get(ref.getFeature());
				if (index == null) {
					throw new IllegalStateException("Biome references unknown feature '" + ref.getFeature() + "'");
				}
				return index;
			}))
			.toList();
	}

	public static void bootstrapConfigured(CompiledPack pack, BootstrapContext<ConfiguredFeature<?, ?>> context) {
		FeatureLibrary library = pack.features();
		MaterialResolver resolver = new MaterialResolver();

		for (FeatureDefinition feature : library.getFeatures()) {
			context.register(configuredKey(pack, feature.getId()), configure(feature, resolver));
		}
		resolver.report("vegetation");
	}

	public static void bootstrapPlaced(CompiledPack pack, BootstrapContext<PlacedFeature> context) {
		HolderGetter<ConfiguredFeature<?, ?>> configured = context.lookup(Registries.CONFIGURED_FEATURE);
		Map<String, FeatureDefinition> library = byId(pack.features());

		// Several biomes may resolve to the same key when they all take the
		// default density, so collect before registering.
		Map<ResourceKey<PlacedFeature>, PlacedFeature> placed = new LinkedHashMap<>();
		for (BiomeDefinition biome : pack.definitions()) {
			for (BiomeFeatureRef ref : biome.getFeatures()) {
				FeatureDefinition definition = library.get(ref.getFeature());
				if (definition == null) {
					throw new IllegalStateException("Biome '" + biome.getId() + "' references unknown feature '" + ref.getFeature() + "'");
				}
				double density = ref.getDensity() == null ? definition.getDensity() : ref.getDensity();
				placed.putIfAbsent(
					placedKeyFor(pack, biome, ref),
					new PlacedFeature(configured.getOrThrow(configuredKey(pack, definition.getId())), place(definition, density))
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
			case DEAD_TREE -> {
				// The half of the survivability check core cannot make. It knows
				// whether a land biome grows something tree-shaped; only here is
				// it knowable whether that shape is actually wood, and a trunk of
				// stone leaves the world just as uncraftable as no trunk at all.
				if (!state.is(BlockTags.LOGS)) {
					Worldsmith.LOGGER.warn(
						"Feature '{}' is shaped like a tree but made of {}, which is not a log; "
							+ "a player cannot craft from it",
						feature.getId(),
						state.getBlock()
					);
				}
				yield new ConfiguredFeature<>(
					Feature.BLOCK_COLUMN,
					BlockColumnConfiguration.simple(UniformInt.of(2, 5), BlockStateProvider.simple(state))
				);
			}
			case BOULDER -> new ConfiguredFeature<>(
				Feature.BLOCK_BLOB,
				new BlockBlobConfiguration(state, BlockPredicate.matchesTag(BlockTags.FOREST_ROCK_CAN_PLACE_ON))
			);
			case ORE_VEIN -> new ConfiguredFeature<>(
				Feature.ORE,
				new OreConfiguration(new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES), state, ORE_VEIN_SIZE)
			);
			case CAVE_PATCH, SURFACE_LAYER -> new ConfiguredFeature<>(
				Feature.SIMPLE_BLOCK,
				new SimpleBlockConfiguration(BlockStateProvider.simple(state))
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
			// Mirrors vanilla's commonOrePlacement, which is a count, a spread, a
			// height range and the biome filter, in that order.
			case ORE_VEIN -> List.of(
				CountPlacement.of(VegetationBudget.veinCount(density)),
				InSquarePlacement.spread(),
				HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(8), VerticalAnchor.absolute(ORE_VEIN_CEILING)),
				BiomeFilter.biome()
			);
			// Dropped into the open underground and then walked down onto the
			// first solid surface, which is what puts it on a cave floor rather
			// than inside the rock.
			case CAVE_PATCH -> List.of(
				CountPlacement.of(VegetationBudget.veinCount(density)),
				InSquarePlacement.spread(),
				HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(8), VerticalAnchor.absolute(ORE_VEIN_CEILING)),
				EnvironmentScanPlacement.scanningFor(
					Direction.DOWN,
					BlockPredicate.solid(),
					BlockPredicate.ONLY_IN_AIR_PREDICATE,
					CAVE_SCAN_DEPTH
				),
				RandomOffsetPlacement.vertical(ConstantInt.of(1)),
				BiomeFilter.biome()
			);
			// Same shape as a ground patch; the difference is the step it runs
			// in, which is after everything else has been placed.
			case SURFACE_LAYER -> List.of(
				CountPlacement.of(VegetationBudget.patchCount(density)),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP_WORLD_SURFACE,
				BiomeFilter.biome(),
				BlockPredicateFilter.forPredicate(BlockPredicate.ONLY_IN_AIR_PREDICATE)
			);
		};
	}

	/**
	 * The decoration step a recipe belongs in.
	 *
	 * <p>The step is ordering, not position: it decides what is already there
	 * when a feature runs. Ore has to be cut into rock before anything stands on
	 * it, a boulder is a change to the land rather than something growing out of
	 * it, and a surface layer has to settle after the things it settles on.
	 */
	static GenerationStep.Decoration step(VegetationRecipe recipe) {
		return switch (recipe) {
			case ORE_VEIN -> GenerationStep.Decoration.UNDERGROUND_ORES;
			case CAVE_PATCH -> GenerationStep.Decoration.UNDERGROUND_DECORATION;
			case BOULDER -> GenerationStep.Decoration.LOCAL_MODIFICATIONS;
			case GROUND_PATCH, DEAD_TREE -> GenerationStep.Decoration.VEGETAL_DECORATION;
			case SURFACE_LAYER -> GenerationStep.Decoration.TOP_LAYER_MODIFICATION;
		};
	}
}
