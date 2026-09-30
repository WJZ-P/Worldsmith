package com.wjz.worldsmith.core.draw;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StairsTest {
    private static Map<Vec3i, BlockStateRef> cells(DrawCanvas canvas) {
        Map<Vec3i, BlockStateRef> cells = new HashMap<>();
        for (var voxel : canvas.snapshot().voxels()) cells.put(voxel.position(), voxel.block().state());
        return cells;
    }

    @Test void aFlightClimbsItsWayWithHeadroomAndWidensToTheClimbersRight() {
        var canvas = new DrawCanvas(Box.of(-8, 0, -8, 8, 12, 8));
        var pen = canvas.pen("minecraft:stone");
        var top = Stairs.flight(pen, new Vec3i(2, 1, 4), Walls.Side.NORTH, 4, Stairs.Flight.of("oak_stairs").withWidth(2));
        assertEquals(new Vec3i(2, 5, 0), top, "one past the last step, standing on the floor it reaches");
        var cells = cells(canvas);
        for (int i = 0; i < 4; i++) for (int w = 0; w < 2; w++) {
            var step = cells.get(new Vec3i(2 + w, 1 + i, 4 - i));
            assertEquals("minecraft:oak_stairs", step.id());
            assertEquals("north", step.properties().get("facing"), "every stair faces up the flight");
            assertEquals("bottom", step.properties().get("half"));
        }
        for (int y = 2; y <= 4; y++) assertTrue(cells.get(new Vec3i(2, y, 4)).isAir(), "headroom over the first step");
        assertNull(cells.get(new Vec3i(2, 2, 2)), "without a support the space under the flight is left as it was");

        Stairs.flight(pen, new Vec3i(-2, 1, -4), Walls.Side.WEST, 3, Stairs.Flight.of("stone_brick_stairs").withWidth(2).withSupport("stone_bricks"));
        cells = cells(canvas);
        assertEquals("west", cells.get(new Vec3i(-4, 3, -5)).properties().get("facing"), "climbing west, the climber's right is north");
        assertEquals("minecraft:stone_bricks", cells.get(new Vec3i(-4, 1, -5)).id());
        assertEquals("minecraft:stone_bricks", cells.get(new Vec3i(-4, 2, -4)).id(), "a support fills beneath each step");
        assertThrows(IllegalArgumentException.class, () -> Stairs.Flight.of("oak_slab"));
    }

    @Test void aFloorBoardsTheRoomBelowAndRunsOutUnderAJettyWithoutCoveringBeams() {
        var canvas = new DrawCanvas(Box.of(-3, -1, -3, 14, 14, 12));
        var pen = canvas.pen("minecraft:stone");
        var frame = Walls.Frame.of("stripped_spruce_log", "spruce_log", "white_concrete", "cobblestone");
        var lower = Box.of(0, 1, 0, 11, 5, 8);
        Walls.floor(pen, lower, "cobblestone");
        Walls.frame(pen, lower, frame);
        var upper = Box.of(-1, 6, -1, 12, 10, 9);
        Walls.floor(pen, upper, "spruce_planks");
        Walls.frame(pen, upper, frame);
        var cells = cells(canvas);

        assertEquals("minecraft:cobblestone", cells.get(new Vec3i(0, 0, 4)).id(), "the ground floor runs under the walls, a threshold for doors");
        assertEquals("minecraft:spruce_planks", cells.get(new Vec3i(5, 5, 4)).id(), "the upper floor boards the room below");
        assertEquals("minecraft:spruce_log", cells.get(new Vec3i(2, 5, 0)).id(), "the lower storey's beam is not covered");
        assertEquals("minecraft:spruce_planks", cells.get(new Vec3i(2, 5, -1)).id(), "boards run out under the jettied wall");
        assertTrue(cells.get(new Vec3i(5, 6, 4)).isAir(), "the upper room is open");
    }
}
