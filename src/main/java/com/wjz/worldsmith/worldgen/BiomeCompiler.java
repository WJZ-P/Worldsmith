package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.AmbientParticleSpec;
import com.wjz.worldsmith.core.model.BiomeAddition;
import com.wjz.worldsmith.core.model.BiomeAudio;
import com.wjz.worldsmith.core.model.BiomeMood;
import com.wjz.worldsmith.core.model.BiomeMusic;
import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomeEnvironment;
import com.wjz.worldsmith.core.model.BiomeGrassColorModifier;
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
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.placement.MiscOverworldPlacements;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.attribute.AmbientAdditionsSettings;
import net.minecraft.world.attribute.AmbientMoodSettings;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.AmbientSounds;
import net.minecraft.world.attribute.BackgroundMusic;
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
	 * <p>Grass, foliage and dry-foliage colours are explicit only when the pack
	 * authors an override. Leaving one absent deliberately restores Minecraft's
	 * temperature/downfall palette, while water remains required by the biome codec.
 *
 * <p>An environment block lands in two different places: grass, foliage and
 * water colours are biome special effects, while fog, sky and particles are
 * environment attributes. Attributes interpolate across biome borders, so fog
 * changes fade in rather than switching at the boundary.
 */
public final class BiomeCompiler {
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

		// Procedural terrain owns every cave family in its NoiseRouter. Adding the
		// legacy configured carvers as well would punch full-strength caves even
		// when all authored densities are zero. Vanilla passthrough shapes retain those carvers;
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
		// The feature performs Minecraft's position-aware temperature check at
		// placement time. Registering it for every overworld biome is what lets a
		// temperate mountain acquire a natural high-altitude snow line; filtering
		// here by the biome's base temperature loses that altitude correction.
		BiomeDefaultFeatures.addSurfaceFreezing(generation);

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

        if (!pack.pack().getCreatures().getCreatures().isEmpty()) {
            var snapshot = pack.creatureSnapshot();
            for (var entry : com.wjz.worldsmith.content.creature.CreatureRuntime.perBiomeSpawnEntries(snapshot,pack.biomeKey(definition.getId()).identifier().toString())) {
                var type = entry.category()==com.wjz.worldsmith.core.content.CreatureCategory.HOSTILE
                    ? com.wjz.worldsmith.content.creature.CreatureRuntime.hostileType()
                    : com.wjz.worldsmith.content.creature.CreatureRuntime.passiveType();
                mobs.addSpawn(type.getCategory(), entry.weight(), new MobSpawnSettings.SpawnerData(type,entry.minGroup(),entry.maxGroup()));
            }
        }

		BiomeEnvironment environment = definition.getEnvironment();
		BiomeSpecialEffects.Builder effects = new BiomeSpecialEffects.Builder()
			.waterColor(rgb(environment.getTint().getWater()))
			.grassColorModifier(grassModifier(environment.getTint().getGrassModifier()));
		if (environment.getTint().getGrass() != null) {
			effects.grassColorOverride(rgb(environment.getTint().getGrass()));
		}
		if (environment.getTint().getFoliage() != null) {
			effects.foliageColorOverride(rgb(environment.getTint().getFoliage()));
		}
		if (environment.getTint().getDryFoliage() != null) {
			effects.dryFoliageColorOverride(rgb(environment.getTint().getDryFoliage()));
		}
		Biome.BiomeBuilder builder = new Biome.BiomeBuilder()
			.hasPrecipitation(definition.getBehavior().getHasPrecipitation())
			.temperature(definition.getBehavior().getTemperature())
			.downfall(definition.getBehavior().getDownfall())
			.temperatureAdjustment(
				definition.getBehavior().getTemperatureVariation() == TemperatureVariation.PATCHY
					? Biome.TemperatureModifier.FROZEN
					: Biome.TemperatureModifier.NONE
			)
			.specialEffects(effects.build());

		builder = fog(builder, environment.getFog());
		builder = sky(builder, environment.getSky());
		builder = light(builder, environment.getLight());

		List<AmbientParticle> particles = particles(definition, environment);
		if (!particles.isEmpty()) {
			builder = builder.setAttribute(EnvironmentAttributes.AMBIENT_PARTICLES, particles);
		}
		builder = audio(builder, definition, environment.getAudio());

		return builder
			.mobSpawnSettings(mobs.build())
			.generationSettings(generation.build())
			.build();
	}

	private static BiomeSpecialEffects.GrassColorModifier grassModifier(BiomeGrassColorModifier modifier) {
		return switch (modifier) {
			case NONE -> BiomeSpecialEffects.GrassColorModifier.NONE;
			case DARK_FOREST -> BiomeSpecialEffects.GrassColorModifier.DARK_FOREST;
			case SWAMP -> BiomeSpecialEffects.GrassColorModifier.SWAMP;
		};
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
	 * Music and ambience. Each attribute is set only when authored, so a biome
	 * without audio keeps the overworld's music and cave mood from the dimension.
	 * An unknown sound event is skipped with a warning, like a particle.
	 */
	private static Biome.BiomeBuilder audio(Biome.BiomeBuilder builder, BiomeDefinition definition, BiomeAudio audio) {
		if (audio == null) {
			return builder;
		}
		Optional<Music> music = music(definition, audio.getMusic());
		Optional<Music> underwater = music(definition, audio.getUnderwaterMusic());
		if (music.isPresent()) {
			// Like vanilla's own biome tracks: creative mode falls back to the biome's default.
			builder = builder.setAttribute(EnvironmentAttributes.BACKGROUND_MUSIC, new BackgroundMusic(music, Optional.empty(), underwater));
		} else if (underwater.isPresent()) {
			builder = builder.setAttribute(EnvironmentAttributes.BACKGROUND_MUSIC, BackgroundMusic.OVERWORLD.withUnderwater(underwater.get()));
		}
		if (audio.getMusicVolume() != null) {
			builder = builder.setAttribute(EnvironmentAttributes.MUSIC_VOLUME, audio.getMusicVolume());
		}
		BiomeMood mood = audio.getMood();
		if (audio.getLoop() != null || !audio.getAdditions().isEmpty() || !mood.equals(new BiomeMood())) {
			Optional<Holder<SoundEvent>> loop = audio.getLoop() == null ? Optional.empty() : sound(definition, audio.getLoop());
			AmbientMoodSettings moodSettings = sound(definition, mood.getSound())
				.map(sound -> new AmbientMoodSettings(sound, mood.getTickDelay(), mood.getBlockSearchExtent(), mood.getOffset()))
				.orElse(AmbientMoodSettings.LEGACY_CAVE_SETTINGS);
			List<AmbientAdditionsSettings> additions = new ArrayList<>();
			for (BiomeAddition addition : audio.getAdditions()) {
				sound(definition, addition.getSound()).ifPresent(sound -> additions.add(new AmbientAdditionsSettings(sound, addition.getTickChance())));
			}
			builder = builder.setAttribute(EnvironmentAttributes.AMBIENT_SOUNDS, new AmbientSounds(loop, Optional.of(moodSettings), List.copyOf(additions)));
		}
		return builder;
	}

	private static Optional<Music> music(BiomeDefinition definition, BiomeMusic music) {
		if (music == null) {
			return Optional.empty();
		}
		return sound(definition, music.getSound())
			.map(sound -> new Music(sound, music.getMinDelayTicks(), music.getMaxDelayTicks(), music.getReplaceCurrent()));
	}

	private static Optional<Holder<SoundEvent>> sound(BiomeDefinition definition, String id) {
		Identifier key = Identifier.tryParse(id);
		Optional<Holder<SoundEvent>> sound = key == null ? Optional.empty() : BuiltInRegistries.SOUND_EVENT.get(key).map(reference -> reference);
		if (sound.isEmpty()) {
			Worldsmith.LOGGER.warn("Biome {} asks for sound event {}, which is not registered", definition.getId(), id);
		}
		return sound;
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
