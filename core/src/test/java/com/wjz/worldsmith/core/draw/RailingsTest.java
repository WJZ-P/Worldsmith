package com.wjz.worldsmith.core.draw;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RailingsTest {
    private static Map<Vec3i, BlockStateRef> cells(DrawCanvas canvas) {
        Map<Vec3i, BlockStateRef> cells = new HashMap<>();
        for (var voxel : canvas.snapshot().voxels()) cells.put(voxel.position(), voxel.block().state());
        return cells;
    }

    @Test void aRailingRunsRoundTheDeckWithPostsConnectionsAndAGap() {
        var canvas = new DrawCanvas(Box.of(-2, 0, -2, 12, 6, 8));
        var pen = canvas.pen("minecraft:stone");
        var deck = Box.of(0, 0, 0, 8, 2, 4);
        Railings.around(pen, deck, Railings.Railing.of("spruce_fence").withPosts("stripped_spruce_log", 4), new Vec3i(4, 3, 4));
        var cells = cells(canvas);

        assertEquals("minecraft:stripped_spruce_log", cells.get(new Vec3i(0, 3, 0)).id(), "posts at the corners");
        assertEquals("minecraft:stripped_spruce_log", cells.get(new Vec3i(4, 3, 0)).id(), "and at the rhythm");
        var rail = cells.get(new Vec3i(2, 3, 0));
        assertEquals("minecraft:spruce_fence", rail.id());
        assertEquals("true", rail.properties().get("east"));
        assertEquals("true", rail.properties().get("west"));
        assertEquals("false", rail.properties().get("south"), "the deck side stays open");
        assertNull(cells.get(new Vec3i(4, 3, 4)), "the opening is left for the stair");
        assertEquals("false", cells.get(new Vec3i(3, 3, 4)).properties().get("east"), "a rail ends where the opening begins");
        assertNull(cells.get(new Vec3i(4, 3, 2)), "the deck itself stays walkable");
    }

    @Test void aWallRailingRaisesItsPostOnlyWhereItTurnsOrEnds() {
        var canvas = new DrawCanvas(Box.of(-2, 0, -2, 12, 6, 8));
        var pen = canvas.pen("minecraft:stone");
        Railings.line(pen, new Vec3i(0, 1, 0), new Vec3i(5, 1, 0), Railings.Railing.of("stone_brick_wall"));
        var cells = cells(canvas);
        assertEquals("true", cells.get(new Vec3i(0, 1, 0)).properties().get("up"), "an end shows its post");
        assertEquals("false", cells.get(new Vec3i(2, 1, 0)).properties().get("up"));
        assertEquals("low", cells.get(new Vec3i(2, 1, 0)).properties().get("east"));
        assertEquals("none", cells.get(new Vec3i(2, 1, 0)).properties().get("north"));
        assertThrows(IllegalArgumentException.class, () -> Railings.line(pen, new Vec3i(0, 1, 0), new Vec3i(3, 1, 3), Railings.Railing.of("oak_fence")));
    }

    @Test void battlementsAlternateMerlonsAndCrenelsWithMerlonCorners() {
        var canvas = new DrawCanvas(Box.of(-2, 0, -2, 12, 12, 8));
        var pen = canvas.pen("minecraft:stone");
        Railings.battlements(pen, Box.of(0, 0, 0, 7, 8, 5), "stone_bricks");
        var cells = cells(canvas);
        for (var corner : new Vec3i[]{new Vec3i(0, 9, 0), new Vec3i(7, 9, 0), new Vec3i(0, 9, 5), new Vec3i(7, 9, 5)})
            assertNotNull(cells.get(corner), corner.toString());
        assertNotNull(cells.get(new Vec3i(2, 9, 0)));
        assertNull(cells.get(new Vec3i(1, 9, 0)), "a crenel");
        assertNull(cells.get(new Vec3i(3, 9, 2)), "nothing inside the wall line");
    }
}
