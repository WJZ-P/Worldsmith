package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wjz.worldsmith.core.model.BiomeAddition;
import com.wjz.worldsmith.core.model.BiomeAudio;
import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomeEnvironment;
import com.wjz.worldsmith.core.model.BiomeGrassColorModifier;
import com.wjz.worldsmith.core.model.BiomeMood;
import com.wjz.worldsmith.core.model.BiomeMusic;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.attribute.AmbientMoodSettings;
import net.minecraft.world.attribute.AmbientSounds;
import net.minecraft.world.attribute.BackgroundMusic;
import net.minecraft.world.attribute.EnvironmentAttributes;
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
			oldManifest.getFormatVersion(), id, "Tint fixture", "Compiler regression", oldManifest.getModules(), oldManifest.getAssets()
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

	@Test
	void authoredAudioBecomesTheBiomesOwnMusicAndAmbience() {
		BiomeEnvironment base = WorldsmithPacks.builtin().getBiomes().getBiomes().getFirst().getEnvironment();
		BiomeAudio audio = new BiomeAudio(
			new BiomeMusic("minecraft:music.overworld.cherry_grove", 6000, 12000, false),
			null,
			0.8F,
			"minecraft:ambient.soul_sand_valley.loop",
			new BiomeMood(),
			List.of(new BiomeAddition("minecraft:block.bell.resonate", 0.0005), new BiomeAddition("worldsmith:no_such_sound", 0.001))
		);
		Biome biome = compiledWith(new BiomeEnvironment(base.getTint(), base.getFog(), base.getSky(), base.getLight(), base.getAmbientParticles(), audio));

		BackgroundMusic music = (BackgroundMusic) biome.getAttributes().get(EnvironmentAttributes.BACKGROUND_MUSIC).argument();
		assertEquals(SoundEvents.MUSIC_BIOME_CHERRY_GROVE, music.defaultMusic().orElseThrow().sound());
		assertEquals(6000, music.defaultMusic().orElseThrow().minDelay());
		assertEquals(0.8F, biome.getAttributes().get(EnvironmentAttributes.MUSIC_VOLUME).argument());
		AmbientSounds ambience = (AmbientSounds) biome.getAttributes().get(EnvironmentAttributes.AMBIENT_SOUNDS).argument();
		assertEquals(SoundEvents.AMBIENT_SOUL_SAND_VALLEY_LOOP, ambience.loop().orElseThrow());
		// The cave mood a biome replaces when it sets its own ambience is kept, not lost.
		assertEquals(AmbientMoodSettings.LEGACY_CAVE_SETTINGS.soundEvent(), ambience.mood().orElseThrow().soundEvent());
		// An unknown event is skipped like an unknown particle; the rest of the biome still loads.
		assertEquals(1, ambience.additions().size());

		Biome silent = compiledWith(base);
		assertFalse(silent.getAttributes().contains(EnvironmentAttributes.BACKGROUND_MUSIC), "an unauthored biome keeps the dimension's music");
		assertFalse(silent.getAttributes().contains(EnvironmentAttributes.AMBIENT_SOUNDS));
	}

	private static Biome compiledWith(BiomeEnvironment environment) {
		WorldsmithPack source = WorldsmithPacks.builtin();
		List<BiomeDefinition> definitions = new ArrayList<>(source.getBiomes().getBiomes());
		BiomeDefinition original = definitions.getFirst();
		definitions.set(0, new BiomeDefinition(
			original.getId(), original.getDisplayName(), original.getArchetype(),
			original.getSlot(), original.getClimate(), original.getPlacements(),
			original.getBehavior(), original.getSurface(), environment,
			original.getTags(), original.getFeatures()
		));
		String id = "d".repeat(64);
		WorldsmithPackManifest oldManifest = source.getManifest();
		WorldsmithPackManifest manifest = new WorldsmithPackManifest(
			oldManifest.getFormatVersion(), id, "Audio fixture", "Compiler regression", oldManifest.getModules(), oldManifest.getAssets()
		);
		BiomePlan plan = new BiomePlan(source.getBiomes().getSchemaVersion(), List.copyOf(definitions), source.getBiomes().getSpatial());
		CompiledPack pack = CompiledPack.scoped(new WorldsmithPack(manifest, source.getTerrain(), plan, source.getFeatures(), id, source.getStructures()));
		HolderLookup.Provider active = WorldsmithPackExporter.compilePatch(WorldsmithPacks.builtinCompiled(), vanilla).full();
		return WorldsmithPackExporter.compilePatch(pack, active).full().lookupOrThrow(Registries.BIOME)
			.getOrThrow(pack.biome(original.getId()).key()).value();
	}
}
