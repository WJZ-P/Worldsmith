package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.SurfaceLayers;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Builds the single surface rule tree for a Worldsmith world.
 *
 * <p>Surface blocks are not a biome field in modern Minecraft. They live in one
 * dimension-wide condition tree that tests {@code isBiome(...)} from the outside,
 * which is exactly why a per-biome agent could never write this: it needs every
 * biome at once. The compiler owns it instead.
 */
public final class WorldsmithSurfaceRules {
	private WorldsmithSurfaceRules() {
	}

	public static SurfaceRules.RuleSource build(HolderGetter<Biome> biomes, MaterialResolver resolver) {
		List<SurfaceRules.RuleSource> perBiome = new ArrayList<>();

		for (CompiledBiome biome : CompiledBiomes.all()) {
			perBiome.add(SurfaceRules.ifTrue(
				SurfaceRules.isBiome(biomes, biome.key()),
				columnFor(biome.definition().getSurface(), resolver)
			));
		}

		// Anything the pack does not claim still needs a floor, so close with stone.
		perBiome.add(SurfaceRules.state(Blocks.STONE.defaultBlockState()));
		return SurfaceRules.sequence(perBiome.toArray(SurfaceRules.RuleSource[]::new));
	}

	private static SurfaceRules.RuleSource columnFor(SurfaceLayers surface, MaterialResolver resolver) {
		SurfaceRules.RuleSource top = SurfaceRules.state(resolver.resolve(surface.getTop(), Blocks.GRASS_BLOCK));
		SurfaceRules.RuleSource under = SurfaceRules.state(resolver.resolve(surface.getUnder(), Blocks.DIRT));
		SurfaceRules.RuleSource deep = SurfaceRules.state(resolver.resolve(surface.getDeep(), Blocks.STONE));

		List<SurfaceRules.RuleSource> column = new ArrayList<>();

		// A steep face shows rock rather than soil, the way vanilla exposes stone
		// on windswept slopes.
		if (surface.getSteepOverride() != null) {
			SurfaceRules.RuleSource steep = SurfaceRules.state(resolver.resolve(surface.getSteepOverride(), Blocks.STONE));
			column.add(SurfaceRules.ifTrue(SurfaceRules.steep(), steep));
		}

		// Only dress the column above the preliminary surface, otherwise the top
		// block also lines every cave ceiling underneath.
		column.add(SurfaceRules.ifTrue(
			SurfaceRules.abovePreliminarySurface(),
			SurfaceRules.sequence(
				SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, top),
				SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, under)
			)
		));
		column.add(SurfaceRules.ifTrue(SurfaceRules.DEEP_UNDER_FLOOR, deep));

		return SurfaceRules.sequence(column.toArray(SurfaceRules.RuleSource[]::new));
	}
}
