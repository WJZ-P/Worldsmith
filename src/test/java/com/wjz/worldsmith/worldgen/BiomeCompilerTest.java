package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomeEnvironment;
import com.wjz.worldsmith.core.model.BiomeGrassColorModifier;
import com.wjz.worldsmith.core.model.BiomePlan;
import com.wjz.worldsmith.core.model.BiomeTint;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.model.WorldsmithPackManifest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.placement.MiscOverworldPlacements;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.levelgen.GenerationStep;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Checks biome behavior that only exists after Minecraft registry compilation. */
final class BiomeCompilerTest {
	private static HolderLookup.Provider vanilla;

	@BeforeAll
	static void bootstrapMinecraft() {
		WorldsmithTestBootstrap.bootStrap();
		vanilla = VanillaRegistries.createLookup();
	}

	@Test
	void everyOverworldBiomeRunsPositionAwareSurfaceFreezing() {
		CompiledPack pack = WorldsmithPacks.builtinCompiled();
		RegistrySetBuilder.PatchedRegistries compiled = WorldsmithPackExporter.compilePatch(pack, vanilla);
		HolderLookup.RegistryLookup<Biome> registry = compiled.full().lookupOrThrow(Registries.BIOME);
		int step = GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal();

		for (CompiledBiome biome : pack.biomes()) {
			List<?> features = registry.getOrThrow(biome.key()).value().getGenerationSettings().features();
			assertTrue(features.size() > step, biome.id() + " has no top-layer step");
			assertTrue(
				registry.getOrThrow(biome.key()).value().getGenerationSettings().features().get(step).stream()
					.anyMatch(holder -> holder.is(MiscOverworldPlacements.FREEZE_TOP_LAYER)),
				biome.id() + " must let Minecraft test freezing at the actual block position"
			);
		}
	}

	@Test
	void omittedPlantTintsStayClimateDerivedAndDryFoliageAndModifierCompile() {
		WorldsmithPack source = WorldsmithPacks.builtin();
		List<BiomeDefinition> definitions = new ArrayList<>(source.getBiomes().getBiomes());
		BiomeDefinition original = definitions.getFirst();
		BiomeEnvironment oldEnvironment = original.getEnvironment();
		BiomeTint tint = new BiomeTint(
			null,
			null,
			oldEnvironment.getTint().getWater(),
			"#C0FFEE",
			BiomeGrassColorModifier.SWAMP
		);
		BiomeEnvironment environment = new BiomeEnvironment(
			tint,
			oldEnvironment.getFog(),
			oldEnvironment.getSky(),
			oldEnvironment.getLight(),
			oldEnvironment.getAmbientParticles()
		);
		definitions.set(0, new BiomeDefinition(
			original.getId(), original.getDisplayName(), original.getArchetype(),
			original.getSlot(), original.getClimate(), original.getPlacements(),
			original.getBehavior(), original.getSurface(), environment,
			original.getTags(), original.getFeatures()
		));

		String id = "b".repeat(64);
		WorldsmithPackManifest oldManifest = source.getManifest();
		WorldsmithPackManifest manifest = new WorldsmithPackManifest(
			oldManifest.getFormatVersion(), id, "Tint fixture", "Compiler regression", oldManifest.getFiles()
		);
		BiomePlan plan = new BiomePlan(
			source.getBiomes().getSchemaVersion(),
			List.copyOf(definitions),
			source.getBiomes().getSpatial()
		);
		CompiledPack pack = CompiledPack.scoped(new WorldsmithPack(
			manifest, source.getTerrain(), plan, source.getFeatures(), id, source.getStructures()
		));
		HolderLookup.Provider active = WorldsmithPackExporter.compilePatch(
			WorldsmithPacks.builtinCompiled(), vanilla
		).full();
		RegistrySetBuilder.PatchedRegistries compiled = WorldsmithPackExporter.compilePatch(pack, active);
		Biome compiledBiome = compiled.full().lookupOrThrow(Registries.BIOME)
			.getOrThrow(pack.biome(original.getId()).key()).value();
		BiomeSpecialEffects effects = compiledBiome.getSpecialEffects();

		assertTrue(effects.grassColorOverride().isEmpty());
		assertTrue(effects.foliageColorOverride().isEmpty());
		// Minecraft stores dry-foliage overrides as opaque ARGB even though the
		// Worldsmith document deliberately accepts the same #RRGGBB notation as
		// grass and foliage.
		assertEquals(0xFFC0FFEE, effects.dryFoliageColorOverride().orElseThrow());
		assertEquals(BiomeSpecialEffects.GrassColorModifier.SWAMP, effects.grassColorModifier());
	}
}
