package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.draw.BlockStateRef;
import com.wjz.worldsmith.core.draw.DrawBlock;
import com.wjz.worldsmith.core.draw.DrawPreviewShapes;
import com.wjz.worldsmith.core.draw.GridTransform;
import com.wjz.worldsmith.core.draw.Vec3i;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Registry and block-shape comparison only: no client, server, level, chunks or GameTest. */
class DrawPreviewShapeParityTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void allStairStatesAndExporterOrientationsMatchNativeShapeSamples() {
        for (var facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            for (var half : Half.values()) for (var shape : StairsShape.values()) {
                var nativeState = Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing)
                        .setValue(StairBlock.HALF, half).setValue(StairBlock.SHAPE, shape);
                var symbolic = BlockStateRef.parse("stone_stairs[facing=" + facing.getSerializedName() +
                        ",half=" + half.getSerializedName() + ",shape=" + shape.getSerializedName() + "]");
                compareOrientations(nativeState, symbolic);
            }
        }
    }

    @Test void allSlabLayersAndExporterOrientationsMatchNativeShapeSamples() {
        for (var type : SlabType.values()) {
            var nativeState = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, type);
            compareOrientations(nativeState, BlockStateRef.parse("stone_slab[type=" + type.getSerializedName() + "]"));
        }
    }

    private static void compareOrientations(BlockState nativeState, BlockStateRef symbolic) {
        for (int turns = 0; turns < 4; turns++) for (boolean mirror : new boolean[]{false, true}) {
            var oriented = nativeState.mirror(mirror ? Mirror.FRONT_BACK : Mirror.NONE).rotate(Rotation.values()[turns]);
            int sampled = sampledMask(oriented);
            var block = new DrawBlock(symbolic, new GridTransform(turns, mirror, Vec3i.ZERO));
            assertEquals(sampled, DrawPreviewShapes.mask(block), symbolic.asString() + ", rotation=" + turns + ", mirror=" + mirror);
        }
    }

    private static int sampledMask(BlockState state) {
        var boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
        int mask = 0;
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            double px = .25 + .5 * x, py = .25 + .5 * y, pz = .25 + .5 * z;
            if (boxes.stream().anyMatch(box -> box.contains(px, py, pz))) mask |= 1 << (x | y << 1 | z << 2);
        }
        return mask;
    }
}
