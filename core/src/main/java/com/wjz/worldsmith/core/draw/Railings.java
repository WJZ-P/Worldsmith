package com.wjz.worldsmith.core.draw;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Railings round decks, balconies and bridges, and battlements on wall tops.
 *
 * <p>An open edge reads as unfinished and, for a player, as a fall. A railing
 * here runs one course above a deck's surface, with posts at a rhythm, and
 * writes the connection states its fences, walls or panes would take in the
 * world, so the preview and the placed structure agree before any neighbour
 * update runs.
 */
public final class Railings {
	/**
	 * A rail block (a fence, wall, pane, iron bars or any solid for a low
	 * parapet) and an optional post block standing at the corners and at most
	 * {@code spacing} (2..16) apart.
	 */
	public record Railing(BlockStateRef rail, BlockStateRef post, int spacing) {
		public Railing {
			Objects.requireNonNull(rail);
			if (spacing < 2 || spacing > 16) throw new IllegalArgumentException("Railing posts stand 2..16 blocks apart");
		}
		public static Railing of(String rail) { return new Railing(BlockStateRef.parse(rail), null, 4); }
		public Railing withPosts(String post, int spacing) { return new Railing(rail, BlockStateRef.parse(post), spacing); }
	}

	private Railings() {}

	/**
	 * A railing round the edge of {@code deck}, one course above its top surface,
	 * leaving out every position in {@code openings} (railing-level cells where a
	 * stair or bridge arrives).
	 */
	public static Painter around(Painter pen, Box deck, Railing railing, Vec3i... openings) {
		Objects.requireNonNull(pen); Objects.requireNonNull(deck); Objects.requireNonNull(railing);
		if (deck.width() < 2 || deck.depth() < 2) throw new IllegalArgumentException("A railed deck is at least 2 blocks each way");
		int y = deck.max().y() + 1, x0 = deck.min().x(), x1 = deck.max().x(), z0 = deck.min().z(), z1 = deck.max().z();
		var skip = Set.of(openings);
		Map<Vec3i, Boolean> cells = new LinkedHashMap<>(); // position -> is a post
		var xPosts = Walls.posts(x0, x1, railing.spacing()); var zPosts = Walls.posts(z0, z1, railing.spacing());
		for (int x = x0; x <= x1; x++) for (int z : new int[]{z0, z1}) cells.put(new Vec3i(x, y, z), xPosts.contains(x));
		for (int z = z0 + 1; z < z1; z++) for (int x : new int[]{x0, x1}) cells.put(new Vec3i(x, y, z), zPosts.contains(z));
		skip.forEach(cells::remove);
		return draw(pen, cells, railing);
	}

	/** A straight railing from {@code from} to {@code to}, which must share a Y and an X or Z. */
	public static Painter line(Painter pen, Vec3i from, Vec3i to, Railing railing) {
		Objects.requireNonNull(pen); Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(railing);
		if (from.y() != to.y() || from.x() != to.x() && from.z() != to.z()) throw new IllegalArgumentException("A railing line runs straight along X or Z at one height");
		boolean alongX = from.z() == to.z();
		int a = alongX ? Math.min(from.x(), to.x()) : Math.min(from.z(), to.z()), b = alongX ? Math.max(from.x(), to.x()) : Math.max(from.z(), to.z());
		var posts = Walls.posts(a, b, railing.spacing());
		Map<Vec3i, Boolean> cells = new LinkedHashMap<>();
		for (int u = a; u <= b; u++) cells.put(alongX ? new Vec3i(u, from.y(), from.z()) : new Vec3i(from.x(), from.y(), u), posts.contains(u));
		return draw(pen, cells, railing);
	}

	/**
	 * Battlements on the wall line of {@code walls}, one course above its top:
	 * merlons at the corners and on every other block between, crenels open.
	 */
	public static Painter battlements(Painter pen, Box walls, String block) {
		Objects.requireNonNull(pen); Objects.requireNonNull(walls);
		if (walls.width() < 3 || walls.depth() < 3) throw new IllegalArgumentException("Battlements need a footprint at least 3 blocks each way");
		int y = walls.max().y() + 1, x0 = walls.min().x(), x1 = walls.max().x(), z0 = walls.min().z(), z1 = walls.max().z();
		var merlons = new ArrayList<Vec3i>();
		for (int x = x0; x <= x1; x++) if ((x - x0) % 2 == 0 || x == x1) { merlons.add(new Vec3i(x, y, z0)); merlons.add(new Vec3i(x, y, z1)); }
		for (int z = z0 + 1; z < z1; z++) if ((z - z0) % 2 == 0) { merlons.add(new Vec3i(x0, y, z)); merlons.add(new Vec3i(x1, y, z)); }
		pen.brush(Brush.solid(BlockStateRef.parse(block))).points(merlons);
		return pen;
	}

	/** Writes each cell with the connections it takes to its neighbours in the same railing. */
	private static Painter draw(Painter pen, Map<Vec3i, Boolean> cells, Railing railing) {
		Set<Vec3i> all = new HashSet<>(cells.keySet());
		Map<BlockStateRef, List<Vec3i>> byState = new LinkedHashMap<>();
		cells.forEach((at, post) -> {
			var base = post && railing.post() != null ? railing.post() : railing.rail();
			byState.computeIfAbsent(connected(base, at, all, post), k -> new ArrayList<>()).add(at);
		});
		byState.forEach((state, points) -> pen.brush(Brush.solid(state)).points(points));
		return pen;
	}

	private static BlockStateRef connected(BlockStateRef state, Vec3i at, Set<Vec3i> all, boolean post) {
		String id = state.id();
		boolean wall = id.endsWith("_wall"), bars = id.endsWith("_fence") || id.endsWith("_pane") || id.equals("minecraft:iron_bars");
		if (!wall && !bars) return state;
		String[] sides = {"north", "south", "west", "east"};
		int[][] steps = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
		var result = state;
		int links = 0;
		for (int i = 0; i < 4; i++) {
			boolean joined = all.contains(new Vec3i(at.x() + steps[i][0], at.y(), at.z() + steps[i][1]));
			if (joined) links++;
			result = result.with(sides[i], wall ? (joined ? "low" : "none") : Boolean.toString(joined));
		}
		// A wall shows its post at ends, corners and declared posts; a straight run is a plain low wall.
		if (wall) {
			boolean straight = links == 2 && (all.contains(new Vec3i(at.x() + 1, at.y(), at.z())) && all.contains(new Vec3i(at.x() - 1, at.y(), at.z()))
				|| all.contains(new Vec3i(at.x(), at.y(), at.z() + 1)) && all.contains(new Vec3i(at.x(), at.y(), at.z() - 1)));
			result = result.with("up", Boolean.toString(post || !straight));
		}
		return result;
	}
}
