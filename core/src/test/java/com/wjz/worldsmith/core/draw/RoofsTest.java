package com.wjz.worldsmith.core.draw;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoofsTest {
    private static final Roofs.Material TILE = Roofs.Material.of("minecraft:deepslate_tile_stairs", "minecraft:deepslate_tile_slab", "minecraft:deepslate_tiles");

    private static Map<Vec3i, BlockStateRef> cells(DrawCanvas canvas) {
        Map<Vec3i, BlockStateRef> cells = new HashMap<>();
        for (var voxel : canvas.snapshot().voxels()) cells.put(voxel.position(), voxel.block().state());
        return cells;
    }

    @Test void gableSlopesClimbTowardTheRidgeAndCloseTheirEnds() {
        var canvas = new DrawCanvas(new Box(new Vec3i(-4, 0, -4), new Vec3i(12, 16, 10)));
        var walls = new Box(new Vec3i(0, 0, 0), new Vec3i(8, 4, 6));
        Roofs.gable(canvas.pen("minecraft:stone"), walls, Roofs.Ridge.X, TILE, Roofs.Options.defaults().withGableWall("minecraft:white_terracotta"));
        var cells = cells(canvas);

        // Overhang 1: eaves at z=-1 and z=7, one block up per block inward, ridge at z=3.
        for (int x = -1; x <= 9; x++) for (int z = -1; z <= 7; z++) {
            if (z == 3) continue;
            var stair = cells.get(new Vec3i(x, 5 + Math.min(z + 1, 7 - z), z));
            assertNotNull(stair, x + "," + z);
            assertEquals("minecraft:deepslate_tile_stairs", stair.id());
            assertEquals(z < 3 ? "south" : "north", stair.properties().get("facing"), "a slope climbs toward its ridge");
            assertEquals("straight", stair.properties().get("shape"));
        }
        assertEquals("bottom", cells.get(new Vec3i(4, 9, 3)).properties().get("type"), "the ridge is capped with slabs");
        // The gable end is walled up under its slope; the attic behind it is open, not terrain.
        assertEquals("minecraft:white_terracotta", cells.get(new Vec3i(0, 7, 2)).id());
        assertTrue(cells.get(new Vec3i(4, 8, 3)).isAir());
        assertNull(cells.get(new Vec3i(4, 3, 3)), "nothing below the eave line is authored");
    }

    @Test void hipCornersTakeMinecraftsOuterShapesAndASquareClosesToAPoint() {
        var canvas = new DrawCanvas(new Box(new Vec3i(-4, 0, -4), new Vec3i(8, 12, 8)));
        var walls = new Box(new Vec3i(0, 0, 0), new Vec3i(4, 3, 4));
        Roofs.hip(canvas.pen("minecraft:stone"), walls, TILE, Roofs.Options.defaults());
        var cells = cells(canvas);

        // The north-west eave corner faces south with the west edge behind it: StairBlock makes that outer_left.
        var corner = cells.get(new Vec3i(-1, 4, -1));
        assertEquals("south", corner.properties().get("facing"));
        assertEquals("outer_left", corner.properties().get("shape"));
        for (var at : new Vec3i[]{new Vec3i(5, 4, -1), new Vec3i(-1, 4, 5), new Vec3i(5, 4, 5)})
            assertTrue(cells.get(at).properties().get("shape").startsWith("outer"), at.toString());
        assertEquals("straight", cells.get(new Vec3i(2, 4, -1)).properties().get("shape"));
        var point = cells.get(new Vec3i(2, 7, 2));
        assertEquals("minecraft:deepslate_tile_slab", point.id(), "a square hip roof closes to a point");
    }

    @Test void flaredCornersUseOnlyPiecesThatKeepTheirShapeInTheWorld() {
        var canvas = new DrawCanvas(new Box(new Vec3i(-4, 0, -4), new Vec3i(10, 12, 10)));
        var walls = new Box(new Vec3i(0, 0, 0), new Vec3i(6, 3, 6));
        Roofs.hip(canvas.pen("minecraft:stone"), walls, TILE, Roofs.Options.defaults().withOverhang(2).withFlaredCorners());
        var cells = cells(canvas);

        var under = cells.get(new Vec3i(-2, 4, -2));
        assertEquals("top", under.properties().get("half"), "an inverted stair curves the corner's underside");
        assertEquals("minecraft:deepslate_tile_slab", cells.get(new Vec3i(-2, 5, -2)).id());
        // The corner sits half a block above its neighbours on the eave line.
        assertEquals("bottom", cells.get(new Vec3i(-1, 4, -2)).properties().get("half"));
        assertNull(cells.get(new Vec3i(-1, 5, -2)));

        var gable = assertThrows(IllegalArgumentException.class, () -> Roofs.gable(canvas.pen("minecraft:stone"), walls, Roofs.Ridge.Z, TILE,
            Roofs.Options.defaults().withFlaredCorners()));
        assertTrue(gable.getMessage().contains("hip"));
    }

    @Test void aRotatedPainterKeepsTheRoofsLocalFacingForNativeRotation() {
        var canvas = new DrawCanvas(new Box(new Vec3i(-12, 0, -12), new Vec3i(12, 12, 12)));
        var walls = new Box(new Vec3i(0, 0, 0), new Vec3i(4, 3, 2));
        Roofs.gable(canvas.pen("minecraft:stone").rotateY(1), walls, Roofs.Ridge.X, TILE, Roofs.Options.defaults().withOverhang(0));
        var eave = canvas.snapshot().voxels().stream().filter(v -> v.block().state().id().endsWith("_stairs")).findFirst().orElseThrow();
        // The state stays as drawn; its orientation carries the turn, as for every other SDK shape.
        assertTrue(java.util.List.of("north", "south").contains(eave.block().state().properties().get("facing")));
        assertNotEquals(GridTransform.IDENTITY.orientation(), eave.block().orientation());
    }

    @Test void materialsMustBeTheirOwnForms() {
        assertThrows(IllegalArgumentException.class, () -> Roofs.Material.of("minecraft:stone", "minecraft:stone_slab", "minecraft:stone"));
        assertThrows(IllegalArgumentException.class, () -> Roofs.Material.of("minecraft:stone_stairs", "minecraft:stone", "minecraft:stone"));
        assertThrows(IllegalArgumentException.class, () -> Roofs.Options.defaults().withOverhang(4));
    }
}
