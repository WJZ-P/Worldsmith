package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.BiomeColors;
import com.wjz.worldsmith.core.model.BiomeSkin;
import com.wjz.worldsmith.core.model.BiomeSkinSet;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Turns one skeleton and its skin into a registrable {@link Biome}.
 *
 * <p>Colours are always written as explicit overrides rather than being derived
 * from temperature and downfall. Those two fields still drive whether snow falls
 * and whether water freezes, so letting them also pick the grass tint would tie
 * the palette to the weather. Overriding keeps the two independent.
 */
public final class BiomeCompiler {
	/** Below this, vanilla treats a biome as snowy. */
	private static final float FREEZING_TEMPERATURE = 0.15F;

	private BiomeCompiler() {
	}

	public static void bootstrap(BootstrapContext<Biome> context) {
		BiomeSkinSet skins = WorldsmithSkins.load();

		for (BiomeSkin skin : skins.getSkins()) {
			BiomeSkeleton skeleton = BiomeSkeletons.byId(skin.getSkeletonId());
			context.register(skeleton.biome(), compile(skeleton, skin, context));
		}
	}

	private static Biome compile(BiomeSkeleton skeleton, BiomeSkin skin, BootstrapContext<Biome> context) {
		HolderGetter<PlacedFeature> placedFeatures = context.lookup(Registries.PLACED_FEATURE);
		HolderGetter<ConfiguredWorldCarver<?>> carvers = context.lookup(Registries.CONFIGURED_CARVER);

		BiomeGenerationSettings.Builder generation = new BiomeGenerationSettings.Builder(placedFeatures, carvers);

		// Stage one keeps vanilla's underground content. A world with no ores and
		// no caves is not playable, and none of it is visible from the surface, so
		// there is nothing for the theme to clash with yet.
		BiomeDefaultFeatures.addDefaultCarversAndLakes(generation);
		BiomeDefaultFeatures.addDefaultCrystalFormations(generation);
		BiomeDefaultFeatures.addDefaultMonsterRoom(generation);
		BiomeDefaultFeatures.addDefaultUndergroundVariety(generation);
		BiomeDefaultFeatures.addDefaultOres(generation);
		BiomeDefaultFeatures.addDefaultSoftDisks(generation);
		BiomeDefaultFeatures.addDefaultSprings(generation);
		if (skeleton.temperature() < FREEZING_TEMPERATURE) {
			BiomeDefaultFeatures.addSurfaceFreezing(generation);
		}

		for (int index = 0; index < skin.getVegetation().size(); index++) {
			generation.addFeature(
				GenerationStep.Decoration.VEGETAL_DECORATION,
				WorldsmithVegetation.placedKey(skin.getSkeletonId(), index)
			);
		}

		MobSpawnSettings.Builder mobs = new MobSpawnSettings.Builder();
		BiomeDefaultFeatures.commonSpawns(mobs);
		if (!skeleton.archetype().isAquatic()) {
			BiomeDefaultFeatures.farmAnimals(mobs);
		}

		BiomeColors colors = skin.getColors();
		return new Biome.BiomeBuilder()
			.hasPrecipitation(skeleton.hasPrecipitation())
			.temperature(skeleton.temperature())
			.downfall(skeleton.downfall())
			.specialEffects(
				new BiomeSpecialEffects.Builder()
					.waterColor(rgb(colors.getWater()))
					.grassColorOverride(rgb(colors.getGrass()))
					.foliageColorOverride(rgb(colors.getFoliage()))
					.build()
			)
			.setAttribute(EnvironmentAttributes.SKY_COLOR, rgb(colors.getSky()))
			.setAttribute(EnvironmentAttributes.FOG_COLOR, rgb(colors.getFog()))
			.setAttribute(EnvironmentAttributes.FOG_END_DISTANCE, colors.getFogEndDistance())
			.mobSpawnSettings(mobs.build())
			.generationSettings(generation.build())
			.build();
	}

	/**
	 * Parses {@code #RRGGBB}. The validator has already rejected anything else,
	 * so a failure here means the fixture bypassed validation.
	 */
	private static int rgb(String hex) {
		return Integer.parseInt(hex.substring(1), 16);
	}
}
