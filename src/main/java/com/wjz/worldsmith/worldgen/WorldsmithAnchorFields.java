package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Density functions that know where they are.
 *
 * <p>Every other terrain control is statistical: noise gives a world that is the
 * same everywhere in distribution, so nothing can be <em>here specifically</em>.
 * That is not a tuning limit. Of the density-function types Minecraft registers,
 * none exposes the raw X or Z of the point being evaluated, so "distance from
 * that place" cannot be written at all with the vanilla vocabulary.
 *
 * <p>These two supply it. Both answer the same question - how far is the nearest
 * anchor - and differ only in how the anchors are found, which is exactly the
 * split vanilla already makes for structures between a fixed set of positions
 * and a jittered lattice that repeats forever.
 *
 * <p>Both return an influence from zero to one rather than a height. The same
 * field decides how far the ground rises, which biome is chosen, which surface
 * materials are painted and where a band may act, so it cannot carry a height
 * inside it; the caller scales it where a height is what is wanted.
 *
 * <p>That caller adds the scaled field to the horizontal height field rather
 * than to the final density. Folding it in upstream is what keeps the surface
 * rules, the preliminary surface level and the biome depth parameter in
 * agreement with the ground that was actually built; adding a mountain to the
 * final density alone produces a summit that still thinks it is at sea level.
 */
public final class WorldsmithAnchorFields {
	private WorldsmithAnchorFields() {
	}

	/**
	 * Influence at a distance, one at the centre and zero past the radius.
	 *
	 * <p>{@code falloff} is the single shape knob: below one gives a plateau
	 * with steep sides, one gives a dome, above one gives a spire standing in a
	 * wide skirt.
	 */
	static double profile(double distance, int radius, double falloff) {
		if (radius <= 0) {
			return 0.0;
		}
		double t = distance / radius;
		if (t >= 1.0) {
			return 0.0;
		}
		return Math.pow(1.0 - t * t, falloff);
	}

	/** Reads a noise field; lets the lattice be shared by a density function and a surface rule. */
	public interface NoiseSampler {
		double get(double x, double y, double z);
	}

	/**
	 * The noises an anchor uses, named once.
	 *
	 * <p>The terrain compiler builds the density function and the surface rule
	 * builds a condition over the same geometry, and each reaches for its noise
	 * separately. Naming them here is what stops the two from drifting onto
	 * different fields and placing the same landmark in two different spots.
	 */
	public static final ResourceKey<NormalNoise.NoiseParameters> JITTER_NOISE = Noises.SPAGHETTI_3D_RARITY;
	public static final ResourceKey<NormalNoise.NoiseParameters> SILHOUETTE_NOISE = Noises.SURFACE_SECONDARY;

	/** How deeply the outline is bitten into, and how broad the bites are. */
	private static final double SILHOUETTE_WARP = 0.5;
	private static final double SILHOUETTE_WARP_SCALE = 180.0;

	/**
	 * Distance to an anchor, with the outline broken up.
	 *
	 * <p>A radial profile draws a perfect circle, which reads as machined rather
	 * than as a place. This distorts the distance instead of the profile, so one
	 * change reaches everything the influence feeds: the ground it raises, the
	 * biome it biases, the rings of material it wears and the bands it bounds.
	 *
	 * <p>The distortion only ever makes the distance larger, so a shape is
	 * bitten into and never bulges out. That keeps the extent inside the
	 * declared radius, which the scattered lattice depends on: instances are
	 * found by searching the nine cells around a point, and that is only exact
	 * while nothing reaches past a neighbouring cell.
	 */
	public static double warpDistance(int x, int z, double distance, int radius, NoiseSampler warp) {
		if (warp == null) {
			return distance;
		}
		double frequency = SILHOUETTE_WARP_SCALE / Math.max(64.0, (double) radius);
		double sample = Mth.clamp(warp.get(x * frequency, 0.0, z * frequency), -1.0, 1.0);
		return distance * (1.0 + SILHOUETTE_WARP * (sample + 1.0) * 0.5);
	}

	/**
	 * Distance to the nearest anchor of a jittered lattice.
	 *
	 * <p>Shared rather than written twice because the terrain compiler and the
	 * surface condition both have to place every instance in exactly the same
	 * spot; two copies of this arithmetic would be two chances to disagree, and
	 * the symptom would be summit materials painted beside the summit.
	 */
	public static double latticeDistance(int x, int z, int spacing, double jitter, NoiseSampler noise) {
		int cellX = Math.floorDiv(x, spacing);
		int cellZ = Math.floorDiv(z, spacing);
		double nearest = Double.MAX_VALUE;

		for (int offsetX = -1; offsetX <= 1; offsetX++) {
			for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
				int neighbourX = cellX + offsetX;
				int neighbourZ = cellZ + offsetZ;
				// Sampled at the cell's world coordinates, not its index. Cell
				// indices differ by one, which lands adjacent cells on almost the
				// same point of a low-frequency noise and translates the whole
				// lattice instead of jittering each cell of it.
				double sampleX = neighbourX * (double) spacing;
				double sampleZ = neighbourZ * (double) spacing;
				// Two samples of one noise, separated on the unused Y axis, stand
				// in for two independent offsets.
				double shiftX = noise.get(sampleX, 0.0, sampleZ);
				double shiftZ = noise.get(sampleX, 640.0, sampleZ);
				double reach = jitter * spacing * 0.5;
				double centreX = (neighbourX + 0.5) * spacing + Mth.clamp(shiftX, -1.0, 1.0) * reach;
				double centreZ = (neighbourZ + 0.5) * spacing + Mth.clamp(shiftZ, -1.0, 1.0) * reach;
				double dx = x - centreX;
				double dz = z - centreZ;
				nearest = Math.min(nearest, Math.sqrt(dx * dx + dz * dz));
			}
		}
		return nearest;
	}

	/** Distance to a single anchor at an authored position. */
	public static double pointDistance(int x, int z, int anchorX, int anchorZ) {
		double dx = x - (double) anchorX;
		double dz = z - (double) anchorZ;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** One anchor at an authored position, for the place a player should be able to find. */
	public record Point(int x, int z, int radius, double falloff, DensityFunction.NoiseHolder silhouetteNoise)
		implements DensityFunction {
		private static final MapCodec<Point> DATA_CODEC = RecordCodecBuilder.mapCodec(
			instance -> instance.group(
					Codec.INT.fieldOf("x").forGetter(Point::x),
					Codec.INT.fieldOf("z").forGetter(Point::z),
					Codec.intRange(1, 100_000).fieldOf("radius").forGetter(Point::radius),
					Codec.doubleRange(0.05, 8.0).fieldOf("falloff").forGetter(Point::falloff),
					DensityFunction.NoiseHolder.CODEC.fieldOf("silhouette_noise").forGetter(Point::silhouetteNoise)
				)
				.apply(instance, Point::new)
		);
		public static final KeyDispatchDataCodec<Point> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

		@Override
		public double compute(DensityFunction.FunctionContext context) {
			int x = context.blockX();
			int z = context.blockZ();
			double distance = pointDistance(x, z, this.x, this.z);
			return profile(
				warpDistance(x, z, distance, this.radius, this.silhouetteNoise::getValue),
				this.radius,
				this.falloff
			);
		}

		@Override
		public void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
			contextProvider.fillAllDirectly(output, this);
		}

		@Override
		public DensityFunction mapChildren(DensityFunction.Visitor visitor) {
			return new Point(
				this.x,
				this.z,
				this.radius,
				this.falloff,
				visitor.visitNoise(this.silhouetteNoise)
			);
		}

		@Override
		public double minValue() {
			return 0.0;
		}

		@Override
		public double maxValue() {
			return 1.0;
		}

		@Override
		public KeyDispatchDataCodec<? extends DensityFunction> codec() {
			return CODEC;
		}
	}

	/**
	 * Anchors repeating forever on a jittered lattice.
	 *
	 * <p>One cell holds one anchor, offset from its centre by a noise sample so
	 * the result does not read as a grid. Only the nine cells around a point can
	 * reach it, because the compiler refuses a radius larger than half the
	 * spacing, so the search is nine distance tests and no more however large
	 * the world grows.
	 *
	 * <p>The jitter comes from a noise rather than the world seed because a
	 * density function is never handed the seed - {@link DensityFunction.Visitor}
	 * offers noise and nothing else. Sampling a seeded noise at cell coordinates
	 * gets the same property with no new plumbing.
	 */
	public record Grid(
		int spacing,
		double jitter,
		int radius,
		double falloff,
		DensityFunction.NoiseHolder offsetNoise,
		DensityFunction.NoiseHolder silhouetteNoise
	) implements DensityFunction {
		private static final MapCodec<Grid> DATA_CODEC = RecordCodecBuilder.mapCodec(
			instance -> instance.group(
					Codec.intRange(64, 1_000_000).fieldOf("spacing").forGetter(Grid::spacing),
					Codec.doubleRange(0.0, 1.0).fieldOf("jitter").forGetter(Grid::jitter),
					Codec.intRange(1, 100_000).fieldOf("radius").forGetter(Grid::radius),
					Codec.doubleRange(0.05, 8.0).fieldOf("falloff").forGetter(Grid::falloff),
					DensityFunction.NoiseHolder.CODEC.fieldOf("offset_noise").forGetter(Grid::offsetNoise),
					DensityFunction.NoiseHolder.CODEC.fieldOf("silhouette_noise").forGetter(Grid::silhouetteNoise)
				)
				.apply(instance, Grid::new)
		);
		public static final KeyDispatchDataCodec<Grid> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

		@Override
		public double compute(DensityFunction.FunctionContext context) {
			int x = context.blockX();
			int z = context.blockZ();
			double distance = latticeDistance(x, z, this.spacing, this.jitter, this.offsetNoise::getValue);
			return profile(
				warpDistance(x, z, distance, this.radius, this.silhouetteNoise::getValue),
				this.radius,
				this.falloff
			);
		}

		@Override
		public void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
			contextProvider.fillAllDirectly(output, this);
		}

		@Override
		public DensityFunction mapChildren(DensityFunction.Visitor visitor) {
			return new Grid(
				this.spacing,
				this.jitter,
				this.radius,
				this.falloff,
				visitor.visitNoise(this.offsetNoise),
				visitor.visitNoise(this.silhouetteNoise)
			);
		}

		@Override
		public double minValue() {
			return 0.0;
		}

		@Override
		public double maxValue() {
			return 1.0;
		}

		@Override
		public KeyDispatchDataCodec<? extends DensityFunction> codec() {
			return CODEC;
		}
	}
}
