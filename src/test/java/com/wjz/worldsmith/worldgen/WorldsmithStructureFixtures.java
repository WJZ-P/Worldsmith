package com.wjz.worldsmith.worldgen;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Test authoring helper: turns a local single piece into the same plan metadata as the compiler. */
final class WorldsmithStructureFixtures {
    static WorldsmithTemplateStructure.Settings settings(Identifier template,BlockPos size,BlockPos origin,List<Rotation> rotations,
        String surface,int slope,String foundation,BlockState material,int depth,List<BlockPos> supports,List<BoundingBox> reserved,
        List<BlockPos> footprint,WorldsmithStructureLayout.Member layout) {
        var offset=origin.multiply(-1);
        var part=new WorldsmithStructurePlan.Part(template,offset,Rotation.NONE,size,reserved);
        var columns=footprint.stream().map(p->new BlockPos(p.getX()-origin.getX(),size.getY()-1,p.getZ()-origin.getZ())).toList();
        var support=supports.stream().map(p->p.offset(offset)).toList();
        var bounds=BoundingBox.fromCorners(offset,new BlockPos(size.getX()-1,size.getY()-1,size.getZ()-1).offset(offset));
        var plan=new WorldsmithStructurePlan(List.of(part),columns,support,size.getY(),bounds);
        var site=new WorldsmithStructureSite(surface,-64,319,0,0,8,slope,foundation,material,depth,0,4096);
        return new WorldsmithTemplateStructure.Settings(List.of(plan),rotations,site,layout);
    }
}
