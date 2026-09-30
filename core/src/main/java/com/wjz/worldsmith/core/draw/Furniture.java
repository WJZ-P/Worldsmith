package com.wjz.worldsmith.core.draw;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Furnishings whose block states are easy to get backwards.
 *
 * <p>An empty room reads as unfinished, but the pieces that furnish it hide
 * their orientation in states that mean different things per block: a bed's
 * head lies on its {@code facing} side, a stair used as a seat faces its back,
 * a wall torch and an open trapdoor face away from what holds them. These
 * helpers take the intent - where the sitter looks, which wall a thing stands
 * against - and write the state that shows it. Directions are {@link Walls.Side}
 * values in painter-local coordinates.
 */
public final class Furniture {
	private Furniture() {}

	/** A bed with its foot at {@code foot} and its head, the pillow end, one block toward {@code headToward}. */
	public static Painter bed(Painter pen, Vec3i foot, Walls.Side headToward, String bed) {
		Objects.requireNonNull(pen); Objects.requireNonNull(foot); Objects.requireNonNull(headToward);
		var state = require(bed, "_bed").with("facing", id(headToward)).with("occupied", "false");
		pen.brush(Brush.solid(state.with("part", "foot"))).points(List.of(foot));
		pen.brush(Brush.solid(state.with("part", "head"))).points(List.of(step(foot, headToward, 1)));
		return pen;
	}

	/** A seat for someone looking toward {@code facing}: a stair with its back behind them. */
	public static Painter seat(Painter pen, Vec3i at, Walls.Side facing, String stairs) {
		Objects.requireNonNull(pen); Objects.requireNonNull(at); Objects.requireNonNull(facing);
		var state = require(stairs, "_stairs").with("facing", facing.inward()).with("half", "bottom").with("shape", "straight");
		pen.brush(Brush.solid(state)).points(List.of(at));
		return pen;
	}

	/** A table or counter top at sitting height: top slabs over the one-course {@code top} box, clear underneath for legs. */
	public static Painter table(Painter pen, Box top, String slab) {
		Objects.requireNonNull(pen); Objects.requireNonNull(top);
		if (top.height() != 1) throw new IllegalArgumentException("A table top is one course");
		pen.brush(Brush.solid(require(slab, "_slab").with("type", "top"))).fill(top);
		return pen;
	}

	/**
	 * A block standing against the {@code wall} side of {@code at} with its front
	 * to the room: furnaces, smokers, barrels, lecterns, looms, shelves, chiseled
	 * bookshelves - anything whose {@code facing} is its front.
	 */
	public static Painter againstWall(Painter pen, Vec3i at, Walls.Side wall, String block) {
		Objects.requireNonNull(pen); Objects.requireNonNull(at); Objects.requireNonNull(wall);
		pen.brush(Brush.solid(BlockStateRef.parse(block).with("facing", wall.inward()))).points(List.of(at));
		return pen;
	}

	/** A lantern hung at {@code at} from {@code chain} links of iron chain above it (0..8); the ceiling is above the top link. */
	public static Painter hangingLantern(Painter pen, Vec3i at, int chain, String lantern) {
		Objects.requireNonNull(pen); Objects.requireNonNull(at);
		if (chain < 0 || chain > 8) throw new IllegalArgumentException("A lantern hangs on 0..8 links");
		var state = require(lantern, "lantern").with("hanging", "true");
		var links = new ArrayList<Vec3i>();
		for (int i = 1; i <= chain; i++) links.add(new Vec3i(at.x(), at.y() + i, at.z()));
		if (!links.isEmpty()) pen.brush(Brush.solid(BlockStateRef.of("iron_chain").with("axis", "y"))).points(links);
		pen.brush(Brush.solid(state)).points(List.of(at));
		return pen;
	}

	/** A torch at {@code at} fixed to the {@code wall} side of its cell: {@code torch}, {@code soul_torch}, {@code copper_torch} or their wall forms. */
	public static Painter wallTorch(Painter pen, Vec3i at, Walls.Side wall, String torch) {
		Objects.requireNonNull(pen); Objects.requireNonNull(at); Objects.requireNonNull(wall);
		var state = BlockStateRef.parse(torch);
		String id = state.id();
		if (!id.endsWith("_wall_torch")) {
			if (!id.endsWith("torch")) throw new IllegalArgumentException("A wall torch is a torch: " + id);
			id = id.substring(0, id.length() - "torch".length()) + "wall_torch";
		}
		pen.brush(Brush.solid(new BlockStateRef(id, state.properties()).with("facing", wall.inward()))).points(List.of(at));
		return pen;
	}

	private static BlockStateRef require(String block, String suffix) {
		var state = BlockStateRef.parse(block);
		if (!state.id().endsWith(suffix)) throw new IllegalArgumentException("Expected a " + suffix.replace("_", "") + " block: " + state.id());
		return state;
	}

	static String id(Walls.Side side) { return side.name().toLowerCase(Locale.ROOT); }

	static Vec3i step(Vec3i at, Walls.Side side, int blocks) { return new Vec3i(at.x() + side.dx * blocks, at.y(), at.z() + side.dz * blocks); }
}
