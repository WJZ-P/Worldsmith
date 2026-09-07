package com.wjz.worldsmith.core.draw;

/** Continuous modelling coordinates, in blocks. Integer values are voxel centres. */
public record Vec3d(double x, double y, double z) {
	public Vec3d {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
			throw new IllegalArgumentException("Coordinates must be finite");
	}
	public Vec3d add(Vec3d v) { return new Vec3d(x + v.x, y + v.y, z + v.z); }
	public Vec3d subtract(Vec3d v) { return new Vec3d(x - v.x, y - v.y, z - v.z); }
	public Vec3d multiply(double scale) { return new Vec3d(x * scale, y * scale, z * scale); }
	public double dot(Vec3d v) { return x * v.x + y * v.y + z * v.z; }
	public double length() { return Math.hypot(Math.hypot(x, y), z); }
	public double distance(Vec3d v) { return subtract(v).length(); }
	public Vec3d lerp(Vec3d v, double t) { return multiply(1 - t).add(v.multiply(t)); }
	public Vec3i rounded() { return new Vec3i(rounded(x), rounded(y), rounded(z)); }
	private static int rounded(double value) { return Math.toIntExact(Math.round(value)); }
}
