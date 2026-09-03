package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.FeatureRecipe;
import com.wjz.worldsmith.core.model.MaterialRole;
import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.TreeDistribution;
import com.wjz.worldsmith.core.model.TreeCrownShape;
import com.wjz.worldsmith.core.model.TreeCrownSpec;
import com.wjz.worldsmith.core.model.TreeHeight;
import com.wjz.worldsmith.core.model.TreeSpec;
import com.wjz.worldsmith.core.model.TreeSubstrate;
import com.wjz.worldsmith.core.model.TreeTrunkShape;
import com.wjz.worldsmith.core.model.TreeTrunkSpec;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verifies the placement grammar that turns isolated trees into actual woods. */
final class WorldsmithTreePlacementTest {
	@BeforeAll
	static void bootstrap() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void scatteredTreesUseFractionalCountsIncludingExactZero() {
		PlacementModifier absent = WorldsmithVegetation.place(tree(TreeDistribution.SCATTERED, TreeSubstrate.NATURAL_SOIL), 0.0).getFirst();
		PlacementModifier occasional = WorldsmithVegetation.place(tree(TreeDistribution.SCATTERED, TreeSubstrate.NATURAL_SOIL), 0.35).getFirst();

		assertInstanceOf(CountPlacement.class, absent);
		assertInstanceOf(CountPlacement.class, occasional);
		assertTrue(encoded(absent).contains("\"count\":0"), encoded(absent));
		assertTrue(encoded(occasional).contains("weighted_list"), encoded(occasional));
		assertTrue(
			encoded(WorldsmithVegetation.place(tree(TreeDistribution.SCATTERED, TreeSubstrate.NATURAL_SOIL), 0.0001).getFirst())
				.contains("weighted_list")
		);
		assertTrue(
			encoded(WorldsmithVegetation.place(tree(TreeDistribution.SCATTERED, TreeSubstrate.NATURAL_SOIL), 0.9999).getFirst())
				.contains("weighted_list")
		);
	}

	@Test
	void groveAndForestModesUseMinecraftsBroadNoiseField() {
		for (TreeDistribution distribution : List.of(
			TreeDistribution.GROVE,
			TreeDistribution.FOREST,
			TreeDistribution.DENSE_FOREST
		)) {
			PlacementModifier count = WorldsmithVegetation.place(tree(distribution, TreeSubstrate.NATURAL_SOIL), 0.7).getFirst();
			assertInstanceOf(NoiseThresholdCountPlacement.class, count, distribution.name());
			assertTrue(encoded(count).contains("noise_threshold_count"), encoded(count));
		}
	}

	@Test
	void densityAndForestModeChangeTheCountsMinecraftActuallyEmits() {
		PlacementModifier scattered = WorldsmithVegetation.place(
			tree(TreeDistribution.SCATTERED, TreeSubstrate.NATURAL_SOIL),
			0.35
		).getFirst();
		RandomSource random = RandomSource.create(0x54524545L);
		long scatteredAttempts = 0;
		for (int sample = 0; sample < 10_000; sample++) {
			scatteredAttempts += scattered.getPositions(null, random, new BlockPos(sample * 16, 0, 0)).count();
		}
		assertTrue(scatteredAttempts > 3_200 && scatteredAttempts < 3_800, "35% scattered density emitted " + scatteredAttempts);

		assertNoiseCounts(TreeDistribution.GROVE, 0, 4);
		assertNoiseCounts(TreeDistribution.FOREST, 3, 7);
		assertNoiseCounts(TreeDistribution.DENSE_FOREST, 6, 11);
	}

	@Test
	void substrateChoicesCompileToDifferentPredicates() {
		String soil = encoded(WorldsmithVegetation.place(tree(TreeDistribution.GROVE, TreeSubstrate.NATURAL_SOIL), 0.5).getLast());
		String sand = encoded(WorldsmithVegetation.place(tree(TreeDistribution.GROVE, TreeSubstrate.SAND), 0.5).getLast());
		List<PlacementModifier> waterPlacement = WorldsmithVegetation.place(
			tree(TreeDistribution.GROVE, TreeSubstrate.SHALLOW_WATER),
			0.5
		);
		String water = encoded(waterPlacement.getLast());
		String solid = encoded(WorldsmithVegetation.place(tree(TreeDistribution.GROVE, TreeSubstrate.ANY_SOLID), 0.5).getLast());

		assertNotEquals(soil, sand);
		assertNotEquals(sand, solid);
		assertNotEquals(water, solid);
		assertTrue(soil.contains("would_survive"), soil);
		assertTrue(sand.contains("minecraft:sand"), sand);
		assertTrue(water.contains("minecraft:water"), water);
		assertTrue(
			encoded(waterPlacement.get(2)).toLowerCase(java.util.Locale.ROOT).contains("ocean_floor"),
			encoded(waterPlacement.get(2))
		);
		assertTrue(solid.contains("has_sturdy_face"), solid);
	}

	private static FeatureDefinition tree(TreeDistribution distribution, TreeSubstrate substrate) {
		return new FeatureDefinition(
			"tree",
			FeatureRecipe.TREE,
			null,
			Map.of(
				MaterialRole.TRUNK, new MaterialSelector("wood", List.of("minecraft:oak_log"), List.of(), List.of()),
				MaterialRole.FOLIAGE, new MaterialSelector("leaves", List.of("minecraft:oak_leaves"), List.of(), List.of())
			),
			0.5,
			new TreeSpec(
				new TreeTrunkSpec(TreeTrunkShape.STRAIGHT, new TreeHeight(7, 9), 1, 0.0, null),
				new TreeCrownSpec(TreeCrownShape.ROUND, 3, 4, 0.85, 0.25, 0.0),
				distribution,
				substrate,
				List.of()
			)
		);
	}

	private static void assertNoiseCounts(TreeDistribution distribution, int expectedBelow, int expectedAbove) {
		PlacementModifier modifier = WorldsmithVegetation.place(
			tree(distribution, TreeSubstrate.NATURAL_SOIL),
			0.7
		).getFirst();
		var counts = new java.util.LinkedHashSet<Long>();
		for (int z = -8_000; z <= 8_000; z += 256) {
			for (int x = -8_000; x <= 8_000; x += 256) {
				counts.add(modifier.getPositions(null, RandomSource.create(1L), new BlockPos(x, 0, z)).count());
			}
		}
		assertTrue(counts.contains((long)expectedBelow), distribution + " never emitted its clearing count: " + counts);
		assertTrue(counts.contains((long)expectedAbove), distribution + " never emitted its wooded count: " + counts);
	}

	private static String encoded(PlacementModifier modifier) {
		var ops = RegistryOps.create(
			JsonOps.INSTANCE,
			RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
		);
		JsonElement json = PlacementModifier.CODEC.encodeStart(ops, modifier)
			.getOrThrow(message -> new IllegalStateException("Could not encode placement: " + message));
		return json.toString();
	}
}
