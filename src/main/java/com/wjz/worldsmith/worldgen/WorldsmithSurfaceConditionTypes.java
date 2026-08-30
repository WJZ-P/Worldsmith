package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.MapCodec;
import com.wjz.worldsmith.Worldsmith;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.SurfaceRules;

/** Registers Worldsmith's serialized surface-condition vocabulary. */
final class WorldsmithSurfaceConditionTypes {
	private static boolean initialized;

	private WorldsmithSurfaceConditionTypes() {
	}

	static synchronized void initialize() {
		if (initialized) {
			return;
		}
		Registry.register(
			BuiltInRegistries.MATERIAL_CONDITION,
			Worldsmith.id("hydrology"),
			(MapCodec<? extends SurfaceRules.ConditionSource>)WorldsmithHydrologyConditionSource.CODEC
		);
		initialized = true;
	}
}
