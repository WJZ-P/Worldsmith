package com.wjz.worldsmith.core.draw;

import java.util.Objects;
import java.util.Optional;

/** Inclusive voxel bounds, not a Minecraft structure-size restriction. */
public record Box(Vec3i min, Vec3i max) {
	public Box {
		Objects.requireNonNull(min); Objects.requireNonNull(max);
		if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z())
			throw new IllegalArgumentException("Box endpoints must be ordered");
		Math.toIntExact((long) max.x() - min.x() + 1);
		Math.toIntExact((long) max.y() - min.y() + 1);
		Math.toIntExact((long) max.z() - min.z() + 1);
	}
	public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
		return new Box(new Vec3i(x1, y1, z1), new Vec3i(x2, y2, z2));
	}
	public static Box sized(int width, int height, int depth) {
		if (width < 1 || height < 1 || depth < 1) throw new IllegalArgumentException("Dimensions must be positive");
		return of(0, 0, 0, width - 1, height - 1, depth - 1);
	}
	public static Box covering(Vec3d min, Vec3d max) {
		return of(integer(Math.floor(min.x())), integer(Math.floor(min.y())), integer(Math.floor(min.z())),
			integer(Math.ceil(max.x())), integer(Math.ceil(max.y())), integer(Math.ceil(max.z())));
	}
	private static int integer(double n) {
		if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw new IllegalArgumentException("Coordinate overflow");
		return (int) n;
	}
	public int width() { return (int) ((long) max.x() - min.x() + 1); }
	public int height() { return (int) ((long) max.y() - min.y() + 1); }
	public int depth() { return (int) ((long) max.z() - min.z() + 1); }
	public long volume() { return Math.multiplyExact(Math.multiplyExact((long) width(), height()), depth()); }
	public boolean contains(Vec3i p) {
		return p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y() && p.z() >= min.z() && p.z() <= max.z();
	}
	public Optional<Box> intersect(Box other) {
		int x1 = Math.max(min.x(), other.min.x()), y1 = Math.max(min.y(), other.min.y()), z1 = Math.max(min.z(), other.min.z());
		int x2 = Math.min(max.x(), other.max.x()), y2 = Math.min(max.y(), other.max.y()), z2 = Math.min(max.z(), other.max.z());
		return x1 > x2 || y1 > y2 || z1 > z2 ? Optional.empty() : Optional.of(of(x1, y1, z1, x2, y2, z2));
	}
	public Box expand(int amount) {
		if (amount < 0) throw new IllegalArgumentException("Expansion must be non-negative");
		return new Box(min.add(-amount, -amount, -amount), max.add(amount, amount, amount));
	}
}
