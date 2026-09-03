package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacerType;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType;

/** Static-registry types used by Worldsmith's data-pack-serializable tree geometry. */
public final class WorldsmithTreePlacerTypes {
	private static TrunkPlacerType<WorldsmithTrunkPlacer> trunk;
	private static FoliagePlacerType<WorldsmithFoliagePlacer> foliage;

	private WorldsmithTreePlacerTypes() {
	}

	static synchronized void initialize() {
		if (trunk != null) {
			return;
		}
		trunk = Registry.register(
			BuiltInRegistries.TRUNK_PLACER_TYPE,
			Worldsmith.id("shaped_trunk"),
			new TrunkPlacerType<>(WorldsmithTrunkPlacer.CODEC)
		);
		foliage = Registry.register(
			BuiltInRegistries.FOLIAGE_PLACER_TYPE,
			Worldsmith.id("shaped_foliage"),
			new FoliagePlacerType<>(WorldsmithFoliagePlacer.CODEC)
		);
	}

	static TrunkPlacerType<WorldsmithTrunkPlacer> trunk() {
		initialize();
		return trunk;
	}

	static FoliagePlacerType<WorldsmithFoliagePlacer> foliage() {
		initialize();
		return foliage;
	}
}
