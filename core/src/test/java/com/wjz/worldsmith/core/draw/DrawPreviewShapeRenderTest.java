package com.wjz.worldsmith.core.draw;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class DrawPreviewShapeRenderTest {
    private static final int BACKGROUND = 0x18242d;
    private static final Box FRAME = Box.of(-1, -1, -1, 1, 1, 1);
    private static DrawVoxel voxel(int x, int y, int z, String state) {
        return new DrawVoxel(new Vec3i(x, y, z), new DrawBlock(BlockStateRef.parse(state)));
    }
    private static DrawStructure single(String state) {
        return new DrawStructure(FRAME, List.of(voxel(0, 0, 0, state)), Map.of());
    }
    private static BufferedImage preview(DrawStructure drawing, String view, Map<String, Integer> colors) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(DrawPreview.png(drawing, view, null, drawing.bounds(), List.of(), "material", colors)));
    }
    private static int frontPixel(BufferedImage image, double x, double y) {
        return image.getRGB((int)Math.round(640 + x * (850.0 / 3)), (int)Math.round(485 - y * (850.0 / 3))) & 0xffffff;
    }

    @Test void topAndBottomSlabsHaveDifferentRealSilhouettesAtTheSameFrame() throws Exception {
        var bottom = preview(single("stone_slab[type=bottom]"), "front", Map.of());
        var top = preview(single("stone_slab[type=top]"), "front", Map.of());
        assertEquals(BACKGROUND, frontPixel(bottom, 0, .25));
        assertNotEquals(BACKGROUND, frontPixel(bottom, 0, -.25));
        assertNotEquals(BACKGROUND, frontPixel(top, 0, .25));
        assertEquals(BACKGROUND, frontPixel(top, 0, -.25));
        var full = DrawPreview.png(single("stone"), "isometric", null, FRAME, List.of(), "clay");
        var doubled = DrawPreview.png(single("stone_slab[type=double]"), "isometric", null, FRAME, List.of(), "clay");
        assertArrayEquals(full, doubled, "Double slabs remain the full cube shape, not two separately shaded slabs");
    }

    @Test void stairsExposeTheCorrectHighHalfInsteadOfAFullVoxel() throws Exception {
        var east = preview(single("stone_stairs[facing=east,half=bottom,shape=straight]"), "front", Map.of());
        assertEquals(BACKGROUND, frontPixel(east, -.25, .25));
        assertNotEquals(BACKGROUND, frontPixel(east, .25, .25));
        assertNotEquals(BACKGROUND, frontPixel(east, -.25, -.25));
        var top = preview(single("stone_stairs[facing=east,half=top,shape=straight]"), "front", Map.of());
        assertEquals(BACKGROUND, frontPixel(top, -.25, -.25));
        assertNotEquals(BACKGROUND, frontPixel(top, -.25, .25));
    }

    @Test void partialFrontNeighborDoesNotEraseTheUncoveredRearFace() throws Exception {
        var drawing = new DrawStructure(FRAME, List.of(
                voxel(0, 0, 0, "red_wool"), voxel(0, 0, -1, "oak_slab[type=bottom]")), Map.of());
        var image = preview(drawing, "front", Map.of("minecraft:red_wool", 0xff0000, "minecraft:oak_slab", 0x0000ff));
        assertEquals(0xe50000, frontPixel(image, 0, .25), "Rear cube remains visible through the slab's absent upper half");
        assertEquals(0x0000e5, frontPixel(image, 0, -.25), "Front slab covers only its occupied half");
    }

    @Test void clayTopDistinguishesRecessedAndFullHeightWithinOneStair() throws Exception {
        var drawing = single("stone_stairs[facing=east,half=bottom,shape=straight]");
        var image = ImageIO.read(new ByteArrayInputStream(DrawPreview.png(drawing, "top", null, FRAME, List.of(), "clay")));
        assertNotEquals(image.getRGB(569, 485), image.getRGB(711, 485), "Height shading follows the actual two stair treads");
    }

    @Test void adjoiningRoofPanelsDoNotLeakBrightInteriorAtSharedPolygonEdges() throws Exception {
        var bounds = Box.of(-3, -2, -3, 3, 2, 3);
        var cells = new ArrayList<DrawVoxel>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            cells.add(voxel(x, 0, z, "magenta_wool"));
            cells.add(voxel(x, 1, z, "blackstone_slab[type=bottom]"));
        }
        var image = preview(new DrawStructure(bounds, cells, Map.of()), "top",
                Map.of("minecraft:blackstone_slab", 0x000000, "minecraft:magenta_wool", 0xff00ff));
        // Stay inside the roof silhouette but cross every cell/half-cell join. A bright underlayer
        // makes even small per-polygon antialias leakage visible, not just complete background holes.
        for (int y = 195; y <= 775; y++) for (int x = 350; x <= 930; x++)
            assertEquals(0x000000, image.getRGB(x, y) & 0xffffff, "Shared roof edge at " + x + "," + y);

        var open = new ArrayList<>(cells);
        open.removeIf(v -> v.position().x() == 0 && v.position().z() == 0 && v.position().y() == 1);
        var withOpening = preview(new DrawStructure(bounds, open, Map.of()), "top",
                Map.of("minecraft:blackstone_slab", 0x000000, "minecraft:magenta_wool", 0xff00ff));
        assertEquals(0xff00ff, withOpening.getRGB(640, 485) & 0xffffff, "A real opening still exposes the bright interior");
    }

    @Test void negativeAndExtremeCoordinatesRemainBoundedAndRenderable() throws Exception {
        for (int coordinate : new int[]{-120, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            var position = new Vec3i(coordinate, coordinate, coordinate);
            var drawing = new DrawStructure(new Box(position, position), List.of(new DrawVoxel(position,
                    new DrawBlock(BlockStateRef.parse("stone_stairs[facing=north,half=bottom,shape=outer_left]")))), Map.of());
            for (String view : List.of("front", "back", "isometric", "isometric_back", "top")) {
                var image = preview(drawing, view, Map.of());
                assertEquals(1280, image.getWidth());
                assertEquals(1000, image.getHeight());
                int colored = 0;
                for (int y = 100; y < 850; y += 10) for (int x = 100; x < 1180; x += 10)
                    if ((image.getRGB(x, y) & 0xffffff) != BACKGROUND) colored++;
                assertTrue(colored > 100, view + ", coordinate=" + coordinate);
            }
        }
    }

    @Test void sparseFaceBudgetIsCheckedBeforeRasterAllocation() {
        int count = DrawPreview.MAX_FACES / 3 + 1;
        var cells = new ArrayList<DrawVoxel>(count);
        var block = new DrawBlock(BlockStateRef.of("stone"));
        for (int x = 0; x < count; x++) cells.add(new DrawVoxel(new Vec3i(x * 2, 0, 0), block));
        var drawing = new DrawStructure(Box.of(0, 0, 0, (count - 1) * 2, 0, 0), cells, Map.of());
        var failure = assertThrows(IllegalArgumentException.class, () -> DrawPreview.png(drawing, "isometric", null));
        assertTrue(failure.getMessage().contains("face budget exceeded"));
    }

    @Test void sliceImageAndCountDescribeOnlyTheSelectedSourceLayer() throws Exception {
        var kept = voxel(0, 0, 0, "stone_slab[type=bottom]");
        var drawing = new DrawStructure(FRAME, List.of(kept, voxel(0, 1, 0, "gold_block")), Map.of());
        var isolated = new DrawStructure(FRAME, List.of(kept), Map.of());
        assertArrayEquals(DrawPreview.png(isolated, "slice", 0), DrawPreview.png(drawing, "slice", 0),
                "Same selected layer and frame produce the same geometry and selected-cell label");
    }
}
