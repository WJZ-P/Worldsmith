package com.wjz.worldsmith.core.draw;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FurnitureTest {
    private static Map<Vec3i, BlockStateRef> cells(DrawCanvas canvas) {
        Map<Vec3i, BlockStateRef> cells = new HashMap<>();
        for (var voxel : canvas.snapshot().voxels()) cells.put(voxel.position(), voxel.block().state());
        return cells;
    }

    @Test void eachPieceWritesTheStateThatShowsItsIntent() {
        var canvas = new DrawCanvas(Box.of(-8, 0, -8, 8, 8, 8));
        var pen = canvas.pen("minecraft:stone");
        Furniture.bed(pen, new Vec3i(0, 1, 2), Walls.Side.NORTH, "red_bed");
        Furniture.seat(pen, new Vec3i(3, 1, 0), Walls.Side.EAST, "oak_stairs");
        Furniture.table(pen, Box.of(4, 1, -1, 5, 1, 0), "spruce_slab");
        Furniture.againstWall(pen, new Vec3i(-3, 1, -4), Walls.Side.NORTH, "furnace");
        Furniture.hangingLantern(pen, new Vec3i(0, 3, 0), 2, "lantern");
        Furniture.wallTorch(pen, new Vec3i(-4, 3, 0), Walls.Side.WEST, "soul_torch");
        var cells = cells(canvas);

        var foot = cells.get(new Vec3i(0, 1, 2)); var head = cells.get(new Vec3i(0, 1, 1));
        assertEquals("foot", foot.properties().get("part"));
        assertEquals("head", head.properties().get("part"), "the head lies on the bed's facing side of the foot");
        assertEquals("north", head.properties().get("facing"));
        assertEquals("west", cells.get(new Vec3i(3, 1, 0)).properties().get("facing"), "a seat looking east has its back, the stair's high side, to the west");
        assertEquals("top", cells.get(new Vec3i(5, 1, -1)).properties().get("type"));
        assertEquals("south", cells.get(new Vec3i(-3, 1, -4)).properties().get("facing"), "against the north wall, its front faces the room");
        assertEquals("true", cells.get(new Vec3i(0, 3, 0)).properties().get("hanging"));
        assertEquals("minecraft:iron_chain", cells.get(new Vec3i(0, 5, 0)).id());
        assertEquals("y", cells.get(new Vec3i(0, 4, 0)).properties().get("axis"));
        var torch = cells.get(new Vec3i(-4, 3, 0));
        assertEquals("minecraft:soul_wall_torch", torch.id());
        assertEquals("east", torch.properties().get("facing"), "a wall torch points away from the wall holding it");

        assertThrows(IllegalArgumentException.class, () -> Furniture.bed(pen, Vec3i.ZERO, Walls.Side.NORTH, "oak_planks"));
        assertThrows(IllegalArgumentException.class, () -> Furniture.table(pen, Box.of(0, 1, 0, 1, 2, 1), "oak_slab"));
        assertThrows(IllegalArgumentException.class, () -> Furniture.wallTorch(pen, Vec3i.ZERO, Walls.Side.NORTH, "lantern"));
    }
}
