package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.MapCodec;
import com.wjz.worldsmith.Worldsmith;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Registers the density-function types that know where they are. */
final class WorldsmithDensityFunctionTypes {
	private static boolean initialized;

	private WorldsmithDensityFunctionTypes() {
	}

	@SuppressWarnings("unchecked")
	static synchronized void initialize() {
		if (initialized) {
			return;
		}
		Registry.register(
			BuiltInRegistries.DENSITY_FUNCTION_TYPE,
			Worldsmith.id("anchor_point"),
			(MapCodec<? extends DensityFunction>) WorldsmithAnchorFields.Point.CODEC.codec()
		);
		Registry.register(
			BuiltInRegistries.DENSITY_FUNCTION_TYPE,
			Worldsmith.id("anchor_grid"),
			(MapCodec<? extends DensityFunction>) WorldsmithAnchorFields.Grid.CODEC.codec()
		);
		initialized = true;
	}
}
