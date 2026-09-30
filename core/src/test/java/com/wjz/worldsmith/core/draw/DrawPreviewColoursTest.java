package com.wjz.worldsmith.core.draw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DrawPreviewColoursTest {
    private static int channel(int rgb, int shift) { return (rgb >> shift) & 0xff; }

    @Test void vanillaBlocksHaveTheirMeasuredColourAndFormsShareTheirBlocks() {
        int mud = DrawPreviewColours.average("minecraft:mud_bricks");
        assertTrue(channel(mud, 16) > channel(mud, 0) + 30, "mud bricks are brown, not a hashed hue");
        assertEquals(mud, DrawPreviewColours.average("minecraft:mud_brick_stairs"), "a stair shows its block's texture");
        assertEquals(DrawPreviewColours.average("minecraft:oak_planks"), DrawPreviewColours.average("minecraft:oak_fence"));
        int terracotta = DrawPreviewColours.average("minecraft:white_terracotta");
        assertTrue(channel(terracotta, 16) - channel(terracotta, 0) > 30, "white terracotta is warm beige, which a preview must not hide");
        assertNull(DrawPreviewColours.average("minecraft:oak_leaves"), "tinted textures are grey until tinted, so they are not measured");
        assertNull(DrawPreviewColours.average("custom:thing"));
    }
}
