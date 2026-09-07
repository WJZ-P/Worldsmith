package com.wjz.worldsmith.worldgen;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Native multi-cell invariants, checked on a logical building before storage tiling. */
public final class WorldsmithNativeGeometryChecks {
	private WorldsmithNativeGeometryChecks() {}
	public static void validate(Map<BlockPos,BlockState> cells) {
		for(var entry:cells.entrySet()) {
			var p=entry.getKey();var s=entry.getValue();BlockPos neighbour;BlockState expected;
			if(s.getBlock() instanceof DoorBlock) {
				boolean lower=s.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER;
				neighbour=lower?p.above():p.below();expected=s.setValue(DoorBlock.HALF,lower?DoubleBlockHalf.UPPER:DoubleBlockHalf.LOWER);
			} else if(s.getBlock() instanceof BedBlock) {
				boolean foot=s.getValue(BedBlock.PART)==BedPart.FOOT;
				neighbour=p.relative(foot?s.getValue(BedBlock.FACING):s.getValue(BedBlock.FACING).getOpposite());
				expected=s.setValue(BedBlock.PART,foot?BedPart.HEAD:BedPart.FOOT);
			} else if(s.getBlock() instanceof DoublePlantBlock) {
				boolean lower=s.getValue(DoublePlantBlock.HALF)==DoubleBlockHalf.LOWER;
				neighbour=lower?p.above():p.below();expected=s.setValue(DoublePlantBlock.HALF,lower?DoubleBlockHalf.UPPER:DoubleBlockHalf.LOWER);
			} else continue;
			if(!expected.equals(cells.get(neighbour)))throw new IllegalArgumentException("Incomplete/inconsistent paired block at "+p+"; expected "+expected+" at "+neighbour);
		}
	}
}
