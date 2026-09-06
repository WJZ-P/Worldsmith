package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Excludes only overlapping 3D volumes; a sky building does not erase forest below it. */
public final class WorldsmithStructureAvoidanceFilter extends PlacementFilter {
    public static final MapCodec<WorldsmithStructureAvoidanceFilter> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
        Codec.intRange(0,16).fieldOf("radius").forGetter(f->f.radius),
        Codec.intRange(0,64).fieldOf("height").forGetter(f->f.height),
        Codec.intRange(0,16).fieldOf("below").forGetter(f->f.below)
    ).apply(i,WorldsmithStructureAvoidanceFilter::new));
    private final int radius, height, below;
    // Plans/roads reach at most 96 blocks from the pivot, plus 16 blocks of site search.
    private static final int MAX_START_REACH=96+16;
    public WorldsmithStructureAvoidanceFilter(int radius,int height,int below) {this.radius=radius;this.height=height;this.below=below;}

    @Override protected boolean shouldPlace(PlacementContext context,RandomSource random,BlockPos origin) {
        WorldGenLevel level=context.getLevel();
        BoundingBox proposed=new BoundingBox(origin.getX()-radius,origin.getY()-below,origin.getZ()-radius,origin.getX()+radius,origin.getY()+height,origin.getZ()+radius);
        return clearOfStarts(level,proposed);
    }

    static boolean clearOfStarts(WorldGenLevel level,BoundingBox proposed) {
        for(int x=Math.floorDiv(proposed.minX()-MAX_START_REACH,16);x<=Math.floorDiv(proposed.maxX()+MAX_START_REACH,16);x++) {
            for(int z=Math.floorDiv(proposed.minZ()-MAX_START_REACH,16);z<=Math.floorDiv(proposed.maxZ()+MAX_START_REACH,16);z++) {
                // FEATURES guarantees STRUCTURE_STARTS at radius 8, but only guarantees
                // STRUCTURE_REFERENCES near the centre. hasChunk alone never proved that
                // references were legal to request. Inspect the owning starts directly.
                // Unavailable coverage conservatively suppresses this decoration only.
                if(!level.hasChunk(x,z))return false;
                var chunk=level instanceof WorldGenRegion
                    ?level.getChunk(x,z,ChunkStatus.EMPTY,false)
                    :level.getChunk(x,z,ChunkStatus.STRUCTURE_STARTS,false);
                if(chunk==null||!chunk.getPersistedStatus().isOrAfter(ChunkStatus.STRUCTURE_STARTS))return false;
                for(var start:chunk.getAllStarts().values()) {
                    if(!start.isValid()||!(start.getStructure() instanceof WorldsmithTemplateStructure)||!start.getBoundingBox().intersects(proposed))continue;
                    for(var piece:start.getPieces()) {
                        if(piece instanceof WorldsmithTemplatePiece template && template.blocksDecoration(proposed))return false;
                        if(piece instanceof WorldsmithRoadPiece road && road.blocksDecoration(proposed))return false;
                    }
                }
            }
        }
        return true;
    }

    @Override public PlacementModifierType<?> type() {return WorldsmithPlacementModifierTypes.structureAvoidance();}
}
