package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Registry keys derived from the skeleton ids inside a Worldsmith pack.
 */
public final class WorldsmithBiomes {
	private WorldsmithBiomes() {
	}

	public static ResourceKey<Biome> key(String path) {
		return ResourceKey.create(Registries.BIOME, Worldsmith.id(path));
	}
}
