package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.WeightedMaterial;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Regression coverage for surface materials, which cannot use a state provider. */
final class WorldsmithSurfacePaletteTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void weightedSurfaceMaterialCompilesEveryAlternativeIntoNoisePatches() {
		MaterialSelector palette = new MaterialSelector(
			"mottled_ground",
			List.of(),
			List.of(),
			List.of(
				new WeightedMaterial(material("dark", "minecraft:gravel"), 4),
				new WeightedMaterial(material("pale", "minecraft:calcite"), 1)
			)
		);

		SurfaceRules.RuleSource rule = WorldsmithSurfaceRules.materialRule(
			palette,
			Blocks.STONE,
			new MaterialResolver()
		);
		JsonElement encoded = SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, rule)
			.getOrThrow(message -> new IllegalStateException("Could not encode surface palette: " + message));
		String json = encoded.toString();

		assertTrue(json.contains("minecraft:noise_threshold"), json);
		assertTrue(json.contains("minecraft:surface_secondary"), json);
		assertTrue(json.contains("minecraft:gravel"), json);
		assertTrue(json.contains("minecraft:calcite"), json);
	}

	@Test
	void paletteThresholdsFollowCumulativeWeightRatherThanLinearNoiseRange() {
		assertEquals(0.0, WorldsmithSurfaceRules.paletteThreshold(0.5), 1.0E-12);
		assertEquals(
			-WorldsmithSurfaceRules.paletteThreshold(0.2),
			WorldsmithSurfaceRules.paletteThreshold(0.8),
			1.0E-12
		);
		assertTrue(WorldsmithSurfaceRules.paletteThreshold(0.8) > 0.2);
	}

	private static MaterialSelector material(String role, String id) {
		return new MaterialSelector(role, List.of(id), List.of(), List.of());
	}
}
