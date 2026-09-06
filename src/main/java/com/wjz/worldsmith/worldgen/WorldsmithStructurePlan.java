package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Lightweight runtime metadata. Geometry stays in individual NBT templates, never one giant template. */
public record WorldsmithStructurePlan(List<Part> parts, List<BlockPos> footprint, List<BlockPos> supports, int height, BoundingBox bounds,List<Link> links) {
    public static final Codec<WorldsmithStructurePlan> CODEC=RecordCodecBuilder.<WorldsmithStructurePlan>create(i->i.group(
        Part.CODEC.listOf().fieldOf("parts").forGetter(WorldsmithStructurePlan::parts),
        BlockPos.CODEC.listOf().fieldOf("footprint").forGetter(WorldsmithStructurePlan::footprint),
        BlockPos.CODEC.listOf().fieldOf("supports").forGetter(WorldsmithStructurePlan::supports),
        Codec.intRange(1,128).fieldOf("height").forGetter(WorldsmithStructurePlan::height),
        BoundingBox.CODEC.fieldOf("bounds").forGetter(WorldsmithStructurePlan::bounds),
        Link.CODEC.listOf().fieldOf("links").forGetter(WorldsmithStructurePlan::links)
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
        if(p.links.size()>15||p.links.stream().anyMatch(l->l.from>=p.parts.size()||l.to>=p.parts.size()||l.from==l.to||!local(l.exitFrom,p.parts.get(l.from).size)||!local(l.exitTo,p.parts.get(l.to).size)))return DataResult.error(()->"Invalid plan connection");
        return DataResult.success(p);
    });
    private static boolean local(BlockPos p,BlockPos size){return p.getX()>=0&&p.getX()<size.getX()&&p.getY()>=0&&p.getY()<size.getY()&&p.getZ()>=0&&p.getZ()<size.getZ();}
    private static boolean valid(BlockPos p,int height){return p.getX()>=-96&&p.getX()<=96&&p.getZ()>=-96&&p.getZ()<=96&&p.getY()>=0&&p.getY()<height;}
    public static long columnKey(BlockPos p){return ((long)p.getX()<<32)|(p.getZ()&0xffffffffL);}

    public record Link(int from,BlockPos exitFrom,Direction facingFrom,int to,BlockPos exitTo,Direction facingTo,boolean passage) {
        public static final Codec<Link> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.intRange(0,15).fieldOf("from").forGetter(Link::from),BlockPos.CODEC.fieldOf("exit_from").forGetter(Link::exitFrom),Direction.CODEC.fieldOf("facing_from").forGetter(Link::facingFrom),
            Codec.intRange(0,15).fieldOf("to").forGetter(Link::to),BlockPos.CODEC.fieldOf("exit_to").forGetter(Link::exitTo),Direction.CODEC.fieldOf("facing_to").forGetter(Link::facingTo),Codec.BOOL.fieldOf("passage").forGetter(Link::passage)
        ).apply(i,Link::new));
    }
    public record Part(Identifier template, BlockPos offset, Rotation rotation, BlockPos size, List<BoundingBox> reserved,
        List<BlockPos> footprint,List<BlockPos> supports,WorldsmithInstanceProcessor.Config detail) {
        public static final Codec<Part> CODEC=RecordCodecBuilder.<Part>create(i->i.group(
            Identifier.CODEC.fieldOf("template").forGetter(Part::template),
            BlockPos.CODEC.fieldOf("offset").forGetter(Part::offset),
            Rotation.CODEC.fieldOf("rotation").forGetter(Part::rotation),
            BlockPos.CODEC.fieldOf("size").forGetter(Part::size),
            BoundingBox.CODEC.listOf().fieldOf("reserved").forGetter(Part::reserved),
            BlockPos.CODEC.listOf().fieldOf("footprint").forGetter(Part::footprint),BlockPos.CODEC.listOf().fieldOf("supports").forGetter(Part::supports),
            WorldsmithInstanceProcessor.Config.CODEC.fieldOf("detail").forGetter(Part::detail)
        ).apply(i,Part::new)).validate(p->{
            if(p.size.getX()<1||p.size.getX()>64||p.size.getY()<1||p.size.getY()>64||p.size.getZ()<1||p.size.getZ()>64||!valid(p.offset,128))return DataResult.error(()->"Invalid bounded template piece");
            if(p.reserved.isEmpty()||p.reserved.size()>33||p.reserved.stream().anyMatch(b->b.minX()<0||b.minY()<0||b.minZ()<0||b.maxX()>=p.size.getX()||b.maxY()>=p.size.getY()||b.maxZ()>=p.size.getZ()))return DataResult.error(()->"Invalid piece reservations");
            if(p.footprint.isEmpty()||p.footprint.size()>4096||p.supports.size()>4096||p.footprint.stream().anyMatch(v->!local(v,p.size))||p.supports.stream().anyMatch(v->!local(v,p.size)||v.getY()!=0))return DataResult.error(()->"Invalid per-piece terrain samples");
            var columns=new HashSet<Long>();for(var v:p.footprint)if(!columns.add(columnKey(v)))return DataResult.error(()->"Duplicate piece footprint column");
            var supported=new HashSet<Long>();for(var v:p.supports)if(!columns.contains(columnKey(v))||!supported.add(columnKey(v)))return DataResult.error(()->"Unprobed or duplicate piece support");
            return DataResult.success(p);
        });
        public BoundingBox bounds(){return BoundingBox.fromCorners(offset,new BlockPos(size.getX()-1,size.getY()-1,size.getZ()-1).rotate(rotation).offset(offset));}
    }
}
