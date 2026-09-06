package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;

/** Cosmetic full cubes only: no carving, gravity or block entity mutations. */
public record WorldsmithInstanceProcessor(long seed,Config config) implements StructureProcessor {
    public record Patch(List<BlockState> sources,BlockState replacement,double probability,int scale) {
        public static final Codec<Patch> CODEC=RecordCodecBuilder.<Patch>create(i->i.group(
            BlockState.CODEC.listOf().fieldOf("sources").forGetter(Patch::sources),BlockState.CODEC.fieldOf("replacement").forGetter(Patch::replacement),
            Codec.doubleRange(0,1).fieldOf("probability").forGetter(Patch::probability),Codec.intRange(1,16).fieldOf("scale").forGetter(Patch::scale)
        ).apply(i,Patch::new)).validate(p->p.sources.size()>=1&&p.sources.size()<=32&&stable(p.replacement)&&p.sources.stream().allMatch(WorldsmithInstanceProcessor::stable)?DataResult.success(p):DataResult.error(()->"Instance patches require stable, non-interactive full cubes"));
    }
    public record Config(List<Patch> patches,List<BoundingBox> protectedAreas) {
        public static final Config EMPTY=new Config(List.of(),List.of());
        public static final Codec<Config> CODEC=RecordCodecBuilder.<Config>create(i->i.group(
            Patch.CODEC.listOf().fieldOf("patches").forGetter(Config::patches),BoundingBox.CODEC.listOf().fieldOf("protected").forGetter(Config::protectedAreas)
        ).apply(i,Config::new)).validate(c->c.patches.size()<=8&&c.protectedAreas.size()<=32?DataResult.success(c):DataResult.error(()->"Too many instance patch rules"));
    }
    public static final MapCodec<WorldsmithInstanceProcessor> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
        Codec.LONG.fieldOf("seed").forGetter(WorldsmithInstanceProcessor::seed),Config.CODEC.fieldOf("config").forGetter(WorldsmithInstanceProcessor::config)
    ).apply(i,WorldsmithInstanceProcessor::new));
    static boolean stable(BlockState s){return !s.hasBlockEntity()&&s.getFluidState().isEmpty()&&!(s.getBlock() instanceof FallingBlock)&&s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);}
    @Override public StructureTemplate.StructureBlockInfo processBlock(LevelReader level,BlockPos target,BlockPos reference,BlockPos local,StructureTemplate.StructureBlockInfo info,StructurePlaceSettings settings) {
        if(info.nbt()!=null)return info;
        for(var box:config.protectedAreas)if(box.isInside(local))return info;
        for(int i=0;i<config.patches.size();i++) {
            var p=config.patches.get(i);boolean matches=false;
            for(var source:p.sources)if(source.getBlock()==info.state().getBlock()){matches=true;break;}
            if(!matches)continue;
            var pos=info.pos();long key=seed+i*0x9E3779B97F4A7C15L;
            key+=Math.floorDiv(pos.getX(),p.scale)*0xD1B54A32D192ED03L+Math.floorDiv(pos.getY(),p.scale)*0x94D049BB133111EBL+Math.floorDiv(pos.getZ(),p.scale)*0xBF58476D1CE4E5B9L;
            if((WorldsmithStructures.mixSeed(key)>>>11)/9007199254740992.0<p.probability)return new StructureTemplate.StructureBlockInfo(info.pos(),p.replacement,null);
        }
        return info;
    }
    @Override public MapCodec<WorldsmithInstanceProcessor> codec(){return CODEC;}
}
