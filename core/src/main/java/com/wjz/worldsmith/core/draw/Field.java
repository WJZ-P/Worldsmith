package com.wjz.worldsmith.core.draw;

/** Inside is <=0. Signed-distance fields additionally support predictable shell/offset thickness. */
@FunctionalInterface
public interface Field {
	double sample(double x, double y, double z);
	default Field union(Field b) { return (x, y, z) -> Math.min(sample(x, y, z), b.sample(x, y, z)); }
	default Field intersect(Field b) { return (x, y, z) -> Math.max(sample(x, y, z), b.sample(x, y, z)); }
	default Field subtract(Field b) { return (x, y, z) -> Math.max(sample(x, y, z), -b.sample(x, y, z)); }
	default Field offset(double distance) {
		Fields.finite(distance); return (x, y, z) -> sample(x, y, z) - distance;
	}
	default Field shell(double thickness) {
		Fields.positive(thickness); return (x, y, z) -> Math.abs(sample(x, y, z)) - thickness * 0.5;
	}
	default Field translate(double dx, double dy, double dz) {
		new Vec3d(dx, dy, dz); return (x, y, z) -> sample(x - dx, y - dy, z - dz);
	}
	/** Continuous Y rotation changes geometry only; use a Painter grid transform to rotate block states too. */
	default Field rotateY(double radians) {
		Fields.finite(radians); double c = Math.cos(radians), s = Math.sin(radians);
		return (x, y, z) -> sample(c * x + s * z, y, -s * x + c * z);
	}
	default Field rotateX(double radians) {
		Fields.finite(radians); double c = Math.cos(radians), s = Math.sin(radians);
		return (x, y, z) -> sample(x, c * y + s * z, -s * y + c * z);
	}
	default Field rotateZ(double radians) {
		Fields.finite(radians); double c = Math.cos(radians), s = Math.sin(radians);
		return (x, y, z) -> sample(c * x + s * y, -s * x + c * y, z);
	}
	default Field scale(double scale) {
		Fields.positive(scale); return (x, y, z) -> sample(x / scale, y / scale, z / scale) * scale;
	}
}
