package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.feature.VegetationBudget;
import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomeFeatureRef;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.FeatureFluid;
import com.wjz.worldsmith.core.model.FeatureLibrary;
import com.wjz.worldsmith.core.model.FeaturePatchSpec;
import com.wjz.worldsmith.core.model.FeaturePlacementConditions;
import com.wjz.worldsmith.core.model.MaterialRole;
import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.FeatureRecipe;
import com.wjz.worldsmith.core.model.BoulderSpec;
import com.wjz.worldsmith.core.model.ColumnSpec;
import com.wjz.worldsmith.core.model.FallenLogSpec;
import com.wjz.worldsmith.core.model.FeatureSubstrate;
import com.wjz.worldsmith.core.model.OreVeinSpec;
import com.wjz.worldsmith.core.model.TreeSpec;
import com.wjz.worldsmith.core.model.TreeBranchSpec;
import com.wjz.worldsmith.core.model.TreeCrownShape;
import com.wjz.worldsmith.core.model.TreeCrownSpec;
import com.wjz.worldsmith.core.model.TreeDecoration;
import com.wjz.worldsmith.core.model.TreeDistribution;
import com.wjz.worldsmith.core.model.TreeHeight;
import com.wjz.worldsmith.core.model.TreeSubstrate;
import com.wjz.worldsmith.core.model.TreeTrunkShape;
import com.wjz.worldsmith.core.model.TreeTrunkSpec;
import java.util.Comparator;
import java.util.ArrayList;
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
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
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
	/** Ore and cave decoration stay below the ordinary surface. */
	private static final int ORE_VEIN_CEILING = 64;
	private static final int DEFAULT_SCAN_DEPTH = 12;

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
			FallenLogSpec log = fallenLog(feature);
			return new ConfiguredFeature<>(
				Feature.FALLEN_TREE,
				new FallenTreeConfiguration.FallenTreeConfigurationBuilder(
					resolver.resolveProvider(material(feature, MaterialRole.TRUNK), Blocks.OAK_LOG),
					// FallenTreeFeature reserves two configuration blocks for the
					// stump gap; expose the horizontal log itself to pack authors.
					UniformInt.of(log.getMinLength() + 2, log.getMaxLength() + 2)
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
				ColumnSpec column = column(feature, 2, 5);
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
					new BlockColumnConfiguration(
						List.of(BlockColumnConfiguration.layer(UniformInt.of(column.getMinLength(), column.getMaxLength()), provider)),
						Direction.UP,
						columnMedium(feature),
						false
					)
				);
			}
			case BOULDER -> new ConfiguredFeature<>(
				Feature.BLOCK_BLOB,
				new BlockBlobConfiguration(state, boulderGround(feature.getPlacement().getSubstrate()))
			);
			case ORE_VEIN -> {
				OreVeinSpec vein = oreVein(feature);
				yield new ConfiguredFeature<>(
					Feature.ORE,
					new OreConfiguration(
						new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES),
						state,
						vein.getSize(),
						(float)vein.getDiscardChanceOnAirExposure()
					)
				);
			}
			case CAVE_PATCH, SURFACE_LAYER, AQUATIC_PATCH -> new ConfiguredFeature<>(
				Feature.SIMPLE_BLOCK,
				new SimpleBlockConfiguration(provider)
			);
			// Grown downward from whatever it is attached to, which is what makes
			// it hang rather than stand.
			case HANGING_PATCH -> new ConfiguredFeature<>(
				Feature.BLOCK_COLUMN,
				new BlockColumnConfiguration(
					List.of(BlockColumnConfiguration.layer(columnProvider(feature, 1, 6), provider)),
					Direction.DOWN,
					columnMedium(feature),
					true
				)
			);
		};
	}

	private static FeaturePatchSpec patch(FeatureDefinition feature) {
		return feature.getPatch() == null
			? new FeaturePatchSpec(1, 0, 0, DEFAULT_SCAN_DEPTH)
			: feature.getPatch();
	}

	private static BoulderSpec boulder(FeatureDefinition feature) {
		return feature.getBoulder() == null ? new BoulderSpec(1, 0) : feature.getBoulder();
	}

	private static OreVeinSpec oreVein(FeatureDefinition feature) {
		return feature.getOreVein() == null ? new OreVeinSpec(33, 0.0) : feature.getOreVein();
	}

	private static ColumnSpec column(FeatureDefinition feature, int defaultMin, int defaultMax) {
		return feature.getColumn() == null ? new ColumnSpec(defaultMin, defaultMax) : feature.getColumn();
	}

	private static IntProvider columnProvider(FeatureDefinition feature, int defaultMin, int defaultMax) {
		ColumnSpec column = column(feature, defaultMin, defaultMax);
		return UniformInt.of(column.getMinLength(), column.getMaxLength());
	}

	private static FallenLogSpec fallenLog(FeatureDefinition feature) {
		return feature.getFallenLog() == null ? new FallenLogSpec(3, 6) : feature.getFallenLog();
	}

	/** The predicate BlockBlobFeature evaluates while descending toward its support. */
	private static BlockPredicate boulderGround(FeatureSubstrate substrate) {
		return switch (substrate) {
			case RECIPE_DEFAULT -> BlockPredicate.matchesTag(BlockTags.FOREST_ROCK_CAN_PLACE_ON);
			case NATURAL_SOIL -> BlockPredicate.matchesTag(BlockTags.DIRT);
			case SAND -> BlockPredicate.matchesTag(BlockTags.SAND);
			case STONE -> BlockPredicate.matchesTag(BlockTags.BASE_STONE_OVERWORLD);
			case ANY_SOLID -> BlockPredicate.solid();
		};
	}

	private static BlockPredicate columnMedium(FeatureDefinition feature) {
		FeatureFluid fluid = resolvedFluid(feature, FeatureFluid.DRY);
		return fluid == FeatureFluid.DRY
			? BlockPredicate.ONLY_IN_AIR_PREDICATE
			: BlockPredicate.ONLY_IN_AIR_OR_WATER_PREDICATE;
	}

	/**
	 * A tree with leaves on it, which is the one shape a column cannot fake.
	 *
	 * <p>Two roles rather than one, because Minecraft builds a tree from a trunk
	 * provider and a foliage provider placed by two separate strategies. The
	 * two custom placer types consume the authored trunk skeleton and crown volume
	 * while vanilla TreeFeature still owns collision checks and leaf updates.
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
		TreeTrunkSpec trunkSpec = tree.getTrunk();
		TreeCrownSpec crownSpec = tree.getCrown();
		TreeHeight height = trunkSpec.getHeight();
		TreeBranchSpec branches = trunkSpec.getBranches();
		int clearancePadding = treeVerticalPadding(tree);
		WorldsmithTrunkPlacer trunkPlacer = new WorldsmithTrunkPlacer(
			height.getMin(),
			height.getMax() - height.getMin(),
			0,
			trunkSpec.getShape(),
			trunkSpec.getThickness(),
			(float)trunkSpec.getBend(),
			(float)trunkSpec.getTaper(),
			trunkSpec.getFlare(),
			trunkSpec.getStems(),
			clearancePadding,
			branches == null ? 0 : branches.getCount(),
			branches == null ? 1 : branches.getLength(),
			branches == null ? 0.6F : (float)branches.getStart(),
			branches == null ? 0.5F : (float)branches.getUpwardBias(),
			branches == null ? 1.0F : (float)branches.getSpread(),
			branches == null ? 0.0F : (float)branches.getLengthVariation()
		);
		WorldsmithFoliagePlacer foliagePlacer = new WorldsmithFoliagePlacer(
			ConstantInt.of(crownSpec.getRadius()),
			ConstantInt.of(0),
			crownSpec.getShape(),
			crownSpec.getHeight(),
			(float)crownSpec.getDensity(),
			(float)crownSpec.getIrregularity(),
			(float)crownSpec.getHangingLeaves()
		);
		TreeConfiguration.TreeConfigurationBuilder builder = new TreeConfiguration.TreeConfigurationBuilder(
			trunk,
			trunkPlacer,
			foliage,
			foliagePlacer,
			treeSize(tree),
			belowTrunkProvider(tree.getSubstrate())
		).ignoreVines();
		List<TreeDecorator> decorations = treeDecorators(tree);
		if (!decorations.isEmpty()) {
			builder.decorators(decorations);
		}
		return new ConfiguredFeature<>(Feature.TREE, builder.build());
	}

	/** Gives vanilla TreeFeature one conservative box covering every authored block. */
	private static TwoLayersFeatureSize treeSize(TreeSpec tree) {
		int reach = treeHorizontalReach(tree);
		if (reach > 16) {
			throw new IllegalArgumentException("Tree horizontal reach " + reach + " exceeds vanilla clearance limit 16");
		}
		// The root row only owns its footprint and flare, so a nearby one-block
		// slope must not reject an otherwise valid tree. From Y=1 upward the full
		// conservative reach applies to curved stems, branches and crowns.
		return new TwoLayersFeatureSize(1, treeBaseReach(tree), reach);
	}

	static int treeHorizontalReach(TreeSpec tree) {
		TreeTrunkSpec trunk = tree.getTrunk();
		TreeBranchSpec branches = trunk.getBranches();
		int drift = 0;
		if (trunk.getShape() == TreeTrunkShape.BENT || trunk.getShape() == TreeTrunkShape.TWISTED ||
			trunk.getShape() == TreeTrunkShape.CROOKED) {
			int interval = Math.max(2, Math.round(7.0F - (float)trunk.getBend() * 5.0F));
			drift = (trunk.getHeight().getMax() - 1) / interval;
		}
		int stemReach = trunk.getShape() == TreeTrunkShape.MULTI_STEM
			? Math.min(2, Math.max(1, trunk.getHeight().getMax() / 8))
			: 0;
		int branchReach = branches == null ? 0 : branches.getLength();
		int pathReach = Math.max(drift + branchReach, stemReach);
		int footprint = trunk.getThickness() - 1;
		return Math.max(treeBaseReach(tree), footprint + pathReach + crownHorizontalReach(tree.getCrown()));
	}

	private static int treeBaseReach(TreeSpec tree) {
		TreeTrunkSpec trunk = tree.getTrunk();
		int footprint = trunk.getThickness() - 1;
		int reach = footprint + trunk.getFlare();
		if (trunk.getHeight().getMin() <= 1 && trunk.getBranches() != null) {
			reach = Math.max(reach, footprint + trunk.getBranches().getLength());
		}
		return reach;
	}

	static int treeVerticalPadding(TreeSpec tree) {
		TreeTrunkSpec trunk = tree.getTrunk();
		TreeBranchSpec branches = trunk.getBranches();
		int nominalHeight = trunk.getHeight().getMax();
		int highestAttachment = nominalHeight;
		if (trunk.getShape() == TreeTrunkShape.FORKED && branches != null) {
			int split = Math.round((nominalHeight - 1) * (float)branches.getStart()) + 1;
			highestAttachment = Math.max(highestAttachment, split + branches.getLength());
		} else if (branches != null && branches.getUpwardBias() > 0.0) {
			highestAttachment += branches.getLength();
		}
		return Math.max(0, highestAttachment + crownVerticalRise(tree.getCrown()) - nominalHeight);
	}

	private static int crownHorizontalReach(TreeCrownSpec crown) {
		return switch (crown.getShape()) {
			case CLUSTERED -> Math.max(
				crown.getRadius(),
				Math.max(1, crown.getRadius() - 1) + Math.max(1, crown.getRadius() / 2 + 1)
			);
			case WINDSWEPT -> crown.getRadius() + Math.round((float)(0.9 * crown.getRadius()));
			default -> crown.getRadius();
		};
	}

	private static int crownVerticalRise(TreeCrownSpec crown) {
		return switch (crown.getShape()) {
			case ROUND, CLUSTERED -> crown.getHeight() / 2;
			case CONICAL, LAYERED -> 0;
			case UMBRELLA, PAGODA -> 1;
			case WEEPING, WINDSWEPT -> Math.max(1, crown.getHeight() / 3);
			case COLUMNAR -> Math.max(1, crown.getHeight() / 4);
		};
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
		List<PlacementModifier> result = switch (feature.getRecipe()) {
			case GROUND_PATCH, SURFACE_LAYER -> surfacePatchPlacement(feature, density, FeatureFluid.DRY);
			case AQUATIC_PATCH -> surfacePatchPlacement(feature, density, FeatureFluid.SUBMERGED);
			case DEAD_TREE, FALLEN_LOG -> singleSurfacePlacement(feature, density);
			case BOULDER -> boulderPlacement(feature, density);
			case TREE -> treePlacement(feature, density);
			case ORE_VEIN -> orePlacement(feature, density);
			case CAVE_PATCH -> cavePatchPlacement(feature, density);
			case HANGING_PATCH -> hangingPatchPlacement(feature, density);
		};
		List<PlacementModifier> guarded = new ArrayList<>(result);
		boolean isTree = feature.getTree() != null;
		int radius = isTree ? treeHorizontalReach(feature.getTree()) : 16;
		int height = isTree ? feature.getTree().getTrunk().getHeight().getMax() + treeVerticalPadding(feature.getTree()) : 16;
		guarded.add(Math.max(1, guarded.size() - 1), new WorldsmithStructureAvoidanceFilter(Math.min(16, radius), Math.min(64, height), isTree ? 0 : 16));
		return List.copyOf(guarded);
	}

	private static List<PlacementModifier> surfacePatchPlacement(
		FeatureDefinition feature,
		double density,
		FeatureFluid recipeFluid
	) {
		FeaturePatchSpec patch = patch(feature);
		List<PlacementModifier> modifiers = new ArrayList<>();
		modifiers.add(CountPlacement.of(VegetationBudget.patchCount(density)));
		modifiers.add(InSquarePlacement.spread());
		addPatchScatter(modifiers, patch, false);
		modifiers.add(surfaceHeightmap(feature, recipeFluid));
		finishPlacement(modifiers, feature, recipeFluid, false);
		return List.copyOf(modifiers);
	}

	private static List<PlacementModifier> singleSurfacePlacement(FeatureDefinition feature, double density) {
		List<PlacementModifier> modifiers = new ArrayList<>();
		modifiers.add(rareAttempt(density));
		modifiers.add(InSquarePlacement.spread());
		modifiers.add(surfaceHeightmap(feature, FeatureFluid.DRY));
		finishPlacement(modifiers, feature, FeatureFluid.DRY, false);
		return List.copyOf(modifiers);
	}

	private static List<PlacementModifier> boulderPlacement(FeatureDefinition feature, double density) {
		BoulderSpec shape = boulder(feature);
		List<PlacementModifier> modifiers = new ArrayList<>();
		modifiers.add(rareAttempt(density));
		modifiers.add(InSquarePlacement.spread());
		if (shape.getBlobs() > 1) {
			modifiers.add(CountPlacement.of(shape.getBlobs()));
		}
		if (shape.getSpread() > 0) {
			modifiers.add(RandomOffsetPlacement.horizontal(UniformInt.of(-shape.getSpread(), shape.getSpread())));
		}
		modifiers.add(surfaceHeightmap(feature, FeatureFluid.DRY));
		finishPlacement(modifiers, feature, FeatureFluid.DRY, false);
		return List.copyOf(modifiers);
	}

	private static List<PlacementModifier> orePlacement(FeatureDefinition feature, double density) {
		List<PlacementModifier> modifiers = new ArrayList<>();
		modifiers.add(CountPlacement.of(VegetationBudget.veinCount(density)));
		modifiers.add(InSquarePlacement.spread());
		modifiers.add(undergroundHeight(feature));
		modifiers.add(BiomeFilter.biome());
		return List.copyOf(modifiers);
	}

	private static List<PlacementModifier> cavePatchPlacement(FeatureDefinition feature, double density) {
		FeaturePatchSpec patch = patch(feature);
		FeatureFluid fluid = resolvedFluid(feature, FeatureFluid.DRY);
		List<PlacementModifier> modifiers = new ArrayList<>();
		modifiers.add(CountPlacement.of(VegetationBudget.veinCount(density)));
		modifiers.add(InSquarePlacement.spread());
		modifiers.add(undergroundHeight(feature));
		addPatchScatter(modifiers, patch, true);
		modifiers.add(EnvironmentScanPlacement.scanningFor(
			Direction.DOWN,
			BlockPredicate.solid(),
			scanMedium(fluid),
			patch.getScanDepth()
		));
		modifiers.add(RandomOffsetPlacement.vertical(ConstantInt.of(1)));
		finishPlacement(modifiers, feature, FeatureFluid.DRY, false);
		return List.copyOf(modifiers);
	}

	private static List<PlacementModifier> hangingPatchPlacement(FeatureDefinition feature, double density) {
		FeaturePatchSpec patch = patch(feature);
		FeatureFluid fluid = resolvedFluid(feature, FeatureFluid.DRY);
		List<PlacementModifier> modifiers = new ArrayList<>();
		modifiers.add(CountPlacement.of(VegetationBudget.veinCount(density)));
		modifiers.add(InSquarePlacement.spread());
		FeaturePlacementConditions placement = feature.getPlacement();
		if (placement.getMinY() == null && placement.getMaxY() == null) {
			modifiers.add(PlacementUtils.FULL_RANGE);
		} else {
			modifiers.add(authoredHeight(placement, -64, 319));
		}
		addPatchScatter(modifiers, patch, true);
		modifiers.add(EnvironmentScanPlacement.scanningFor(
			Direction.UP,
			BlockPredicate.solid(),
			scanMedium(fluid),
			patch.getScanDepth()
		));
		modifiers.add(RandomOffsetPlacement.vertical(ConstantInt.of(-1)));
		finishPlacement(modifiers, feature, FeatureFluid.DRY, true);
		return List.copyOf(modifiers);
	}

	private static void addPatchScatter(List<PlacementModifier> modifiers, FeaturePatchSpec patch, boolean vertical) {
		if (patch.getAttempts() > 1) {
			modifiers.add(CountPlacement.of(patch.getAttempts()));
		}
		int horizontal = patch.getHorizontalSpread();
		int verticalSpread = vertical ? patch.getVerticalSpread() : 0;
		if (horizontal > 0 || verticalSpread > 0) {
			modifiers.add(RandomOffsetPlacement.of(
				horizontal == 0 ? ConstantInt.of(0) : UniformInt.of(-horizontal, horizontal),
				verticalSpread == 0 ? ConstantInt.of(0) : UniformInt.of(-verticalSpread, verticalSpread)
			));
		}
	}

	private static PlacementModifier undergroundHeight(FeatureDefinition feature) {
		FeaturePlacementConditions placement = feature.getPlacement();
		return placement.getMinY() == null && placement.getMaxY() == null
			? HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(8), VerticalAnchor.absolute(ORE_VEIN_CEILING))
			: authoredHeight(placement, -64, 319);
	}

	private static PlacementModifier authoredHeight(FeaturePlacementConditions placement, int defaultMin, int defaultMax) {
		int minY = placement.getMinY() == null ? defaultMin : placement.getMinY();
		int maxY = placement.getMaxY() == null ? defaultMax : placement.getMaxY();
		return HeightRangePlacement.uniform(VerticalAnchor.absolute(minY), VerticalAnchor.absolute(maxY));
	}

	private static PlacementModifier surfaceHeightmap(FeatureDefinition feature, FeatureFluid recipeFluid) {
		FeatureFluid fluid = resolvedFluid(feature, recipeFluid);
		return fluid == FeatureFluid.SUBMERGED || fluid == FeatureFluid.SHALLOW_WATER || fluid == FeatureFluid.ANY
			? PlacementUtils.HEIGHTMAP_OCEAN_FLOOR
			: PlacementUtils.HEIGHTMAP_WORLD_SURFACE;
	}

	private static void finishPlacement(
		List<PlacementModifier> modifiers,
		FeatureDefinition feature,
		FeatureFluid recipeFluid,
		boolean hanging
	) {
		modifiers.add(BiomeFilter.biome());
		FeaturePlacementConditions placement = feature.getPlacement();
		if (placement.getMinY() != null || placement.getMaxY() != null) {
			modifiers.add(WorldsmithHeightRangeFilter.of(
				placement.getMinY() == null ? -64 : placement.getMinY(),
				placement.getMaxY() == null ? 319 : placement.getMaxY()
			));
		}
		if (placement.getSubstrate() != FeatureSubstrate.RECIPE_DEFAULT) {
			modifiers.add(BlockPredicateFilter.forPredicate(substratePredicate(placement.getSubstrate(), hanging)));
		}
		BlockPredicate fluid = fluidPredicate(resolvedFluid(feature, recipeFluid));
		if (fluid != null) {
			modifiers.add(BlockPredicateFilter.forPredicate(fluid));
		}
	}

	private static FeatureFluid resolvedFluid(FeatureDefinition feature, FeatureFluid recipeFluid) {
		FeatureFluid authored = feature.getPlacement().getFluid();
		return authored == FeatureFluid.RECIPE_DEFAULT ? recipeFluid : authored;
	}

	private static BlockPredicate scanMedium(FeatureFluid fluid) {
		return fluid == FeatureFluid.DRY
			? BlockPredicate.ONLY_IN_AIR_PREDICATE
			: BlockPredicate.ONLY_IN_AIR_OR_WATER_PREDICATE;
	}

	private static BlockPredicate fluidPredicate(FeatureFluid fluid) {
		return switch (fluid) {
			case RECIPE_DEFAULT -> throw new IllegalStateException("recipe fluid was not resolved");
			// WORLD_SURFACE_WG reports the air block above a liquid surface too.
			// Checking only the origin would therefore let a nominally dry patch,
			// log or boulder float on water. Reject every fluid directly below the
			// origin while preserving snow layers and other non-fluid decoration.
			case DRY -> BlockPredicate.allOf(
				BlockPredicate.ONLY_IN_AIR_PREDICATE,
				BlockPredicate.noFluid(new BlockPos(0, -1, 0))
			);
			case SUBMERGED -> BlockPredicate.matchesFluids(Fluids.WATER);
			case SHALLOW_WATER -> BlockPredicate.allOf(
				BlockPredicate.matchesFluids(Fluids.WATER),
				BlockPredicate.matchesTag(new BlockPos(0, 4, 0), BlockTags.AIR)
			);
			// For scatter recipes ANY means either dry air or water, not lava or
			// every modded fluid. Ores bypass this origin filter and retain their
			// solid replacement target as documented by FeatureFluid.
			case ANY -> BlockPredicate.ONLY_IN_AIR_OR_WATER_PREDICATE;
		};
	}

	private static BlockPredicate substratePredicate(FeatureSubstrate substrate, boolean hanging) {
		BlockPos support = hanging ? new BlockPos(0, 1, 0) : new BlockPos(0, -1, 0);
		return switch (substrate) {
			case RECIPE_DEFAULT -> throw new IllegalStateException("default substrate needs no predicate");
			case NATURAL_SOIL -> BlockPredicate.matchesTag(support, BlockTags.DIRT);
			case SAND -> BlockPredicate.matchesTag(support, BlockTags.SAND);
			case STONE -> BlockPredicate.matchesTag(support, BlockTags.BASE_STONE_OVERWORLD);
			case ANY_SOLID -> BlockPredicate.hasSturdyFace(support, hanging ? Direction.DOWN : Direction.UP);
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
