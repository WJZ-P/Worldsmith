package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded local network policy, not a world-wide road graph. */
public record WorldsmithRoadSettings(BlockState material,Optional<BlockState> stairs,Optional<BlockState> bridge,int width,int maxSpan,int maxCut,
    int radius,boolean terrainFollowing,int maxElevationDifference) {
    public static final Codec<WorldsmithRoadSettings> CODEC=RecordCodecBuilder.<WorldsmithRoadSettings>create(i->i.group(
        BlockState.CODEC.fieldOf("material").forGetter(WorldsmithRoadSettings::material),
        BlockState.CODEC.optionalFieldOf("stairs").forGetter(WorldsmithRoadSettings::stairs),
        BlockState.CODEC.optionalFieldOf("bridge").forGetter(WorldsmithRoadSettings::bridge),
        Codec.intRange(1,3).fieldOf("width").forGetter(WorldsmithRoadSettings::width),
        Codec.intRange(0,48).fieldOf("max_span").forGetter(WorldsmithRoadSettings::maxSpan),
        Codec.intRange(0,4).fieldOf("max_cut").forGetter(WorldsmithRoadSettings::maxCut),
        Codec.intRange(16,96).fieldOf("radius").forGetter(WorldsmithRoadSettings::radius),
        Codec.BOOL.fieldOf("terrain_following").forGetter(WorldsmithRoadSettings::terrainFollowing),
        Codec.intRange(0,32).fieldOf("max_elevation_difference").forGetter(WorldsmithRoadSettings::maxElevationDifference)
    ).apply(i,WorldsmithRoadSettings::new)).validate(r->(r.width==1||r.width==3)&&WorldsmithInstanceProcessor.stable(r.material)
        &&r.bridge.map(WorldsmithInstanceProcessor::stable).orElse(true)
        &&r.stairs.map(s->s.getBlock() instanceof StairBlock&&s.getFluidState().isEmpty()).orElse(true)
        ?DataResult.success(r):DataResult.error(()->"Roads require stable full-block deck materials and optional actual stair blocks"));
}
