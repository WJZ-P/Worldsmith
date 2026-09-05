package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Placement policy shared by every variant and every piece of one rigid assembly. */
public record WorldsmithStructureSite(String surface,int minY,int maxY,int layer,int searchRadius,int minAirBelow,
    int maxHeightDifference,String foundation,BlockState foundationState,int maxDepth,int maxCut,int maxBlocks) {
    public static final Codec<WorldsmithStructureSite> CODEC=RecordCodecBuilder.<WorldsmithStructureSite>create(i->i.group(
        Codec.STRING.fieldOf("surface").forGetter(WorldsmithStructureSite::surface),
        Codec.intRange(-2032,2031).fieldOf("min_y").forGetter(WorldsmithStructureSite::minY),
        Codec.intRange(-2032,2031).fieldOf("max_y").forGetter(WorldsmithStructureSite::maxY),
        Codec.intRange(0,15).fieldOf("layer").forGetter(WorldsmithStructureSite::layer),
        Codec.intRange(0,16).fieldOf("search_radius").forGetter(WorldsmithStructureSite::searchRadius),
        Codec.intRange(1,64).fieldOf("min_air_below").forGetter(WorldsmithStructureSite::minAirBelow),
        Codec.intRange(0,12).fieldOf("max_height_difference").forGetter(WorldsmithStructureSite::maxHeightDifference),
        Codec.STRING.fieldOf("foundation").forGetter(WorldsmithStructureSite::foundation),
        BlockState.CODEC.fieldOf("foundation_state").forGetter(WorldsmithStructureSite::foundationState),
        Codec.intRange(0,16).fieldOf("max_depth").forGetter(WorldsmithStructureSite::maxDepth),
        Codec.intRange(0,8).fieldOf("max_cut").forGetter(WorldsmithStructureSite::maxCut),
        Codec.intRange(1,8192).fieldOf("max_blocks").forGetter(WorldsmithStructureSite::maxBlocks)
    ).apply(i,WorldsmithStructureSite::new)).validate(s->{
        if(!List.of("LAND_SURFACE","OCEAN_FLOOR","WATER_SURFACE","SKY_SURFACE","CAVE_FLOOR","CAVE_CEILING").contains(s.surface)||s.minY>s.maxY||!List.of("NONE","FILL","PILLARS").contains(s.foundation))return DataResult.error(()->"Invalid structure site policy");
        if(!s.foundation.equals("NONE")&&(s.maxDepth==0||s.foundationState.isAir()||!s.foundationState.getFluidState().isEmpty()||!s.foundationState.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO)))return DataResult.error(()->"Foundation requires a dry full block and positive depth");
        if(s.foundation.equals("NONE")&&s.maxDepth!=0||s.surface.equals("CAVE_CEILING")&&!s.foundation.equals("NONE")||s.maxCut>0&&(!s.surface.equals("LAND_SURFACE")||!s.foundation.equals("FILL")))return DataResult.error(()->"Unused or contradictory foundation/earthwork settings");
        return DataResult.success(s);
    });
}
