package com.wjz.worldsmith.core.draw;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WallsTest {
    private static final Walls.Frame FRAME = Walls.Frame.of("stripped_dark_oak_log", "dark_oak_log", "white_terracotta", "cobblestone");

    private static Map<Vec3i, BlockStateRef> cells(DrawCanvas canvas) {
        Map<Vec3i, BlockStateRef> cells = new HashMap<>();
        for (var voxel : canvas.snapshot().voxels()) cells.put(voxel.position(), voxel.block().state());
        return cells;
    }

    @Test void postsStandAtAnEvenRhythmWithTheirMembersOrientedAlongThem() {
        assertEquals(List.of(0, 4, 8, 12), Walls.posts(0, 12, 4));
        assertEquals(List.of(0, 3, 7, 10), Walls.posts(0, 10, 4), "an awkward length is evened out rather than leaving a stub bay");

        var canvas = new DrawCanvas(Box.of(-2, 0, -2, 14, 8, 10));
        var walls = Box.of(0, 1, 0, 12, 5, 8);
        Walls.frame(canvas.pen("minecraft:stone"), walls, FRAME);
        var cells = cells(canvas);

        assertEquals("y", cells.get(new Vec3i(4, 3, 0)).properties().get("axis"), "a post runs up");
        assertEquals("minecraft:stripped_dark_oak_log", cells.get(new Vec3i(0, 3, 0)).id(), "corners are posts");
        assertEquals("x", cells.get(new Vec3i(2, 5, 0)).properties().get("axis"), "the top beam runs along its wall");
        assertEquals("z", cells.get(new Vec3i(0, 5, 2)).properties().get("axis"));
        assertEquals("minecraft:cobblestone", cells.get(new Vec3i(2, 1, 0)).id(), "the base course is the plinth");
        assertEquals("minecraft:white_terracotta", cells.get(new Vec3i(2, 3, 0)).id());
        assertTrue(cells.get(new Vec3i(6, 3, 4)).isAir(), "the room inside is open");
        assertFalse(cells.get(new Vec3i(2, 3, 0)).properties().containsKey("axis"), "panels keep the state they were given");
    }

    @Test void windowsSitInTheirBaysWithASillAndHoodOutsideTheWall() {
        var canvas = new DrawCanvas(Box.of(-2, 0, -2, 14, 8, 10));
        var walls = Box.of(0, 1, 0, 12, 5, 8);
        var pen = canvas.pen("minecraft:stone");
        Walls.frame(pen, walls, FRAME);
        var window = Walls.Window.of("glass_pane").withSize(1, 2).withSill(1).withTrim("spruce_stairs");
        Walls.windows(pen, walls, Walls.Side.SOUTH, FRAME, window, 6);
        var cells = cells(canvas);

        // Bays 0-4, 4-8 (skipped: it holds 6), 8-12: windows centred at x=2 and x=10.
        for (int x : new int[]{2, 10}) {
            assertEquals("minecraft:glass_pane", cells.get(new Vec3i(x, 2, 8)).id());
            assertEquals("minecraft:glass_pane", cells.get(new Vec3i(x, 3, 8)).id());
            var sill = cells.get(new Vec3i(x, 1, 9));
            assertEquals("north", sill.properties().get("facing"), "the sill leans into the wall it projects from");
            assertEquals("top", sill.properties().get("half"));
            assertEquals("bottom", cells.get(new Vec3i(x, 4, 9)).properties().get("half"));
        }
        assertEquals("minecraft:white_terracotta", cells.get(new Vec3i(6, 3, 8)).id(), "the skipped bay keeps its wall");
        assertThrows(IllegalArgumentException.class, () -> Walls.window(pen, walls, Walls.Side.SOUTH, 0, window), "not in a corner post");
        assertThrows(IllegalArgumentException.class, () -> Walls.window(pen, walls, Walls.Side.SOUTH, 5, window.withSize(1, 4)), "not through the top beam");
    }

    @Test void aDoorIsBothHalvesWithAWalkableWayThrough() {
        var canvas = new DrawCanvas(Box.of(-2, 0, -2, 14, 8, 10));
        var walls = Box.of(0, 1, 0, 12, 5, 8);
        var pen = canvas.pen("minecraft:stone");
        Walls.frame(pen, walls, FRAME);
        Walls.door(pen, walls, Walls.Side.WEST, 4, "spruce_door");
        var cells = cells(canvas);

        var lower = cells.get(new Vec3i(0, 1, 4)); var upper = cells.get(new Vec3i(0, 2, 4));
        assertEquals("lower", lower.properties().get("half"));
        assertEquals("upper", upper.properties().get("half"));
        assertEquals("east", lower.properties().get("facing"), "hung from outside, facing into the room");
        for (int y : new int[]{1, 2}) {
            assertTrue(cells.get(new Vec3i(-1, y, 4)).isAir());
            assertTrue(cells.get(new Vec3i(1, y, 4)).isAir());
        }
        assertThrows(IllegalArgumentException.class, () -> Walls.door(pen, walls, Walls.Side.WEST, 4, "spruce_trapdoor"));
    }
}
