package com.wjz.worldsmith.worldgen;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** Persists only this selected piece and its bounded earthwork, not the entire variant catalog. */
public final class WorldsmithTemplatePiece extends TemplateStructurePiece {
    private final WorldsmithStructurePlan.Part part;
    private final BlockState foundationState;
    private final List<BoundingBox> foundations,cuts,reserved;
    private final long contentSeed;

    public WorldsmithTemplatePiece(StructureTemplateManager manager,WorldsmithStructurePlan.Part part,BlockState foundationState,
        BlockPos position,Rotation rotation,List<BoundingBox> foundations,List<BoundingBox> cuts,long contentSeed) {
        super(WorldsmithStructureTypes.piece(),0,manager,part.template(),part.template().toString(),placement(rotation),position);
        this.part=part;this.foundationState=foundationState;this.foundations=List.copyOf(foundations);this.cuts=List.copyOf(cuts);this.contentSeed=contentSeed;
        this.reserved=reserved(part,position,rotation);validateAndExpand();
    }
    public WorldsmithTemplatePiece(StructurePieceSerializationContext context,CompoundTag tag) {
        super(WorldsmithStructureTypes.piece(),tag,context.structureTemplateManager(),id->placement(Rotation.valueOf(tag.getStringOr("Rotation","NONE"))));
        this.part=WorldsmithStructurePlan.Part.CODEC.parse(NbtOps.INSTANCE,tag.getCompoundOrEmpty("Part")).getOrThrow();
        this.foundationState=BlockState.CODEC.parse(NbtOps.INSTANCE,tag.getCompoundOrEmpty("FoundationState")).getOrThrow();
        this.foundations=BoundingBox.CODEC.listOf().parse(NbtOps.INSTANCE,tag.getListOrEmpty("Foundations")).getOrThrow();
        this.cuts=BoundingBox.CODEC.listOf().parse(NbtOps.INSTANCE,tag.getListOrEmpty("Cuts")).getOrThrow();
        this.contentSeed=tag.getLongOr("ContentSeed",0L);
        this.reserved=reserved(part,this.templatePosition,this.getRotation());validateAndExpand();
    }
    private void validateAndExpand() {
        if(!this.template.getSize().equals(part.size()))throw new IllegalArgumentException("Template size differs from its selected piece metadata");
        if(foundations.size()+cuts.size()>16384)throw new IllegalArgumentException("Too many persisted earthwork columns");
        long blocks=0;
        for(var box:java.util.stream.Stream.concat(foundations.stream(),cuts.stream()).toList()) {
            if(box.getXSpan()!=1||box.getZSpan()!=1||box.getYSpan()>16)throw new IllegalArgumentException("Earthwork must consist of bounded one-block columns");
            blocks+=box.getYSpan();if(blocks>8192)throw new IllegalArgumentException("Persisted earthwork exceeds 8192 blocks");
            this.boundingBox.encapsulate(box);
        }
    }
    private static StructurePlaceSettings placement(Rotation rotation){return new StructurePlaceSettings().setRotation(rotation).setIgnoreEntities(true);}
    private static List<BoundingBox> reserved(WorldsmithStructurePlan.Part part,BlockPos position,Rotation rotation) {
        return part.reserved().stream().map(b->BoundingBox.fromCorners(new BlockPos(b.minX(),b.minY(),b.minZ()).rotate(rotation).offset(position),new BlockPos(b.maxX(),b.maxY(),b.maxZ()).rotate(rotation).offset(position))).toList();
    }
    public boolean blocksDecoration(BoundingBox volume){return reserved.stream().anyMatch(b->b.intersects(volume))||foundations.stream().anyMatch(b->b.intersects(volume))||cuts.stream().anyMatch(b->b.intersects(volume));}
    public BlockPos planOrigin(){
        var overall=Rotation.values()[Math.floorMod(getRotation().ordinal()-part.rotation().ordinal(),4)];
        return templatePosition.subtract(part.offset().rotate(overall));
    }
    @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context,CompoundTag tag) {
        super.addAdditionalSaveData(context,tag);tag.putString("Rotation",getRotation().name());tag.putLong("ContentSeed",contentSeed);
        tag.put("Part",WorldsmithStructurePlan.Part.CODEC.encodeStart(NbtOps.INSTANCE,part).getOrThrow());
        tag.put("FoundationState",BlockState.CODEC.encodeStart(NbtOps.INSTANCE,foundationState).getOrThrow());
        tag.put("Foundations",BoundingBox.CODEC.listOf().encodeStart(NbtOps.INSTANCE,foundations).getOrThrow());
        tag.put("Cuts",BoundingBox.CODEC.listOf().encodeStart(NbtOps.INSTANCE,cuts).getOrThrow());
    }
    @Override public void postProcess(WorldGenLevel level,StructureManager structures,ChunkGenerator generator,RandomSource random,BoundingBox chunkBox,ChunkPos chunkPos,BlockPos referencePos) {
        // The root piece is ordered before children. All cuts precede every child
        // template in each chunk; clips make forward/reverse chunk order equivalent.
        applyColumns(level,chunkBox,cuts,Blocks.AIR.defaultBlockState());
        applyColumns(level,chunkBox,foundations,foundationState.rotate(getRotation()));
        this.template.placeInWorld(level,this.templatePosition,referencePos,this.placeSettings.copy().setBoundingBox(chunkBox),
            RandomSource.create(WorldsmithStructures.mixSeed(contentSeed+chunkPos.pack()*0xD1B54A32D192ED03L)),2);
    }
    private static void applyColumns(WorldGenLevel level,BoundingBox clip,List<BoundingBox> columns,BlockState state) {
        for(var box:columns)if(box.intersects(clip))for(int y=Math.max(box.minY(),clip.minY());y<=Math.min(box.maxY(),clip.maxY());y++) {
            var pos=new BlockPos(box.minX(),y,box.minZ());if(clip.isInside(pos))level.setBlock(pos,state,2);
        }
    }
    @Override protected void handleDataMarker(String id,BlockPos pos,ServerLevelAccessor level,RandomSource random,BoundingBox box) {}
}
