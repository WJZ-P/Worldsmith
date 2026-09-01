package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.AmbientParticleSpec;
import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomeEnvironment;
import com.wjz.worldsmith.core.model.TemperatureVariation;
import com.wjz.worldsmith.core.model.BiomeFog;
import com.wjz.worldsmith.core.model.BiomeLight;
import com.wjz.worldsmith.core.model.BiomeSky;
import com.wjz.worldsmith.core.model.BiomeFeatureRef;
import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.TerrainShape;
import com.wjz.worldsmith.core.model.WaterFog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
		// when caveDensity is zero. Vanilla passthrough shapes retain those carvers;
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

		Map<String, FeatureDefinition> library = new LinkedHashMap<>();
		pack.features().getFeatures().forEach(feature -> library.put(feature.getId(), feature));
		for (BiomeFeatureRef ref : WorldsmithVegetation.orderedRefs(pack.features(), definition.getFeatures())) {
			generation.addFeature(
				WorldsmithVegetation.step(library.get(ref.getFeature()).getRecipe()),
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
			.temperatureAdjustment(
				definition.getBehavior().getTemperatureVariation() == TemperatureVariation.PATCHY
					? Biome.TemperatureModifier.FROZEN
					: Biome.TemperatureModifier.NONE
			)
			.specialEffects(
				new BiomeSpecialEffects.Builder()
					.waterColor(rgb(environment.getTint().getWater()))
					.grassColorOverride(rgb(environment.getTint().getGrass()))
					.foliageColorOverride(rgb(environment.getTint().getFoliage()))
					.build()
			);

		builder = fog(builder, environment.getFog());
		builder = sky(builder, environment.getSky());
		builder = light(builder, environment.getLight());

		List<AmbientParticle> particles = particles(definition, environment);
		if (!particles.isEmpty()) {
			builder = builder.setAttribute(EnvironmentAttributes.AMBIENT_PARTICLES, particles);
		}

		return builder
			.mobSpawnSettings(mobs.build())
			.generationSettings(generation.build())
			.build();
	}

	private static Biome.BiomeBuilder fog(Biome.BiomeBuilder builder, BiomeFog fog) {
		builder = builder
			.setAttribute(EnvironmentAttributes.FOG_COLOR, rgb(fog.getColor()))
			.setAttribute(EnvironmentAttributes.FOG_START_DISTANCE, fog.getStartDistance())
			.setAttribute(EnvironmentAttributes.FOG_END_DISTANCE, fog.getEndDistance());
		if (fog.getSkyEndDistance() != null) {
			builder = builder.setAttribute(EnvironmentAttributes.SKY_FOG_END_DISTANCE, fog.getSkyEndDistance());
		}
		if (fog.getCloudEndDistance() != null) {
			builder = builder.setAttribute(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE, fog.getCloudEndDistance());
		}

		WaterFog water = fog.getWater();
		if (water != null) {
			builder = builder
				.setAttribute(EnvironmentAttributes.WATER_FOG_COLOR, rgb(water.getColor()))
				.setAttribute(EnvironmentAttributes.WATER_FOG_START_DISTANCE, water.getStartDistance())
				.setAttribute(EnvironmentAttributes.WATER_FOG_END_DISTANCE, water.getEndDistance());
		}
		return builder;
	}

	private static Biome.BiomeBuilder sky(Biome.BiomeBuilder builder, BiomeSky sky) {
		builder = builder.setAttribute(EnvironmentAttributes.SKY_COLOR, rgb(sky.getColor()));
		if (sky.getCloudColor() != null) {
			builder = builder.setAttribute(EnvironmentAttributes.CLOUD_COLOR, argb(sky.getCloudColor()));
		}
		if (sky.getCloudHeight() != null) {
			builder = builder.setAttribute(EnvironmentAttributes.CLOUD_HEIGHT, sky.getCloudHeight());
		}
		if (sky.getSunriseSunsetColor() != null) {
			builder = builder.setAttribute(
				EnvironmentAttributes.SUNRISE_SUNSET_COLOR,
				argb(sky.getSunriseSunsetColor())
			);
		}
		if (sky.getStarBrightness() != null) {
			builder = builder.setAttribute(EnvironmentAttributes.STAR_BRIGHTNESS, sky.getStarBrightness());
		}
		return builder;
	}

	/**
	 * Light is left entirely to Minecraft unless a pack asks otherwise.
	 *
	 * <p>Every field here has a sensible vanilla default, and overriding one
	 * changes how every block in the biome reads, so silence is the right
	 * default rather than writing the vanilla value back.
	 */
	private static Biome.BiomeBuilder light(Biome.BiomeBuilder builder, BiomeLight light) {
		if (light.getSkyColor() != null) {
			builder = builder.setAttribute(EnvironmentAttributes.SKY_LIGHT_COLOR, rgb(light.getSkyColor()));
		}
		if (light.getAmbientColor() != null) {
			builder = builder.setAttribute(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, rgb(light.getAmbientColor()));
		}
		if (light.getBlockTint() != null) {
			builder = builder.setAttribute(EnvironmentAttributes.BLOCK_LIGHT_TINT, rgb(light.getBlockTint()));
		}
		if (light.getSkyFactor() != null) {
			builder = builder.setAttribute(EnvironmentAttributes.SKY_LIGHT_FACTOR, light.getSkyFactor());
		}
		return builder;
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

	/**
	 * Parses {@code #AARRGGBB}. Alpha puts the value past Integer.MAX_VALUE, so
	 * it is read as a long first and then narrowed; parseInt would reject it.
	 */
	private static int argb(String hex) {
		return (int) Long.parseLong(hex.substring(1), 16);
	}
}
