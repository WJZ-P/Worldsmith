package com.wjz.worldsmith.core.draw;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rooms furnished for what they are used for.
 *
 * <p>A building tells a player what happens inside it only through its rooms:
 * the beds of a house, the kegs and long tables of a tavern, the anvil of a
 * forge. Furnishing every room piece by piece is the step most often skipped,
 * so a room here is furnished in one call, in a way that keeps it usable:
 * furniture stands in the band along the walls, the next ring in is always
 * left free as a walkway, tables and seats only use the middle beyond that, the
 * room's centre cell stays open, and nothing is placed beside a doorway, stair
 * or other kept area. Lanterns stand on furniture or the floor, never hanging
 * from a ceiling that may not be there, until every free floor cell is
 * readably lit.
 */
public final class Rooms {
	/** What a room is for; each lays its own pieces along the walls and in the middle. */
	public enum Use { BEDROOM, KITCHEN, TAVERN, LIBRARY, FORGE, STOREROOM }

	private static final Set<String> WOODS = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped");
	private static final Set<String> CLOTHS = Set.of("white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink");
	/** The block light every free floor cell is kept at, feet and head: the READABLE minimum. */
	private static final int READABLE = 8;
	private static final BlockStateRef LANTERN = BlockStateRef.of("lantern").with("hanging", "false");
	private static final BlockStateRef CANDLES = BlockStateRef.of("candle").with("candles", "3").with("lit", "true");

	/** The wood of seats and tables, the dye of beds and rugs, and a seed that varies where along the walls pieces start. */
	public record Style(String wood, String cloth, long seed) {
		public Style {
			if (!WOODS.contains(wood)) throw new IllegalArgumentException("A room's wood is one of " + WOODS);
			if (!CLOTHS.contains(cloth)) throw new IllegalArgumentException("A room's cloth is one of the sixteen dye colours");
		}
		public static Style of(String wood, String cloth) { return new Style(wood, cloth, 0); }
		public Style withSeed(long seed) { return new Style(wood, cloth, seed); }
		BlockStateRef stairs() { return BlockStateRef.of(wood + "_stairs"); }
		BlockStateRef slab() { return BlockStateRef.of(wood + "_slab"); }
	}

	/** A light the room stands, with the block and the level to declare it as a lighting source. */
	public record Light(Vec3i at, BlockStateRef state, int level) {}

	private record Slot(Vec3i at, Walls.Side wall, Walls.Side along) {}

	/** One furnishing in progress: what it has filled, and the lights it has stood. */
	private static final class Work {
		final Painter pen; final Box room; final Style style;
		final Set<Vec3i> filled = new HashSet<>();
		final List<Light> lights = new ArrayList<>();
		Work(Painter pen, Box room, Style style) { this.pen = pen; this.room = room; this.style = style; }
		void put(Vec3i at, BlockStateRef state) { Furniture.place(pen, at, state); filled.add(at); }
		void light(Vec3i at, BlockStateRef state, int level) { put(at, state); lights.add(new Light(at, state, level)); }
	}

	private interface Piece {
		int width();
		void place(Work work, List<Slot> slots);
	}

	private Rooms() {}

	/** The room inside a {@link Walls} footprint: within the wall line, from its floor up to the course under its top. */
	public static Box inside(Box walls) {
		return new Box(new Vec3i(walls.min().x() + 1, walls.min().y(), walls.min().z() + 1), new Vec3i(walls.max().x() - 1, walls.max().y() - 1, walls.max().z() - 1));
	}

	/**
	 * Furnishes {@code room}, the open volume a person stands in (at least 3 x 3
	 * and 3 high), for {@code use}. Nothing is placed within one block of any
	 * {@code keepClear} box: pass the cell inside each doorway and the footprint of
	 * each stair flight and landing. Returns the lights it stood, to declare as
	 * the structure's lighting sources.
	 */
	public static List<Light> furnish(Painter pen, Box room, Use use, Style style, Box... keepClear) {
		Objects.requireNonNull(pen); Objects.requireNonNull(room); Objects.requireNonNull(use); Objects.requireNonNull(style);
		if (room.width() < 3 || room.depth() < 3 || room.height() < 3) throw new IllegalArgumentException("A furnished room is at least 3 x 3 and 3 high");
		var keep = List.of(keepClear);
		var work = new Work(pen, room, style);
		var slots = slots(room, keep, style.seed());
		centre(work, use, keep);
		var pieces = pieces(use, room);
		// Libraries and storerooms line every free wall; other rooms set each piece once with a gap between.
		boolean dense = use == Use.LIBRARY || use == Use.STOREROOM;
		int next = 0;
		for (int i = 0; i < slots.size() && (dense || next < pieces.size()); ) {
			var piece = pieces.get(next % pieces.size());
			if (!fits(slots, i, piece.width())) { i++; continue; }
			piece.place(work, slots.subList(i, i + piece.width()));
			i += piece.width() + (dense ? 0 : 1);
			next++;
		}
		lightUp(work, slots);
		return List.copyOf(work.lights);
	}

	/**
	 * While any free floor cell of the room is darker than the READABLE level at
	 * feet or head, stands a lantern on the floor of the free wall cell nearest
	 * to it. Light spreads as the structure check spreads it: through the room's
	 * open cells, around the furniture placed here, one level per step.
	 */
	private static void lightUp(Work work, List<Slot> slots) {
		// A cell one lantern at its nearest free wall cell cannot light is given up, not chased with more.
		var tried = new HashSet<Vec3i>();
		for (int round = 0; round < 32; round++) {
			var dark = darkest(work, tried);
			if (dark == null) return;
			tried.add(dark);
			Slot best = null;
			for (var slot : slots)
				if (!work.filled.contains(slot.at()) && (best == null || distance(slot.at(), dark) < distance(best.at(), dark))) best = slot;
			if (best == null) {
				if (work.lights.isEmpty()) work.light(work.room.min(), LANTERN, 15);
				return;
			}
			work.light(best.at(), LANTERN, 15);
		}
	}

	/** The first free floor cell not yet tried that is below the READABLE level at feet or head, or null. */
	private static Vec3i darkest(Work work, Set<Vec3i> tried) {
		var levels = new HashMap<Vec3i, Integer>();
		var queue = new ArrayDeque<Vec3i>();
		for (var light : work.lights) if (light.level() > levels.getOrDefault(light.at(), 0)) { levels.put(light.at(), light.level()); queue.add(light.at()); }
		int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		while (!queue.isEmpty()) {
			var at = queue.removeFirst();
			int next = levels.get(at) - 1;
			if (next <= 0) continue;
			for (var s : steps) {
				var to = new Vec3i(at.x() + s[0], at.y() + s[1], at.z() + s[2]);
				if (!work.room.contains(to) || work.filled.contains(to) || next <= levels.getOrDefault(to, 0)) continue;
				levels.put(to, next); queue.add(to);
			}
		}
		int y = work.room.min().y();
		for (int x = work.room.min().x(); x <= work.room.max().x(); x++)
			for (int z = work.room.min().z(); z <= work.room.max().z(); z++) {
				var feet = new Vec3i(x, y, z); var head = new Vec3i(x, y + 1, z);
				if (work.filled.contains(feet) || work.filled.contains(head) || tried.contains(feet)) continue;
				if (Math.min(levels.getOrDefault(feet, 0), levels.getOrDefault(head, 0)) < READABLE) return feet;
			}
		return null;
	}

	private static int distance(Vec3i a, Vec3i b) { return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()); }

	/** The cell a declared room names as its destination: its centre on the floor. */
	private static Vec3i middle(Box room) {
		return new Vec3i((room.min().x() + room.max().x()) / 2, room.min().y(), (room.min().z() + room.max().z()) / 2);
	}

	/** Floor cells along the walls, clockwise from above, minus those near kept areas, starting at a seeded point. */
	private static List<Slot> slots(Box room, List<Box> keep, long seed) {
		int y = room.min().y(), x0 = room.min().x(), x1 = room.max().x(), z0 = room.min().z(), z1 = room.max().z();
		var ring = new ArrayList<Slot>();
		for (int x = x0; x <= x1; x++) ring.add(new Slot(new Vec3i(x, y, z0), Walls.Side.NORTH, Walls.Side.EAST));
		for (int z = z0 + 1; z <= z1; z++) ring.add(new Slot(new Vec3i(x1, y, z), Walls.Side.EAST, Walls.Side.SOUTH));
		for (int x = x1 - 1; x >= x0; x--) ring.add(new Slot(new Vec3i(x, y, z1), Walls.Side.SOUTH, Walls.Side.WEST));
		for (int z = z1 - 1; z > z0; z--) ring.add(new Slot(new Vec3i(x0, y, z), Walls.Side.WEST, Walls.Side.NORTH));
		int start = Math.floorMod(Long.hashCode(seed * 0x9E3779B97F4A7C15L), ring.size());
		var result = new ArrayList<Slot>();
		var centre = middle(room);
		for (int i = 0; i < ring.size(); i++) {
			var slot = ring.get((start + i) % ring.size());
			if (!kept(slot.at(), keep) && !slot.at().equals(centre)) result.add(slot);
		}
		return result;
	}

	private static boolean kept(Vec3i at, List<Box> keep) {
		for (var box : keep)
			if (at.x() >= box.min().x() - 1 && at.x() <= box.max().x() + 1 && at.z() >= box.min().z() - 1 && at.z() <= box.max().z() + 1
				&& at.y() + 1 >= box.min().y() && at.y() <= box.max().y()) return true;
		return false;
	}

	/** Consecutive slots on one wall, each next to the last. */
	private static boolean fits(List<Slot> slots, int i, int width) {
		if (i + width > slots.size()) return false;
		for (int k = 1; k < width; k++) {
			Slot a = slots.get(i + k - 1), b = slots.get(i + k);
			if (b.wall() != a.wall() || !Furniture.step(a.at(), a.along(), 1).equals(b.at())) return false;
		}
		return true;
	}

	/** Tables, seats or a rug in the middle, two blocks in from the walls so the walkway ring stays free. */
	private static void centre(Work work, Use use, List<Box> keep) {
		Box room = work.room;
		int y = room.min().y();
		int x0 = room.min().x() + 2, x1 = room.max().x() - 2, z0 = room.min().z() + 2, z1 = room.max().z() - 2;
		if (x0 > x1 || z0 > z1) return;
		var centre = middle(room);
		boolean alongX = x1 - x0 >= z1 - z0;
		int long0 = alongX ? x0 : z0, long1 = alongX ? x1 : z1, short0 = alongX ? z0 : x0, short1 = alongX ? z1 : x1;
		int centreLong = alongX ? centre.x() : centre.z(), centreShort = alongX ? centre.z() : centre.x();
		switch (use) {
			case BEDROOM -> {
				// A runner on the side of the middle away from the centre cell, which stays bare floor.
				for (int u = long0; u <= long1; u++) for (int v = short0; v < centreShort; v++)
					if (!kept(at(alongX, u, y, v), keep)) work.put(at(alongX, u, y, v), BlockStateRef.of(work.style.cloth() + "_carpet"));
			}
			case KITCHEN, LIBRARY, TAVERN -> {
				// Rows of seat | table | seat across the short axis; along the long axis
				// two-block tables with a gap every third block, the centre falling in a gap.
				int rows = use == Use.TAVERN ? Math.max(1, (short1 - short0 + 2) / 4) : 1;
				int first = long0 + Math.floorMod(centreLong - long0 - 2, 3);
				for (int r = 0; r < rows; r++) {
					int seatA = short0 + r * 4, table = seatA + 1, seatB = seatA + 2;
					if (seatB > short1) break;
					for (int u = first; u + 1 <= long1; u += 3) {
						var tableCells = List.of(at(alongX, u, y, table), at(alongX, u + 1, y, table));
						var seats = List.of(at(alongX, u, y, seatA), at(alongX, u + 1, y, seatA), at(alongX, u, y, seatB), at(alongX, u + 1, y, seatB));
						if (tableCells.stream().anyMatch(p -> kept(p, keep) || p.equals(centre)) || seats.stream().anyMatch(p -> kept(p, keep) || p.equals(centre))) continue;
						for (var cell : tableCells) work.put(cell, work.style.slab().with("type", "top"));
						Walls.Side towardB = alongX ? Walls.Side.SOUTH : Walls.Side.EAST, towardA = alongX ? Walls.Side.NORTH : Walls.Side.WEST;
						for (int k = 0; k < 2; k++) {
							Furniture.seat(work.pen, seats.get(k), towardB, work.style.stairs().id());
							Furniture.seat(work.pen, seats.get(2 + k), towardA, work.style.stairs().id());
						}
						work.filled.addAll(seats);
						var top = new Vec3i(tableCells.get(0).x(), y + 1, tableCells.get(0).z());
						if (work.lights.isEmpty()) work.light(top, LANTERN, 15); else work.light(top, CANDLES, 9);
						if (use != Use.TAVERN) return;
					}
				}
			}
			default -> { }
		}
	}

	private static Vec3i at(boolean alongX, int u, int y, int v) { return alongX ? new Vec3i(u, y, v) : new Vec3i(v, y, u); }

	private static List<Piece> pieces(Use use, Box room) {
		int tall = Math.min(3, room.height() - 1);
		return switch (use) {
			case BEDROOM -> List.of(bed(), lit("barrel[facing=up]"), stack("bookshelf", Math.min(2, tall)), bed(), lit("barrel[facing=up]"));
			case KITCHEN -> List.of(fronted("furnace"), fronted("smoker"), plain("crafting_table"), lit("barrel[facing=up]"), plain("cauldron"), stack("barrel[facing=up]", 2));
			case TAVERN -> List.of(counter(3), stack("barrel[facing=up]", 2), stack("barrel[facing=up]", 2), lit("barrel[facing=up]"), stack("barrel[facing=up]", 2), fronted("furnace"));
			case LIBRARY -> List.of(fronted("lectern"), stack("bookshelf", tall), stack("bookshelf", tall), lit("bookshelf"), stack("bookshelf", tall), fronted("chiseled_bookshelf"), stack("bookshelf", tall));
			case FORGE -> List.of(fronted("blast_furnace"), along("anvil"), plain("smithing_table"), lit("barrel[facing=up]"), along("grindstone[face=floor]"), plain("cauldron"), stack("barrel[facing=up]", 2));
			case STOREROOM -> List.of(lit("barrel[facing=up]"), stack("barrel[facing=up]", 2), stack("hay_block", 2), stack("barrel[facing=up]", 2), stack("barrel[facing=up]", 1));
		};
	}

	private interface PieceBody { void place(Work work, Slot slot); }

	private static Piece one(PieceBody body) {
		return new Piece() {
			public int width() { return 1; }
			public void place(Work work, List<Slot> slots) { body.place(work, slots.get(0)); }
		};
	}

	private static Piece plain(String block) { return one((work, s) -> work.put(s.at(), BlockStateRef.parse(block))); }

	/** A block standing against its wall with its front to the room. */
	private static Piece fronted(String block) { return one((work, s) -> work.put(s.at(), BlockStateRef.parse(block).with("facing", s.wall().inward()))); }

	/** A block whose facing runs along the wall, an anvil or a grindstone. */
	private static Piece along(String block) { return one((work, s) -> work.put(s.at(), BlockStateRef.parse(block).with("facing", Furniture.id(s.along())))); }

	private static Piece stack(String block, int height) {
		return one((work, s) -> {
			for (int h = 0; h < height && s.at().y() + h <= work.room.max().y() - 1; h++) work.put(new Vec3i(s.at().x(), s.at().y() + h, s.at().z()), BlockStateRef.parse(block));
		});
	}

	/** A block with a lantern standing on it. */
	private static Piece lit(String block) {
		return one((work, s) -> {
			work.put(s.at(), BlockStateRef.parse(block));
			work.light(new Vec3i(s.at().x(), s.at().y() + 1, s.at().z()), LANTERN, 15);
		});
	}

	/** A bed lying along the wall, its head toward the way the slots run. */
	private static Piece bed() {
		return new Piece() {
			public int width() { return 2; }
			public void place(Work work, List<Slot> slots) {
				Furniture.bed(work.pen, slots.get(0).at(), slots.get(0).along(), work.style.cloth() + "_bed");
				work.filled.add(slots.get(0).at()); work.filled.add(slots.get(1).at());
			}
		};
	}

	/** A serving counter of top slabs along the wall, with a lantern at one end. */
	private static Piece counter(int length) {
		return new Piece() {
			public int width() { return length; }
			public void place(Work work, List<Slot> slots) {
				for (var s : slots) work.put(s.at(), work.style.slab().with("type", "top"));
				var first = slots.get(0).at();
				work.light(new Vec3i(first.x(), first.y() + 1, first.z()), LANTERN, 15);
			}
		};
	}
}
