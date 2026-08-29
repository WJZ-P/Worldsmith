package com.wjz.worldsmith.datagen;

import com.wjz.worldsmith.worldgen.BiomeSkeleton;
import com.wjz.worldsmith.worldgen.BiomeSkeletons;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Adds every Worldsmith biome to the vanilla biome tags its archetype implies.
 *
 * <p>This is what keeps a fully custom biome set from silently disabling vanilla
 * content: structure placement intersects a structure's biome set with the
 * world's possible biomes, so a biome in no tags means no structures at all.
 */
public final class WorldsmithBiomeTagProvider extends FabricTagsProvider<Biome> {
	public WorldsmithBiomeTagProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, Registries.BIOME, registries);
	}

	@Override
	protected void addTags(HolderLookup.Provider registries) {
		for (BiomeSkeleton skeleton : BiomeSkeletons.all()) {
			for (TagKey<Biome> tag : skeleton.archetype().tags()) {
				tag(tag).add(skeleton.biome());
			}
		}
	}
}
