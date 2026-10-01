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

    @Test void theWalkwayRingAndKeptAreasStayEmpty() {
        for (var use : Rooms.Use.values()) {
            var cells = furnished(use, Box.of(5, 1, 7, 5, 1, 7));
            for (int x = 2; x <= 9; x++) for (int z = 2; z <= 6; z++) {
                boolean ring = x == 2 || x == 9 || z == 2 || z == 6;
                if (ring) assertNull(cells.get(new Vec3i(x, 1, z)), use + " walkway at " + x + "," + z);
            }
            for (int x = 4; x <= 6; x++) assertNull(cells.get(new Vec3i(x, 1, 7)), use + " beside the door");
        }
        assertThrows(IllegalArgumentException.class, () -> Rooms.Style.of("stone", "red"));
        assertThrows(IllegalArgumentException.class, () -> Rooms.Style.of("oak", "crimson"));
    }
}
