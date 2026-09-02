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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
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
	/** Vanilla's oak: four blocks plus up to two more, with a two-block crown. */
	private static final int TREE_BASE_HEIGHT = 4;
	private static final int TREE_HEIGHT_VARIATION = 2;
	private static final int TREE_FOLIAGE_RADIUS = 2;
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
	 * shapes are the compiler's to choose, as everywhere else: this is vanilla's
	 * straight-trunk blob-foliage oak, and the pack decides only what it is made
	 * of. Naming further shapes - conifer, weeping, blossom - is adding placer
	 * pairs here, not new vocabulary for the pack to get wrong.
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
		// One placer pair per silhouette. The pack names the look it wants and
		// never the geometry, exactly as it names a relief band and never an
		// erosion range; adding a shape later is another arm here rather than
		// another thing a document can get wrong.
		TreeShape shape = switch (tree.getSilhouette()) {
			case CONIFER -> new TreeShape(
				new StraightTrunkPlacer(6, 3, 0),
				new SpruceFoliagePlacer(UniformInt.of(2, 3), UniformInt.of(0, 2), UniformInt.of(1, 2)),
				new TwoLayersFeatureSize(1, 0, 1)
			);
			case BLOSSOM -> {
				TrunkPlacer trunkPlacer = new CherryTrunkPlacer(
					7,
					1,
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
					new CherryFoliagePlacer(ConstantInt.of(4), ConstantInt.of(0), ConstantInt.of(5), 0.25F, 0.5F, 0.1666F, 0.3333F),
					new TwoLayersFeatureSize(1, 0, 2)
				);
			}
			// A leaning trunk under a crown that trails: the hanging chances are
			// what read as a willow rather than as a bent oak.
			case WEEPING -> new TreeShape(
				new BendingTrunkPlacer(5, 2, 1, 4, UniformInt.of(1, 2)),
				new CherryFoliagePlacer(ConstantInt.of(3), ConstantInt.of(0), ConstantInt.of(4), 0.2F, 0.4F, 0.75F, 0.6F),
				new TwoLayersFeatureSize(1, 0, 2)
			);
			case UMBRELLA -> new TreeShape(
				new ForkingTrunkPlacer(5, 2, 2),
				new AcaciaFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0)),
				new TwoLayersFeatureSize(1, 0, 2)
			);
			case SHRUB -> new TreeShape(
				new StraightTrunkPlacer(1, 0, 0),
				new BushFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 2),
				new TwoLayersFeatureSize(1, 0, 1)
			);
			case BROADLEAF -> new TreeShape(
				new StraightTrunkPlacer(TREE_BASE_HEIGHT, TREE_HEIGHT_VARIATION, 0),
				new BlobFoliagePlacer(ConstantInt.of(TREE_FOLIAGE_RADIUS), ConstantInt.of(0), 3),
				new TwoLayersFeatureSize(1, 0, 1)
			);
		};

		return new ConfiguredFeature<>(
			Feature.TREE,
			new TreeConfiguration.TreeConfigurationBuilder(
				trunk,
				shape.trunk(),
				foliage,
				shape.foliage(),
				shape.size(),
				BlockStateProvider.simple(Blocks.DIRT)
			).ignoreVines().build()
		);
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
			// A tree also has to check that its sapling would survive where it
			// lands, or it grows out of stone and gravel.
			case TREE -> List.of(
				RarityFilter.onAverageOnceEvery(VegetationBudget.rarity(density)),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP_WORLD_SURFACE,
				BiomeFilter.biome(),
				BlockPredicateFilter.forPredicate(BlockPredicate.wouldSurvive(Blocks.OAK_SAPLING.defaultBlockState(), BlockPos.ZERO))
			);
			// FallenTreeFeature already searches for a sturdy floor and tolerates
			// short gaps along the log. Applying a living oak sapling's soil rule
			// here would erase fallen wood from stone, sand and wintry ground.
			case FALLEN_LOG -> List.of(
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
