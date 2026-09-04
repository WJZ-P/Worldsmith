package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.FeatureFluid;
import com.wjz.worldsmith.core.model.FeaturePlacementConditions;
import com.wjz.worldsmith.core.model.FeatureRecipe;
import com.wjz.worldsmith.core.model.FeatureSubstrate;
import com.wjz.worldsmith.core.model.MaterialRole;
import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.TreeSpec;
import com.wjz.worldsmith.core.model.TreeBranchSpec;
import com.wjz.worldsmith.core.model.TreeCrownShape;
import com.wjz.worldsmith.core.model.TreeCrownSpec;
import com.wjz.worldsmith.core.model.TreeDistribution;
import com.wjz.worldsmith.core.model.TreeSubstrate;
import com.wjz.worldsmith.core.model.TreeDecoration;
import com.wjz.worldsmith.core.model.TreeHeight;
import com.wjz.worldsmith.core.model.TreeTrunkShape;
import com.wjz.worldsmith.core.model.TreeTrunkSpec;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Builds every recipe once, because a placer only complains where it is used.
 *
 * <p>A trunk or foliage placer with impossible parameters throws where it is
 * constructed, and a configuration that does not round-trip through its codec
 * cannot be written into a pack. Neither shows up until a world is generated
 * otherwise, and by then the symptom is an empty biome rather than an error.
 */
class WorldsmithRecipeShapeTest {
	@BeforeAll
	static void bootstrap() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void everyRecipeBuildsAndEncodes() {
		var ops = VanillaRegistries.createLookup().createSerializationContext(JsonOps.INSTANCE);

		for (FeatureRecipe recipe : FeatureRecipe.values()) {
			MaterialResolver resolver = new MaterialResolver();
			ConfiguredFeature<?, ?> configured = WorldsmithVegetation.configure(sample(recipe), resolver);

			assertNotNull(configured, recipe + " compiled to nothing");
			assertTrue(
				ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, configured).isSuccess(),
				recipe + " built a configuration that cannot be written into a pack"
			);
			assertEquals(List.of(), resolver.problems(), recipe + " could not resolve its own sample materials");
		}
	}

	@Test
	void everyTrunkAndCrownCombinationIsActuallyATree() {
		var encodedShapes = new LinkedHashSet<String>();
		for (TreeTrunkShape trunk : TreeTrunkShape.values()) {
			for (TreeCrownShape crown : TreeCrownShape.values()) {
				ConfiguredFeature<?, ?> configured = WorldsmithVegetation.configure(sampleTree(trunk, crown), new MaterialResolver());
				assertEquals(Feature.TREE, configured.feature(), trunk + "/" + crown + " did not build a tree");
				encodedShapes.add(encoded(configured));
			}
		}
		int combinations = TreeTrunkShape.values().length * TreeCrownShape.values().length;
		assertEquals(combinations, encodedShapes.size(), "two authored shape combinations compiled identically");
	}

	@Test
	void treeGeometryAndDecorationsReachTheMinecraftConfiguration() {
		TreeSpec tree = new TreeSpec(
			new TreeTrunkSpec(
				TreeTrunkShape.BRANCHING,
				new TreeHeight(10, 14),
				2,
				0.0,
				new TreeBranchSpec(4, 5, 0.55, 0.7, 0.8, 0.25),
				0.0,
				1,
				1
			),
			new TreeCrownSpec(TreeCrownShape.CLUSTERED, 6, 7, 0.9, 0.45, 0.8),
			TreeDistribution.FOREST,
			TreeSubstrate.ANY_SOLID,
			List.of(TreeDecoration.VINES, TreeDecoration.LEAF_LITTER)
		);
		FeatureDefinition feature = new FeatureDefinition(
			"custom_tree",
			FeatureRecipe.TREE,
			null,
			Map.of(
				MaterialRole.TRUNK, selector(MaterialRole.TRUNK),
				MaterialRole.FOLIAGE, selector(MaterialRole.FOLIAGE)
			),
			0.7,
			tree,
			null, null, null, null, null,
			defaultPlacement()
		);

		String json = encoded(WorldsmithVegetation.configure(feature, new MaterialResolver()));

		assertTrue(json.contains("\"base_height\":10"), json);
		assertTrue(json.contains("\"height_rand_a\":4"), json);
		assertTrue(json.contains("\"radius\":6"), json);
		assertTrue(json.contains("\"branch_count\":4"), json);
		assertTrue(json.contains("\"branch_spread\":0.8"), json);
		assertTrue(json.contains("\"branch_length_variation\":0.25"), json);
		assertTrue(json.contains("\"clearance_padding\":8"), json);
		assertTrue(json.contains("\"upper_size\":15"), json);
		assertTrue(json.contains("\"hanging_leaves\":0.8"), json);
		assertTrue(json.contains("worldsmith:shaped_trunk"), json);
		assertTrue(json.contains("worldsmith:shaped_foliage"), json);
		assertTrue(json.contains("trunk_vine"), json);
		assertTrue(json.contains("place_on_ground"), json);
	}

	/** A definition carrying exactly the roles the recipe declares it reads. */
	private static FeatureDefinition sample(FeatureRecipe recipe) {
		Map<MaterialRole, MaterialSelector> materials = new LinkedHashMap<>();
		for (MaterialRole role : recipe.getRoles()) {
			materials.put(role, selector(role));
		}
		TreeSpec tree = recipe.isTree() ? treeSpec(TreeTrunkShape.STRAIGHT, TreeCrownShape.ROUND) : null;
		return new FeatureDefinition(
			"sample", recipe, null, materials, 0.3, tree,
			null, null, null, null, null,
			defaultPlacement()
		);
	}

	private static FeatureDefinition sampleTree(TreeTrunkShape trunk, TreeCrownShape crown) {
		return new FeatureDefinition(
			"sample_" + trunk.name().toLowerCase(java.util.Locale.ROOT) + "_" + crown.name().toLowerCase(java.util.Locale.ROOT),
			FeatureRecipe.TREE,
			null,
			Map.of(
				MaterialRole.TRUNK, selector(MaterialRole.TRUNK),
				MaterialRole.FOLIAGE, selector(MaterialRole.FOLIAGE)
			),
			0.3,
			treeSpec(trunk, crown),
			null, null, null, null, null,
			defaultPlacement()
		);
	}

	private static FeaturePlacementConditions defaultPlacement() {
		return new FeaturePlacementConditions(null, null, FeatureSubstrate.RECIPE_DEFAULT, FeatureFluid.RECIPE_DEFAULT);
	}

	private static TreeSpec treeSpec(TreeTrunkShape trunk, TreeCrownShape crown) {
		TreeBranchSpec branches = switch (trunk) {
			case FORKED -> new TreeBranchSpec(2, 4, 0.7, 0.6, 1.0, 0.2);
			case BRANCHING -> new TreeBranchSpec(4, 4, 0.6, 0.5, 0.75, 0.3);
			default -> null;
		};
		double bend = trunk == TreeTrunkShape.BENT || trunk == TreeTrunkShape.TWISTED ||
			trunk == TreeTrunkShape.CROOKED ? 0.35 : 0.0;
		return new TreeSpec(
			new TreeTrunkSpec(
				trunk,
				new TreeHeight(10, 12),
				trunk == TreeTrunkShape.TAPERED ? 2 : 1,
				bend,
				branches,
				trunk == TreeTrunkShape.TAPERED ? 0.55 : 0.0,
				1,
				trunk == TreeTrunkShape.MULTI_STEM ? 3 : 1
			),
			new TreeCrownSpec(crown, 3, 4, 0.85, 0.25, 0.15),
			TreeDistribution.GROVE,
			TreeSubstrate.NATURAL_SOIL,
			List.of()
		);
	}

	private static MaterialSelector selector(MaterialRole role) {
		String id = switch (role) {
			case TRUNK -> "minecraft:oak_log";
			case FOLIAGE -> "minecraft:oak_leaves";
			case BLOCK -> "minecraft:short_grass";
		};
		return new MaterialSelector(role.name().toLowerCase(java.util.Locale.ROOT), List.of(id), List.of(), List.of());
	}

	private static String encoded(ConfiguredFeature<?, ?> feature) {
		var ops = VanillaRegistries.createLookup().createSerializationContext(JsonOps.INSTANCE);
		JsonElement json = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, feature)
			.getOrThrow(message -> new IllegalStateException("Could not encode feature: " + message));
		return json.toString();
	}
}
