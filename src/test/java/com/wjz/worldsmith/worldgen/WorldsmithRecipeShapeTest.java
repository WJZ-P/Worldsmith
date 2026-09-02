package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.FeatureRecipe;
import com.wjz.worldsmith.core.model.MaterialRole;
import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.TreeSilhouette;
import com.wjz.worldsmith.core.model.TreeSpec;
import com.wjz.worldsmith.core.model.TreeDistribution;
import com.wjz.worldsmith.core.model.TreeSubstrate;
import com.wjz.worldsmith.core.model.TreeDecoration;
import com.wjz.worldsmith.core.model.TreeHeight;
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
	void everySilhouetteIsActuallyATree() {
		var encodedShapes = new LinkedHashSet<String>();
		for (TreeSilhouette silhouette : TreeSilhouette.values()) {
			ConfiguredFeature<?, ?> configured = WorldsmithVegetation.configure(sampleTree(silhouette), new MaterialResolver());
			assertEquals(Feature.TREE, configured.feature(), silhouette + " did not build a tree");
			encodedShapes.add(encoded(configured));
		}
		assertEquals(6, TreeSilhouette.values().length);
		assertEquals(6, encodedShapes.size(), "two silhouette names compiled to the same tree configuration");
	}

	@Test
	void treeGeometryAndDecorationsReachTheMinecraftConfiguration() {
		TreeSpec tree = new TreeSpec(
			TreeSilhouette.BLOSSOM,
			TreeDistribution.FOREST,
			TreeSubstrate.ANY_SOLID,
			new TreeHeight(10, 14),
			6,
			0.8,
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
			tree
		);

		String json = encoded(WorldsmithVegetation.configure(feature, new MaterialResolver()));

		assertTrue(json.contains("\"base_height\":10"), json);
		assertTrue(json.contains("\"height_rand_a\":4"), json);
		assertTrue(json.contains("\"radius\":6"), json);
		assertTrue(json.contains("\"hanging_leaves_chance\":0.8"), json);
		assertTrue(json.contains("trunk_vine"), json);
		assertTrue(json.contains("place_on_ground"), json);
	}

	/** A definition carrying exactly the roles the recipe declares it reads. */
	private static FeatureDefinition sample(FeatureRecipe recipe) {
		Map<MaterialRole, MaterialSelector> materials = new LinkedHashMap<>();
		for (MaterialRole role : recipe.getRoles()) {
			materials.put(role, selector(role));
		}
		TreeSpec tree = recipe.isTree() ? treeSpec(TreeSilhouette.BROADLEAF) : null;
		return new FeatureDefinition("sample", recipe, null, materials, 0.3, tree);
	}

	private static FeatureDefinition sampleTree(TreeSilhouette silhouette) {
		return new FeatureDefinition(
			"sample_" + silhouette.name().toLowerCase(java.util.Locale.ROOT),
			FeatureRecipe.TREE,
			null,
			Map.of(
				MaterialRole.TRUNK, selector(MaterialRole.TRUNK),
				MaterialRole.FOLIAGE, selector(MaterialRole.FOLIAGE)
			),
			0.3,
			treeSpec(silhouette)
		);
	}

	private static TreeSpec treeSpec(TreeSilhouette silhouette) {
		return new TreeSpec(
			silhouette,
			TreeDistribution.GROVE,
			TreeSubstrate.NATURAL_SOIL,
			null,
			null,
			null,
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
