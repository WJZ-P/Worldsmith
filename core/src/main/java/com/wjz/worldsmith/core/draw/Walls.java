package com.wjz.worldsmith.core.draw;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Framed walls, windows, doors and arches around a rectangular footprint.
 *
 * <p>A wall drawn as one flat material is what makes a building read as a box.
 * Vernacular building everywhere has the same few layers - a heavier base
 * course, posts at the corners and at a regular rhythm, a beam along the top,
 * lighter panels between them - and the openings carry the depth: a sill that
 * juts out below a window, a hood above it. These helpers lay those layers in
 * painter-local coordinates. The {@code walls} box is the footprint: its X/Z
 * edges are the wall line, {@code minY} the floor a person stands on, and
 * {@code maxY} the course the roof sits on.
 */
public final class Walls {
	/** A wall of the footprint, named by the way it faces. */
	public enum Side {
		NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);
		final int dx, dz;
		Side(int dx, int dz) { this.dx = dx; this.dz = dz; }
		boolean alongX() { return dx == 0; }
		String inward() { return switch (this) { case NORTH -> "south"; case SOUTH -> "north"; case WEST -> "east"; case EAST -> "west"; }; }
	}

	/**
	 * Materials of a framed wall by role, with posts every {@code bay} blocks at
	 * most (evened out along each wall) and the room inside cleared to AIR unless
	 * {@link #keepingInterior()} is used.
	 */
	public record Frame(BlockStateRef post, BlockStateRef beam, BlockStateRef infill, BlockStateRef plinth, int bay, boolean clearInterior) {
		public Frame {
			Objects.requireNonNull(post); Objects.requireNonNull(beam); Objects.requireNonNull(infill); Objects.requireNonNull(plinth);
			if (bay < 2 || bay > 16) throw new IllegalArgumentException("A bay is 2..16 blocks");
		}
		public static Frame of(String post, String beam, String infill, String plinth) {
			return new Frame(BlockStateRef.parse(post), BlockStateRef.parse(beam), BlockStateRef.parse(infill), BlockStateRef.parse(plinth), 4, true);
		}
		public Frame withBay(int blocks) { return new Frame(post, beam, infill, plinth, blocks, clearInterior); }
		public Frame keepingInterior() { return new Frame(post, beam, infill, plinth, bay, false); }
	}

	/**
	 * A window {@code width} x {@code height} whose lowest pane is {@code sill}
	 * blocks above the floor. With {@code trim} stairs it gets a sill that juts
	 * out beneath it and a hood above; with {@code shutter} trapdoors, a shutter
	 * folded open against the wall on either side.
	 */
	public record Window(int width, int height, int sill, BlockStateRef glass, BlockStateRef trim, BlockStateRef shutter) {
		public Window {
			Objects.requireNonNull(glass);
			if (width < 1 || width > 8 || height < 1 || height > 8 || sill < 0 || sill > 8) throw new IllegalArgumentException("A window is 1..8 by 1..8 with a sill 0..8 above the floor");
			if (trim != null && !trim.id().endsWith("_stairs")) throw new IllegalArgumentException("Window trim must be stairs: " + trim.id());
			if (shutter != null && !shutter.id().endsWith("_trapdoor")) throw new IllegalArgumentException("Window shutters must be trapdoors: " + shutter.id());
		}
		public static Window of(String glass) { return new Window(1, 2, 1, BlockStateRef.parse(glass), null, null); }
		public Window withSize(int width, int height) { return new Window(width, height, sill, glass, trim, shutter); }
		public Window withSill(int blocksAboveFloor) { return new Window(width, height, blocksAboveFloor, glass, trim, shutter); }
		public Window withTrim(String stairs) { return new Window(width, height, sill, glass, BlockStateRef.parse(stairs), shutter); }
		public Window withShutters(String trapdoor) { return new Window(width, height, sill, glass, trim, BlockStateRef.parse(trapdoor)); }
	}

	/**
	 * A round-headed opening {@code width} wide (2..15) whose straight jambs stand
	 * {@code spring} blocks (0..16) before the head curves over in {@code rise}
	 * rows (1..8, by default half the width rounded up, a semicircle; less is a
	 * flatter segmental arch, more a taller one). It cuts {@code depth} blocks
	 * (1..8) into the wall from its line. A {@code surround} block dresses the
	 * jambs and the ring of voussoirs over the head; {@code stairs} round each
	 * step of the head with an inverted stair.
	 */
	public record Arch(int width, int spring, int rise, int depth, BlockStateRef surround, BlockStateRef stairs) {
		public Arch {
			if (width < 2 || width > 15 || spring < 0 || spring > 16 || rise < 1 || rise > 8 || depth < 1 || depth > 8)
				throw new IllegalArgumentException("An arch is 2..15 wide with 0..16 of jamb, a rise of 1..8 and a depth of 1..8");
			if (stairs != null && !stairs.id().endsWith("_stairs")) throw new IllegalArgumentException("Arch stairs must be stairs: " + stairs.id());
		}
		public static Arch of(int width, int spring) { return new Arch(width, spring, (width + 1) / 2, 1, null, null); }
		public Arch withRise(int rows) { return new Arch(width, spring, rows, depth, surround, stairs); }
		public Arch withDepth(int blocks) { return new Arch(width, spring, rise, blocks, surround, stairs); }
		public Arch withSurround(String block) { return new Arch(width, spring, rise, depth, BlockStateRef.parse(block), stairs); }
		public Arch withStairs(String block) { return new Arch(width, spring, rise, depth, surround, BlockStateRef.parse(block)); }

		/** Open rows of the head above the jambs in column {@code i} (0..width-1): cells whose centres lie inside the ellipse. */
		int head(int i) {
			double r = width / 2.0, du = (i + 0.5 - r) / r;
			int rows = 0;
			while (rows < rise && du * du + Math.pow((rows + 0.5) / rise, 2) < 1) rows++;
			return rows;
		}
	}

	private static final Set<String> PILLARS = Set.of("minecraft:bamboo_block", "minecraft:stripped_bamboo_block", "minecraft:basalt",
		"minecraft:polished_basalt", "minecraft:hay_block", "minecraft:bone_block", "minecraft:deepslate", "minecraft:muddy_mangrove_roots");

	private Walls() {}

	/** Four walls on the footprint's edges: posts, a plinth course at the floor, a beam course on top, panels between. */
	public static Painter frame(Painter pen, Box walls, Frame frame) {
		Objects.requireNonNull(pen); Objects.requireNonNull(walls); Objects.requireNonNull(frame);
		if (walls.max().x() - walls.min().x() < 2 || walls.max().z() - walls.min().z() < 2 || walls.max().y() - walls.min().y() < 2)
			throw new IllegalArgumentException("A framed footprint is at least 3 blocks each way and 3 courses tall");
		var posts = new ArrayList<Vec3i>(); var beamsX = new ArrayList<Vec3i>(); var beamsZ = new ArrayList<Vec3i>();
		var plinth = new ArrayList<Vec3i>(); var infill = new ArrayList<Vec3i>();
		var xPosts = posts(walls.min().x(), walls.max().x(), frame.bay());
		var zPosts = posts(walls.min().z(), walls.max().z(), frame.bay());
		for (int y = walls.min().y(); y <= walls.max().y(); y++) {
			for (int x = walls.min().x(); x <= walls.max().x(); x++) for (int z : new int[]{walls.min().z(), walls.max().z()})
				sort(new Vec3i(x, y, z), xPosts.contains(x), y, walls, posts, beamsX, plinth, infill);
			for (int z = walls.min().z() + 1; z < walls.max().z(); z++) for (int x : new int[]{walls.min().x(), walls.max().x()})
				sort(new Vec3i(x, y, z), zPosts.contains(z), y, walls, posts, beamsZ, plinth, infill);
		}
		if (frame.clearInterior()) {
			var inside = new Box(new Vec3i(walls.min().x() + 1, walls.min().y(), walls.min().z() + 1), new Vec3i(walls.max().x() - 1, walls.max().y(), walls.max().z() - 1));
			pen.brush(Brush.air()).fill(inside);
		}
		pen.brush(Brush.solid(frame.infill())).points(infill);
		pen.brush(Brush.solid(frame.plinth())).points(plinth);
		pen.brush(Brush.solid(axis(frame.beam(), "x"))).points(beamsX);
		pen.brush(Brush.solid(axis(frame.beam(), "z"))).points(beamsZ);
		pen.brush(Brush.solid(axis(frame.post(), "y"))).points(posts);
		return pen;
	}

	/**
	 * The floor a person stands on in this footprint: the course below
	 * {@code minY}, under the walls too, so a doorway has a threshold. It fills
	 * only unbuilt or AIR cells: over a storey below it boards the room without
	 * covering that storey's beams, and under a jettied storey it runs out over
	 * them like joists. Lay floors before cutting stairwells through them.
	 */
	public static Painter floor(Painter pen, Box walls, String material) {
		Objects.requireNonNull(pen); Objects.requireNonNull(walls);
		if (walls.max().x() - walls.min().x() < 2 || walls.max().z() - walls.min().z() < 2) throw new IllegalArgumentException("A floor needs a footprint at least 3 blocks each way");
		var boards = new Box(new Vec3i(walls.min().x(), walls.min().y() - 1, walls.min().z()), new Vec3i(walls.max().x(), walls.min().y() - 1, walls.max().z()));
		pen.brush(Brush.solid(BlockStateRef.parse(material))).masked(Mask.solidOnly().not()).fill(boards);
		return pen;
	}

	/** A window centred at {@code center} along the side, with its trim outside the wall line. */
	public static Painter window(Painter pen, Box walls, Side side, int center, Window window) {
		Objects.requireNonNull(pen); Objects.requireNonNull(walls); Objects.requireNonNull(side); Objects.requireNonNull(window);
		int from = center - (window.width() - 1) / 2, to = from + window.width() - 1;
		int floor = walls.min().y(), bottom = floor + window.sill(), top = bottom + window.height() - 1;
		requireAlong(walls, side, from, to);
		if (top >= walls.max().y()) throw new IllegalArgumentException("A window must stay below the wall's top course");
		var panes = new ArrayList<Vec3i>();
		for (int u = from; u <= to; u++) for (int y = bottom; y <= top; y++) panes.add(onWall(walls, side, u, y, 0));
		pen.brush(Brush.solid(window.glass())).points(panes);
		if (window.trim() != null) {
			var sill = new ArrayList<Vec3i>(); var hood = new ArrayList<Vec3i>();
			for (int u = from; u <= to; u++) { sill.add(onWall(walls, side, u, bottom - 1, 1)); hood.add(onWall(walls, side, u, top + 1, 1)); }
			var stair = window.trim().with("facing", side.inward());
			pen.brush(Brush.solid(stair.with("half", "top"))).points(sill);
			pen.brush(Brush.solid(stair.with("half", "bottom"))).points(hood);
		}
		if (window.shutter() != null) {
			// An open trapdoor lies against the side of its cell opposite its facing,
			// so facing outward folds it flat against the wall.
			var leaves = new ArrayList<Vec3i>();
			for (int y = bottom; y <= top; y++) { leaves.add(onWall(walls, side, from - 1, y, 1)); leaves.add(onWall(walls, side, to + 1, y, 1)); }
			var leaf = window.shutter().with("facing", side.name().toLowerCase(Locale.ROOT)).with("open", "true").with("half", "bottom").with("powered", "false");
			pen.brush(Brush.solid(leaf)).points(leaves);
		}
		return pen;
	}

	/** One window centred in every bay of the side wide enough to hold it, skipping bays that contain any {@code skip} position. */
	public static Painter windows(Painter pen, Box walls, Side side, Frame frame, Window window, int... skip) {
		Objects.requireNonNull(frame);
		var posts = side.alongX() ? posts(walls.min().x(), walls.max().x(), frame.bay()) : posts(walls.min().z(), walls.max().z(), frame.bay());
		for (int i = 0; i + 1 < posts.size(); i++) {
			int a = posts.get(i), b = posts.get(i + 1);
			if (b - a - 1 < window.width()) continue;
			boolean skipped = false;
			for (int s : skip) if (s > a && s < b) skipped = true;
			if (!skipped) window(pen, walls, side, a + (b - a) / 2, window);
		}
		return pen;
	}

	/**
	 * An arched opening centred at {@code center} along the side, cut from the
	 * floor up through {@code depth} blocks of wall, with AIR authored in front of
	 * and behind it at walking height so the way through is open.
	 */
	public static Painter arch(Painter pen, Box walls, Side side, int center, Arch arch) {
		Objects.requireNonNull(pen); Objects.requireNonNull(walls); Objects.requireNonNull(side); Objects.requireNonNull(arch);
		int from = center - (arch.width() - 1) / 2, to = from + arch.width() - 1, floor = walls.min().y(), spring = floor + arch.spring();
		requireAlong(walls, side, from, to);
		int[] head = new int[arch.width()];
		int top = spring - 1;
		for (int i = 0; i < head.length; i++) { head[i] = arch.head(i); top = Math.max(top, spring + head[i] - 1); }
		if (top + (arch.surround() != null ? 1 : 0) >= walls.max().y()) throw new IllegalArgumentException("An arch, with its surround, must stay below the wall's top course");
		java.util.function.BiPredicate<Integer, Integer> open = (u, y) -> u >= from && u <= to && y >= floor && y < spring + head[u - from];
		var opening = new ArrayList<Vec3i>(); var ring = new ArrayList<Vec3i>(); var passage = new ArrayList<Vec3i>();
		var westward = new ArrayList<Vec3i>(); var eastward = new ArrayList<Vec3i>();
		for (int d = 0; d < arch.depth(); d++) {
			for (int u = from - 1; u <= to + 1; u++) for (int y = floor; y <= top + 1; y++) {
				if (open.test(u, y)) { opening.add(onWall(walls, side, u, y, -d)); continue; }
				boolean touches = false;
				for (int du = -1; du <= 1 && !touches; du++) for (int dy = -1; dy <= 1; dy++) if (open.test(u + du, y + dy)) { touches = true; break; }
				if (!touches) continue;
				int i = u - from;
				// The first closed cell over a column whose inner neighbour rises higher is a step of the head.
				boolean step = i >= 0 && i < head.length && y == spring + head[i] && y >= spring
					&& (2 * i + 1 < head.length ? head[i + 1] > head[i] : 2 * i + 1 > head.length && head[i - 1] > head[i]);
				if (step && arch.stairs() != null) (2 * i + 1 < head.length ? westward : eastward).add(onWall(walls, side, u, y, -d));
				else if (arch.surround() != null) ring.add(onWall(walls, side, u, y, -d));
			}
		}
		for (int u = from; u <= to; u++) for (int y = floor; y <= floor + 1; y++) {
			passage.add(onWall(walls, side, u, y, 1)); passage.add(onWall(walls, side, u, y, -arch.depth()));
		}
		pen.brush(Brush.air()).points(opening);
		pen.brush(Brush.air()).points(passage);
		if (!ring.isEmpty()) pen.brush(Brush.solid(arch.surround())).points(ring);
		if (arch.stairs() != null) {
			// An inverted stair facing the jamb leaves its lower inner quarter open, rounding the step.
			var stair = arch.stairs().with("half", "top").with("shape", "straight");
			pen.brush(Brush.solid(stair.with("facing", side.alongX() ? "west" : "north"))).points(westward);
			pen.brush(Brush.solid(stair.with("facing", side.alongX() ? "east" : "south"))).points(eastward);
		}
		return pen;
	}

	/**
	 * A two-block door in the side at {@code center}, opening onto AIR on both
	 * sides so the way in is walkable. It faces inward, as if hung from outside.
	 */
	public static Painter door(Painter pen, Box walls, Side side, int center, String door) {
		Objects.requireNonNull(pen); Objects.requireNonNull(walls); Objects.requireNonNull(side);
		var state = BlockStateRef.parse(door);
		if (!state.id().endsWith("_door")) throw new IllegalArgumentException("A door must be a door block: " + state.id());
		requireAlong(walls, side, center, center);
		int floor = walls.min().y();
		var passage = new ArrayList<Vec3i>();
		for (int y = floor; y <= floor + 1; y++) { passage.add(onWall(walls, side, center, y, 1)); passage.add(onWall(walls, side, center, y, -1)); }
		pen.brush(Brush.air()).points(passage);
		var hung = state.with("facing", side.inward()).with("hinge", "left").with("open", "false").with("powered", "false");
		pen.brush(Brush.solid(hung.with("half", "lower"))).points(List.of(onWall(walls, side, center, floor, 0)));
		pen.brush(Brush.solid(hung.with("half", "upper"))).points(List.of(onWall(walls, side, center, floor + 1, 0)));
		return pen;
	}

	/** Evenly spaced post positions from {@code a} to {@code b}, both ends included, at most {@code bay} apart. */
	static List<Integer> posts(int a, int b, int bay) {
		int span = b - a, bays = Math.max(1, (span + bay - 1) / bay);
		var result = new ArrayList<Integer>();
		for (int i = 0; i <= bays; i++) result.add(a + Math.round((float) span * i / bays));
		return result;
	}

	private static void sort(Vec3i at, boolean post, int y, Box walls, List<Vec3i> posts, List<Vec3i> beams, List<Vec3i> plinth, List<Vec3i> infill) {
		if (post) posts.add(at);
		else if (y == walls.max().y()) beams.add(at);
		else if (y == walls.min().y()) plinth.add(at);
		else infill.add(at);
	}

	/** Logs, stems and pillars run along their member; anything else keeps the state it was given. */
	private static BlockStateRef axis(BlockStateRef state, String axis) {
		if (state.properties().containsKey("axis")) return state;
		String id = state.id();
		boolean oriented = id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae") || id.endsWith("_pillar") || PILLARS.contains(id);
		return oriented ? state.with("axis", axis) : state;
	}

	private static Vec3i onWall(Box walls, Side side, int along, int y, int outward) {
		return switch (side) {
			case NORTH -> new Vec3i(along, y, walls.min().z() - outward);
			case SOUTH -> new Vec3i(along, y, walls.max().z() + outward);
			case WEST -> new Vec3i(walls.min().x() - outward, y, along);
			case EAST -> new Vec3i(walls.max().x() + outward, y, along);
		};
	}

	private static void requireAlong(Box walls, Side side, int from, int to) {
		int min = side.alongX() ? walls.min().x() : walls.min().z(), max = side.alongX() ? walls.max().x() : walls.max().z();
		if (from <= min || to >= max) throw new IllegalArgumentException("An opening must sit inside the " + side + " wall, clear of its corners");
	}
}
