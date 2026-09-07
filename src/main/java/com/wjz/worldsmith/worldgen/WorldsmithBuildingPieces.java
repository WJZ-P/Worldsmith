package com.wjz.worldsmith.worldgen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** Expands an already fitted logical building into storage pieces. No tile is fitted independently. */
public final class WorldsmithBuildingPieces {
	private WorldsmithBuildingPieces() {}
	public static List<WorldsmithTemplatePiece> create(StructureTemplateManager manager,WorldsmithRoadPlanner.PlacedPart placed,BlockState foundation,long seed) {
		var part=placed.part();
		if(part.tiles().isEmpty())return List.of(new WorldsmithTemplatePiece(manager,part,foundation,placed.position(),placed.rotation(),placed.foundations(),placed.cuts(),seed));
		var pieces=new ArrayList<WorldsmithTemplatePiece>();int index=0;
		for(var tile:part.tiles()) {
			var offset=tile.offset();var size=tile.size();
			var bounds=BoundingBox.fromCorners(offset,offset.offset(size).offset(-1,-1,-1));
			var reserved=clip(part.reserved(),bounds,offset);
			var protectedAreas=clip(part.detail().protectedAreas(),bounds,offset);
			var columns=part.footprint().stream().filter(p->p.getX()>=bounds.minX()&&p.getX()<=bounds.maxX()&&p.getZ()>=bounds.minZ()&&p.getZ()<=bounds.maxZ())
				.map(p->new BlockPos(p.getX()-offset.getX(),Math.clamp(p.getY()-offset.getY(),0,size.getY()-1),p.getZ()-offset.getZ())).toList();
			var supports=offset.getY()==0?part.supports().stream().filter(p->bounds.isInside(p)).map(p->p.subtract(offset)).toList():List.<BlockPos>of();
			var metadata=new WorldsmithStructurePlan.Part(tile.template(),offset.rotate(part.rotation()).offset(part.offset()),part.rotation(),size,reserved,columns,supports,
                new WorldsmithInstanceProcessor.Config(part.detail().patches(),protectedAreas),List.of(),true);
			var position=offset.rotate(placed.rotation()).offset(placed.position());
			pieces.add(new WorldsmithTemplatePiece(manager,metadata,foundation,position,placed.rotation(),index==0?placed.foundations():List.of(),index==0?placed.cuts():List.of(),seed ^ ((long)index*0xD1B54A32D192ED03L)));
			index++;
		}
		return List.copyOf(pieces);
	}
	private static List<BoundingBox> clip(List<BoundingBox> areas,BoundingBox bounds,BlockPos offset) {
		var result=new ArrayList<BoundingBox>();
		for(var a:areas)if(a.intersects(bounds))result.add(new BoundingBox(Math.max(a.minX(),bounds.minX())-offset.getX(),Math.max(a.minY(),bounds.minY())-offset.getY(),Math.max(a.minZ(),bounds.minZ())-offset.getZ(),
			Math.min(a.maxX(),bounds.maxX())-offset.getX(),Math.min(a.maxY(),bounds.maxY())-offset.getY(),Math.min(a.maxZ(),bounds.maxZ())-offset.getZ()));
		return List.copyOf(result);
	}
}
