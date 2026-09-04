package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

/** Static-registry types used by data-pack-serializable feature placement. */
public final class WorldsmithPlacementModifierTypes {
	private static PlacementModifierType<WorldsmithHeightRangeFilter> heightRangeFilter;

	private WorldsmithPlacementModifierTypes() {
	}

	static synchronized void initialize() {
		if (heightRangeFilter != null) {
			return;
		}
		heightRangeFilter = Registry.register(
			BuiltInRegistries.PLACEMENT_MODIFIER_TYPE,
			Worldsmith.id("height_range_filter"),
			() -> WorldsmithHeightRangeFilter.CODEC
		);
	}

	static PlacementModifierType<WorldsmithHeightRangeFilter> heightRangeFilter() {
		initialize();
		return heightRangeFilter;
	}
}
