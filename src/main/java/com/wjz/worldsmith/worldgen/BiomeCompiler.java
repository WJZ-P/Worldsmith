package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.AmbientParticleSpec;
import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomeEnvironment;
import com.wjz.worldsmith.core.model.BiomeFeatureRef;
import com.wjz.worldsmith.core.model.TerrainShape;
import com.wjz.worldsmith.core.model.WaterFog;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.placement.MiscOverworldPlacements;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Turns one pack biome into a registrable {@link Biome}.
 *
 * <p>Colours are always written as explicit overrides rather than being derived
 * from temperature and downfall. Those two fields still drive whether snow falls
 * and whether water freezes, so letting them also pick the grass tint would tie
 * the palette to the weather. Overriding keeps the two independent.
 *
 * <p>An environment block lands in two different places: grass, foliage and
 * water colours are biome special effects, while fog, sky and particles are
 * environment attributes. Attributes interpolate across biome borders, so fog
 * changes fade in rather than switching at the boundary.
 */
public final class BiomeCompiler {
	/** Below this, vanilla treats a biome as snowy. */
	private static final float FREEZING_TEMPERATURE = 0.15F;

	private BiomeCompiler() {
	}

	public static void bootstrap(CompiledPack pack, BootstrapContext<Biome> context) {
		for (CompiledBiome biome : pack.biomes()) {
			context.register(biome.key(), compile(pack, biome, context));
		}
	}

	private static Biome compile(CompiledPack pack, CompiledBiome biome, BootstrapContext<Biome> context) {
		BiomeDefinition definition = biome.definition();
		HolderGetter<PlacedFeature> placedFeatures = context.lookup(Registries.PLACED_FEATURE);
		HolderGetter<ConfiguredWorldCarver<?>> carvers = context.lookup(Registries.CONFIGURED_CARVER);

		BiomeGenerationSettings.Builder generation = new BiomeGenerationSettings.Builder(placedFeatures, carvers);

		// Procedural terrain owns cave density in its NoiseRouter. Adding the
		// legacy configured carvers as well would punch full-strength caves even
		// when caveDensity is zero. Compatibility shapes retain vanilla's carvers;
		// both paths keep the ordinary underground and surface lava lakes.
		if (pack.terrain().getShape() instanceof TerrainShape.Vanilla) {
			BiomeDefaultFeatures.addDefaultCarversAndLakes(generation);
		} else {
			generation.addFeature(GenerationStep.Decoration.LAKES, MiscOverworldPlacements.LAKE_LAVA_UNDERGROUND);
			generation.addFeature(GenerationStep.Decoration.LAKES, MiscOverworldPlacements.LAKE_LAVA_SURFACE);
		}
		BiomeDefaultFeatures.addDefaultCrystalFormations(generation);
		BiomeDefaultFeatures.addDefaultMonsterRoom(generation);
		BiomeDefaultFeatures.addDefaultUndergroundVariety(generation);
		BiomeDefaultFeatures.addDefaultOres(generation);
		BiomeDefaultFeatures.addDefaultSoftDisks(generation);
		BiomeDefaultFeatures.addDefaultSprings(generation);
		if (definition.getBehavior().getTemperature() < FREEZING_TEMPERATURE) {
			BiomeDefaultFeatures.addSurfaceFreezing(generation);
		}

		for (BiomeFeatureRef ref : definition.getFeatures()) {
			generation.addFeature(
				GenerationStep.Decoration.VEGETAL_DECORATION,
				WorldsmithVegetation.placedKeyFor(pack, definition, ref)
			);
		}

		MobSpawnSettings.Builder mobs = new MobSpawnSettings.Builder();
		BiomeDefaultFeatures.commonSpawns(mobs);
		if (!biome.archetype().isAquatic()) {
			BiomeDefaultFeatures.farmAnimals(mobs);
		}

		BiomeEnvironment environment = definition.getEnvironment();
		Biome.BiomeBuilder builder = new Biome.BiomeBuilder()
			.hasPrecipitation(definition.getBehavior().getHasPrecipitation())
			.temperature(definition.getBehavior().getTemperature())
			.downfall(definition.getBehavior().getDownfall())
			.specialEffects(
				new BiomeSpecialEffects.Builder()
					.waterColor(rgb(environment.getWaterColor()))
					.grassColorOverride(rgb(environment.getGrassColor()))
					.foliageColorOverride(rgb(environment.getFoliageColor()))
					.build()
			)
			.setAttribute(EnvironmentAttributes.SKY_COLOR, rgb(environment.getSkyColor()))
			.setAttribute(EnvironmentAttributes.FOG_COLOR, rgb(environment.getFogColor()))
			.setAttribute(EnvironmentAttributes.FOG_END_DISTANCE, environment.getFogEndDistance());

		WaterFog waterFog = environment.getWaterFog();
		if (waterFog != null) {
			builder = builder
				.setAttribute(EnvironmentAttributes.WATER_FOG_COLOR, rgb(waterFog.getColor()))
				.setAttribute(EnvironmentAttributes.WATER_FOG_START_DISTANCE, waterFog.getStartDistance())
				.setAttribute(EnvironmentAttributes.WATER_FOG_END_DISTANCE, waterFog.getEndDistance());
		}

		List<AmbientParticle> particles = particles(definition, environment);
		if (!particles.isEmpty()) {
			builder = builder.setAttribute(EnvironmentAttributes.AMBIENT_PARTICLES, particles);
		}

		return builder
			.mobSpawnSettings(mobs.build())
			.generationSettings(generation.build())
			.build();
	}

	/**
	 * Only particles that need no extra data can be named by id alone. Anything
	 * else is skipped with a warning rather than failing the whole world, since a
	 * missing ambient effect is cosmetic.
	 */
	private static List<AmbientParticle> particles(BiomeDefinition definition, BiomeEnvironment environment) {
		List<AmbientParticle> particles = new ArrayList<>();
		for (AmbientParticleSpec spec : environment.getAmbientParticles()) {
			Identifier id = Identifier.tryParse(spec.getParticle());
			Optional<ParticleType<?>> type = id == null ? Optional.empty() : BuiltInRegistries.PARTICLE_TYPE.getOptional(id);
			if (type.isPresent() && type.get() instanceof ParticleOptions options) {
				particles.add(new AmbientParticle(options, spec.getProbability()));
			} else {
				Worldsmith.LOGGER.warn(
					"Biome {} asks for ambient particle {}, which is not a simple registered particle",
					definition.getId(),
					spec.getParticle()
				);
			}
		}
		return List.copyOf(particles);
	}

	/**
	 * Parses {@code #RRGGBB}. The validator has already rejected anything else,
	 * so a failure here means the pack bypassed validation.
	 */
	private static int rgb(String hex) {
		return Integer.parseInt(hex.substring(1), 16);
	}
}
