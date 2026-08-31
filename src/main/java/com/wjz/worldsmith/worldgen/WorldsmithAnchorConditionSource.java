package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * A surface condition that is true inside a ring of one anchor's influence.
 *
 * <p>Surface rules see a position and nothing else, so the anchor's geometry is
 * carried in the condition rather than looked up. That is the same trade the
 * hydrology condition already makes, and it is why both are written from one
 * source: nothing checks at load time that a copied constant still agrees with
 * the terrain it came from.
 */
record WorldsmithAnchorConditionSource(
	double minInfluence,
	double maxInfluence,
	int radius,
	double falloff,
	boolean scattered,
	int x,
	int z,
	int spacing,
	double jitter
) implements SurfaceRules.ConditionSource {
	static final MapCodec<WorldsmithAnchorConditionSource> CODEC = RecordCodecBuilder.mapCodec(
		instance -> instance.group(
				Codec.DOUBLE.fieldOf("min_influence").forGetter(WorldsmithAnchorConditionSource::minInfluence),
				Codec.DOUBLE.fieldOf("max_influence").forGetter(WorldsmithAnchorConditionSource::maxInfluence),
				Codec.INT.fieldOf("radius").forGetter(WorldsmithAnchorConditionSource::radius),
				Codec.DOUBLE.fieldOf("falloff").forGetter(WorldsmithAnchorConditionSource::falloff),
				Codec.BOOL.fieldOf("scattered").forGetter(WorldsmithAnchorConditionSource::scattered),
				Codec.INT.fieldOf("x").forGetter(WorldsmithAnchorConditionSource::x),
				Codec.INT.fieldOf("z").forGetter(WorldsmithAnchorConditionSource::z),
				Codec.INT.fieldOf("spacing").forGetter(WorldsmithAnchorConditionSource::spacing),
				Codec.DOUBLE.fieldOf("jitter").forGetter(WorldsmithAnchorConditionSource::jitter)
			)
			.apply(instance, WorldsmithAnchorConditionSource::new)
	);

	@Override
	public MapCodec<WorldsmithAnchorConditionSource> codec() {
		return CODEC;
	}

	@Override
	public SurfaceRules.Condition apply(SurfaceRules.Context context) {
		WorldsmithAnchorFields.NoiseSampler jitterNoise = this.scattered
			? context.randomState.getOrCreateNoise(WorldsmithAnchorFields.JITTER_NOISE)::getValue
			: null;
		WorldsmithAnchorFields.NoiseSampler silhouetteNoise =
			context.randomState.getOrCreateNoise(WorldsmithAnchorFields.SILHOUETTE_NOISE)::getValue;

		return new SurfaceRules.Condition() {
			private long lastPosition = Long.MIN_VALUE;
			private boolean lastResult;

			@Override
			public boolean test() {
				long position = ((long) context.blockX << 32) ^ (context.blockZ & 0xFFFF_FFFFL);
				if (position != this.lastPosition) {
					this.lastPosition = position;
					this.lastResult = matches(context.blockX, context.blockZ, jitterNoise, silhouetteNoise);
				}
				return this.lastResult;
			}
		};
	}

	private boolean matches(
		int blockX,
		int blockZ,
		WorldsmithAnchorFields.NoiseSampler jitterNoise,
		WorldsmithAnchorFields.NoiseSampler silhouetteNoise
	) {
		double distance = this.scattered
			? WorldsmithAnchorFields.latticeDistance(blockX, blockZ, this.spacing, this.jitter, jitterNoise)
			: WorldsmithAnchorFields.pointDistance(blockX, blockZ, this.x, this.z);
		double influence = WorldsmithAnchorFields.profile(
			WorldsmithAnchorFields.warpDistance(blockX, blockZ, distance, this.radius, silhouetteNoise),
			this.radius,
			this.falloff
		);
		return influence > this.minInfluence && influence <= this.maxInfluence;
	}
}
