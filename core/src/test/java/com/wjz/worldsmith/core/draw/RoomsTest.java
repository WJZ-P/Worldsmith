package com.wjz.worldsmith.core.draw;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoomsTest {
    private static Map<Vec3i, BlockStateRef> furnished(Rooms.Use use, Box... keep) {
        var canvas = new DrawCanvas(Box.of(-2, 0, -2, 14, 8, 10));
        Rooms.furnish(canvas.pen("minecraft:stone"), Box.of(1, 1, 1, 10, 4, 7), use, Rooms.Style.of("dark_oak", "blue"), keep);
        Map<Vec3i, BlockStateRef> cells = new HashMap<>();
        for (var voxel : canvas.snapshot().voxels()) cells.put(voxel.position(), voxel.block().state());
        return cells;
    }

    @Test void eachUseSetsWhatTheRoomIsFor() {
        var bedroom = furnished(Rooms.Use.BEDROOM);
        assertTrue(bedroom.values().stream().anyMatch(s -> s.id().equals("minecraft:blue_bed") && "head".equals(s.properties().get("part"))));
        assertTrue(bedroom.values().stream().anyMatch(s -> s.id().equals("minecraft:blue_carpet")), "a rug in the middle");
        var tavern = furnished(Rooms.Use.TAVERN);
        assertTrue(tavern.values().stream().filter(s -> s.id().equals("minecraft:dark_oak_slab")).count() >= 4 + 3, "tables and a counter");
        assertTrue(tavern.values().stream().filter(s -> s.id().equals("minecraft:dark_oak_stairs")).count() >= 8, "seats round the tables");
        var forge = furnished(Rooms.Use.FORGE);
        assertTrue(forge.values().stream().anyMatch(s -> s.id().equals("minecraft:anvil")));
        var furnace = forge.entrySet().stream().filter(e -> e.getValue().id().equals("minecraft:blast_furnace")).findFirst().orElseThrow();
        var at = furnace.getKey();
        // Its front faces away from the wall it stands against.
        String expected = at.z() == 1 ? "south" : at.x() == 10 ? "west" : at.z() == 7 ? "north" : "east";
        assertEquals(expected, furnace.getValue().properties().get("facing"));
    }

    @Test void doorsWindowsAndWhatIsAlreadyThereAreReadFromTheCanvas() {
        var canvas = new DrawCanvas(Box.of(-2, 0, -2, 14, 8, 10));
        var pen = canvas.pen("minecraft:stone");
        var walls = Box.of(0, 1, 0, 11, 5, 8);
        var frame = Walls.Frame.of("stripped_oak_log", "oak_log", "calcite", "cobblestone");
        Walls.frame(pen, walls, frame);
        Walls.door(pen, walls, Walls.Side.SOUTH, 5, "oak_door");
        Walls.windows(pen, walls, Walls.Side.NORTH, frame, Walls.Window.of("glass_pane"));
        Furniture.againstWall(pen, new Vec3i(10, 1, 4), Walls.Side.EAST, "lectern");
        Rooms.furnish(pen, Rooms.inside(walls), Rooms.Use.LIBRARY, Rooms.Style.of("oak", "green"));
        Map<Vec3i, BlockStateRef> cells = new HashMap<>();
        for (var voxel : canvas.snapshot().voxels()) cells.put(voxel.position(), voxel.block().state());

        for (int x = 4; x <= 6; x++) assertTrue(cells.get(new Vec3i(x, 1, 7)).isAir(), "the doorway is kept clear without being named: " + x);
        for (int x : new int[]{2, 5, 9}) {
            var sill = cells.get(new Vec3i(x, 2, 1));
            assertTrue(sill.isAir() || sill.id().equals("minecraft:lantern"), "nothing tall in front of the window at x=" + x + ": " + sill);
        }
        assertEquals("minecraft:lectern", cells.get(new Vec3i(10, 1, 4)).id(), "what was drawn stays");
        assertTrue(cells.get(new Vec3i(10, 1, 3)).isAir() && cells.get(new Vec3i(10, 1, 5)).isAir(), "and keeps its own clearance");
        assertTrue(cells.values().stream().filter(s -> s.id().equals("minecraft:bookshelf")).count() > 8, "the other walls are still shelved");
    }

    @Test void theWalkwayRingAndKeptAreasStayEmpty() {
        for (var use : Rooms.Use.values()) {
            var cells = furnished(use, Box.of(5, 1, 7, 5, 1, 7));
            for (int x = 2; x <= 9; x++) for (int z = 2; z <= 6; z++) {
                boolean ring = x == 2 || x == 9 || z == 2 || z == 6;
                if (ring) assertNull(cells.get(new Vec3i(x, 1, z)), use + " walkway at " + x + "," + z);
            }
            for (int x = 4; x <= 6; x++) assertNull(cells.get(new Vec3i(x, 1, 7)), use + " beside the door");
            assertNull(cells.get(new Vec3i(5, 1, 4)), use + " leaves the centre a declared room walks to");
        }
        assertThrows(IllegalArgumentException.class, () -> Rooms.Style.of("stone", "red"));
        assertThrows(IllegalArgumentException.class, () -> Rooms.Style.of("oak", "crimson"));
    }
}
