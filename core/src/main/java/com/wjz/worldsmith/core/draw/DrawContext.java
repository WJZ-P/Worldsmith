package com.wjz.worldsmith.core.draw;

import java.util.Map;
import java.util.SplittableRandom;

/** Geometry inputs only; deliberately contains no game, player, filesystem or chunk objects. */
public record DrawContext(long seed, Map<String, String> parameters, DrawLimits limits) {
	public DrawContext { parameters = Map.copyOf(parameters); java.util.Objects.requireNonNull(limits); }
	public SplittableRandom random() { return new SplittableRandom(seed); }
	public DrawCanvas canvas(Box bounds) { return new DrawCanvas(bounds, limits); }
}
