package com.wjz.worldsmith.datagen;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.WorldPresetTags;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** Pack choices are supplied lazily by the library UI; keep legacy preset data out of the new-world menu. */
public final class WorldsmithWorldPresetTagProvider extends FabricTagsProvider<WorldPreset> {
	public WorldsmithWorldPresetTagProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, Registries.WORLD_PRESET, registries);
	}

	@Override
	protected void addTags(HolderLookup.Provider registries) {
		tag(WorldPresetTags.NORMAL);
	}
}
