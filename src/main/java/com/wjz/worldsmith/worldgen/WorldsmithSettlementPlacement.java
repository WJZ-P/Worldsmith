package com.wjz.worldsmith.worldgen;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Fits detached buildings independently, then requires their local road graph to succeed. */
public final class WorldsmithSettlementPlacement {
    public record Result(List<WorldsmithRoadPlanner.PlacedPart> parts,Map<BlockPos,BlockState> roads) {}
    private WorldsmithSettlementPlacement() {}
    public static Optional<Result> fit(WorldsmithStructurePlan plan,WorldsmithStructureSite site,Optional<WorldsmithRoadSettings> roads,
        BlockPos anchor,Rotation rotation,WorldsmithTerrainProbe.Sampler sampler,int minY,int maxY,java.util.function.Predicate<BlockPos> biome) {
        List<WorldsmithRoadPlanner.PlacedPart> parts=new ArrayList<>();
        if(roads.isPresent()&&roads.get().terrainFollowing()) {
            int lowest=Integer.MAX_VALUE,highest=Integer.MIN_VALUE;long work=0;
            for(var part:plan.parts()) {
                var local=new WorldsmithStructurePlan.Part(part.template(),BlockPos.ZERO,Rotation.NONE,part.size(),part.reserved(),part.footprint(),part.supports(),part.detail());
                var single=new WorldsmithStructurePlan(List.of(local),part.footprint(),part.supports(),part.size().getY(),local.bounds(),List.of());
                var combined=Rotation.values()[(rotation.ordinal()+part.rotation().ordinal())&3];
                var corner=part.offset().rotate(rotation).offset(anchor.atY(0));
                var fitted=WorldsmithTerrainProbe.probe(single,site,corner,combined,minY,maxY,sampler);
                if(!fitted.accepted())return Optional.empty();
                var p=fitted.plan();lowest=Math.min(lowest,p.position().getY());highest=Math.max(highest,p.position().getY());
                if(highest-lowest>roads.get().maxElevationDifference())return Optional.empty();
                work+=p.foundations().stream().mapToInt(BoundingBox::getYSpan).sum()+p.cuts().stream().mapToInt(BoundingBox::getYSpan).sum();
                if(work>8192)return Optional.empty();
                parts.add(new WorldsmithRoadPlanner.PlacedPart(part,p.position(),combined,p.foundations(),p.cuts()));
            }
        } else {
            var fitted=WorldsmithTerrainProbe.probe(plan,site,anchor,rotation,minY,maxY,sampler);if(!fitted.accepted())return Optional.empty();
            for(int i=0;i<plan.parts().size();i++) {
                var part=plan.parts().get(i);var combined=Rotation.values()[(rotation.ordinal()+part.rotation().ordinal())&3];
                parts.add(new WorldsmithRoadPlanner.PlacedPart(part,part.offset().rotate(rotation).offset(fitted.plan().position()),combined,
                    i==0?fitted.plan().foundations():List.of(),i==0?fitted.plan().cuts():List.of()));
            }
        }
        if(!biome.test(anchor.atY(parts.getFirst().position().getY())))return Optional.empty();
        if(roads.map(WorldsmithRoadSettings::terrainFollowing).orElse(false)&&parts.stream().anyMatch(p->!biome.test(p.transform(new BlockPos(p.part().size().getX()/2,0,p.part().size().getZ()/2)))))return Optional.empty();
        if(roads.isEmpty())return Optional.of(new Result(List.copyOf(parts),Map.of()));
        return WorldsmithRoadPlanner.plan(plan,parts,roads.get(),anchor,sampler,minY,maxY).map(blocks->new Result(List.copyOf(parts),blocks));
    }
}
