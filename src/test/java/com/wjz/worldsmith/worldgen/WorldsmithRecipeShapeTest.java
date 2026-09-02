package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.FeatureRecipe;
import com.wjz.worldsmith.core.model.MaterialRole;
import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.TreeSilhouette;
import com.wjz.worldsmith.core.model.TreeSpec;
import java.util.LinkedHashMap;
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
		for (TreeSilhouette silhouette : TreeSilhouette.values()) {
			ConfiguredFeature<?, ?> configured = WorldsmithVegetation.configure(sampleTree(silhouette), new MaterialResolver());
			assertEquals(Feature.TREE, configured.feature(), silhouette + " did not build a tree");
		}
		assertEquals(6, TreeSilhouette.values().length);
	}

	/** A definition carrying exactly the roles the recipe declares it reads. */
	private static FeatureDefinition sample(FeatureRecipe recipe) {
		Map<MaterialRole, MaterialSelector> materials = new LinkedHashMap<>();
		for (MaterialRole role : recipe.getRoles()) {
			materials.put(role, selector(role));
		}
		TreeSpec tree = recipe.isTree() ? new TreeSpec(TreeSilhouette.BROADLEAF) : null;
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
			new TreeSpec(silhouette)
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
}
