package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.BiomeDefinition;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * One biome from a Worldsmith pack, resolved against Minecraft's types.
 *
 * <p>The pack declares a climate slot or a raw box; by the time a definition
 * reaches here that choice is already collapsed into a single parameter point,
 * so nothing downstream has to care which form the author used.
 *
 * @param definition the pack entry this was compiled from
 * @param key        registry key of the generated biome
 * @param archetype  role used to derive biome tag membership
 * @param climate    parameter box handed to the biome source
 */
public record CompiledBiome(
	BiomeDefinition definition,
	ResourceKey<Biome> key,
	BiomeArchetype archetype,
	Climate.ParameterPoint climate
) {
	public String id() {
		return this.definition.getId();
	}
}
