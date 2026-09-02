package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.WeightedMaterial;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The half of the material system core cannot check.
 *
 * <p>Core knows a recipe asked for a trunk and a foliage; only here is it
 * knowable whether those ids resolve to real blocks, and only here does a
 * weighted selector actually become a provider that returns different blocks on
 * different draws. Getting that wrong looks like a world of one plant, which is
 * indistinguishable from a world that was never given a mix.
 */
class WorldsmithTreeCompilerTest {
	/** Neither provider under test reads the level, so there is nothing to stand up. */
	private static final WorldGenLevel NO_LEVEL = null;

	@BeforeAll
	static void bootstrap() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void aPlainSelectorStaysOneBlock() {
		MaterialResolver resolver = new MaterialResolver();

		BlockStateProvider provider = resolver.resolveProvider(
			new MaterialSelector("wood", List.of("minecraft:cherry_log"), List.of(), List.of()),
			Blocks.OAK_LOG
		);

		assertInstanceOf(SimpleStateProvider.class, provider);
		assertEquals(
			Blocks.CHERRY_LOG,
			provider.getState(NO_LEVEL, RandomSource.create(1L), BlockPos.ZERO).getBlock()
		);
	}

	@Test
	void aWeightedSelectorReturnsMoreThanOneBlock() {
		MaterialResolver resolver = new MaterialResolver();

		BlockStateProvider provider = resolver.resolveProvider(
			new MaterialSelector(
				"meadow_flora",
				List.of(),
				List.of(),
				List.of(
					new WeightedMaterial(new MaterialSelector("grass", List.of("minecraft:short_grass"), List.of(), List.of()), 1),
					new WeightedMaterial(new MaterialSelector("fern", List.of("minecraft:fern"), List.of(), List.of()), 1)
				)
			),
			Blocks.DEAD_BUSH
		);

		assertInstanceOf(WeightedStateProvider.class, provider);

		// The point of the whole mechanism: the same provider has to be able to
		// answer differently, or the ground reads as one plant repeated.
		RandomSource random = RandomSource.create(42L);
		BlockState first = provider.getState(NO_LEVEL, random, BlockPos.ZERO);
		boolean sawSomethingElse = false;
		for (int i = 0; i < 200 && !sawSomethingElse; i++) {
			sawSomethingElse = !provider.getState(NO_LEVEL, random, BlockPos.ZERO).equals(first);
		}
		assertTrue(sawSomethingElse, "a weighted provider returned the same block 200 times");
		assertNotEquals(Blocks.DEAD_BUSH.defaultBlockState(), first, "neither entry should have fallen back");
	}

	@Test
	void anUnresolvableWeightedEntryFallsBackAndIsReported() {
		MaterialResolver resolver = new MaterialResolver();

		resolver.resolveProvider(
			new MaterialSelector(
				"broken",
				List.of(),
				List.of(),
				List.of(new WeightedMaterial(new MaterialSelector("ghost", List.of("minecraft:sakura_log"), List.of(), List.of()), 1))
			),
			Blocks.DEAD_BUSH
		);

		// One invented id degrades one entry rather than the world, and says so.
		assertTrue(
			resolver.problems().stream().anyMatch(problem -> problem.contains("minecraft:sakura_log")),
			resolver.problems().toString()
		);
	}
}
