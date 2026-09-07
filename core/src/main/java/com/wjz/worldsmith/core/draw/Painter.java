package com.wjz.worldsmith.core.draw;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Predicate;

/**
 * Immutable drawing view over a mutable canvas. Coordinates, masks and brushes are local to this view.
 * Drawing is clipped to the canvas (and optional clip); shape boundaries are sampled at integer block centres.
 */
public final class Painter {
	private final DrawCanvas canvas;
	private final Brush brush;
	private final Mask mask;
	private final GridTransform transform;
	private final Optional<Box> clip;

	Painter(DrawCanvas canvas, Brush brush, Mask mask, GridTransform transform, Optional<Box> clip) {
		this.canvas = canvas; this.brush = Objects.requireNonNull(brush); this.mask = mask; this.transform = transform; this.clip = clip;
	}
	public Painter brush(Brush brush) { return new Painter(canvas, brush, mask, transform, clip); }
	public Painter masked(Mask extra) { return new Painter(canvas, brush, mask.and(extra), transform, clip); }
	public Painter clipped(Box localBox) {
		return new Painter(canvas, brush, mask, transform, clip.flatMap(b -> b.intersect(transform.apply(localBox))));
	}
	/** Child coordinates are transformed into this view's coordinates before the parent's transform. */
	public Painter transformed(GridTransform localToParent) {
		return new Painter(canvas, brush, mask, localToParent.andThen(transform), clip);
	}
	public Painter translate(int x, int y, int z) { return transformed(GridTransform.translate(x, y, z)); }
	public Painter rotateY(int turns) { return transformed(GridTransform.rotateY(turns)); }
	public Painter mirrorX() { return transformed(GridTransform.reflectX()); }
	public Painter mirrorZ() { return transformed(GridTransform.reflectZ()); }

	public Painter set(int x, int y, int z) { return points(List.of(new Vec3i(x, y, z))); }
	public Painter fill(Box box) { return scan(box, p -> true, 1); }
	/** Clears to explicit air, respecting this view's mask/clip. */
	public Painter clear(Box box) { brush(Brush.air()).fill(box); return this; }
	/** Removes authored cells, restoring KEEP, rather than clearing the eventual world. */
	public Painter forget(Box box) {
		localClip(box).ifPresent(area -> canvas.edit(area.volume(), batch -> visit(area, p -> {
			Vec3i world = transform.apply(p); DrawBlock old = canvas.get(world).orElse(null);
			if (mask.allows(p, old)) batch.set(world, null);
		})));
		return this;
	}
	/** Draws the six faces only. Interior cells remain unchanged; use clear explicitly for a hollow room. */
	public Painter shell(Box box, int thickness) {
		if (thickness < 1) throw new IllegalArgumentException("Shell thickness must be positive");
		return scan(box, p -> (long) p.x() - box.min().x() < thickness || (long) box.max().x() - p.x() < thickness
			|| (long) p.y() - box.min().y() < thickness || (long) box.max().y() - p.y() < thickness
			|| (long) p.z() - box.min().z() < thickness || (long) box.max().z() - p.z() < thickness, 1);
	}
	/** Free-form implicit geometry. The caller supplies a finite rasterization window. */
	public Painter field(Box box, Field field) {
		Objects.requireNonNull(field);
		return scan(box, p -> {
			double distance = field.sample(p.x(), p.y(), p.z());
			if (Double.isNaN(distance)) throw new IllegalArgumentException("Field returned NaN at " + p);
			return distance <= 1e-9;
		}, 1);
	}
	/** Arbitrary Java predicate geometry, useful for custom lattices, patterns and cross-sections. */
	public Painter volume(Box box, Predicate<Vec3i> inside) { return scan(box, Objects.requireNonNull(inside), 1); }
	public Painter sphere(Vec3d centre, double radius) {
		Fields.radius(radius); return field(around(centre, centre, radius), Fields.sphere(centre, radius));
	}
	public Painter ellipsoid(Vec3d centre, Vec3d radii) {
		Field field = Fields.ellipsoid(centre, radii);
		return field(Box.covering(centre.subtract(radii), centre.add(radii)), field);
	}
	public Painter cylinder(Vec3d from, Vec3d to, double radius) {
		Fields.radius(radius); return field(around(from, to, radius), Fields.cylinder(from, to, radius));
	}
	public Painter frustum(Vec3d from, Vec3d to, double bottomRadius, double topRadius) {
		Field field = Fields.frustum(from, to, bottomRadius, topRadius);
		return field(around(from, to, Math.max(bottomRadius, topRadius)), field);
	}
	public Painter torus(Vec3d centre, double majorRadius, double tubeRadius) {
		Field field = Fields.torus(centre, majorRadius, tubeRadius);
		return field(Box.covering(new Vec3d(centre.x() - majorRadius - tubeRadius, centre.y() - tubeRadius, centre.z() - majorRadius - tubeRadius),
			new Vec3d(centre.x() + majorRadius + tubeRadius, centre.y() + tubeRadius, centre.z() + majorRadius + tubeRadius)), field);
	}

	/** Polygon prism, including boundary centres; concave and self-intersecting outlines use even/odd fill. */
	public Painter extrude(List<Vec2d> outline, int minY, int maxY) {
		var points = List.copyOf(outline);
		if (points.size() < 3 || points.size() > canvas.limits().maxPathSamples()) throw new IllegalArgumentException("Invalid polygon point count");
		double minX = points.stream().mapToDouble(Vec2d::x).min().orElseThrow(), maxX = points.stream().mapToDouble(Vec2d::x).max().orElseThrow();
		double minZ = points.stream().mapToDouble(Vec2d::z).min().orElseThrow(), maxZ = points.stream().mapToDouble(Vec2d::z).max().orElseThrow();
		Box box = Box.covering(new Vec3d(minX, minY, minZ), new Vec3d(maxX, maxY, maxZ));
		return scan(box, p -> polygonContains(points, p.x(), p.z()), points.size());
	}
	private static boolean polygonContains(List<Vec2d> points, double x, double z) {
		boolean inside = false;
		for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
			Vec2d a = points.get(j), b = points.get(i);
			double cross = (x - a.x()) * (b.z() - a.z()) - (z - a.z()) * (b.x() - a.x());
			if (Math.abs(cross) <= 1e-9 && x >= Math.min(a.x(), b.x()) && x <= Math.max(a.x(), b.x())
				&& z >= Math.min(a.z(), b.z()) && z <= Math.max(a.z(), b.z())) return true;
			if ((a.z() > z) != (b.z() > z) && x < (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) inside = !inside;
		}
		return inside;
	}

	/** y=f(x,z), rounded down. thickness=0 fills from the window floor; positive values draw a surface coat. */
	public Painter heightField(Box window, DoubleBinaryOperator height, int thickness) {
		Objects.requireNonNull(height);
		if (thickness < 0) throw new IllegalArgumentException("Thickness must be non-negative");
		localClip(window).ifPresent(area -> canvas.edit(area.volume(), batch -> {
			for (long x = area.min().x(); x <= area.max().x(); x++) for (long z = area.min().z(); z <= area.max().z(); z++) {
				double value = height.applyAsDouble(x, z); Fields.finite(value);
				if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Height outside integer range");
				long top = (long) Math.floor(value), bottom = thickness == 0 ? window.min().y() : top - thickness + 1;
				for (long y = Math.max(area.min().y(), bottom); y <= Math.min(area.max().y(), top); y++)
					batch.paint(new Vec3i((int) x, (int) y, (int) z), transform, brush, mask);
			}
		}));
		return this;
	}

	public Painter points(List<Vec3i> points) {
		var copy = List.copyOf(points);
		canvas.edit(copy.size(), batch -> {
			for (var p : copy) if (insideClip(p)) batch.paint(p, transform, brush, mask);
		});
		return this;
	}
	/** Six-connected centreline; radius adds a round capsule stroke around each segment. */
	public Painter line(Vec3d from, Vec3d to, double radius) { return polyline(List.of(from, to), radius); }
	public Painter polyline(List<Vec3d> path, double radius) {
		Fields.radius(radius); var copy = List.copyOf(path);
		if (copy.size() < 2 || copy.size() > canvas.limits().maxPathSamples()) throw new IllegalArgumentException("Invalid path sample count");
		var centreline = new ArrayList<Vec3i>();
		var areas = new ArrayList<Optional<Box>>(); long work = 0;
		for (int i = 1; i < copy.size(); i++) {
			connect(copy.get(i - 1).rounded(), copy.get(i).rounded(), centreline, canvas.limits().maxPathSamples());
			Optional<Box> area = radius == 0 ? Optional.empty() : localClip(around(copy.get(i - 1), copy.get(i), radius));
			areas.add(area); if (area.isPresent()) work = Math.addExact(work, area.get().volume());
		}
		work = Math.addExact(work, centreline.size());
		canvas.edit(work, batch -> {
			for (var p : centreline) if (insideClip(p)) batch.paint(p, transform, brush, mask);
			for (int i = 0; i < areas.size(); i++) {
				Field segment = Fields.capsule(copy.get(i), copy.get(i + 1), radius);
				areas.get(i).ifPresent(area -> visit(area, p -> {
					if (segment.sample(p.x(), p.y(), p.z()) <= 1e-9) batch.paint(p, transform, brush, mask);
				}));
			}
		});
		return this;
	}
	/** Quadratic or cubic Bezier. Sampling density follows the control polygon, not an arbitrary fixed count. */
	public Painter bezier(List<Vec3d> controls, double radius) {
		var points = List.copyOf(controls);
		if (points.size() < 3 || points.size() > 4) throw new IllegalArgumentException("Bezier needs 3 or 4 controls");
		double length = 0; for (int i = 1; i < points.size(); i++) length += points.get(i - 1).distance(points.get(i));
		int samples = samples(length); var path = new ArrayList<Vec3d>(samples + 1);
		for (int i = 0; i <= samples; i++) {
			double t = (double) i / samples; var row = new ArrayList<>(points);
			while (row.size() > 1) {
				var next = new ArrayList<Vec3d>(); for (int j = 1; j < row.size(); j++) next.add(row.get(j - 1).lerp(row.get(j), t)); row = next;
			}
			path.add(row.getFirst());
		}
		return polyline(path, radius);
	}
	/** Circular arc in the X/Z plane; use fields or a custom polyline for other planes. */
	public Painter arc(Vec3d centre, double radius, double fromRadians, double toRadians, double strokeRadius) {
		Fields.radius(radius); Fields.finite(fromRadians); Fields.finite(toRadians);
		int samples = samples(Math.abs(toRadians - fromRadians) * radius); var path = new ArrayList<Vec3d>(samples + 1);
		for (int i = 0; i <= samples; i++) {
			double angle = fromRadians + (toRadians - fromRadians) * i / samples;
			path.add(new Vec3d(centre.x() + radius * Math.cos(angle), centre.y(), centre.z() + radius * Math.sin(angle)));
		}
		return polyline(path, strokeRadius);
	}
	private int samples(double length) {
		Fields.finite(length); double count = Math.max(1, Math.ceil(length * 2));
		if (count + 1 > canvas.limits().maxPathSamples()) throw new IllegalStateException("Path sample budget exceeded");
		return (int) count;
	}
	private static void connect(Vec3i a, Vec3i b, List<Vec3i> result, int max) {
		long dx = (long) b.x() - a.x(), dy = (long) b.y() - a.y(), dz = (long) b.z() - a.z();
		long work = Math.abs(dx) + Math.abs(dy) + Math.abs(dz) + 1;
		if (work > max - result.size()) throw new IllegalStateException("Connected path exceeds sample budget");
		long steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))); Vec3i p = a; result.add(p);
		for (long i = 1; i <= steps; i++) {
			var target = new Vec3i(Math.toIntExact(a.x() + Math.round((double) dx * i / steps)),
				Math.toIntExact(a.y() + Math.round((double) dy * i / steps)), Math.toIntExact(a.z() + Math.round((double) dz * i / steps)));
			while (p.x() != target.x()) { p = p.add(Integer.compare(target.x(), p.x()), 0, 0); result.add(p); }
			while (p.y() != target.y()) { p = p.add(0, Integer.compare(target.y(), p.y()), 0); result.add(p); }
			while (p.z() != target.z()) { p = p.add(0, 0, Integer.compare(target.z(), p.z())); result.add(p); }
		}
	}

	/** Copies authored cells with their existing block orientation. Does not copy/rename named anchors. */
	public Painter paste(DrawStructure source, GridTransform placement, boolean includeAir) {
		GridTransform total = placement.andThen(transform);
		canvas.edit(source.voxels().size(), batch -> {
			for (var voxel : source.voxels()) {
				if (!includeAir && voxel.block().state().isAir()) continue;
				Vec3i p = placement.apply(voxel.position()), world = total.apply(voxel.position());
				if (clip.isPresent() && clip.get().contains(world) && mask.allows(p, canvas.get(world).orElse(null)))
					batch.set(world, voxel.block().transformed(total));
			}
		});
		return this;
	}
	/** Places the source's minimum corner at the given local coordinate. */
	public Painter paste(DrawStructure source, Vec3i at) {
		Vec3i min = source.bounds().min();
		return paste(source, GridTransform.translate(Math.subtractExact(at.x(), min.x()), Math.subtractExact(at.y(), min.y()), Math.subtractExact(at.z(), min.z())), true);
	}
	private Painter scan(Box box, Predicate<Vec3i> included, long cost) {
		localClip(box).ifPresent(area -> canvas.edit(Math.multiplyExact(area.volume(), cost), batch -> visit(area, p -> {
			if (included.test(p)) batch.paint(p, transform, brush, mask);
		})));
		return this;
	}
	private Optional<Box> localClip(Box box) { return clip.flatMap(b -> box.intersect(transform.inverse().apply(b))); }
	private boolean insideClip(Vec3i local) { return clip.isPresent() && clip.get().contains(transform.apply(local)); }
	private static void visit(Box area, java.util.function.Consumer<Vec3i> action) {
		for (long y = area.min().y(); y <= area.max().y(); y++) for (long z = area.min().z(); z <= area.max().z(); z++)
			for (long x = area.min().x(); x <= area.max().x(); x++) action.accept(new Vec3i((int) x, (int) y, (int) z));
	}
	private static Box around(Vec3d a, Vec3d b, double radius) {
		return Box.covering(new Vec3d(Math.min(a.x(), b.x()) - radius, Math.min(a.y(), b.y()) - radius, Math.min(a.z(), b.z()) - radius),
			new Vec3d(Math.max(a.x(), b.x()) + radius, Math.max(a.y(), b.y()) + radius, Math.max(a.z(), b.z()) + radius));
	}
}
