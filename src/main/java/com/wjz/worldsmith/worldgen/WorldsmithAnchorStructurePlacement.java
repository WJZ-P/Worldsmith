package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

/** Optional native placement type: the candidate chunk contains the exact terrain anchor pivot. */
public final class WorldsmithAnchorStructurePlacement extends StructurePlacement {
    public static final MapCodec<WorldsmithAnchorStructurePlacement> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        WorldsmithStructureAnchor.CODEC.fieldOf("anchor").forGetter(WorldsmithAnchorStructurePlacement::anchor),
        Codec.intRange(0, Integer.MAX_VALUE).fieldOf("salt").forGetter(p -> p.salt())
    ).apply(i, WorldsmithAnchorStructurePlacement::new));

    private final WorldsmithStructureAnchor anchor;

    public WorldsmithAnchorStructurePlacement(WorldsmithStructureAnchor anchor, int salt) {
        super(Vec3i.ZERO, FrequencyReductionMethod.DEFAULT, 1, salt, Optional.empty());
        this.anchor = anchor;
    }

    public WorldsmithStructureAnchor anchor() { return anchor; }

    public static WorldsmithAnchorFields.NoiseSampler noise(RandomState state) {
        return state.getOrCreateNoise(WorldsmithAnchorFields.JITTER_NOISE)::getValue;
    }

    @Override protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int x, int z) {
        return anchor.inChunk(new ChunkPos(x, z), anchor instanceof WorldsmithStructureAnchor.Scattered ? noise(state.randomState()) : null).isPresent();
    }

    @Override public BlockPos getLocatePos(ChunkPos chunk) {
        return anchor instanceof WorldsmithStructureAnchor.Fixed fixed
            ? new BlockPos(fixed.x(), 0, fixed.z()) : chunk.getMiddleBlockPosition(0);
    }

    @Override public StructurePlacementType<?> type() { return WorldsmithStructureTypes.anchorPlacement(); }
}
