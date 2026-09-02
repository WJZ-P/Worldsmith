package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.feature.VegetationBudget;
import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomeFeatureRef;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.FeatureLibrary;
import com.wjz.worldsmith.core.model.MaterialRole;
import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.FeatureRecipe;
import com.wjz.worldsmith.core.model.TreeSilhouette;
import com.wjz.worldsmith.core.model.TreeSpec;
import com.wjz.worldsmith.core.model.TreeDecoration;
import com.wjz.worldsmith.core.model.TreeDistribution;
import com.wjz.worldsmith.core.model.TreeHeight;
import com.wjz.worldsmith.core.model.TreeSubstrate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.features.VegetationFeatures;
import net.minecraft.data.worldgen.placement.PlacementUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.util.valueproviders.WeightedListInt;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.BlockBlobConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.BlockColumnConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.FallenTreeConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.FeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BushFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.CherryFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.SpruceFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.BendingTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.CherryTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.ForkingTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.AcaciaFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.LeaveVineDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.PlaceOnGroundDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TrunkVineDecorator;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
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
 * <p>The pack picks a recipe and bounded semantic controls; this compiler maps
 * them to the target version's feature, placer and modifier types. Keeping that
 * vocabulary closed makes the output checkable without freezing every tree to
 * one hard-coded geometry.
 */
public final class WorldsmithVegetation {
	/** Vanilla's common ore size; a vein of roughly this many blocks. */
	private static final int ORE_VEIN_SIZE = 33;
	/** Ore and cave decoration stay below the ordinary surface. */
	private static final int ORE_VEIN_CEILING = 64;
	/** How far down a cave patch looks for a floor before giving up. */
	private static final int CAVE_SCAN_DEPTH = 12;
	/** How far a hanging growth reaches down from what it is attached to. */
	private static final int HANGING_LENGTH = 6;

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

	/** The selector a recipe declared for one of its roles; the validator has already required it. */
	private static MaterialSelector material(FeatureDefinition feature, MaterialRole role) {
		MaterialSelector selector = feature.getAllMaterials().get(role);
		if (selector == null) {
			throw new IllegalStateException("Feature '" + feature.getId() + "' declares no " + role + " material");
		}
		return selector;
	}

	static ConfiguredFeature<?, ?> configure(FeatureDefinition feature, MaterialResolver resolver) {
		if (feature.getRecipe().isTree()) {
			return tree(feature, resolver);
		}
		if (feature.getRecipe() == FeatureRecipe.FALLEN_LOG) {
			return new ConfiguredFeature<>(
				Feature.FALLEN_TREE,
				new FallenTreeConfiguration.FallenTreeConfigurationBuilder(
					resolver.resolveProvider(material(feature, MaterialRole.TRUNK), Blocks.OAK_LOG),
					UniformInt.of(3, 6)
				).build()
			);
		}
		MaterialRole primaryRole = feature.getRecipe().getRoles().iterator().next();
		MaterialSelector primary = material(feature, primaryRole);
		MaterialResolver.ResolvedMaterial resolved = resolver.resolveMaterial(primary, Blocks.DEAD_BUSH);
		BlockState state = resolved.representativeState();
		BlockStateProvider provider = resolved.provider();
		return switch (feature.getRecipe()) {
			case TREE, FALLEN_LOG ->
				throw new IllegalStateException("handled above");
			case GROUND_PATCH -> new ConfiguredFeature<>(
				Feature.SIMPLE_BLOCK,
				new SimpleBlockConfiguration(provider)
			);
			case DEAD_TREE -> {
				// The half of the survivability check core cannot make. It knows
				// whether a land biome grows something tree-shaped; only here is
				// it knowable whether that shape is actually wood, and a trunk of
				// stone leaves the world just as uncraftable as no trunk at all.
				if (!invalidLogStates(List.of(state)).isEmpty()) {
					Worldsmith.LOGGER.warn(
						"Feature '{}' is shaped like a tree but made of {}, which is not a log; "
							+ "a player cannot craft from it",
						feature.getId(),
						state.getBlock()
					);
				}
				yield new ConfiguredFeature<>(
					Feature.BLOCK_COLUMN,
					BlockColumnConfiguration.simple(UniformInt.of(2, 5), provider)
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
			case CAVE_PATCH, SURFACE_LAYER, AQUATIC_PATCH -> new ConfiguredFeature<>(
				Feature.SIMPLE_BLOCK,
				new SimpleBlockConfiguration(provider)
			);
			// Grown downward from whatever it is attached to, which is what makes
			// it hang rather than stand.
			case HANGING_PATCH -> new ConfiguredFeature<>(
				Feature.BLOCK_COLUMN,
				new BlockColumnConfiguration(
					List.of(BlockColumnConfiguration.layer(UniformInt.of(1, HANGING_LENGTH), provider)),
					Direction.DOWN,
					BlockPredicate.ONLY_IN_AIR_PREDICATE,
					true
				)
			);
		};
	}

	/**
	 * A tree with leaves on it, which is the one shape a column cannot fake.
	 *
	 * <p>Two roles rather than one, because Minecraft builds a tree from a trunk
	 * provider and a foliage provider placed by two separate strategies. The
	 * silhouette selects a proven placer family while the optional bounded fields
	 * tune its height, crown and trailing leaves.
	 */
	private static ConfiguredFeature<?, ?> tree(FeatureDefinition feature, MaterialResolver resolver) {
		TreeSpec tree = feature.getTree();
		if (tree == null) {
			throw new IllegalStateException("Feature '" + feature.getId() + "' declares TREE without a tree specification");
		}
		MaterialResolver.ResolvedMaterial trunkMaterial = resolver.resolveMaterial(
			material(feature, MaterialRole.TRUNK),
			Blocks.OAK_LOG
		);
		MaterialResolver.ResolvedMaterial foliageMaterial = resolver.resolveMaterial(
			material(feature, MaterialRole.FOLIAGE),
			Blocks.OAK_LEAVES
		);
		BlockStateProvider trunk = trunkMaterial.provider();
		BlockStateProvider foliage = foliageMaterial.provider();
		List<BlockState> invalidTrunks = invalidLogStates(trunkMaterial.states());
		if (!invalidTrunks.isEmpty()) {
			Worldsmith.LOGGER.warn(
				"Feature '{}' has tree trunk alternatives that are not logs: {}; a player cannot craft from them",
				feature.getId(),
				invalidTrunks.stream().map(state -> state.getBlock().toString()).toList()
			);
		}
		TreeShape shape = treeShape(tree);
		TreeConfiguration.TreeConfigurationBuilder builder = new TreeConfiguration.TreeConfigurationBuilder(
			trunk,
			shape.trunk(),
			foliage,
			shape.foliage(),
			shape.size(),
			belowTrunkProvider(tree.getSubstrate())
		).ignoreVines();
		List<TreeDecorator> decorations = treeDecorators(tree);
		if (!decorations.isEmpty()) {
			builder.decorators(decorations);
		}
		return new ConfiguredFeature<>(Feature.TREE, builder.build());
	}

	/** Maps a semantic, bounded tree specification onto Minecraft's placer vocabulary. */
	private static TreeShape treeShape(TreeSpec tree) {
		TreeHeight height = tree.getHeight() == null
			? new TreeHeight(tree.getSilhouette().getDefaultMinHeight(), tree.getSilhouette().getDefaultMaxHeight())
			: tree.getHeight();
		int baseHeight = height.getMin();
		int heightVariation = height.getMax() - height.getMin();
		int radius = tree.getCrownRadius() == null
			? tree.getSilhouette().getDefaultCrownRadius()
			: tree.getCrownRadius();

		return switch (tree.getSilhouette()) {
			case CONIFER -> new TreeShape(
				new StraightTrunkPlacer(baseHeight, heightVariation, 0),
				new SpruceFoliagePlacer(ConstantInt.of(radius), UniformInt.of(0, 2), UniformInt.of(1, 2)),
				new TwoLayersFeatureSize(1, 0, 1)
			);
			case BLOSSOM -> {
				TrunkPlacer trunkPlacer = new CherryTrunkPlacer(
					baseHeight,
					heightVariation,
					0,
					new WeightedListInt(
						WeightedList.<IntProvider>builder()
							.add(ConstantInt.of(1), 1)
							.add(ConstantInt.of(2), 1)
							.add(ConstantInt.of(3), 1)
							.build()
					),
					UniformInt.of(2, 4),
					UniformInt.of(-4, -3),
					UniformInt.of(-1, 0)
				);
				yield new TreeShape(
					trunkPlacer,
					cherryFoliage(tree, radius, false),
					new TwoLayersFeatureSize(1, 0, 2)
				);
			}
			// A leaning trunk under a crown that trails: the hanging chances are
			// what read as a willow rather than as a bent oak.
			case WEEPING -> new TreeShape(
				new BendingTrunkPlacer(baseHeight, heightVariation, 0, Math.max(1, baseHeight - 1), UniformInt.of(1, 2)),
				cherryFoliage(tree, radius, true),
				new TwoLayersFeatureSize(1, 0, 2)
			);
			case UMBRELLA -> new TreeShape(
				new ForkingTrunkPlacer(baseHeight, heightVariation, 0),
				new AcaciaFoliagePlacer(ConstantInt.of(radius), ConstantInt.of(0)),
				new TwoLayersFeatureSize(1, 0, 2)
			);
			case SHRUB -> new TreeShape(
				new StraightTrunkPlacer(baseHeight, heightVariation, 0),
				new BushFoliagePlacer(ConstantInt.of(radius), ConstantInt.of(0), Math.max(1, radius)),
				new TwoLayersFeatureSize(1, 0, 1)
			);
			case BROADLEAF -> new TreeShape(
				new StraightTrunkPlacer(baseHeight, heightVariation, 0),
				new BlobFoliagePlacer(ConstantInt.of(radius), ConstantInt.of(0), Math.max(2, radius + 1)),
				new TwoLayersFeatureSize(1, 0, 1)
			);
		};
	}

	private static CherryFoliagePlacer cherryFoliage(TreeSpec tree, int radius, boolean weeping) {
		double defaultHanging = weeping ? 0.75 : 0.16666667;
		double hanging = tree.getHangingLeaves() == null ? defaultHanging : tree.getHangingLeaves();
		double extension = weeping ? hanging * 0.8 : Math.min(1.0, hanging * 2.0);
		return new CherryFoliagePlacer(
			ConstantInt.of(radius),
			ConstantInt.of(0),
			ConstantInt.of(Math.max(4, radius + 1)),
			weeping ? 0.2F : 0.25F,
			weeping ? 0.4F : 0.5F,
			(float)hanging,
			(float)extension
		);
	}

	private static BlockStateProvider belowTrunkProvider(TreeSubstrate substrate) {
		return substrate == TreeSubstrate.NATURAL_SOIL
			? RuleBasedStateProvider.ifTrueThenProvide(TreeConfiguration.CAN_PLACE_BELOW_TREE_TRUNKS, Blocks.DIRT)
			: RuleBasedStateProvider.ifTrueThenProvide(BlockPredicate.not(BlockPredicate.alwaysTrue()), Blocks.DIRT);
	}

	private static List<TreeDecorator> treeDecorators(TreeSpec tree) {
		List<TreeDecorator> decorators = new java.util.ArrayList<>();
		for (TreeDecoration decoration : tree.getDecorations()) {
			switch (decoration) {
				case VINES -> {
					decorators.add(TrunkVineDecorator.INSTANCE);
					decorators.add(new LeaveVineDecorator(0.25F));
				}
				case LEAF_LITTER -> decorators.add(new PlaceOnGroundDecorator(
					96,
					2,
					2,
					new WeightedStateProvider(VegetationFeatures.leafLitterPatchBuilder(1, 4).build())
				));
			}
		}
		return List.copyOf(decorators);
	}

	private record TreeShape(TrunkPlacer trunk, FoliagePlacer foliage, FeatureSize size) {
	}

	/**
	 * Checks wood only when the live registry has actually bound the log tag.
	 *
	 * <p>Datagen and the plain-JVM compiler tests bootstrap blocks before data
	 * pack tags are loaded. In that phase even {@code minecraft:oak_log} reports
	 * false for {@link BlockTags#LOGS}; treating that as a real answer produced a
	 * warning about every valid trunk. World creation has the tag and receives
	 * the full check, while an unbound environment stays deliberately silent.
	 */
	private static List<BlockState> invalidLogStates(List<BlockState> states) {
		if (!BuiltInRegistries.BLOCK.getTagOrEmpty(BlockTags.LOGS).iterator().hasNext()) {
			return List.of();
		}
		return states.stream().filter(state -> !state.is(BlockTags.LOGS)).toList();
	}

	static List<PlacementModifier> place(FeatureDefinition feature, double density) {
		return switch (feature.getRecipe()) {
			case GROUND_PATCH -> List.of(
				CountPlacement.of(VegetationBudget.patchCount(density)),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP_WORLD_SURFACE,
				BiomeFilter.biome(),
				BlockPredicateFilter.forPredicate(BlockPredicate.ONLY_IN_AIR_PREDICATE)
			);
			case DEAD_TREE, BOULDER -> List.of(
				rareAttempt(density),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP_WORLD_SURFACE,
				BiomeFilter.biome()
			);
			case TREE -> treePlacement(feature, density);
			// FallenTreeFeature already searches for a sturdy floor and tolerates
			// short gaps along the log. Applying a living oak sapling's soil rule
			// here would erase fallen wood from stone, sand and wintry ground.
			case FALLEN_LOG -> List.of(
				rareAttempt(density),
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
			// Placed on the sea floor and then refused unless it is actually under
			// water, so a coastal biome does not sprout kelp on its beach.
			case AQUATIC_PATCH -> List.of(
				CountPlacement.of(VegetationBudget.patchCount(density)),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP_OCEAN_FLOOR,
				BiomeFilter.biome(),
				BlockPredicateFilter.forPredicate(BlockPredicate.matchesFluids(Fluids.WATER))
			);
			// Scans upward for something to hang from, so it lands on cave
			// ceilings and the underside of overhangs rather than in open air.
			case HANGING_PATCH -> List.of(
				CountPlacement.of(VegetationBudget.veinCount(density)),
				InSquarePlacement.spread(),
				PlacementUtils.FULL_RANGE,
				EnvironmentScanPlacement.scanningFor(
					Direction.UP,
					BlockPredicate.solid(),
					BlockPredicate.ONLY_IN_AIR_PREDICATE,
					CAVE_SCAN_DEPTH
				),
				RandomOffsetPlacement.vertical(ConstantInt.of(-1)),
				BiomeFilter.biome()
			);
			case SURFACE_LAYER -> List.of(
				CountPlacement.of(VegetationBudget.patchCount(density)),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP_WORLD_SURFACE,
				BiomeFilter.biome(),
				BlockPredicateFilter.forPredicate(BlockPredicate.ONLY_IN_AIR_PREDICATE)
			);
		};
	}

	/** A density of zero is absence, not one last feature every 32 chunks. */
	private static PlacementModifier rareAttempt(double density) {
		return density <= 0.0
			? CountPlacement.of(0)
			: RarityFilter.onAverageOnceEvery(VegetationBudget.rarity(density));
	}

	private static List<PlacementModifier> treePlacement(FeatureDefinition feature, double density) {
		TreeSpec tree = feature.getTree();
		if (tree == null) {
			throw new IllegalStateException("Feature '" + feature.getId() + "' declares TREE without a tree specification");
		}
		return List.of(
			treeCount(tree.getDistribution(), density),
			InSquarePlacement.spread(),
			tree.getSubstrate() == TreeSubstrate.SHALLOW_WATER
				? PlacementUtils.HEIGHTMAP_OCEAN_FLOOR
				: PlacementUtils.HEIGHTMAP_WORLD_SURFACE,
			BiomeFilter.biome(),
			BlockPredicateFilter.forPredicate(treeSubstrate(tree.getSubstrate()))
		);
	}

	private static PlacementModifier treeCount(TreeDistribution distribution, double density) {
		if (distribution == TreeDistribution.SCATTERED) {
			return CountPlacement.of(fractionalZeroOrOne(density));
		}
		return NoiseThresholdCountPlacement.of(
			VegetationBudget.treeNoiseThreshold(distribution),
			VegetationBudget.treeBelowNoiseCount(distribution, density),
			VegetationBudget.treeAboveNoiseCount(distribution, density)
		);
	}

	/** Samples one attempt with the requested probability, including exact zero and one. */
	private static IntProvider fractionalZeroOrOne(double probability) {
		if (probability <= 0.0) {
			return ConstantInt.of(0);
		}
		if (probability >= 1.0) {
			return ConstantInt.of(1);
		}
		int oneWeight = Math.max(1, Math.min(999, (int)Math.round(probability * 1_000.0)));
		int zeroWeight = 1_000 - oneWeight;
		return new WeightedListInt(
			WeightedList.<IntProvider>builder()
				.add(ConstantInt.of(0), zeroWeight)
				.add(ConstantInt.of(1), oneWeight)
				.build()
		);
	}

	private static BlockPredicate treeSubstrate(TreeSubstrate substrate) {
		BlockPos below = new BlockPos(0, -1, 0);
		return switch (substrate) {
			case NATURAL_SOIL -> BlockPredicate.wouldSurvive(Blocks.OAK_SAPLING.defaultBlockState(), BlockPos.ZERO);
			case SAND -> BlockPredicate.matchesTag(below, BlockTags.SAND);
			case SHALLOW_WATER -> BlockPredicate.allOf(
				BlockPredicate.matchesFluids(Fluids.WATER),
				BlockPredicate.hasSturdyFace(below, Direction.UP),
				BlockPredicate.matchesTag(new BlockPos(0, 4, 0), BlockTags.AIR)
			);
			case ANY_SOLID -> BlockPredicate.hasSturdyFace(below, Direction.UP);
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
	static GenerationStep.Decoration step(FeatureRecipe recipe) {
		return switch (recipe) {
			case ORE_VEIN -> GenerationStep.Decoration.UNDERGROUND_ORES;
			case CAVE_PATCH -> GenerationStep.Decoration.UNDERGROUND_DECORATION;
			case BOULDER -> GenerationStep.Decoration.LOCAL_MODIFICATIONS;
			case HANGING_PATCH -> GenerationStep.Decoration.UNDERGROUND_DECORATION;
			case GROUND_PATCH, DEAD_TREE, AQUATIC_PATCH, TREE, FALLEN_LOG ->
				GenerationStep.Decoration.VEGETAL_DECORATION;
			case SURFACE_LAYER -> GenerationStep.Decoration.TOP_LAYER_MODIFICATION;
		};
	}
}
