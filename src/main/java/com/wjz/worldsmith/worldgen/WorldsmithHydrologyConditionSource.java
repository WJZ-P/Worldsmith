package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.model.RiverFill;
import com.wjz.worldsmith.core.model.SurfaceHydrology;
import java.util.Locale;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Serializable material condition backed by the same masks as terrain hydrology. */
record WorldsmithHydrologyConditionSource(
	SurfaceHydrology signal,
	double landRatio,
	double continentScale,
	double coastRoughness,
	double riverCoverage,
	double riverWidth,
	double riverMeander,
	RiverFill riverFill,
	double lakeDensity,
	double lakeScale
) implements SurfaceRules.ConditionSource {
	private static final Codec<SurfaceHydrology> SIGNAL_CODEC = enumCodec(SurfaceHydrology.class);
	private static final Codec<RiverFill> FILL_CODEC = enumCodec(RiverFill.class);
	static final MapCodec<WorldsmithHydrologyConditionSource> CODEC = RecordCodecBuilder.mapCodec(
		instance -> instance.group(
			SIGNAL_CODEC.fieldOf("signal").forGetter(WorldsmithHydrologyConditionSource::signal),
			Codec.DOUBLE.fieldOf("land_ratio").forGetter(WorldsmithHydrologyConditionSource::landRatio),
			Codec.DOUBLE.fieldOf("continent_scale").forGetter(WorldsmithHydrologyConditionSource::continentScale),
			Codec.DOUBLE.fieldOf("coast_roughness").forGetter(WorldsmithHydrologyConditionSource::coastRoughness),
			Codec.DOUBLE.fieldOf("river_coverage").forGetter(WorldsmithHydrologyConditionSource::riverCoverage),
			Codec.DOUBLE.fieldOf("river_width").forGetter(WorldsmithHydrologyConditionSource::riverWidth),
			Codec.DOUBLE.fieldOf("river_meander").forGetter(WorldsmithHydrologyConditionSource::riverMeander),
			FILL_CODEC.fieldOf("river_fill").forGetter(WorldsmithHydrologyConditionSource::riverFill),
			Codec.DOUBLE.fieldOf("lake_density").forGetter(WorldsmithHydrologyConditionSource::lakeDensity),
			Codec.DOUBLE.fieldOf("lake_scale").forGetter(WorldsmithHydrologyConditionSource::lakeScale)
		).apply(instance, WorldsmithHydrologyConditionSource::new)
	);

	@Override
	public MapCodec<WorldsmithHydrologyConditionSource> codec() {
		return CODEC;
	}

	@Override
	public SurfaceRules.Condition apply(SurfaceRules.Context context) {
		NormalNoise route = context.randomState.getOrCreateNoise(Noises.BADLANDS_SURFACE);
		NormalNoise bend = context.randomState.getOrCreateNoise(Noises.ICEBERG_SURFACE);
		NormalNoise lake = context.randomState.getOrCreateNoise(Noises.SWAMP);
		NormalNoise shift = context.randomState.getOrCreateNoise(Noises.SHIFT);
		NormalNoise continents = context.randomState.getOrCreateNoise(Noises.CONTINENTALNESS);
		NormalNoise coast = context.randomState.getOrCreateNoise(Noises.SURFACE);

		return new SurfaceRules.Condition() {
			private long lastPosition = Long.MIN_VALUE;
			private boolean lastResult;

			@Override
			public boolean test() {
				long position = ((long)context.blockX << 32) ^ (context.blockZ & 0xFFFF_FFFFL);
				if (position != this.lastPosition) {
					this.lastPosition = position;
					this.lastResult = compute(context.blockX, context.blockZ, route, bend, lake, shift, continents, coast);
				}
				return this.lastResult;
			}
		};
	}

	private boolean compute(
		int blockX,
		int blockZ,
		NormalNoise route,
		NormalNoise bend,
		NormalNoise lake,
		NormalNoise shift,
		NormalNoise continents,
		NormalNoise coast
	) {
		if (!isInland(blockX, blockZ, shift, continents, coast)) {
			return false;
		}
		double river = WorldsmithHydrology.riverEffectAt(
			this.riverCoverage,
			this.riverWidth,
			this.riverMeander,
			route,
			bend,
			blockX,
			blockZ
		);
		return switch (this.signal) {
			case DRY_RIVERBED -> this.riverFill == RiverFill.DRY && river >= 0.72;
			case WET_RIVERBED -> this.riverFill == RiverFill.FLUID && river >= 0.72;
			case RIVER_BANK -> river >= 0.45 && river < 0.72;
			case LAKEBED -> WorldsmithHydrology.lakeEffectAt(
				this.lakeDensity, this.lakeScale, lake, blockX, blockZ
			) >= 0.72;
		};
	}

	private boolean isInland(int blockX, int blockZ, NormalNoise shift, NormalNoise continents, NormalNoise coast) {
		double shiftX = shift.getValue(blockX * 0.25, 0.0, blockZ * 0.25) * 4.0;
		double shiftZ = shift.getValue(blockZ * 0.25, blockX * 0.25, 0.0) * 4.0;
		double scale = 0.25 / this.continentScale;
		double continent = continents.getValue(blockX * scale + shiftX, 0.0, blockZ * scale + shiftZ);
		double detail = coast.getValue(blockX * scale * 5.0 + shiftX, 0.0, blockZ * scale * 5.0 + shiftZ);
		double combined = clamp(
			continent + WorldsmithNoiseSettings.landBias(this.landRatio) + detail * this.coastRoughness * 0.18,
			-1.2,
			1.0
		);
		return combined + 0.11 >= 0.02;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static <E extends Enum<E>> Codec<E> enumCodec(Class<E> type) {
		return Codec.STRING.comapFlatMap(
			name -> {
				try {
					return DataResult.success(Enum.valueOf(type, name.toUpperCase(Locale.ROOT)));
				} catch (IllegalArgumentException failure) {
					return DataResult.error(() -> "Unknown " + type.getSimpleName() + " value '" + name + "'");
				}
			},
			value -> value.name().toLowerCase(Locale.ROOT)
		);
	}
}
