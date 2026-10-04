package com.wjz.worldsmith.core.draw;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DrawPreviewShapesTest {
    private static DrawBlock block(String text) { return new DrawBlock(BlockStateRef.parse(text)); }
    private static DrawBlock stair(String facing, String half, String shape) {
        return block("minecraft:stone_stairs[facing=" + facing + ",half=" + half + ",shape=" + shape + "]");
    }

    @Test void slabLayersDefaultsAndUnknownStatesStayExplicit() {
        int bottom = DrawPreviewShapes.mask(block("stone_slab[type=bottom]"));
        int top = DrawPreviewShapes.mask(block("stone_slab[type=top]"));
        assertEquals(4, Integer.bitCount(bottom));
        assertEquals(4, Integer.bitCount(top));
        assertEquals(0, bottom & top);
        assertEquals(255, bottom | top);
        assertEquals(bottom, DrawPreviewShapes.mask(block("stone_slab")));
        assertEquals(255, DrawPreviewShapes.mask(block("stone_slab[type=double]")));
        for (String state : List.of("stone", "glass", "custom:stone_slab[type=bottom]", "stone_slab[type=sideways]",
                "stone_stairs[facing=up]", "stone_stairs[half=left]", "stone_stairs[shape=diagonal]"))
            assertEquals(255, DrawPreviewShapes.mask(block(state)), state);
        assertEquals(0, DrawPreviewShapes.mask(block("air")));
        assertEquals(0, DrawPreviewShapes.mask(block("cave_air")));
    }

    @Test void thinBlocksAreDrawnFromTheirModelBoxes() {
        var fence = DrawPreviewShapes.parts(block("oak_fence[north=true,east=false,south=false,west=true]"));
        assertEquals(new DrawPreviewShapes.Part(6, 0, 6, 10, 16, 10), fence.get(0), "the post");
        assertTrue(fence.contains(new DrawPreviewShapes.Part(7, 12, 0, 9, 15, 9)), "an upper rail to the north");
        assertTrue(fence.contains(new DrawPreviewShapes.Part(0, 6, 7, 9, 9, 9)), "a lower rail to the west");
        assertEquals(5, fence.size());
        // The door model for facing=east is a panel on the west side of its cell.
        assertEquals(List.of(new DrawPreviewShapes.Part(0, 0, 0, 3, 16, 16)), DrawPreviewShapes.parts(block("oak_door[facing=east,half=lower]")));
        var wall = DrawPreviewShapes.parts(block("cobblestone_wall[up=false,north=low,south=tall]"));
        assertTrue(wall.contains(new DrawPreviewShapes.Part(5, 0, 0, 11, 14, 8)) && wall.contains(new DrawPreviewShapes.Part(5, 0, 8, 11, 16, 16)) && wall.size() == 2);
        // A quarter turn carries the connections with the geometry, as native rotation turns the state.
        var turned = DrawPreviewShapes.parts(new DrawBlock(BlockStateRef.parse("glass_pane[north=true]"), GridTransform.rotateY(1)));
        assertTrue(turned.contains(new DrawPreviewShapes.Part(9, 0, 7, 16, 16, 9)), turned.toString());
        for (String full : List.of("stone", "jack_o_lantern", "sea_lantern", "glass", "oak_planks"))
            assertTrue(DrawPreviewShapes.parts(block(full)).isEmpty(), full);
        assertFalse(DrawPreviewShapes.parts(block("lantern[hanging=true]")).isEmpty());
    }

    @Test void paneWrittenWithoutSidesJoinsWhatTheWorldWouldJoin() {
        assertTrue(DrawPreviewShapes.joinsNeighbours(BlockStateRef.parse("glass_pane")));
        assertFalse(DrawPreviewShapes.joinsNeighbours(BlockStateRef.parse("glass_pane[north=true]")), "written sides are kept as written");
        assertFalse(DrawPreviewShapes.joinsNeighbours(BlockStateRef.parse("glass")));
        var pane = DrawPreviewShapes.parts(DrawPreviewShapes.joined(block("glass_pane"), s -> s == 1 || s == 3));
        assertTrue(pane.contains(new DrawPreviewShapes.Part(9, 0, 7, 16, 16, 9)) && pane.contains(new DrawPreviewShapes.Part(0, 0, 7, 7, 16, 9)), "spans to the walls east and west");
        var wall = DrawPreviewShapes.joined(block("stone_brick_wall"), s -> s == 0 || s == 2);
        assertEquals("false", wall.state().properties().get("up"), "a straight run shows no post");
    }

    @Test void trapdoorsAreAPanelWhereTheirLeafLies() {
        assertEquals(DrawPreviewShapes.mask(block("stone_slab[type=bottom]")), DrawPreviewShapes.mask(block("oak_trapdoor[half=bottom,open=false]")));
        assertEquals(DrawPreviewShapes.mask(block("stone_slab[type=top]")), DrawPreviewShapes.mask(block("oak_trapdoor[half=top,open=false]")));
        assertEquals(DrawPreviewShapes.mask(block("stone_slab[type=bottom]")), DrawPreviewShapes.mask(block("red_carpet")), "a rug is not a block");
        // Open, a trapdoor stands against the side of its cell opposite its facing: north-facing lies on the south (+Z) half.
        int north = DrawPreviewShapes.mask(block("oak_trapdoor[facing=north,half=bottom,open=true]"));
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) {
            assertTrue(DrawPreviewShapes.occupied(north, x, y, 1));
            assertFalse(DrawPreviewShapes.occupied(north, x, y, 0));
        }
        int east = DrawPreviewShapes.mask(block("oak_trapdoor[facing=east,half=top,open=true]"));
        assertTrue(DrawPreviewShapes.occupied(east, 0, 1, 0) && !DrawPreviewShapes.occupied(east, 1, 1, 0));
        var mirrored = new DrawBlock(BlockStateRef.parse("oak_trapdoor[facing=east,open=true]"), GridTransform.reflectX());
        assertEquals(DrawPreviewShapes.mask(block("oak_trapdoor[facing=west,open=true]")), DrawPreviewShapes.mask(mirrored));
    }

    @Test void stairsHaveTheExpectedVolumesAndHandedCorners() {
        for (String facing : List.of("north", "east", "south", "west")) {
            for (String half : List.of("bottom", "top")) {
                for (String shape : List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right")) {
                    int mask = DrawPreviewShapes.mask(stair(facing, half, shape));
                    int expected = shape.startsWith("inner") ? 7 : shape.startsWith("outer") ? 5 : 6;
                    assertEquals(expected, Integer.bitCount(mask), facing + "/" + half + "/" + shape);
                    int baseY = half.equals("bottom") ? 0 : 1;
                    for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++)
                        assertTrue(DrawPreviewShapes.occupied(mask, x, baseY, z));
                }
            }
        }
        int outerLeft = DrawPreviewShapes.mask(stair("north", "bottom", "outer_left"));
        assertTrue(DrawPreviewShapes.occupied(outerLeft, 0, 1, 0), "North-west high quarter");
        assertFalse(DrawPreviewShapes.occupied(outerLeft, 1, 1, 0));
        assertFalse(DrawPreviewShapes.occupied(outerLeft, 0, 1, 1));
        int innerLeft = DrawPreviewShapes.mask(stair("north", "bottom", "inner_left"));
        assertFalse(DrawPreviewShapes.occupied(innerLeft, 1, 1, 1), "Only the south-east high quarter is absent");
        assertEquals(DrawPreviewShapes.mask(stair("north", "bottom", "straight")), DrawPreviewShapes.mask(block("stone_stairs")));
    }

    @Test void rotationsAndRepeatedTransformsPreserveShapeTopology() {
        var facings = List.of("north", "east", "south", "west");
        for (int i = 0; i < facings.size(); i++) for (String half : List.of("bottom", "top")) {
            for (String shape : List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right")) {
                var original = stair(facings.get(i), half, shape);
                assertEquals(DrawPreviewShapes.mask(stair(facings.get((i + 1) % 4), half, shape)),
                        DrawPreviewShapes.mask(original.transformed(GridTransform.rotateY(1))), "Clockwise north -> east");
                var rotated = original;
                for (int turn = 0; turn < 4; turn++) rotated = rotated.transformed(GridTransform.rotateY(1));
                assertEquals(DrawPreviewShapes.mask(original), DrawPreviewShapes.mask(rotated));
                assertEquals(DrawPreviewShapes.mask(original),
                        DrawPreviewShapes.mask(original.transformed(GridTransform.reflectX()).transformed(GridTransform.reflectX())));
                assertEquals(DrawPreviewShapes.mask(original),
                        DrawPreviewShapes.mask(original.transformed(GridTransform.translate(Integer.MAX_VALUE, 0, Integer.MIN_VALUE))),
                        "Deferred block orientation never applies position translation twice");
            }
        }
    }

    @Test void internalAndNeighborFacesAreCulledByQuarterRatherThanWholeVoxel() {
        int bottom = DrawPreviewShapes.mask(block("stone_slab[type=bottom]"));
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
            assertEquals(1, DrawPreviewShapes.surfaces(255, axis, sign, 0).size(), "Cube remains one quad per side");
            assertEquals(4, area(DrawPreviewShapes.surfaces(255, axis, sign, 0)));
            assertTrue(DrawPreviewShapes.surfaces(255, axis, sign, 255).isEmpty());
        }
        assertEquals(2, area(DrawPreviewShapes.surfaces(255, 2, -1, bottom)), "Neighbor slab covers only half the cube side");
        assertEquals(0, area(DrawPreviewShapes.surfaces(255, 1, 1, bottom)), "Bottom slab above touches the complete cube top");
        int top = DrawPreviewShapes.mask(block("stone_slab[type=top]"));
        assertEquals(4, area(DrawPreviewShapes.surfaces(255, 1, 1, top)), "Top slab above leaves an air gap over the cube");
        var slabTop = DrawPreviewShapes.surfaces(bottom, 1, 1, 255);
        assertEquals(1, slabTop.size());
        assertEquals(1, slabTop.getFirst().plane(), "A recessed slab top is not culled by an upper neighboring cube");
        assertEquals(4, area(slabTop));
    }

    @Test void stairCornerMirrorsFollowNativeExporterStateSemantics() {
        // Minecraft 26.2 FRONT_BACK is not a pure geometric reflection for every corner state.
        assertEquals(DrawPreviewShapes.mask(stair("north", "bottom", "outer_left")),
                DrawPreviewShapes.mask(stair("north", "bottom", "outer_left").transformed(GridTransform.reflectX())));
        assertEquals(DrawPreviewShapes.mask(stair("west", "bottom", "inner_left")),
                DrawPreviewShapes.mask(stair("east", "bottom", "inner_left").transformed(GridTransform.reflectX())));
        assertEquals(DrawPreviewShapes.mask(stair("west", "top", "outer_right")),
                DrawPreviewShapes.mask(stair("east", "top", "outer_left").transformed(GridTransform.reflectX())));
    }

    @Test void allOctantMasksProduceBoundedNonoverlappingExposedRectangles() {
        // Exhaust every possible local shape, with representative neighbors including arbitrary occupancy.
        // This also protects the clipping algorithm if new half-grid shapes are introduced later.
        for (int mask = 0; mask < 256; mask++) for (int neighbor : new int[]{0, 1, 15, 51, 85, 170, 204, 254, 255}) {
            for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
                Set<String> expected = new HashSet<>();
                int u = (axis + 1) % 3, v = (axis + 2) % 3;
                for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
                    if (!DrawPreviewShapes.occupied(mask, x, y, z)) continue;
                    int[] current = {x, y, z}, next = {x, y, z};
                    next[axis] += sign;
                    boolean external = next[axis] < 0 || next[axis] > 1;
                    next[axis] = Math.floorMod(next[axis], 2);
                    if (!DrawPreviewShapes.occupied(external ? neighbor : mask, next[0], next[1], next[2]))
                        expected.add((current[axis] + (sign > 0 ? 1 : 0)) + ":" + current[u] + ":" + current[v]);
                }
                Set<String> actual = new HashSet<>();
                var surfaces = DrawPreviewShapes.surfaces(mask, axis, sign, neighbor);
                assertTrue(surfaces.size() <= 8, "At most the eight occupied octants can expose a directional face");
                for (var surface : surfaces) {
                    assertTrue(surface.plane() >= 0 && surface.plane() <= 2);
                    assertTrue(surface.u() >= 0 && surface.v() >= 0 && surface.width() >= 1 && surface.height() >= 1);
                    assertTrue(surface.u() + surface.width() <= 2 && surface.v() + surface.height() <= 2);
                    for (int b = surface.u(); b < surface.u() + surface.width(); b++)
                        for (int c = surface.v(); c < surface.v() + surface.height(); c++)
                            assertTrue(actual.add(surface.plane() + ":" + b + ":" + c), "Rectangles never overlap");
                }
                assertEquals(expected, actual, "mask=" + mask + ", neighbor=" + neighbor + ", axis=" + axis + ", sign=" + sign);
            }
        }
    }

    private static int area(List<DrawPreviewShapes.Surface> surfaces) {
        return surfaces.stream().mapToInt(surface -> surface.width() * surface.height()).sum();
    }
}
