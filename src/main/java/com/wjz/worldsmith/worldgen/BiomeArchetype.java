package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.BiomeArchetypeRole;
import com.wjz.worldsmith.core.model.BiomeTagOverrides;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Coarse role of a biome, used to decide which vanilla biome tags it joins.
 *
 * <p>Tag membership is the only thing keeping vanilla content alive in a world
 * built entirely from custom biomes: {@code ChunkGeneratorStructureState} skips
 * any structure whose biome set does not intersect the world's possible biomes.
 */
public enum BiomeArchetype {
	DEEP_OCEAN(BiomeTags.IS_OCEAN, BiomeTags.IS_DEEP_OCEAN),
	OCEAN(BiomeTags.IS_OCEAN),
	BEACH(BiomeTags.IS_BEACH),
	MOUNTAIN(BiomeTags.IS_MOUNTAIN, BiomeTags.IS_HILL, BiomeTags.STRONGHOLD_BIASED_TO),
	HILL(BiomeTags.IS_HILL, BiomeTags.STRONGHOLD_BIASED_TO),
	LOWLAND(BiomeTags.STRONGHOLD_BIASED_TO);

	/**
	 * Tags every Worldsmith biome joins. {@link BiomeTags#HAS_STRONGHOLD} is not
	 * optional: without it no stronghold generates, and without a stronghold the
	 * world has no reachable end portal.
	 */
	private static final List<TagKey<Biome>> SHARED_TAGS = List.of(BiomeTags.IS_OVERWORLD, BiomeTags.HAS_STRONGHOLD);

	private final List<TagKey<Biome>> ownTags;

	@SafeVarargs
	BiomeArchetype(TagKey<Biome>... ownTags) {
		this.ownTags = List.of(ownTags);
	}

	public List<TagKey<Biome>> tags() {
		List<TagKey<Biome>> all = new ArrayList<>(SHARED_TAGS);
		all.addAll(this.ownTags);
		return List.copyOf(all);
	}

	public boolean isAquatic() {
		return this == DEEP_OCEAN || this == OCEAN;
	}

	public static BiomeArchetype from(BiomeArchetypeRole role) {
		return valueOf(role.name());
	}

	/**
	 * The tags a biome actually joins: its archetype defaults, then the pack's
	 * own adjustments.
	 *
	 * <p>Removal only subtracts from those defaults. A Worldsmith biome is never
	 * in a vanilla tag unless this compiler put it there, so there is nothing
	 * else for a pack to take it out of.
	 */
	public static List<TagKey<Biome>> tagsFor(CompiledBiome biome) {
		Set<TagKey<Biome>> tags = new LinkedHashSet<>(biome.archetype().tags());
		BiomeTagOverrides overrides = biome.definition().getTags();
		overrides.getRemove().forEach(id -> tags.remove(tagKey(id)));
		overrides.getAdd().forEach(id -> tags.add(tagKey(id)));
		return List.copyOf(tags);
	}

	private static TagKey<Biome> tagKey(String id) {
		return TagKey.create(Registries.BIOME, Identifier.parse(id));
	}
}
