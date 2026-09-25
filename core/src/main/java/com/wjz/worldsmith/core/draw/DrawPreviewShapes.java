package com.wjz.worldsmith.core.draw;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded offline shape hints, not registry-resolved models, collision or neighbor-state simulation.
 * Known vanilla slabs and stairs fit an exact 2 x 2 x 2 grid; everything else stays a unit cube.
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
