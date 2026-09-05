package com.wjz.worldsmith.worldgen;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** One persistent start decision; neighbouring chunks only place their clipped part. */
public final class WorldsmithTemplatePiece extends TemplateStructurePiece {
    private final WorldsmithTemplateStructure.Settings config;
    private final List<BoundingBox> foundations;
    private final List<BoundingBox> reserved;

    public WorldsmithTemplatePiece(StructureTemplateManager manager, WorldsmithTemplateStructure.Settings config,
        BlockPos position, Rotation rotation, List<BoundingBox> foundations) {
        super(WorldsmithStructureTypes.piece(), 0, manager, config.template(), config.template().toString(), placement(rotation), position);
        this.config=config;
        this.foundations=List.copyOf(foundations);
        this.reserved=reserved(config,position,rotation);
        this.foundations.forEach(this.boundingBox::encapsulate);
    }

    public WorldsmithTemplatePiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(WorldsmithStructureTypes.piece(), tag, context.structureTemplateManager(),
            id -> placement(Rotation.valueOf(tag.getStringOr("Rotation", "NONE"))));
        this.config=WorldsmithTemplateStructure.Settings.CODEC.parse(NbtOps.INSTANCE, tag.getCompoundOrEmpty("Config")).getOrThrow();
        this.foundations=BoundingBox.CODEC.listOf().parse(NbtOps.INSTANCE, tag.getListOrEmpty("Foundations")).getOrThrow();
        this.reserved=reserved(this.config,this.templatePosition,this.getRotation());
        this.foundations.forEach(this.boundingBox::encapsulate);
    }

    private static StructurePlaceSettings placement(Rotation rotation) {
        return new StructurePlaceSettings().setRotation(rotation).setIgnoreEntities(true);
    }

    private static List<BoundingBox> reserved(WorldsmithTemplateStructure.Settings config,BlockPos position,Rotation rotation) {
        return config.reserved().stream().map(box->{
            BlockPos a=new BlockPos(box.minX(),box.minY(),box.minZ()).rotate(rotation).offset(position);
            BlockPos b=new BlockPos(box.maxX(),box.maxY(),box.maxZ()).rotate(rotation).offset(position);
            return BoundingBox.fromCorners(a,b);
        }).toList();
    }

    public boolean blocksDecoration(BoundingBox volume) {
        return this.reserved.stream().anyMatch(box->box.intersects(volume)) || this.foundations.stream().anyMatch(box->box.intersects(volume));
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        super.addAdditionalSaveData(context,tag);
        tag.putString("Rotation",this.placeSettings.getRotation().name());
        tag.put("Config",WorldsmithTemplateStructure.Settings.CODEC.encodeStart(NbtOps.INSTANCE,this.config).getOrThrow());
        tag.put("Foundations",BoundingBox.CODEC.listOf().encodeStart(NbtOps.INSTANCE,this.foundations).getOrThrow());
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
        RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos referencePos) {
        // Foundation decisions are persisted with the start. No neighbour-chunk
        // reads or traversal-order-dependent probing happens during placement.
        for(BoundingBox column:this.foundations) {
            if(!column.intersects(chunkBox))continue;
            for(int y=Math.max(column.minY(),chunkBox.minY());y<=Math.min(column.maxY(),chunkBox.maxY());y++) {
                BlockPos pos=new BlockPos(column.minX(),y,column.minZ());
                if(chunkBox.isInside(pos))level.setBlock(pos,this.config.foundationState().rotate(this.getRotation()),2);
            }
        }
        // The start/piece is shared by chunk-generation jobs. Clone the mutable
        // settings per call instead of mutating TemplateStructurePiece's shared
        // bounding box; each job owns only its clip and random source.
        this.template.placeInWorld(level,this.templatePosition,referencePos,
            this.placeSettings.copy().setBoundingBox(chunkBox),random,2);
    }

    @Override protected void handleDataMarker(String id, BlockPos pos, ServerLevelAccessor level, RandomSource random, BoundingBox box) {}
}
