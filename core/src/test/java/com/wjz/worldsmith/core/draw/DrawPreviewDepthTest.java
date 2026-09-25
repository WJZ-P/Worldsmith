package com.wjz.worldsmith.core.draw;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DrawPreviewDepthTest {
    @Test void halfGridTJunctionsRemainWatertightInReverseIsometricView() throws Exception {
        // These points lie inside uniform visible faces, not near silhouette/colour boundaries.
        // A full cube edge meeting two half edges formerly rasterized with tiny bright/dark cracks
        // even after disabling per-face antialiasing. Preserve common half-grid edge vertices.
        int[][] cases = {
                {0, 559, 320, 0x3030f0}, {0, 635, 396, 0x3030f0},
                {3, 540, 510, 0x2424b8}, {3, 730, 605, 0x2424b8}, {4, 502, 472, 0x3030f0}
        };
        for (int seed : new int[]{0, 3, 4}) {
            var drawing = fixture(seed);
            var colors = Map.of("minecraft:stone", 0xf03030, "minecraft:stone_slab", 0x30f030, "minecraft:stone_stairs", 0x3030f0);
            var image = ImageIO.read(new ByteArrayInputStream(DrawPreview.png(drawing, "isometric_back", null,
                    drawing.bounds(), List.of(), "material", colors)));
            for (int[] sample : cases) if (sample[0] == seed)
                assertEquals(sample[3], image.getRGB(sample[1], sample[2]) & 0xffffff,
                        "Half-grid junction seed=" + seed + " at " + sample[1] + "," + sample[2]);
        }
    }

    private static DrawStructure fixture(int seed) {
        var states = List.of("stone", "stone_slab[type=bottom]", "stone_slab[type=top]",
                "stone_stairs[facing=east,shape=inner_left]", "stone_stairs[facing=north,shape=outer_left]",
                "stone_stairs[facing=south,half=top,shape=straight]");
        var random = new Random(seed);
        var voxels = new ArrayList<DrawVoxel>();
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) {
            if (random.nextInt(4) == 0) continue;
            var block = new DrawBlock(BlockStateRef.parse(states.get(random.nextInt(states.size()))));
            voxels.add(new DrawVoxel(new Vec3i(x, y, z), block));
        }
        return new DrawStructure(Box.of(-1, -1, -1, 4, 4, 4), voxels, Map.of());
    }
}
