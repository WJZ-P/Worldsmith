package com.wjz.worldsmith.core.draw;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded offline shape hints, not registry-resolved models, collision or neighbor-state simulation.
 * Known vanilla slabs and stairs fit an exact 2 x 2 x 2 grid; trapdoors and carpets are drawn as a
 * half-block panel where their thinner one lies. Everything else stays a unit cube.
 */
public final class DrawPreviewShapes {
    private DrawPreviewShapes() {}
    public static final int FULL_CUBE = 0xff;
    private static final List<Surface> FULL_NEGATIVE_FACE = List.of(new Surface(0, 0, 0, 2, 2));
    private static final List<Surface> FULL_POSITIVE_FACE = List.of(new Surface(2, 0, 0, 2, 2));

    /** Bit x | y << 1 | z << 2 occupies one half-sized cell, with zero on each negative axis. */
    public static int mask(DrawBlock block) {
        var state = block.state();
        if (state.isAir()) return 0;
        if (!state.id().startsWith("minecraft:")) return FULL_CUBE;
        int shape;
        if (state.id().endsWith("_slab")) {
            shape = switch (state.properties().getOrDefault("type", "bottom")) {
                case "bottom" -> half(0);
                case "top" -> half(1);
                case "double" -> FULL_CUBE;
                default -> FULL_CUBE;
            };
        } else if (state.id().endsWith("_stairs")) {
            String facing = state.properties().getOrDefault("facing", "north");
            String half = state.properties().getOrDefault("half", "bottom");
            String stairShape = state.properties().getOrDefault("shape", "straight");
            int dx, dz;
            switch (facing) {
                case "north" -> { dx = 0; dz = -1; }
                case "south" -> { dx = 0; dz = 1; }
                case "east" -> { dx = 1; dz = 0; }
                case "west" -> { dx = -1; dz = 0; }
                default -> { return FULL_CUBE; }
            }
            if (!half.equals("bottom") && !half.equals("top")) return FULL_CUBE;
            if (!List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right").contains(stairShape)) return FULL_CUBE;
            // Match the current exporter and Minecraft 26.2 StairBlock.mirror(FRONT_BACK),
            // including its corner-state semantics rather than an ideal geometric reflection.
            // Facing-X inner corners keep their handedness; facing-Z corner states stay unchanged.
            if (block.orientation().mirrorX() && dx != 0) {
                dx = -dx;
                stairShape = switch (stairShape) {
                    case "outer_left" -> "outer_right";
                    case "outer_right" -> "outer_left";
                    default -> stairShape;
                };
            }
            int baseY = half.equals("bottom") ? 0 : 1;
            shape = half(baseY);
            for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
                int px = 2 * x - 1, pz = 2 * z - 1;
                boolean front = px * dx + pz * dz > 0;
                boolean left = px * dz - pz * dx > 0;
                boolean filled = switch (stairShape) {
                    case "inner_left" -> front || left;
                    case "inner_right" -> front || !left;
                    case "outer_left" -> front && left;
                    case "outer_right" -> front && !left;
                    default -> front;
                };
                if (filled) shape |= bit(x, 1 - baseY, z);
            }
        } else if (state.id().endsWith("_carpet")) {
            shape = half(0);
        } else if (state.id().endsWith("_trapdoor")) {
            String half = state.properties().getOrDefault("half", "bottom");
            if (!half.equals("bottom") && !half.equals("top")) return FULL_CUBE;
            if (!state.properties().getOrDefault("open", "false").equals("true")) shape = half(half.equals("bottom") ? 0 : 1);
            else {
                int dx, dz;
                switch (state.properties().getOrDefault("facing", "north")) {
                    case "north" -> { dx = 0; dz = -1; }
                    case "south" -> { dx = 0; dz = 1; }
                    case "east" -> { dx = 1; dz = 0; }
                    case "west" -> { dx = -1; dz = 0; }
                    default -> { return FULL_CUBE; }
                }
                if (block.orientation().mirrorX() && dx != 0) dx = -dx;
                // An open trapdoor stands against the side of its cell opposite its facing.
                shape = 0;
                for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
                    if ((2 * x - 1) * dx + (2 * z - 1) * dz < 0) shape |= bit(x, y, z);
            }
        } else return FULL_CUBE;

        // Mirror was applied as a native state operation above; rotation is the ordinary XZ isometry.
        // Positions were already transformed by the drawing SDK; this is shape orientation only.
        var orientation = GridTransform.rotateY(block.orientation().quarterTurns());
        if (shape == FULL_CUBE || orientation.equals(GridTransform.IDENTITY)) return shape;
        int transformed = 0;
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            if (!occupied(shape, x, y, z)) continue;
            var point = orientation.apply(new Vec3i(2 * x - 1, 2 * y - 1, 2 * z - 1));
            transformed |= bit(point.x() > 0 ? 1 : 0, y, point.z() > 0 ? 1 : 0);
        }
        return transformed;
    }

    /** A box in sixteenths of a block, 0..16 on each axis with X east, Y up and Z south. */
    public record Part(int x0, int y0, int z0, int x1, int y1, int z1) {}

    private static final String[] SIDES = {"north", "east", "south", "west"};

    /**
     * Thin vanilla blocks as boxes from their models, in their placed orientation:
     * fences, walls, panes and bars, chains, doors, lanterns, candles, torches and
     * flower pots. Empty for every other block, which is drawn from its half-grid
     * {@link #mask}. Connections are taken from the state as written.
     */
    public static List<Part> parts(DrawBlock block) {
        var state = block.state();
        String id = state.id();
        if (!id.startsWith("minecraft:") || state.isAir()) return List.of();
        var p = state.properties();
        var parts = new ArrayList<Part>();
        if (id.endsWith("_fence")) {
            parts.add(new Part(6, 0, 6, 10, 16, 10));
            for (int s = 0; s < 4; s++) if ("true".equals(p.get(SIDES[s]))) {
                parts.add(turned(new Part(7, 12, 0, 9, 15, 9), s)); parts.add(turned(new Part(7, 6, 0, 9, 9, 9), s));
            }
        } else if (id.endsWith("_wall")) {
            if (!"false".equals(p.get("up"))) parts.add(new Part(4, 0, 4, 12, 16, 12));
            for (int s = 0; s < 4; s++) {
                String side = p.getOrDefault(SIDES[s], "none");
                if (!side.equals("none")) parts.add(turned(new Part(5, 0, 0, 11, side.equals("tall") ? 16 : 14, 8), s));
            }
        } else if (id.endsWith("_pane") || id.endsWith("_bars")) {
            parts.add(new Part(7, 0, 7, 9, 16, 9));
            for (int s = 0; s < 4; s++) if ("true".equals(p.get(SIDES[s]))) parts.add(turned(new Part(7, 0, 0, 9, 16, 7), s));
        } else if (id.endsWith("_chain") || id.equals("minecraft:chain")) {
            parts.add(switch (p.getOrDefault("axis", "y")) {
                case "x" -> new Part(0, 7, 7, 16, 9, 9);
                case "z" -> new Part(7, 7, 0, 9, 9, 16);
                default -> new Part(7, 0, 7, 9, 16, 9);
            });
        } else if (id.endsWith("_door")) {
            // A closed door is a panel on the side of its cell opposite its facing.
            int s = side(p.getOrDefault("facing", "north"));
            if (s < 0) return List.of();
            parts.add(turned(new Part(0, 0, 13, 16, 16, 16), s));
        } else if (id.endsWith("lantern") && !id.equals("minecraft:sea_lantern") && !id.equals("minecraft:jack_o_lantern")) {
            int lift = "true".equals(p.get("hanging")) ? 1 : 0;
            parts.add(new Part(5, lift, 5, 11, 7 + lift, 11)); parts.add(new Part(6, 7 + lift, 6, 10, 9 + lift, 10));
        } else if (id.endsWith("candle")) {
            parts.add(new Part(6, 0, 6, 10, 6, 10));
        } else if (id.equals("minecraft:flower_pot") || id.startsWith("minecraft:potted_")) {
            parts.add(new Part(5, 0, 5, 11, 6, 11));
        } else if (id.endsWith("_wall_torch") || id.equals("minecraft:wall_torch")) {
            // Leaning out from the wall behind it, opposite its facing.
            int s = side(p.getOrDefault("facing", "north"));
            if (s < 0) return List.of();
            parts.add(turned(new Part(7, 3, 11, 9, 13, 16), s));
        } else if (id.endsWith("torch")) {
            parts.add(new Part(7, 0, 7, 9, 10, 9));
        } else return List.of();
        // Mirror and quarter turns as the block's orientation places it.
        var orientation = block.orientation().orientation();
        if (orientation.equals(GridTransform.IDENTITY)) return parts;
        var placed = new ArrayList<Part>(parts.size());
        for (var part : parts) {
            var a = orientation.apply(new Vec3i(2 * part.x0() - 16, part.y0(), 2 * part.z0() - 16));
            var b = orientation.apply(new Vec3i(2 * part.x1() - 16, part.y1(), 2 * part.z1() - 16));
            placed.add(new Part((Math.min(a.x(), b.x()) + 16) / 2, part.y0(), (Math.min(a.z(), b.z()) + 16) / 2,
                (Math.max(a.x(), b.x()) + 16) / 2, part.y1(), (Math.max(a.z(), b.z()) + 16) / 2));
        }
        return placed;
    }

    /**
     * A fence, wall, pane or bars written without any side state. The world joins
     * such a block to its neighbours when it is placed, so the preview joins it too.
     */
    public static boolean joinsNeighbours(BlockStateRef state) {
        String id = state.id();
        boolean joining = id.endsWith("_fence") || id.endsWith("_wall") || id.endsWith("_pane") || id.endsWith("_bars");
        return joining && id.startsWith("minecraft:") && java.util.Arrays.stream(SIDES).noneMatch(state.properties()::containsKey);
    }

    /** The block joined, in world directions, to each side (north, east, south, west) whose neighbour is occupied. */
    public static DrawBlock joined(DrawBlock block, java.util.function.IntPredicate occupiedSide) {
        var state = block.state();
        boolean wall = state.id().endsWith("_wall");
        boolean[] join = new boolean[4];
        for (int s = 0; s < 4; s++) {
            join[s] = occupiedSide.test(s);
            state = state.with(SIDES[s], wall ? (join[s] ? "low" : "none") : Boolean.toString(join[s]));
        }
        if (wall) state = state.with("up", Boolean.toString(!(join[0] && join[2] && !join[1] && !join[3] || join[1] && join[3] && !join[0] && !join[2])));
        return new DrawBlock(state, GridTransform.IDENTITY);
    }

    private static int side(String facing) {
        for (int s = 0; s < 4; s++) if (SIDES[s].equals(facing)) return s;
        return -1;
    }

    /** A part modelled on the north side, turned clockwise from above to side {@code s} (north, east, south, west). */
    private static Part turned(Part n, int s) {
        return switch (s) {
            case 1 -> new Part(16 - n.z1(), n.y0(), n.x0(), 16 - n.z0(), n.y1(), n.x1());
            case 2 -> new Part(16 - n.x1(), n.y0(), 16 - n.z1(), 16 - n.x0(), n.y1(), 16 - n.z0());
            case 3 -> new Part(n.z0(), n.y0(), 16 - n.x1(), n.z1(), n.y1(), 16 - n.x0());
            default -> n;
        };
    }

    static boolean occupied(int mask, int x, int y, int z) { return (mask & bit(x, y, z)) != 0; }
    private static int bit(int x, int y, int z) { return 1 << (x | y << 1 | z << 2); }
    private static int half(int y) {
        int result = 0;
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) result |= bit(x, y, z);
        return result;
    }

    /** Local half-grid plane 0..2, and a nonoverlapping rectangle on its two cyclic axes. */
    record Surface(int plane, int u, int v, int width, int height) {}

    /**
     * Only one facing direction is emitted. Internal octant faces and only the genuinely covered
     * neighbor quarters are culled. Equal-plane quarters are merged: an isolated cube stays one face.
     */
    static List<Surface> surfaces(int mask, int axis, int sign, int neighborMask) {
        if (axis < 0 || axis > 2 || Math.abs(sign) != 1) throw new IllegalArgumentException("Invalid surface direction");
        if (mask == 0 || mask == FULL_CUBE && neighborMask == FULL_CUBE) return List.of();
        if (mask == FULL_CUBE && neighborMask == 0) return sign < 0 ? FULL_NEGATIVE_FACE : FULL_POSITIVE_FACE;
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        int[] planes = new int[3];
        int[] cell = new int[3];
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int c = 0; c < 2; c++) {
            cell[axis] = a; cell[u] = b; cell[v] = c;
            if (!occupied(mask, cell[0], cell[1], cell[2])) continue;
            int next = a + sign;
            cell[axis] = Math.floorMod(next, 2);
            int adjacent = next < 0 || next > 1 ? neighborMask : mask;
            if (occupied(adjacent, cell[0], cell[1], cell[2])) continue;
            planes[a + (sign > 0 ? 1 : 0)] |= 1 << (b | c << 1);
        }
        var result = new ArrayList<Surface>(4);
        for (int plane = 0; plane < planes.length; plane++) {
            int remaining = planes[plane];
            for (int row = 0; row < 2; row++) for (int column = 0; column < 2; column++) {
                if ((remaining & 1 << (column | row << 1)) == 0) continue;
                int width = column == 0 && (remaining & 1 << (1 | row << 1)) != 0 ? 2 : 1;
                int strip = ((1 << width) - 1) << column;
                int height = row == 0 && (remaining >> 2 & strip) == strip ? 2 : 1;
                for (int r = row; r < row + height; r++) remaining &= ~(strip << (r << 1));
                result.add(new Surface(plane, column, row, width, height));
            }
        }
        return result;
    }
}
