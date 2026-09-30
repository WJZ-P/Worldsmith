package com.wjz.worldsmith.core.draw;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pitched roofs laid from stairs and slabs over a wall footprint.
 *
 * <p>The roof decides whether a Minecraft building reads as architecture, and it
 * is the part a drawing program most easily gets wrong: every stair needs the
 * facing that climbs toward the ridge, and every hip corner an inner or outer
 * shape. A roof here is computed in painter-local coordinates, like every other
 * shape, and its stair shapes are resolved with Minecraft's own neighbour rule,
 * so the preview shows the corners the placed structure will have.
 */
public final class Roofs {
	/** The axis the ridge of a gable roof runs along. */
	public enum Ridge { X, Z }

	/** One material's three forms, for example deepslate_tile_stairs, deepslate_tile_slab and deepslate_tiles. */
	public record Material(BlockStateRef stairs, BlockStateRef slab, BlockStateRef solid) {
		public Material {
			Objects.requireNonNull(stairs); Objects.requireNonNull(slab); Objects.requireNonNull(solid);
			if (!stairs.id().endsWith("_stairs")) throw new IllegalArgumentException("Roof stairs must be a stairs block: " + stairs.id());
			if (!slab.id().endsWith("_slab")) throw new IllegalArgumentException("Roof slab must be a slab block: " + slab.id());
		}
		public static Material of(String stairs, String slab, String solid) {
			return new Material(BlockStateRef.parse(stairs), BlockStateRef.parse(slab), BlockStateRef.parse(solid));
		}
	}

	/**
	 * How far the eaves reach past the walls (0..3), whether the eave corners lift,
	 * what closes the walls up to the roof - the gable ends and, under a deep
	 * overhang, the strip between the wall top and the roof (null leaves both
	 * open) - whether the space under the roof inside the walls is authored as AIR
	 * so terrain cannot fill the attic, and whether the roof climbs half a block
	 * per block in slabs instead of a full block in stairs.
	 */
	public record Options(int overhang, boolean flaredCorners, BlockStateRef gableWall, boolean clearAttic, boolean lowPitch) {
		public Options {
			if (overhang < 0 || overhang > 3) throw new IllegalArgumentException("Roof overhang is 0..3 blocks");
			if (flaredCorners && lowPitch) throw new IllegalArgumentException("Flared corners lift a stair eave; a low-pitched roof is laid in slabs");
		}
		public static Options defaults() { return new Options(1, false, null, true, false); }
		public Options withOverhang(int blocks) { return new Options(blocks, flaredCorners, gableWall, clearAttic, lowPitch); }
		public Options withFlaredCorners() { return new Options(overhang, true, gableWall, clearAttic, lowPitch); }
		public Options withGableWall(String block) { return new Options(overhang, flaredCorners, BlockStateRef.parse(block), clearAttic, lowPitch); }
		public Options keepingAttic() { return new Options(overhang, flaredCorners, gableWall, false, lowPitch); }
		public Options withLowPitch() { return new Options(overhang, flaredCorners, gableWall, clearAttic, true); }

		/** Whole blocks the roof has risen {@code row} rows in from its eave. */
		int rise(int row) { return lowPitch ? row / 2 : row; }
	}

	private enum Facing {
		NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);
		final int dx, dz;
		Facing(int dx, int dz) { this.dx = dx; this.dz = dz; }
		Facing opposite() { return values()[(ordinal() + 2) % 4]; }
		Facing counterClockwise() { return values()[(ordinal() + 3) % 4]; }
		boolean sameAxis(Facing other) { return dx == 0 == (other.dx == 0); }
		String id() { return name().toLowerCase(java.util.Locale.ROOT); }
	}

	private record Stair(Facing facing, boolean top) {}

	private Roofs() {}

	/**
	 * A two-slope roof whose ridge runs along {@code ridge}, sitting on the top
	 * course of {@code walls}. The slopes climb one block per block of run.
	 */
	public static Painter gable(Painter pen, Box walls, Ridge ridge, Material material, Options options) {
		Objects.requireNonNull(pen); Objects.requireNonNull(walls); Objects.requireNonNull(ridge); Objects.requireNonNull(material); Objects.requireNonNull(options);
		if (options.flaredCorners()) throw new IllegalArgumentException("Flared corners belong to hip roofs; a gable's eaves run straight to its gable ends");
		var plan = new Plan(material, walls);
		int o = options.overhang(), y0 = walls.max().y() + 1;
		// Local frame: u runs along the ridge, v across it; cells map back through `at`.
		boolean alongX = ridge == Ridge.X;
		int u0 = (alongX ? walls.min().x() : walls.min().z()) - o, u1 = (alongX ? walls.max().x() : walls.max().z()) + o;
		int v0 = (alongX ? walls.min().z() : walls.min().x()) - o, v1 = (alongX ? walls.max().z() : walls.max().x()) + o;
		Facing up0 = alongX ? Facing.SOUTH : Facing.EAST, up1 = up0.opposite();
		for (int i = 0; v0 + i <= v1 - i; i++) {
			int a = v0 + i, b = v1 - i, y = y0 + options.rise(i);
			for (int u = u0; u <= u1; u++) {
				if (options.lowPitch()) { plan.slab(at(alongX, u, y, a), i % 2 == 1); plan.slab(at(alongX, u, y, b), i % 2 == 1); }
				else if (a == b) plan.slab(at(alongX, u, y, a), false);
				else {
					plan.stair(at(alongX, u, y, a), up0, false);
					plan.stair(at(alongX, u, y, b), up1, false);
					if (a + 1 == b) { plan.slab(at(alongX, u, y + 1, a), false); plan.slab(at(alongX, u, y + 1, b), false); }
				}
			}
		}
		int wu0 = alongX ? walls.min().x() : walls.min().z(), wu1 = alongX ? walls.max().x() : walls.max().z();
		int wv0 = alongX ? walls.min().z() : walls.min().x(), wv1 = alongX ? walls.max().z() : walls.max().x();
		for (int v = wv0; v <= wv1; v++) {
			int rise = options.rise(Math.min(v - v0, v1 - v));
			for (int y = y0; y < y0 + rise; y++) {
				if (options.gableWall() != null) {
					plan.fill(at(alongX, wu0, y, v), options.gableWall()); plan.fill(at(alongX, wu1, y, v), options.gableWall());
					if (v == wv0 || v == wv1) for (int u = wu0 + 1; u < wu1; u++) plan.fill(at(alongX, u, y, v), options.gableWall());
				}
				if (options.clearAttic() && v > wv0 && v < wv1) for (int u = wu0 + 1; u < wu1; u++) plan.air(at(alongX, u, y, v));
			}
		}
		return plan.draw(pen);
	}

	/**
	 * A roof sloping down on all four sides. On a square footprint it closes to a
	 * point, the pavilion roof; otherwise it closes to a short ridge of slabs.
	 */
	public static Painter hip(Painter pen, Box walls, Material material, Options options) {
		Objects.requireNonNull(pen); Objects.requireNonNull(walls); Objects.requireNonNull(material); Objects.requireNonNull(options);
		var plan = new Plan(material, walls);
		int o = options.overhang(), y0 = walls.max().y() + 1;
		int x0 = walls.min().x() - o, x1 = walls.max().x() + o, z0 = walls.min().z() - o, z1 = walls.max().z() + o;
		for (int i = 0; x0 + i <= x1 - i && z0 + i <= z1 - i; i++) {
			int ax0 = x0 + i, ax1 = x1 - i, az0 = z0 + i, az1 = z1 - i, y = y0 + options.rise(i);
			if (options.lowPitch() || ax0 == ax1 || az0 == az1) {
				// A slab ring; the innermost ring fills the remaining ridge or point.
				boolean last = !(x0 + i + 1 <= x1 - i - 1 && z0 + i + 1 <= z1 - i - 1);
				for (int x = ax0; x <= ax1; x++) for (int z = az0; z <= az1; z++)
					if (last || x == ax0 || x == ax1 || z == az0 || z == az1) plan.slab(new Vec3i(x, y, z), options.lowPitch() && i % 2 == 1);
				continue;
			}
			for (int x = ax0; x <= ax1; x++) { plan.stair(new Vec3i(x, y, az0), Facing.SOUTH, false); plan.stair(new Vec3i(x, y, az1), Facing.NORTH, false); }
			for (int z = az0 + 1; z < az1; z++) { plan.stair(new Vec3i(ax0, y, z), Facing.EAST, false); plan.stair(new Vec3i(ax1, y, z), Facing.WEST, false); }
		}
		if (options.flaredCorners()) {
			// Each eave corner lifts: an inverted stair curves its underside up from the
			// eave line and a slab tops it half a block above the neighbouring tiles.
			// Both keep their shape when the placed structure recomputes stair corners.
			for (int[] c : new int[][]{{x0, z0, 1}, {x1, z0, 1}, {x0, z1, -1}, {x1, z1, -1}}) {
				plan.stair(new Vec3i(c[0], y0, c[1]), c[2] > 0 ? Facing.SOUTH : Facing.NORTH, true);
				plan.slab(new Vec3i(c[0], y0 + 1, c[1]), false);
			}
		}
		for (int x = walls.min().x(); x <= walls.max().x(); x++) for (int z = walls.min().z(); z <= walls.max().z(); z++) {
			int rise = options.rise(Math.min(Math.min(x - x0, x1 - x), Math.min(z - z0, z1 - z)));
			boolean edge = x == walls.min().x() || x == walls.max().x() || z == walls.min().z() || z == walls.max().z();
			for (int y = y0; y < y0 + rise; y++) {
				if (edge && options.gableWall() != null) plan.fill(new Vec3i(x, y, z), options.gableWall());
				if (!edge && options.clearAttic()) plan.air(new Vec3i(x, y, z));
			}
		}
		return plan.draw(pen);
	}

	/**
	 * A single slope falling toward the {@code low} side, the lean-to of a porch,
	 * a covered walk or a wing against a larger wall. The eaves overhang the low
	 * side and both ends; the high side stops at its wall line so it can meet the
	 * wall it leans on. A gable wall, if given, closes both ends and the high
	 * side under the slope, and the low side up to a deep overhang.
	 */
	public static Painter shed(Painter pen, Box walls, Walls.Side low, Material material, Options options) {
		Objects.requireNonNull(pen); Objects.requireNonNull(walls); Objects.requireNonNull(low); Objects.requireNonNull(material); Objects.requireNonNull(options);
		if (options.flaredCorners()) throw new IllegalArgumentException("Flared corners belong to hip roofs");
		var plan = new Plan(material, walls);
		int o = options.overhang(), y0 = walls.max().y() + 1;
		// Local frame: u runs along the eave, v climbs from the low eave to the high wall line.
		boolean alongX = low.alongX(), lowAtMin = low == Walls.Side.NORTH || low == Walls.Side.WEST;
		int wu0 = alongX ? walls.min().x() : walls.min().z(), wu1 = alongX ? walls.max().x() : walls.max().z();
		int wv0 = alongX ? walls.min().z() : walls.min().x(), wv1 = alongX ? walls.max().z() : walls.max().x();
		int eave = lowAtMin ? wv0 - o : wv1 + o, high = lowAtMin ? wv1 : wv0, step = lowAtMin ? 1 : -1;
		Facing up = alongX ? (lowAtMin ? Facing.SOUTH : Facing.NORTH) : (lowAtMin ? Facing.EAST : Facing.WEST);
		for (int i = 0, v = eave; ; i++, v += step) {
			int y = y0 + options.rise(i);
			for (int u = wu0 - o; u <= wu1 + o; u++) {
				if (options.lowPitch()) plan.slab(at(alongX, u, y, v), i % 2 == 1);
				else plan.stair(at(alongX, u, y, v), up, false);
			}
			if (v == high) break;
		}
		for (int v = wv0; v <= wv1; v++) {
			int rise = options.rise(Math.abs(v - eave));
			for (int y = y0; y < y0 + rise; y++) {
				if (options.gableWall() != null) {
					plan.fill(at(alongX, wu0, y, v), options.gableWall()); plan.fill(at(alongX, wu1, y, v), options.gableWall());
					if (v == wv0 || v == wv1) for (int u = wu0 + 1; u < wu1; u++) plan.fill(at(alongX, u, y, v), options.gableWall());
				}
				if (options.clearAttic() && v > wv0 && v < wv1) for (int u = wu0 + 1; u < wu1; u++) plan.air(at(alongX, u, y, v));
			}
		}
		return plan.draw(pen);
	}

	private static Vec3i at(boolean alongX, int u, int y, int v) { return alongX ? new Vec3i(u, y, v) : new Vec3i(v, y, u); }

	/** Collects a roof locally, resolves stair shapes, then writes it with one brush per state. */
	private static final class Plan {
		private final Material material;
		private final Box walls;
		private final Map<Vec3i, Stair> stairs = new LinkedHashMap<>();
		private final Map<Vec3i, BlockStateRef> blocks = new LinkedHashMap<>();
		private final Set<Vec3i> gableWalls = new HashSet<>();
		private final List<Vec3i> air = new ArrayList<>();

		Plan(Material material, Box walls) { this.material = material; this.walls = walls; }

		void stair(Vec3i at, Facing facing, boolean top) { blocks.remove(at); stairs.put(at, new Stair(facing, top)); }
		void slab(Vec3i at, boolean top) { stairs.remove(at); blocks.put(at, material.slab().with("type", top ? "top" : "bottom")); }
		void fill(Vec3i at, BlockStateRef state) { if (!stairs.containsKey(at) && blocks.putIfAbsent(at, state) == null) gableWalls.add(at); }
		void air(Vec3i at) { air.add(at); }

		/** The top course of the walls, or a gable wall this roof filled. */
		private boolean wall(Vec3i at) {
			boolean edge = at.x() == walls.min().x() || at.x() == walls.max().x() || at.z() == walls.min().z() || at.z() == walls.max().z();
			boolean within = at.x() >= walls.min().x() && at.x() <= walls.max().x() && at.z() >= walls.min().z() && at.z() <= walls.max().z();
			return at.y() == walls.max().y() && edge && within || gableWalls.contains(at);
		}

		Painter draw(Painter pen) {
			// A top slab resting on a wall would leave a half-block slit above it; a full
			// block closes the slit and shows the same surface from above.
			var upper = material.slab().with("type", "top");
			blocks.replaceAll((at, state) -> state.equals(upper) && wall(new Vec3i(at.x(), at.y() - 1, at.z())) ? material.solid() : state);
			Map<BlockStateRef, List<Vec3i>> byState = new LinkedHashMap<>();
			stairs.forEach((at, stair) -> byState.computeIfAbsent(material.stairs()
				.with("facing", stair.facing().id()).with("half", stair.top() ? "top" : "bottom").with("shape", shape(at, stair)), k -> new ArrayList<>()).add(at));
			blocks.forEach((at, state) -> byState.computeIfAbsent(state, k -> new ArrayList<>()).add(at));
			List<Vec3i> open = air.stream().filter(p -> !stairs.containsKey(p) && !blocks.containsKey(p)).distinct().toList();
			if (!open.isEmpty()) pen.brush(Brush.air()).points(open);
			byState.forEach((state, points) -> pen.brush(Brush.solid(state)).points(points));
			return pen;
		}

		/** Minecraft's StairBlock rule, applied to this roof's own stairs. */
		private String shape(Vec3i at, Stair stair) {
			Facing f = stair.facing();
			Stair behind = stairs.get(offset(at, f));
			if (behind != null && behind.top() == stair.top() && !behind.facing().sameAxis(f) && canTakeShape(at, stair, behind.facing().opposite()))
				return behind.facing() == f.counterClockwise() ? "outer_left" : "outer_right";
			Stair front = stairs.get(offset(at, f.opposite()));
			if (front != null && front.top() == stair.top() && !front.facing().sameAxis(f) && canTakeShape(at, stair, front.facing()))
				return front.facing() == f.counterClockwise() ? "inner_left" : "inner_right";
			return "straight";
		}

		private boolean canTakeShape(Vec3i at, Stair stair, Facing towards) {
			Stair neighbour = stairs.get(offset(at, towards));
			return neighbour == null || neighbour.facing() != stair.facing() || neighbour.top() != stair.top();
		}

		private static Vec3i offset(Vec3i at, Facing f) { return new Vec3i(at.x() + f.dx, at.y(), at.z() + f.dz); }
	}
}
