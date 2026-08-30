package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

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
 * <p>Both return a height contribution in blocks, so the caller adds them to the
 * horizontal height field rather than to the final density. Folding them in
 * upstream is what keeps the surface rules, the preliminary surface level and
 * the biome depth parameter in agreement with the ground that was actually
 * built; adding a mountain to the final density alone produces a mountain whose
 * summit still thinks it is at sea level.
 */
public final class WorldsmithAnchorFields {
	private WorldsmithAnchorFields() {
	}

	/**
	 * Height added at a distance, as a fraction of the anchor's amplitude.
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

	/** One anchor at an authored position, for the place a player should be able to find. */
	public record Point(int x, int z, int radius, double amplitude, double falloff)
		implements DensityFunction.SimpleFunction {
		private static final MapCodec<Point> DATA_CODEC = RecordCodecBuilder.mapCodec(
			instance -> instance.group(
					Codec.INT.fieldOf("x").forGetter(Point::x),
					Codec.INT.fieldOf("z").forGetter(Point::z),
					Codec.intRange(1, 100_000).fieldOf("radius").forGetter(Point::radius),
					Codec.DOUBLE.fieldOf("amplitude").forGetter(Point::amplitude),
					Codec.doubleRange(0.05, 8.0).fieldOf("falloff").forGetter(Point::falloff)
				)
				.apply(instance, Point::new)
		);
		public static final KeyDispatchDataCodec<Point> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

		@Override
		public double compute(DensityFunction.FunctionContext context) {
			double dx = context.blockX() - (double) this.x;
			double dz = context.blockZ() - (double) this.z;
			return this.amplitude * profile(Math.sqrt(dx * dx + dz * dz), this.radius, this.falloff);
		}

		@Override
		public double minValue() {
			return Math.min(0.0, this.amplitude);
		}

		@Override
		public double maxValue() {
			return Math.max(0.0, this.amplitude);
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
		double amplitude,
		double falloff,
		DensityFunction.NoiseHolder offsetNoise
	) implements DensityFunction {
		private static final MapCodec<Grid> DATA_CODEC = RecordCodecBuilder.mapCodec(
			instance -> instance.group(
					Codec.intRange(64, 1_000_000).fieldOf("spacing").forGetter(Grid::spacing),
					Codec.doubleRange(0.0, 1.0).fieldOf("jitter").forGetter(Grid::jitter),
					Codec.intRange(1, 100_000).fieldOf("radius").forGetter(Grid::radius),
					Codec.DOUBLE.fieldOf("amplitude").forGetter(Grid::amplitude),
					Codec.doubleRange(0.05, 8.0).fieldOf("falloff").forGetter(Grid::falloff),
					DensityFunction.NoiseHolder.CODEC.fieldOf("offset_noise").forGetter(Grid::offsetNoise)
				)
				.apply(instance, Grid::new)
		);
		public static final KeyDispatchDataCodec<Grid> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

		@Override
		public double compute(DensityFunction.FunctionContext context) {
			int x = context.blockX();
			int z = context.blockZ();
			int cellX = Math.floorDiv(x, this.spacing);
			int cellZ = Math.floorDiv(z, this.spacing);
			double nearest = Double.MAX_VALUE;

			for (int offsetX = -1; offsetX <= 1; offsetX++) {
				for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
					int neighbourX = cellX + offsetX;
					int neighbourZ = cellZ + offsetZ;
					// Sampled at the cell's world coordinates, not its index. Cell
					// indices differ by one, which lands adjacent cells on almost
					// the same point of a low-frequency noise and translates the
					// whole lattice instead of jittering each cell of it.
					double sampleX = neighbourX * (double) this.spacing;
					double sampleZ = neighbourZ * (double) this.spacing;
					// Two samples of one noise, separated on the unused Y axis,
					// stand in for two independent offsets.
					double shiftX = this.offsetNoise.getValue(sampleX, 0.0, sampleZ);
					double shiftZ = this.offsetNoise.getValue(sampleX, 640.0, sampleZ);
					double reach = this.jitter * this.spacing * 0.5;
					double centreX = (neighbourX + 0.5) * this.spacing + Mth.clamp(shiftX, -1.0, 1.0) * reach;
					double centreZ = (neighbourZ + 0.5) * this.spacing + Mth.clamp(shiftZ, -1.0, 1.0) * reach;
					double dx = x - centreX;
					double dz = z - centreZ;
					nearest = Math.min(nearest, Math.sqrt(dx * dx + dz * dz));
				}
			}
			return this.amplitude * profile(nearest, this.radius, this.falloff);
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
				this.amplitude,
				this.falloff,
				visitor.visitNoise(this.offsetNoise)
			);
		}

		@Override
		public double minValue() {
			return Math.min(0.0, this.amplitude);
		}

		@Override
		public double maxValue() {
			return Math.max(0.0, this.amplitude);
		}

		@Override
		public KeyDispatchDataCodec<? extends DensityFunction> codec() {
			return CODEC;
		}
	}
}
