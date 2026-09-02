package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.FeatureRecipe;
import com.wjz.worldsmith.core.model.MaterialRole;
import com.wjz.worldsmith.core.model.MaterialSelector;
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
		// The whole point of the family: six names that all reach Feature.TREE
		// and differ only in the placer pair the compiler chose.
		for (FeatureRecipe recipe : FeatureRecipe.values()) {
			if (!recipe.isTree()) {
				continue;
			}
			ConfiguredFeature<?, ?> configured = WorldsmithVegetation.configure(sample(recipe), new MaterialResolver());
			assertEquals(Feature.TREE, configured.feature(), recipe + " is named a tree but did not build one");
		}
		assertEquals(6, java.util.Arrays.stream(FeatureRecipe.values()).filter(FeatureRecipe::isTree).count());
	}

	/** A definition carrying exactly the roles the recipe declares it reads. */
	private static FeatureDefinition sample(FeatureRecipe recipe) {
		Map<MaterialRole, MaterialSelector> materials = new LinkedHashMap<>();
		for (MaterialRole role : recipe.getRoles()) {
			materials.put(role, selector(role));
		}
		return new FeatureDefinition("sample", recipe, null, materials, 0.3);
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
