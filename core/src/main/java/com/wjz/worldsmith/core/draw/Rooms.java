package com.wjz.worldsmith.core.draw;

import java.util.ArrayList;
import java.util.List;
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
 * left free as a walkway, tables and seats only use the middle beyond that, and
 * nothing is placed beside a doorway, stair or other kept area. Every room gets
 * at least one light standing on its furniture or floor, never hanging from a
 * ceiling that may not be there.
 */
public final class Rooms {
	/** What a room is for; each lays its own pieces along the walls and in the middle. */
	public enum Use { BEDROOM, KITCHEN, TAVERN, LIBRARY, FORGE, STOREROOM }

	private static final Set<String> WOODS = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped");
	private static final Set<String> CLOTHS = Set.of("white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink");

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

	private record Slot(Vec3i at, Walls.Side wall, Walls.Side along) {}

	private interface Piece {
		int width();
		/** Draws on {@code slots}, returning where it put a light, if anywhere. */
		List<Vec3i> place(Painter pen, List<Slot> slots, Box room, Style style);
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
	 * each stair flight and landing. Returns where the lights stand, for the
	 * structure's lighting sources.
	 */
	public static List<Vec3i> furnish(Painter pen, Box room, Use use, Style style, Box... keepClear) {
		Objects.requireNonNull(pen); Objects.requireNonNull(room); Objects.requireNonNull(use); Objects.requireNonNull(style);
		if (room.width() < 3 || room.depth() < 3 || room.height() < 3) throw new IllegalArgumentException("A furnished room is at least 3 x 3 and 3 high");
		var keep = List.of(keepClear);
		var slots = slots(room, keep, style.seed());
		var lights = new ArrayList<Vec3i>();
		lights.addAll(centre(pen, room, use, style, keep));
		var pieces = pieces(use, room);
		// Libraries and storerooms line every free wall; other rooms set each piece once with a gap between.
		boolean dense = use == Use.LIBRARY || use == Use.STOREROOM;
		int next = 0;
		for (int i = 0; i < slots.size() && (dense || next < pieces.size()); ) {
			var piece = pieces.get(next % pieces.size());
			if (!fits(slots, i, piece.width())) { i++; continue; }
			lights.addAll(piece.place(pen, slots.subList(i, i + piece.width()), room, style));
			i += piece.width() + (dense ? 0 : 1);
			next++;
		}
		if (lights.isEmpty()) {
			// A room too small or too kept for its pieces still needs a light: a lantern on the floor by a wall.
			var free = slots.isEmpty() ? room.min() : slots.get(0).at();
			Furniture.place(pen, free, BlockStateRef.of("lantern").with("hanging", "false"));
			lights.add(free);
		}
		return lights;
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
		for (int i = 0; i < ring.size(); i++) {
			var slot = ring.get((start + i) % ring.size());
			if (!kept(slot.at(), keep)) result.add(slot);
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
	private static List<Vec3i> centre(Painter pen, Box room, Use use, Style style, List<Box> keep) {
		var lights = new ArrayList<Vec3i>();
		int y = room.min().y();
		int x0 = room.min().x() + 2, x1 = room.max().x() - 2, z0 = room.min().z() + 2, z1 = room.max().z() - 2;
		if (x0 > x1 || z0 > z1) return lights;
		boolean alongX = x1 - x0 >= z1 - z0;
		int long0 = alongX ? x0 : z0, long1 = alongX ? x1 : z1, short0 = alongX ? z0 : x0, short1 = alongX ? z1 : x1;
		switch (use) {
			case BEDROOM -> {
				var rug = new ArrayList<Vec3i>();
				for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) if (!kept(new Vec3i(x, y, z), keep)) rug.add(new Vec3i(x, y, z));
				if (!rug.isEmpty()) pen.brush(Brush.solid(BlockStateRef.of(style.cloth() + "_carpet"))).points(rug);
			}
			case KITCHEN, LIBRARY, TAVERN -> {
				// Rows of seat | table | seat across the short axis; a tavern repeats them, others set one.
				int rows = use == Use.TAVERN ? Math.max(1, (short1 - short0 + 2) / 4) : 1;
				for (int r = 0; r < rows; r++) {
					int seatA = short0 + r * 4, table = seatA + 1, seatB = seatA + 2;
					if (seatB > short1) break;
					for (int u = long0; u + 1 <= long1; u += 3) {
						var tableCells = List.of(at(alongX, u, y, table), at(alongX, u + 1, y, table));
						var seats = List.of(at(alongX, u, y, seatA), at(alongX, u + 1, y, seatA), at(alongX, u, y, seatB), at(alongX, u + 1, y, seatB));
						if (tableCells.stream().anyMatch(p -> kept(p, keep)) || seats.stream().anyMatch(p -> kept(p, keep))) continue;
						pen.brush(Brush.solid(style.slab().with("type", "top"))).points(tableCells);
						Walls.Side towardB = alongX ? Walls.Side.SOUTH : Walls.Side.EAST, towardA = alongX ? Walls.Side.NORTH : Walls.Side.WEST;
						for (int k = 0; k < 2; k++) {
							Furniture.seat(pen, seats.get(k), towardB, style.stairs().id());
							Furniture.seat(pen, seats.get(2 + k), towardA, style.stairs().id());
						}
						var top = new Vec3i(tableCells.get(0).x(), y + 1, tableCells.get(0).z());
						if (lights.isEmpty()) Furniture.place(pen, top, BlockStateRef.of("lantern").with("hanging", "false"));
						else Furniture.place(pen, top, BlockStateRef.of("candle").with("candles", "3").with("lit", "true"));
						lights.add(top);
						if (use != Use.TAVERN) return lights;
					}
				}
			}
			default -> { }
		}
		return lights;
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

	private static Piece one(PieceBody body) {
		return new Piece() {
			public int width() { return 1; }
			public List<Vec3i> place(Painter pen, List<Slot> slots, Box room, Style style) { return body.place(pen, slots.get(0), room); }
		};
	}

	private interface PieceBody { List<Vec3i> place(Painter pen, Slot slot, Box room); }

	private static Piece plain(String block) { return one((pen, s, room) -> { Furniture.place(pen, s.at(), BlockStateRef.parse(block)); return List.of(); }); }

	private static Piece fronted(String block) { return one((pen, s, room) -> { Furniture.againstWall(pen, s.at(), s.wall(), block); return List.of(); }); }

	/** A block whose facing runs along the wall, an anvil or a grindstone. */
	private static Piece along(String block) {
		return one((pen, s, room) -> { Furniture.place(pen, s.at(), BlockStateRef.parse(block).with("facing", Furniture.id(s.along()))); return List.of(); });
	}

	private static Piece stack(String block, int height) {
		return one((pen, s, room) -> {
			var state = BlockStateRef.parse(block);
			for (int h = 0; h < height && s.at().y() + h <= room.max().y() - 1; h++) Furniture.place(pen, new Vec3i(s.at().x(), s.at().y() + h, s.at().z()), state);
			return List.of();
		});
	}

	/** A block with a lantern standing on it. */
	private static Piece lit(String block) {
		return one((pen, s, room) -> {
			Furniture.place(pen, s.at(), BlockStateRef.parse(block));
			var lantern = new Vec3i(s.at().x(), s.at().y() + 1, s.at().z());
			Furniture.place(pen, lantern, BlockStateRef.of("lantern").with("hanging", "false"));
			return List.of(lantern);
		});
	}

	/** A bed lying along the wall, its head toward the way the slots run. */
	private static Piece bed() {
		return new Piece() {
			public int width() { return 2; }
			public List<Vec3i> place(Painter pen, List<Slot> slots, Box room, Style style) {
				Furniture.bed(pen, slots.get(0).at(), slots.get(0).along(), style.cloth() + "_bed");
				return List.of();
			}
		};
	}

	/** A serving counter of top slabs along the wall, with a lantern at one end. */
	private static Piece counter(int length) {
		return new Piece() {
			public int width() { return length; }
			public List<Vec3i> place(Painter pen, List<Slot> slots, Box room, Style style) {
				var top = new ArrayList<Vec3i>();
				for (var s : slots) top.add(s.at());
				pen.brush(Brush.solid(style.slab().with("type", "top"))).points(top);
				var lantern = new Vec3i(top.get(0).x(), top.get(0).y() + 1, top.get(0).z());
				Furniture.place(pen, lantern, BlockStateRef.of("lantern").with("hanging", "false"));
				return List.of(lantern);
			}
		};
	}
}
