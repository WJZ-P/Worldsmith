package com.wjz.worldsmith.worldgen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Bounded, side-effect-free site fitting, shared by generation and diagnostic tests. */
public final class WorldsmithTerrainProbe {
    public enum Rejection { WRONG_FLUID, EXCESSIVE_SLOPE, OUTSIDE_WORLD, MISSING_SUPPORT }
    public record Column(int groundY, int surfaceY) {}
    public record Plan(BlockPos position, List<BoundingBox> foundations) {}
    public record Result(Plan plan, Rejection rejection) {
        public boolean accepted() {return this.plan != null;}
    }
    @FunctionalInterface public interface Sampler { Column sample(int x,int z); }
    private WorldsmithTerrainProbe() {}

    public static Result probe(WorldsmithTemplateStructure.Settings config,BlockPos anchor,Rotation rotation,
        int worldMin,int worldMax,Sampler sampler) {
        BlockPos translated=anchor.atY(0).subtract(config.origin().rotate(rotation));
        List<BlockPos> samples=new ArrayList<>();
        int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
        for(BlockPos local:config.supports()) {
            BlockPos pos=local.rotate(rotation).offset(translated);
            Column column=sampler.sample(pos.getX(),pos.getZ());
            if(config.surface().equals("LAND_SURFACE") && column.surfaceY()>column.groundY() ||
                config.surface().equals("OCEAN_FLOOR") && column.surfaceY()<=column.groundY())return rejected(Rejection.WRONG_FLUID);
            samples.add(new BlockPos(pos.getX(),column.groundY(),pos.getZ()));
            min=Math.min(min,column.groundY());max=Math.max(max,column.groundY());
        }
        if(samples.isEmpty())return rejected(Rejection.MISSING_SUPPORT);
        if(max-min>config.maxHeightDifference())return rejected(Rejection.EXCESSIVE_SLOPE);
        if(min<worldMin+1 || max+config.size().getY()>worldMax+1)return rejected(Rejection.OUTSIDE_WORLD);
        if(config.foundation().equals("NONE") && max!=min || !config.foundation().equals("NONE") && max-min>config.maxDepth())return rejected(Rejection.MISSING_SUPPORT);
        List<BoundingBox> columns=new ArrayList<>();
        if(!config.foundation().equals("NONE"))for(BlockPos sample:samples) {
            if(sample.getY()<max)columns.add(new BoundingBox(sample.getX(),sample.getY(),sample.getZ(),sample.getX(),max-1,sample.getZ()));
        }
        return new Result(new Plan(new BlockPos(translated.getX(),max,translated.getZ()),List.copyOf(columns)),null);
    }

    private static Result rejected(Rejection rejection) {return new Result(null,rejection);}
}
