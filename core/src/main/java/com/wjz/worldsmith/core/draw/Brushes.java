package com.wjz.worldsmith.core.draw;

import java.util.List;
import java.util.Objects;

/** Deterministic coordinate-keyed brushes: repeated calls and loop order do not move the pattern. */
public final class Brushes {
	private Brushes() {}
	public record Weighted(BlockStateRef state, int weight) {
		public Weighted { Objects.requireNonNull(state); if (weight < 1) throw new IllegalArgumentException("Weight must be positive"); }
	}
	public static Brush checker(BlockStateRef a, BlockStateRef b, int scale) {
		Objects.requireNonNull(a); Objects.requireNonNull(b); scale(scale);
		return (p, previous) -> (((long) Math.floorDiv(p.x(), scale) + Math.floorDiv(p.z(), scale)) & 1) == 0 ? a : b;
	}
	public static Brush weighted(long seed, int scale, List<Weighted> choices) {
		scale(scale); var values = List.copyOf(choices);
		if (values.isEmpty() || values.size() > 256) throw new IllegalArgumentException("Use 1..256 weighted states");
		long total = values.stream().mapToLong(Weighted::weight).sum();
		return (p, previous) -> {
			long selected = (long) (noise(seed, p, scale) * total);
			for (var value : values) { if (selected < value.weight) return value.state; selected -= value.weight; }
			return values.getLast().state;
		};
	}
	/** Probability chooses cells to paint; untouched cells keep their previous value. */
	public static Brush probability(Brush brush, double probability, long seed, int scale) {
		Objects.requireNonNull(brush); scale(scale);
		if (!(probability >= 0 && probability <= 1)) throw new IllegalArgumentException("Probability must be 0..1");
		return (p, previous) -> noise(seed, p, scale) < probability ? brush.sample(p, previous) : null;
	}
	public static Brush layers(int firstY, int layerHeight, List<BlockStateRef> states) {
		scale(layerHeight); var palette = List.copyOf(states);
		if (palette.isEmpty()) throw new IllegalArgumentException("Layers need a palette");
		return (p, previous) -> palette.get((int) Math.floorMod(Math.floorDiv((long) p.y() - firstY, layerHeight), palette.size()));
	}
	private static void scale(int scale) { if (scale < 1) throw new IllegalArgumentException("Brush scale must be positive"); }
	private static double noise(long seed, Vec3i p, int scale) {
		long n = seed ^ Math.floorDiv(p.x(), scale) * 0x9E3779B97F4A7C15L
			^ Math.floorDiv(p.y(), scale) * 0xD1B54A32D192ED03L ^ Math.floorDiv(p.z(), scale) * 0x94D049BB133111EBL;
		n = (n ^ (n >>> 30)) * 0xBF58476D1CE4E5B9L;
		n = (n ^ (n >>> 27)) * 0x94D049BB133111EBL;
		return ((n ^ (n >>> 31)) >>> 11) * 0x1.0p-53;
	}
}
