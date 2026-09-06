package com.wjz.worldsmith.worldgen;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/** Palette-compressed, immutable road decisions; chunk callbacks never rerun pathfinding. */
public final class WorldsmithRoadPiece extends StructurePiece {
    private final Map<BlockPos,BlockState> blocks;
    private final Map<ChunkPos,List<Map.Entry<BlockPos,BlockState>>> chunks=new HashMap<>();
    private final Map<Long,int[]> columns=new HashMap<>();
    public WorldsmithRoadPiece(Map<BlockPos,BlockState> blocks) {
        super(WorldsmithStructureTypes.roadPiece(),0,BoundingBox.encapsulatingPositions(blocks.keySet()).orElseThrow());
        this.blocks=Map.copyOf(blocks);setOrientation(null);index();
    }
    public WorldsmithRoadPiece(StructurePieceSerializationContext context,CompoundTag tag) {
        super(WorldsmithStructureTypes.roadPiece(),tag);
        var palette=BlockState.CODEC.listOf().parse(NbtOps.INSTANCE,tag.getListOrEmpty("Palette")).getOrThrow();
        var data=tag.getListOrEmpty("Blocks");
        if(palette.size()>64||data.size()>WorldsmithRoadPlanner.MAX_BLOCKS)throw new IllegalArgumentException("Road data exceeds its budget");
        Map<BlockPos,BlockState> result=new LinkedHashMap<>();
        for(var value:data) {
            if(!(value instanceof IntArrayTag array))throw new IllegalArgumentException("Invalid road block tuple");
            int[] v=array.getAsIntArray();if(v.length!=4||v[3]<0||v[3]>=palette.size())throw new IllegalArgumentException("Invalid road palette index");
            var p=new BlockPos(v[0]+boundingBox.minX(),v[1]+boundingBox.minY(),v[2]+boundingBox.minZ());
            if(!boundingBox.isInside(p))throw new IllegalArgumentException("Road block outside its saved bounds");result.put(p,palette.get(v[3]));
        }
        this.blocks=Map.copyOf(result);index();
    }
    private void index() {
        if(blocks.isEmpty()||blocks.size()>WorldsmithRoadPlanner.MAX_BLOCKS||boundingBox.getXSpan()>193||boundingBox.getZSpan()>193)throw new IllegalArgumentException("Invalid road extent");
        for(var e:blocks.entrySet()) {
            var s=e.getValue();if(!s.isAir()&&!WorldsmithInstanceProcessor.stable(s)&&!(s.getBlock() instanceof StairBlock&&s.getFluidState().isEmpty()))throw new IllegalArgumentException("Invalid road material");
            chunks.computeIfAbsent(ChunkPos.containing(e.getKey()),k->new ArrayList<>()).add(e);
            columns.compute(WorldsmithStructurePlan.columnKey(e.getKey()),(k,r)->r==null?new int[]{e.getKey().getY(),e.getKey().getY()}:new int[]{Math.min(r[0],e.getKey().getY()),Math.max(r[1],e.getKey().getY())});
        }
    }
    public boolean blocksDecoration(BoundingBox box) {
        if(!boundingBox.intersects(box))return false;
        for(int x=box.minX();x<=box.maxX();x++)for(int z=box.minZ();z<=box.maxZ();z++) {
            var range=columns.get(((long)x<<32)|(z&0xffffffffL));if(range!=null&&range[0]<=box.maxY()&&range[1]>=box.minY())return true;
        }
        return false;
    }
    @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context,CompoundTag tag) {
        Map<BlockState,Integer> palette=new LinkedHashMap<>();ListTag data=new ListTag();
        blocks.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<BlockPos,BlockState> e)->e.getKey().getY()).thenComparingInt(e->e.getKey().getX()).thenComparingInt(e->e.getKey().getZ())).forEach(e->{
            int index=palette.computeIfAbsent(e.getValue(),s->palette.size());var p=e.getKey();data.add(new IntArrayTag(new int[]{p.getX()-boundingBox.minX(),p.getY()-boundingBox.minY(),p.getZ()-boundingBox.minZ(),index}));
        });
        tag.put("Palette",BlockState.CODEC.listOf().encodeStart(NbtOps.INSTANCE,List.copyOf(palette.keySet())).getOrThrow());tag.put("Blocks",data);
    }
    @Override public void postProcess(WorldGenLevel level,StructureManager manager,ChunkGenerator generator,RandomSource random,BoundingBox clip,ChunkPos chunk,BlockPos reference) {
        for(var e:chunks.getOrDefault(chunk,List.of()))if(clip.isInside(e.getKey()))level.setBlock(e.getKey(),e.getValue(),2);
    }
}
