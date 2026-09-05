package com.wjz.worldsmith.worldgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;

/** Bounded column-only placement. Never asks worldgen to load another chunk. */
public final class WorldsmithTerrainProbe {
    public static final int MAX_FOUNDATION_BLOCKS=4096;
    public static final int MAX_COLUMNS=12288;
    public enum Rejection { WRONG_FLUID, EXCESSIVE_SLOPE, OUTSIDE_WORLD, MISSING_SUPPORT, FOUNDATION_BUDGET, NO_SURFACE, OBSTRUCTED }
    /** floorY is first air, ceilingY first solid above, or worldMax+1 for open sky. */
    public record AirSpan(int floorY,int ceilingY,boolean solidFloor,boolean waterFloor,int supportY,int airBelow) {}
    public record Column(int groundY,int surfaceY,boolean water,List<AirSpan> spans) {
        public Column(int groundY,int surfaceY){this(groundY,surfaceY,surfaceY>groundY,List.of());}
    }
    public record Plan(BlockPos position,List<BoundingBox> foundations,List<BoundingBox> cuts) {}
    public record Result(Plan plan,Rejection rejection){public boolean accepted(){return plan!=null;}}
    @FunctionalInterface public interface Sampler {Column sample(int x,int z);}
    public static final class ProbeBudgetExceeded extends RuntimeException {}
    public static final class CachedSampler implements Sampler {
        private final Sampler source;private final Map<Long,Column> columns=new HashMap<>();
        public CachedSampler(Sampler source){this.source=source;}
        @Override public Column sample(int x,int z){
            long key=((long)x<<32)|(z&0xffffffffL);var cached=columns.get(key);if(cached!=null)return cached;
            if(columns.size()>=MAX_COLUMNS)throw new ProbeBudgetExceeded();
            var value=source.sample(x,z);columns.put(key,value);return value;
        }
        public int sampledColumns(){return columns.size();}
    }
    private record Surface(int y,int ground,int ceiling) {}
    private WorldsmithTerrainProbe() {}

    public static Column readColumn(NoiseColumn column,int worldMin,int worldMax){return readColumn(column,worldMin,worldMax,null,1);}
    public static Column readColumn(NoiseColumn column,int worldMin,int worldMax,WorldsmithStructureSite site,int height) {
        int ground=worldMin,surface=worldMin;boolean water=false;
        for(int y=worldMax;y>=worldMin;y--){
            var block=column.getBlock(y);
            if(surface==worldMin&&Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(block)){
                surface=y+1;water=block.getFluidState().is(Fluids.WATER)||block.getFluidState().is(Fluids.FLOWING_WATER);
            }
            if(Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(block)){ground=y+1;break;}
        }
        // Land/ocean fitting needs just the two native heightmaps. Special sites
        // also retain bounded air intervals, not thousands of full NoiseColumns.
        if(site!=null&&(site.surface().equals("LAND_SURFACE")||site.surface().equals("OCEAN_FLOOR")))return new Column(ground,surface,water,List.of());
        List<AirSpan> spans=new ArrayList<>();
        int start=Integer.MIN_VALUE,lastSolidTop=worldMin,previousAir=0,airBelowRun=0;boolean previousSolid=false,previousWater=false,solidFloor=false,waterFloor=false;
        int support=worldMin,below=0;
        for(int y=worldMin;y<=worldMax+1;y++) {
            boolean air=y<=worldMax&&column.getBlock(y).isAir();
            if(air) {
                if(start==Integer.MIN_VALUE){start=y;solidFloor=previousSolid;waterFloor=previousWater;support=lastSolidTop;below=airBelowRun;}
                previousAir++;previousSolid=false;previousWater=false;
            } else {
                if(start!=Integer.MIN_VALUE){
                    var span=new AirSpan(start,y,solidFloor,waterFloor,support,below);
                    if(y-start>=height && (site==null||matches(site,span,worldMax)))spans.add(span);
                    start=Integer.MIN_VALUE;
                }
                if(y<=worldMax) {
                    var block=column.getBlock(y);boolean solid=Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(block);
                    if(solid){if(!previousSolid)airBelowRun=previousAir;lastSolidTop=y+1;}
                    previousSolid=solid;previousWater=block.getFluidState().is(Fluids.WATER)||block.getFluidState().is(Fluids.FLOWING_WATER);previousAir=0;
                }
            }
        }
        var ordered=spans.stream().sorted(Comparator.comparingInt(AirSpan::floorY).reversed()).limit(128).toList();
        return new Column(ground,surface,water,ordered);
    }
    private static boolean matches(WorldsmithStructureSite s,AirSpan p,int worldMax) {
        if(s.surface().equals("CAVE_CEILING"))return p.ceilingY<=worldMax&&p.ceilingY>=s.minY()&&p.ceilingY<=s.maxY();
        if(p.floorY<s.minY()||p.floorY>s.maxY())return false;
        return switch(s.surface()){
            case "SKY_SURFACE" -> p.solidFloor&&p.airBelow>=s.minAirBelow();
            case "CAVE_FLOOR" -> p.solidFloor&&p.ceilingY<=worldMax;
            case "WATER_SURFACE" -> p.waterFloor;
            default -> true;
        };
    }
    private static List<Surface> surfaces(WorldsmithStructureSite site,Column c,int worldMax,int height) {
        if(site.surface().equals("LAND_SURFACE"))return c.surfaceY==c.groundY&&inRange(site,c.groundY)?List.of(new Surface(c.groundY,c.groundY,worldMax+1)):List.of();
        if(site.surface().equals("OCEAN_FLOOR"))return c.surfaceY>c.groundY&&inRange(site,c.groundY)?List.of(new Surface(c.groundY,c.groundY,worldMax+1)):List.of();
        if(site.surface().equals("WATER_SURFACE")&&c.spans.isEmpty())return c.water&&c.surfaceY>c.groundY&&inRange(site,c.surfaceY)?List.of(new Surface(c.surfaceY,c.groundY,worldMax+1)):List.of();
        return c.spans.stream().filter(p->p.ceilingY-p.floorY>=height&&matches(site,p,worldMax))
            .map(p->new Surface(site.surface().equals("CAVE_CEILING")?p.ceilingY:p.floorY,site.surface().equals("WATER_SURFACE")?p.supportY:p.floorY,p.ceilingY))
            .sorted(Comparator.comparingInt(Surface::y).reversed()).toList();
    }
    private static boolean inRange(WorldsmithStructureSite s,int y){return y>=s.minY()&&y<=s.maxY();}

    /** Nearest-first coarse grid including the search boundary, capped at 16 pivots. */
    public static List<BlockPos> sites(BlockPos nominal,int radius) {
        List<BlockPos> result=new ArrayList<>();result.add(nominal);
        int step=Math.max(1,(radius+1)/2);
        if(radius>0)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){
            if(x==0&&z==0||x*x+z*z>radius*radius)continue;
            if(x%step==0&&z%step==0)result.add(nominal.offset(x,0,z));
        }
        if(radius>0){result.add(nominal.offset(radius,0,0));result.add(nominal.offset(-radius,0,0));result.add(nominal.offset(0,0,radius));result.add(nominal.offset(0,0,-radius));}
        return result.stream().distinct().sorted(Comparator.comparingDouble((BlockPos p)->nominal.distSqr(p)).thenComparingInt(p->p.getX()).thenComparingInt(p->p.getZ())).limit(16).toList();
    }

    public static Result probe(WorldsmithStructurePlan plan,WorldsmithStructureSite site,BlockPos anchor,Rotation rotation,int worldMin,int worldMax,Sampler sampler) {
        Sampler cached=sampler instanceof CachedSampler?sampler:new CachedSampler(sampler);
        Map<Long,Surface> selected=new HashMap<>();List<Integer> levels=new ArrayList<>();
        int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;Integer reference=null;
        for(var local:plan.footprint()) {
            BlockPos pos=local.rotate(rotation).offset(anchor.atY(0));
            var c=cached.sample(pos.getX(),pos.getZ());
            var candidates=surfaces(site,c,worldMax,plan.height());
            if(candidates.isEmpty())return rejected(site.surface().equals("LAND_SURFACE")||site.surface().equals("OCEAN_FLOOR")?Rejection.WRONG_FLUID:Rejection.NO_SURFACE);
            Surface chosen;
            if(reference==null){if(site.layer()>=candidates.size())return rejected(Rejection.NO_SURFACE);chosen=candidates.get(site.layer());reference=chosen.y;}
            else {int target=reference;chosen=candidates.stream().min(Comparator.comparingInt(p->Math.abs(p.y-target))).orElseThrow();}
            selected.put(WorldsmithStructurePlan.columnKey(local),chosen);levels.add(chosen.y);
            min=Math.min(min,chosen.y);max=Math.max(max,chosen.y);
            if(max-min>site.maxHeightDifference())return rejected(Rejection.EXCESSIVE_SLOPE);
        }
        if(levels.isEmpty())return rejected(Rejection.MISSING_SUPPORT);
        boolean ceiling=site.surface().equals("CAVE_CEILING");
        int base=ceiling?min-plan.height():max;
        if(site.maxCut()>0){levels.sort(Integer::compare);base=levels.get(levels.size()/2);}
        if(base<worldMin+1||base+plan.height()>worldMax+1)return rejected(Rejection.OUTSIDE_WORLD);
        List<BoundingBox> fills=new ArrayList<>(),cuts=new ArrayList<>();int work=0;
        for(var local:plan.footprint()) {
            var selectedSurface=selected.get(WorldsmithStructurePlan.columnKey(local));
            if(base+local.getY()>=selectedSurface.ceiling)return rejected(Rejection.OBSTRUCTED);
            if(ceiling&&base<selectedSurface.ground)return rejected(Rejection.OBSTRUCTED);
            int cut=selectedSurface.ground-base;
            if(!ceiling&&cut>0){
                if(site.maxCut()==0||cut>site.maxCut())return rejected(Rejection.EXCESSIVE_SLOPE);
                work+=cut;if(work>site.maxBlocks())return rejected(Rejection.FOUNDATION_BUDGET);
                BlockPos p=local.rotate(rotation).offset(anchor.atY(0));cuts.add(new BoundingBox(p.getX(),base,p.getZ(),p.getX(),selectedSurface.ground-1,p.getZ()));
            }
        }
        if(!ceiling)for(var local:plan.supports()) {
            var surface=selected.get(WorldsmithStructurePlan.columnKey(local));
            int gap=base+local.getY()-surface.ground;
            boolean floating=site.surface().equals("WATER_SURFACE")&&site.foundation().equals("NONE");
            if(!floating&&site.foundation().equals("NONE")&&gap!=0 || !site.foundation().equals("NONE")&&gap>site.maxDepth())return rejected(Rejection.MISSING_SUPPORT);
            if(!site.foundation().equals("NONE")&&gap>0){
                work+=gap;if(work>site.maxBlocks())return rejected(Rejection.FOUNDATION_BUDGET);
                BlockPos p=local.rotate(rotation).offset(anchor.atY(0));fills.add(new BoundingBox(p.getX(),surface.ground,p.getZ(),p.getX(),base+local.getY()-1,p.getZ()));
            }
        }
        return new Result(new Plan(anchor.atY(base),List.copyOf(fills),List.copyOf(cuts)),null);
    }
    private static Result rejected(Rejection rejection){return new Result(null,rejection);}
}
