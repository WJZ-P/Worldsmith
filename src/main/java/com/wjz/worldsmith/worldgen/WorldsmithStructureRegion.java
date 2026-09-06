package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/** Shared seeded influence; never queries already-generated starts. */
public record WorldsmithStructureRegion(int groupSeed,int cellSize,double minInfluence,double maxInfluence,double chance,String water,int waterRadius) {
    public static final Codec<WorldsmithStructureRegion> CODEC=RecordCodecBuilder.<WorldsmithStructureRegion>create(i->i.group(
        Codec.INT.fieldOf("group_seed").forGetter(WorldsmithStructureRegion::groupSeed),
        Codec.intRange(128,8192).fieldOf("cell_size").forGetter(WorldsmithStructureRegion::cellSize),
        Codec.doubleRange(0,1).fieldOf("min_influence").forGetter(WorldsmithStructureRegion::minInfluence),
        Codec.doubleRange(0,1).fieldOf("max_influence").forGetter(WorldsmithStructureRegion::maxInfluence),
        Codec.doubleRange(0,1).fieldOf("chance").forGetter(WorldsmithStructureRegion::chance),
        Codec.STRING.fieldOf("water").forGetter(WorldsmithStructureRegion::water),
        Codec.intRange(8,64).fieldOf("water_radius").forGetter(WorldsmithStructureRegion::waterRadius)
    ).apply(i,WorldsmithStructureRegion::new)).validate(r->r.minInfluence<=r.maxInfluence&&java.util.List.of("ANY","NEAR","AWAY").contains(r.water)?DataResult.success(r):DataResult.error(()->"Invalid region"));
    private static double unit(long seed){return (WorldsmithStructures.mixSeed(seed)>>>11)/9007199254740992.0;}
    public double influence(long seed,BlockPos point) {
        int cx=Math.floorDiv(point.getX(),cellSize),cz=Math.floorDiv(point.getZ(),cellSize);double best=0;
        for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++) {
            long key=seed+groupSeed+(long)x*0x9E3779B97F4A7C15L+(long)z*0xD1B54A32D192ED03L;
            double px=(x+0.5)*cellSize+(unit(key)-0.5)*cellSize*0.5,pz=(z+0.5)*cellSize+(unit(key+73)-0.5)*cellSize*0.5;
            double t=Math.clamp(1-Math.hypot(point.getX()-px,point.getZ()-pz)/(cellSize*0.7),0,1);best=Math.max(best,t*t*(3-2*t));
        }
        return best;
    }
    public boolean contains(long seed,BlockPos p){double v=influence(seed,p);return v>=minInfluence&&v<=maxInfluence;}
    public boolean accepts(long seed,int salt,BlockPos p){return contains(seed,p)&&unit(seed+salt+net.minecraft.world.level.ChunkPos.pack(p)*0x94D049BB133111EBL)<chance;}
    /** Coarse 8-block sampling, not an exact distance transform. */
    public boolean waterMatches(BlockPos p,WorldsmithTerrainProbe.Sampler sampler) {
        if(water.equals("ANY"))return true;
        int extent=(waterRadius/8)*8;
        for(int x=-extent;x<=extent;x+=8)for(int z=-extent;z<=extent;z+=8) {
            if(x*x+z*z>waterRadius*waterRadius)continue;
            var c=sampler.sample(p.getX()+x,p.getZ()+z);if(c.water()&&c.surfaceY()>c.groundY())return water.equals("NEAR");
        }
        return water.equals("AWAY");
    }
}
