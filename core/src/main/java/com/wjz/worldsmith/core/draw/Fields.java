package com.wjz.worldsmith.core.draw;

/** Geometry building blocks, not architectural presets. All distances are measured from voxel centres. */
public final class Fields {
	private Fields() {}
	static void finite(double n) { if (!Double.isFinite(n)) throw new IllegalArgumentException("Value must be finite"); }
	static void positive(double n) { if (!(n > 0) || !Double.isFinite(n)) throw new IllegalArgumentException("Value must be finite and positive"); }
	static void radius(double n) { if (!(n >= 0) || !Double.isFinite(n)) throw new IllegalArgumentException("Radius must be finite and non-negative"); }
	public static Field sphere(Vec3d centre, double radius) {
		radius(radius); return (x, y, z) -> Math.hypot(Math.hypot(x - centre.x(), y - centre.y()), z - centre.z()) - radius;
	}
	public static Field box(Vec3d centre, Vec3d halfSize) {
		positive(halfSize.x()); positive(halfSize.y()); positive(halfSize.z());
		return (x, y, z) -> {
			double a = Math.abs(x - centre.x()) - halfSize.x(), b = Math.abs(y - centre.y()) - halfSize.y(), c = Math.abs(z - centre.z()) - halfSize.z();
			return Math.hypot(Math.hypot(Math.max(a, 0), Math.max(b, 0)), Math.max(c, 0)) + Math.min(Math.max(a, Math.max(b, c)), 0);
		};
	}
	public static Field box(Box bounds) {
		return box(new Vec3d(bounds.min().x() + (bounds.width() - 1) * .5, bounds.min().y() + (bounds.height() - 1) * .5,
			bounds.min().z() + (bounds.depth() - 1) * .5), new Vec3d(bounds.width() * .5, bounds.height() * .5, bounds.depth() * .5));
	}
	/** Ellipsoid level set, not an exact signed distance; shell thickness is not uniform in world units. */
	public static Field ellipsoid(Vec3d centre, Vec3d radii) {
		positive(radii.x()); positive(radii.y()); positive(radii.z());
		return (x, y, z) -> {
			double a = (x - centre.x()) / radii.x(), b = (y - centre.y()) / radii.y(), c = (z - centre.z()) / radii.z();
			return Math.hypot(Math.hypot(a, b), c) - 1;
		};
	}
	public static Field capsule(Vec3d a, Vec3d b, double radius) {
		radius(radius); Vec3d delta = b.subtract(a); double length2 = delta.dot(delta);
		if (!Double.isFinite(length2)) throw new IllegalArgumentException("Segment is too large");
		return (x, y, z) -> {
			double px = x - a.x(), py = y - a.y(), pz = z - a.z();
			double t = length2 == 0 ? 0 : Math.max(0, Math.min(1, (px * delta.x() + py * delta.y() + pz * delta.z()) / length2));
			return Math.hypot(Math.hypot(px - delta.x() * t, py - delta.y() * t), pz - delta.z() * t) - radius;
		};
	}
	/** Flat-capped cylinder along any continuous axis. */
	public static Field cylinder(Vec3d a, Vec3d b, double radius) {
		radius(radius); Vec3d delta = b.subtract(a); double length = delta.length(); positive(length);
		Vec3d axis = delta.multiply(1 / length);
		return (x, y, z) -> {
			double px = x - a.x(), py = y - a.y(), pz = z - a.z(), t = px * axis.x() + py * axis.y() + pz * axis.z();
			double radial = Math.hypot(Math.hypot(px - axis.x() * t, py - axis.y() * t), pz - axis.z() * t) - radius;
			double end = Math.abs(t - length * .5) - length * .5;
			return Math.hypot(Math.max(radial, 0), Math.max(end, 0)) + Math.min(Math.max(radial, end), 0);
		};
	}
	/** Capped cone/frustum level set. Supports zero radius at either end; not an exact distance. */
	public static Field frustum(Vec3d a, Vec3d b, double bottomRadius, double topRadius) {
		radius(bottomRadius); radius(topRadius); Vec3d delta = b.subtract(a); double length = delta.length(); positive(length);
		Vec3d axis = delta.multiply(1 / length);
		return (x, y, z) -> {
			double px = x - a.x(), py = y - a.y(), pz = z - a.z(), t = px * axis.x() + py * axis.y() + pz * axis.z();
			double r = bottomRadius + (topRadius - bottomRadius) * Math.max(0, Math.min(1, t / length));
			return Math.max(Math.hypot(Math.hypot(px - axis.x() * t, py - axis.y() * t), pz - axis.z() * t) - r, Math.max(-t, t - length));
		};
	}
	public static Field torus(Vec3d centre, double majorRadius, double tubeRadius) {
		positive(majorRadius); positive(tubeRadius);
		return (x, y, z) -> Math.hypot(Math.hypot(x - centre.x(), z - centre.z()) - majorRadius, y - centre.y()) - tubeRadius;
	}
}
