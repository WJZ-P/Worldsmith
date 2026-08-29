package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.BiomeSkeletonIds;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.world.level.biome.Climate;

/**
 * The eight fixed skeletons of stage one.
 *
 * <p>The bands mirror {@code OverworldBiomeBuilder} so that terrain and biome
 * assignment stay derived from the same numbers. Terrain shape comes from
 * continentalness and erosion; selecting biomes with those same bands is what
 * keeps a swamp off a mountain peak.
 *
 * <p>The split is total: continentalness is cut into four adjacent spans, the
 * inland span is cut into three adjacent erosion spans, and the flat span is cut
 * into three adjacent temperature spans. Every point lands in exactly one box,
 * so stage one needs no reachability sampling to prove that all eight biomes
 * generate.
 */
public final class BiomeSkeletons {
	private static final Climate.Parameter FULL = Climate.Parameter.span(-1.0F, 1.0F);

	// Continentalness, matching the vanilla ocean/coast/inland bands.
	private static final Climate.Parameter DEEP_WATER = Climate.Parameter.span(-1.2F, -0.455F);
	private static final Climate.Parameter SHALLOW_WATER = Climate.Parameter.span(-0.455F, -0.19F);
	private static final Climate.Parameter COAST = Climate.Parameter.span(-0.19F, -0.11F);
	private static final Climate.Parameter INLAND = Climate.Parameter.span(-0.11F, 1.0F);

	// Erosion. Low erosion keeps high ground, high erosion grinds it flat.
	private static final Climate.Parameter STEEP = Climate.Parameter.span(-1.0F, -0.375F);
	private static final Climate.Parameter ROLLING = Climate.Parameter.span(-0.375F, 0.05F);
	private static final Climate.Parameter FLAT = Climate.Parameter.span(0.05F, 1.0F);

	// Temperature, using the vanilla band edges.
	private static final Climate.Parameter COLD = Climate.Parameter.span(-1.0F, -0.15F);
	private static final Climate.Parameter TEMPERATE = Climate.Parameter.span(-0.15F, 0.55F);
	private static final Climate.Parameter HOT = Climate.Parameter.span(0.55F, 1.0F);

	public static final BiomeSkeleton ABYSS = new BiomeSkeleton(
		BiomeSkeletonIds.ABYSS, WorldsmithBiomes.ABYSS, BiomeArchetype.DEEP_OCEAN,
		box(FULL, DEEP_WATER, FULL), 0.5F, 0.5F, true
	);
	public static final BiomeSkeleton SHALLOWS = new BiomeSkeleton(
		BiomeSkeletonIds.SHALLOWS, WorldsmithBiomes.SHALLOWS, BiomeArchetype.OCEAN,
		box(FULL, SHALLOW_WATER, FULL), 0.5F, 0.5F, true
	);
	public static final BiomeSkeleton SHORE = new BiomeSkeleton(
		BiomeSkeletonIds.SHORE, WorldsmithBiomes.SHORE, BiomeArchetype.BEACH,
		box(FULL, COAST, FULL), 0.7F, 0.3F, true
	);
	public static final BiomeSkeleton PEAKS = new BiomeSkeleton(
		BiomeSkeletonIds.PEAKS, WorldsmithBiomes.PEAKS, BiomeArchetype.MOUNTAIN,
		box(FULL, INLAND, STEEP), 0.1F, 0.4F, true
	);
	public static final BiomeSkeleton HIGHLAND = new BiomeSkeleton(
		BiomeSkeletonIds.HIGHLAND, WorldsmithBiomes.HIGHLAND, BiomeArchetype.HILL,
		box(FULL, INLAND, ROLLING), 0.5F, 0.4F, true
	);
	public static final BiomeSkeleton FLATS_COLD = new BiomeSkeleton(
		BiomeSkeletonIds.FLATS_COLD, WorldsmithBiomes.FLATS_COLD, BiomeArchetype.LOWLAND,
		box(COLD, INLAND, FLAT), 0.0F, 0.4F, true
	);
	public static final BiomeSkeleton FLATS_TEMPERATE = new BiomeSkeleton(
		BiomeSkeletonIds.FLATS_TEMPERATE, WorldsmithBiomes.FLATS_TEMPERATE, BiomeArchetype.LOWLAND,
		box(TEMPERATE, INLAND, FLAT), 0.7F, 0.4F, true
	);
	public static final BiomeSkeleton FLATS_HOT = new BiomeSkeleton(
		BiomeSkeletonIds.FLATS_HOT, WorldsmithBiomes.FLATS_HOT, BiomeArchetype.LOWLAND,
		box(HOT, INLAND, FLAT), 1.6F, 0.0F, false
	);

	public static final List<BiomeSkeleton> ALL = List.of(
		ABYSS, SHALLOWS, SHORE, PEAKS, HIGHLAND, FLATS_COLD, FLATS_TEMPERATE, FLATS_HOT
	);

	private static final Map<String, BiomeSkeleton> BY_ID =
		ALL.stream().collect(Collectors.toUnmodifiableMap(BiomeSkeleton::id, Function.identity()));

	private BiomeSkeletons() {
	}

	public static BiomeSkeleton byId(String id) {
		BiomeSkeleton skeleton = BY_ID.get(id);
		if (skeleton == null) {
			throw new IllegalArgumentException("Unknown biome skeleton '" + id + "'");
		}
		return skeleton;
	}

	/**
	 * Humidity, depth and weirdness stay open in stage one. Humidity becomes a
	 * real axis in stage two; depth and weirdness are left full so the grid keeps
	 * covering caves and river bands instead of leaving holes for the nearest
	 * neighbour search to fill arbitrarily.
	 */
	private static Climate.ParameterPoint box(Climate.Parameter temperature, Climate.Parameter continentalness, Climate.Parameter erosion) {
		return new Climate.ParameterPoint(temperature, FULL, continentalness, erosion, FULL, FULL, 0L);
	}
}
