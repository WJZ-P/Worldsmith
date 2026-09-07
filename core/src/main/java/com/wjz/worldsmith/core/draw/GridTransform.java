package com.wjz.worldsmith.core.draw;

/** Exact horizontal grid isometry: mirror X, then clockwise Y rotation, then translation. */
public record GridTransform(int quarterTurns, boolean mirrorX, Vec3i translation) {
	public static final GridTransform IDENTITY = new GridTransform(0, false, Vec3i.ZERO);
	public GridTransform {
		quarterTurns = Math.floorMod(quarterTurns, 4);
		java.util.Objects.requireNonNull(translation);
	}
	public static GridTransform translate(int x, int y, int z) { return new GridTransform(0, false, new Vec3i(x, y, z)); }
	public static GridTransform rotateY(int turns) { return new GridTransform(turns, false, Vec3i.ZERO); }
	public static GridTransform reflectX() { return new GridTransform(0, true, Vec3i.ZERO); }
	public static GridTransform reflectZ() { return new GridTransform(2, true, Vec3i.ZERO); }
	public GridTransform orientation() { return new GridTransform(quarterTurns, mirrorX, Vec3i.ZERO); }
	public Vec3i apply(Vec3i p) {
		int x = mirrorX ? Math.negateExact(p.x()) : p.x(), z = p.z();
		Vec3i rotated = switch (quarterTurns) {
			case 1 -> new Vec3i(Math.negateExact(z), p.y(), x);
			case 2 -> new Vec3i(Math.negateExact(x), p.y(), Math.negateExact(z));
			case 3 -> new Vec3i(z, p.y(), Math.negateExact(x));
			default -> new Vec3i(x, p.y(), z);
		};
		return rotated.add(translation);
	}
	/** Apply this transform first, and outer second. */
	public GridTransform andThen(GridTransform outer) {
		int turns = outer.quarterTurns + (outer.mirrorX ? -quarterTurns : quarterTurns);
		return new GridTransform(turns, mirrorX ^ outer.mirrorX, outer.apply(translation));
	}
	public GridTransform inverse() {
		var rotation = new GridTransform(mirrorX ? quarterTurns : -quarterTurns, mirrorX, Vec3i.ZERO);
		var shift = rotation.apply(translation);
		return new GridTransform(rotation.quarterTurns, mirrorX,
			new Vec3i(Math.negateExact(shift.x()), Math.negateExact(shift.y()), Math.negateExact(shift.z())));
	}
	public Box apply(Box box) {
		Vec3i a = apply(box.min()), b = apply(box.max());
		return Box.of(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
			Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
	}
}
