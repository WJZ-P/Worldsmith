package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Lightweight runtime metadata. Geometry stays in individual NBT templates, never one giant template. */
public record WorldsmithStructurePlan(List<Part> parts, List<BlockPos> footprint, List<BlockPos> supports, int height, BoundingBox bounds) {
    public static final Codec<WorldsmithStructurePlan> CODEC=RecordCodecBuilder.<WorldsmithStructurePlan>create(i->i.group(
        Part.CODEC.listOf().fieldOf("parts").forGetter(WorldsmithStructurePlan::parts),
        BlockPos.CODEC.listOf().fieldOf("footprint").forGetter(WorldsmithStructurePlan::footprint),
        BlockPos.CODEC.listOf().fieldOf("supports").forGetter(WorldsmithStructurePlan::supports),
        Codec.intRange(1,128).fieldOf("height").forGetter(WorldsmithStructurePlan::height),
        BoundingBox.CODEC.fieldOf("bounds").forGetter(WorldsmithStructurePlan::bounds)
    ).apply(i,WorldsmithStructurePlan::new)).validate(p->{
        if(p.parts.isEmpty()||p.parts.size()>16||p.footprint.isEmpty()||p.footprint.size()>8192||p.supports.isEmpty()||p.supports.size()>8192)
            return DataResult.error(()->"Structure plan exceeds piece/column limits or has no support");
        var columns=new HashSet<Long>();
        for(var point:p.footprint)if(!valid(point,p.height)||!p.bounds.isInside(point)||!columns.add(columnKey(point)))return DataResult.error(()->"Invalid or duplicate footprint column");
        var supportColumns=new HashSet<Long>();
        for(var point:p.supports)if(!valid(point,p.height)||!p.bounds.isInside(point)||!columns.contains(columnKey(point))||!supportColumns.add(columnKey(point)))return DataResult.error(()->"Invalid or unprobed support column");
        if(p.bounds.minY()!=0||p.bounds.maxY()!=p.height-1||p.bounds.minX() < -96||p.bounds.minZ() < -96||p.bounds.maxX()>96||p.bounds.maxZ()>96)
            return DataResult.error(()->"Plan bounds exceed the bounded structure envelope");
        for(var part:p.parts) {
            if(!p.bounds.isInside(part.bounds().minX(),part.bounds().minY(),part.bounds().minZ())||!p.bounds.isInside(part.bounds().maxX(),part.bounds().maxY(),part.bounds().maxZ()))
                return DataResult.error(()->"Part outside plan bounds");
        }
        for(int a=0;a<p.parts.size();a++)for(int b=a+1;b<p.parts.size();b++)if(p.parts.get(a).bounds().intersects(p.parts.get(b).bounds()))return DataResult.error(()->"Structure pieces overlap");
        return DataResult.success(p);
    });
    private static boolean valid(BlockPos p,int height){return p.getX()>=-96&&p.getX()<=96&&p.getZ()>=-96&&p.getZ()<=96&&p.getY()>=0&&p.getY()<height;}
    public static long columnKey(BlockPos p){return ((long)p.getX()<<32)|(p.getZ()&0xffffffffL);}

    public record Part(Identifier template, BlockPos offset, Rotation rotation, BlockPos size, List<BoundingBox> reserved) {
        public static final Codec<Part> CODEC=RecordCodecBuilder.<Part>create(i->i.group(
            Identifier.CODEC.fieldOf("template").forGetter(Part::template),
            BlockPos.CODEC.fieldOf("offset").forGetter(Part::offset),
            Rotation.CODEC.fieldOf("rotation").forGetter(Part::rotation),
            BlockPos.CODEC.fieldOf("size").forGetter(Part::size),
            BoundingBox.CODEC.listOf().fieldOf("reserved").forGetter(Part::reserved)
        ).apply(i,Part::new)).validate(p->{
            if(p.size.getX()<1||p.size.getX()>64||p.size.getY()<1||p.size.getY()>64||p.size.getZ()<1||p.size.getZ()>64||!valid(p.offset,128))return DataResult.error(()->"Invalid bounded template piece");
            if(p.reserved.isEmpty()||p.reserved.size()>33||p.reserved.stream().anyMatch(b->b.minX()<0||b.minY()<0||b.minZ()<0||b.maxX()>=p.size.getX()||b.maxY()>=p.size.getY()||b.maxZ()>=p.size.getZ()))return DataResult.error(()->"Invalid piece reservations");
            return DataResult.success(p);
        });
        public BoundingBox bounds(){return BoundingBox.fromCorners(offset,new BlockPos(size.getX()-1,size.getY()-1,size.getZ()-1).rotate(rotation).offset(offset));}
    }
}
