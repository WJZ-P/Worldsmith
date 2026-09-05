package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
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
    public WorldsmithStructureAvoidanceFilter(int radius,int height,int below) {this.radius=radius;this.height=height;this.below=below;}

    @Override protected boolean shouldPlace(PlacementContext context,RandomSource random,BlockPos origin) {
        WorldGenLevel level=context.getLevel();
        StructureManager manager=level.getLevel().structureManager();
        if(level instanceof WorldGenRegion region)manager=manager.forWorldGenRegion(region);
        BoundingBox proposed=new BoundingBox(origin.getX()-radius,origin.getY()-below,origin.getZ()-radius,origin.getX()+radius,origin.getY()+height,origin.getZ()+radius);
        for(int x=Math.floorDiv(proposed.minX(),16);x<=Math.floorDiv(proposed.maxX(),16);x++) {
            for(int z=Math.floorDiv(proposed.minZ(),16);z<=Math.floorDiv(proposed.maxZ(),16);z++) {
                // World generation already supplies the reference neighbourhood.
                // Never request an extra chunk merely to decide decoration.
                if(!level.hasChunk(x,z))continue;
                for(var start:manager.startsForStructure(new ChunkPos(x,z),s->s instanceof WorldsmithTemplateStructure)) {
                    for(var piece:start.getPieces()) {
                        if(piece instanceof WorldsmithTemplatePiece template && template.blocksDecoration(proposed))return false;
                    }
                }
            }
        }
        return true;
    }

    @Override public PlacementModifierType<?> type() {return WorldsmithPlacementModifierTypes.structureAvoidance();}
}
