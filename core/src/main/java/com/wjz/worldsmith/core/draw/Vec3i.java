package com.wjz.worldsmith.core.draw;

/** Integer block centres. X=east, Y=up, Z=south. Negative coordinates are valid. */
public record Vec3i(int x, int y, int z) implements Comparable<Vec3i> {
	public static final Vec3i ZERO = new Vec3i(0, 0, 0);
	public Vec3i add(int dx, int dy, int dz) {
		return new Vec3i(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
	}
	public Vec3i add(Vec3i other) { return add(other.x, other.y, other.z); }
	public Vec3d asDouble() { return new Vec3d(x, y, z); }
	@Override public int compareTo(Vec3i other) {
		int order = Integer.compare(y, other.y);
		if (order == 0) order = Integer.compare(z, other.z);
		return order == 0 ? Integer.compare(x, other.x) : order;
	}
}
