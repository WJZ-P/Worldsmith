package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.BiomeDefinition;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * One biome from a Worldsmith pack, resolved against Minecraft's types.
 *
 * <p>The pack declares slots or raw boxes; by the time a definition reaches
 * here that choice is already resolved into parameter points, so nothing
 * downstream has to care which form the author used. There may be more than
 * one: a biome that claims two places that are not neighbours is one biome with
 * two boxes, not two biomes that have to be kept in step by hand.
 *
 * @param definition the pack entry this was compiled from
 * @param key        registry key of the generated biome
 * @param archetype  role used to derive biome tag membership
 * @param climates   parameter boxes handed to the biome source, at least one
 */
public record CompiledBiome(
	BiomeDefinition definition,
	ResourceKey<Biome> key,
	BiomeArchetype archetype,
	List<Climate.ParameterPoint> climates
) {
	public String id() {
		return this.definition.getId();
	}
}
