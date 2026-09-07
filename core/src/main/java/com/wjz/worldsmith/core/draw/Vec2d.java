package com.wjz.worldsmith.core.draw;

/** A polygon point in the local X/Z plane. */
public record Vec2d(double x, double z) {
	public Vec2d { Fields.finite(x); Fields.finite(z); }
}
