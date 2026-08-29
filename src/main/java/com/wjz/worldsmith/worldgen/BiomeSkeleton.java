package com.wjz.worldsmith.worldgen;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * One fixed slot in the stage-one climate grid.
 *
 * <p>A skeleton owns everything the language model is not allowed to decide:
 * where the biome generates, which tags it joins, and how it behaves for
 * precipitation. Stage one ships eight of these and never generates more.
 *
 * @param id             identifier shared with the core module
 * @param biome          registry key of the compiled biome
 * @param archetype      role used to derive biome tag membership
 * @param climate        climate parameter box handed to the biome source
 * @param temperature    drives snow, ice and fire behaviour, not the grass tint
 * @param downfall       drives rain behaviour, not the grass tint
 * @param hasPrecipitation whether weather falls here at all
 */
public record BiomeSkeleton(
	String id,
	ResourceKey<Biome> biome,
	BiomeArchetype archetype,
	Climate.ParameterPoint climate,
	float temperature,
	float downfall,
	boolean hasPrecipitation
) {
}
