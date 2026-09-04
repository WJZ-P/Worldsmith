package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.BoulderSpec;
import com.wjz.worldsmith.core.model.ColumnSpec;
import com.wjz.worldsmith.core.model.FallenLogSpec;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.FeatureFluid;
import com.wjz.worldsmith.core.model.FeaturePatchSpec;
import com.wjz.worldsmith.core.model.FeaturePlacementConditions;
import com.wjz.worldsmith.core.model.FeatureRecipe;
import com.wjz.worldsmith.core.model.FeatureSubstrate;
import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.OreVeinSpec;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Proves every newly authored non-tree control reaches Minecraft's codecs. */
final class WorldsmithNonTreeFeatureTest {
	@BeforeAll
	static void bootstrap() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void oreColumnAndFallenLogGeometryReachConfiguredFeatures() {
		String ore = configured(feature(
			FeatureRecipe.ORE_VEIN, null, null, new OreVeinSpec(51, 0.65), null, null,
			new FeaturePlacementConditions(-40, 18, FeatureSubstrate.RECIPE_DEFAULT, FeatureFluid.ANY)
		));
		String bareColumn = configured(feature(
			FeatureRecipe.DEAD_TREE, null, null, null, new ColumnSpec(5, 11), null, defaults()
		));
		String hangingColumn = configured(feature(
			FeatureRecipe.HANGING_PATCH, new FeaturePatchSpec(3, 2, 1, 19), null, null,
			new ColumnSpec(4, 13), null, defaults()
		));
		String fallen = configured(feature(
			FeatureRecipe.FALLEN_LOG, null, null, null, null, new FallenLogSpec(8, 14), defaults()
		));

		assertTrue(ore.contains("\"size\":51"), ore);
		assertTrue(ore.contains("\"discard_chance_on_air_exposure\":0.65"), ore);
		assertTrue(bareColumn.contains("\"min_inclusive\":5"), bareColumn);
		assertTrue(bareColumn.contains("\"max_inclusive\":11"), bareColumn);
		assertTrue(hangingColumn.contains("\"min_inclusive\":4"), hangingColumn);
		assertTrue(hangingColumn.contains("\"max_inclusive\":13"), hangingColumn);
		assertTrue(fallen.contains("\"min_inclusive\":10"), fallen);
		assertTrue(fallen.contains("\"max_inclusive\":16"), fallen);
	}

	@Test
	void boulderAndPatchShapeBecomeRealPlacementModifiers() {
		FeatureDefinition boulder = feature(
			FeatureRecipe.BOULDER, null, new BoulderSpec(5, 4), null, null, null, defaults()
		);
		FeatureDefinition patch = feature(
			FeatureRecipe.GROUND_PATCH, new FeaturePatchSpec(7, 3, 0, 12), null, null, null, null,
			new FeaturePlacementConditions(80, 150, FeatureSubstrate.SAND, FeatureFluid.SHALLOW_WATER)
		);

		String boulderPlacement = placement(boulder);
		String patchPlacement = placement(patch);

		assertTrue(boulderPlacement.contains("\"count\":5"), boulderPlacement);
		assertTrue(boulderPlacement.contains("random_offset"), boulderPlacement);
		assertTrue(boulderPlacement.contains("minecraft:all_of"), boulderPlacement);
		assertTrue(boulderPlacement.contains("minecraft:empty"), boulderPlacement);
		assertTrue(boulderPlacement.contains("\"offset\":[0,-1,0]"), boulderPlacement);
		assertTrue(patchPlacement.contains("\"count\":7"), patchPlacement);
		assertTrue(patchPlacement.contains("height_range_filter"), patchPlacement);
		assertTrue(patchPlacement.contains("\"min_y\":80"), patchPlacement);
		assertTrue(patchPlacement.contains("\"max_y\":150"), patchPlacement);
		assertTrue(patchPlacement.contains("minecraft:sand"), patchPlacement);
		assertTrue(patchPlacement.contains("minecraft:water"), patchPlacement);
	}

	@Test
	void anyFluidMeansAirOrWaterWhileDryRejectsLiquidSurfaces() {
		FeatureDefinition any = feature(
			FeatureRecipe.GROUND_PATCH, null, null, null, null, null,
			new FeaturePlacementConditions(null, null, FeatureSubstrate.RECIPE_DEFAULT, FeatureFluid.ANY)
		);

		String encoded = placement(any);

		assertTrue(encoded.contains("minecraft:any_of"), encoded);
		assertTrue(encoded.contains("minecraft:air"), encoded);
		assertTrue(encoded.contains("minecraft:water"), encoded);
	}

	@Test
	void caveClusterControlsScanAndSpatialSpread() {
		FeatureDefinition cave = feature(
			FeatureRecipe.CAVE_PATCH,
			new FeaturePatchSpec(6, 4, 3, 23),
			null, null, null, null,
			new FeaturePlacementConditions(-20, 50, FeatureSubstrate.STONE, FeatureFluid.ANY)
		);

		String placement = placement(cave);

		assertTrue(placement.contains("\"count\":6"), placement);
		assertTrue(placement.contains("random_offset"), placement);
		assertTrue(placement.contains("\"max_steps\":23"), placement);
		assertTrue(placement.contains("minecraft:base_stone_overworld"), placement);
	}

	@Test
	void absoluteHeightFilterKeepsTheAlreadySelectedSurface() {
		WorldsmithHeightRangeFilter filter = WorldsmithHeightRangeFilter.of(80, 120);

		assertEquals(1, filter.getPositions(null, RandomSource.create(1L), new BlockPos(4, 100, 7)).count());
		assertEquals(0, filter.getPositions(null, RandomSource.create(1L), new BlockPos(4, 79, 7)).count());
		assertEquals(0, filter.getPositions(null, RandomSource.create(1L), new BlockPos(4, 121, 7)).count());
	}

	private static FeatureDefinition feature(
		FeatureRecipe recipe,
		FeaturePatchSpec patch,
		BoulderSpec boulder,
		OreVeinSpec ore,
		ColumnSpec column,
		FallenLogSpec fallenLog,
		FeaturePlacementConditions placement
	) {
		return new FeatureDefinition(
			"sample_" + recipe.name().toLowerCase(java.util.Locale.ROOT),
			recipe,
			new MaterialSelector("material", List.of("minecraft:stone"), List.of(), List.of()),
			Map.of(),
			0.4,
			null,
			patch,
			boulder,
			ore,
			column,
			fallenLog,
			placement
		);
	}

	private static FeaturePlacementConditions defaults() {
		return new FeaturePlacementConditions(null, null, FeatureSubstrate.RECIPE_DEFAULT, FeatureFluid.RECIPE_DEFAULT);
	}

	private static String configured(FeatureDefinition feature) {
		ConfiguredFeature<?, ?> configured = WorldsmithVegetation.configure(feature, new MaterialResolver());
		var ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		JsonElement json = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, configured)
			.getOrThrow(message -> new IllegalStateException("Could not encode configured feature: " + message));
		return json.toString();
	}

	private static String placement(FeatureDefinition feature) {
		return WorldsmithVegetation.place(feature, feature.getDensity()).stream()
			.map(WorldsmithNonTreeFeatureTest::encoded)
			.reduce("", (left, right) -> left + "\n" + right);
	}

	private static String encoded(PlacementModifier modifier) {
		var ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		JsonElement json = PlacementModifier.CODEC.encodeStart(ops, modifier)
			.getOrThrow(message -> new IllegalStateException("Could not encode placement: " + message));
		return json.toString();
	}
}
