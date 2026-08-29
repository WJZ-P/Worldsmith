package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.BiomeSkeletonIds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Registry keys for the fixed stage-one biome skeletons.
 *
 * <p>The identifiers come from {@link BiomeSkeletonIds} so that the core module
 * and the Minecraft-facing compiler cannot drift apart.
 */
public final class WorldsmithBiomes {
	public static final ResourceKey<Biome> ABYSS = key(BiomeSkeletonIds.ABYSS);
	public static final ResourceKey<Biome> SHALLOWS = key(BiomeSkeletonIds.SHALLOWS);
	public static final ResourceKey<Biome> SHORE = key(BiomeSkeletonIds.SHORE);
	public static final ResourceKey<Biome> PEAKS = key(BiomeSkeletonIds.PEAKS);
	public static final ResourceKey<Biome> HIGHLAND = key(BiomeSkeletonIds.HIGHLAND);
	public static final ResourceKey<Biome> FLATS_COLD = key(BiomeSkeletonIds.FLATS_COLD);
	public static final ResourceKey<Biome> FLATS_TEMPERATE = key(BiomeSkeletonIds.FLATS_TEMPERATE);
	public static final ResourceKey<Biome> FLATS_HOT = key(BiomeSkeletonIds.FLATS_HOT);

	private WorldsmithBiomes() {
	}

	private static ResourceKey<Biome> key(String path) {
		return ResourceKey.create(Registries.BIOME, Worldsmith.id(path));
	}
}
