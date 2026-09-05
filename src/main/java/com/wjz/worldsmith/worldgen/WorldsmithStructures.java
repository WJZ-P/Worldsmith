package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.structure.FoundationMode;
import com.wjz.worldsmith.core.structure.StructureGeometryCompiler;
import com.wjz.worldsmith.core.structure.WorldStructureDefinition;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.util.ArrayList;
import net.minecraft.world.level.EmptyBlockGetter;

public final class WorldsmithStructures {
    private WorldsmithStructures() {}

    public static void bootstrap(CompiledPack pack, BootstrapContext<Structure> context) {
        var biomes=context.lookup(Registries.BIOME);
        for(var structure:pack.pack().getStructures().getStructures()) {
            var definition=structure.getBlueprint();
            var geometry=StructureGeometryCompiler.compile(definition);
            // Resolve every written material before publishing registry elements.
            try {
                WorldsmithStructureTemplates.encode(geometry);
            } catch(IllegalArgumentException failure) {
                throw new IllegalArgumentException("Structure '"+structure.getId()+"', blueprint '"+definition.getId()+"': "+failure.getMessage(),failure);
            }
            var rule=structure.getPlacement();
            var fit=rule.getTerrainFit();
            var foundation=fit.getFoundation();
            List<BlockPos> supports=foundation.getMode()==FoundationMode.PILLARS
                ? foundation.getSupports().stream().map(p->new BlockPos(p.getX(),0,p.getZ())).toList()
                : geometry.getVoxels().stream().filter(v->v.getPosition().getY()==0 && !WorldsmithStructureTemplates.resolve(v.getMaterial()).isAir())
                    .map(v->new BlockPos(v.getPosition().getX(),0,v.getPosition().getZ())).distinct().toList();
            // Probe every authored column, not just the selected pillars. A
            // hill between pillars must not grow through the room/roof.
            var footprint=geometry.getVoxels().stream()
                .map(v->new BlockPos(v.getPosition().getX(),0,v.getPosition().getZ())).distinct().toList();
            if(supports.isEmpty())throw new IllegalArgumentException("Structure '"+structure.getId()+"' needs solid floor cells at local Y=0");
            List<BoundingBox> reserved=new ArrayList<>();
            reserved.add(BoundingBox.encapsulatingPositions(geometry.getVoxels().stream()
                .map(v->new BlockPos(v.getPosition().getX(),v.getPosition().getY(),v.getPosition().getZ())).toList()).orElseThrow());
            geometry.getKeepClear().forEach(box->reserved.add(new BoundingBox(box.getFrom().getX(),box.getFrom().getY(),box.getFrom().getZ(),box.getTo().getX(),box.getTo().getY(),box.getTo().getZ())));
            var foundationState=foundation.getMaterial()==null?Blocks.STONE.defaultBlockState():WorldsmithStructureTemplates.resolve(definition.getPalette().get(foundation.getMaterial()));
            if(foundation.getMode()!=FoundationMode.NONE && (foundationState.isAir() || !foundationState.getFluidState().isEmpty() || !foundationState.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO))) {
                throw new IllegalArgumentException("Structure '"+structure.getId()+"' foundation material must be a dry full supporting block");
            }
            var size=new BlockPos(definition.getSize().getX(),definition.getSize().getY(),definition.getSize().getZ());
            var origin=new BlockPos(definition.getOrigin().getX(),0,definition.getOrigin().getZ());
            var rotations=rule.getRotations().stream().map(r->Rotation.valueOf(r.name())).toList();
            var layout=layout(pack,structure);
            var config=new WorldsmithTemplateStructure.Settings(
                pack.structureTemplateId(definition.getId()),
                size,origin,rotations,
                fit.getSurface().name(),fit.getMaxHeightDifference(),foundation.getMode().name(),
                foundationState,
                foundation.getMaxDepth(),supports,reserved,footprint,layout
            );
            var allowed=HolderSet.direct(rule.getBiomes().stream().map(id->biomes.getOrThrow(pack.biomeKey(id))).toList());
            context.register(pack.structureKey(structure.getId()),new WorldsmithTemplateStructure(new Structure.StructureSettings(allowed),config));
        }
    }

    public static void bootstrapSets(CompiledPack pack, BootstrapContext<StructureSet> context) {
        var structures=context.lookup(Registries.STRUCTURE);
        for(var definition:pack.pack().getStructures().getStructures()) {
            context.register(pack.structureSetKey(definition.getId()),new StructureSet(
                structures.getOrThrow(pack.structureKey(definition.getId())),
                layout(pack,definition).placement()));
        }
    }

    static WorldsmithStructureLayout.Member layout(CompiledPack pack, WorldStructureDefinition definition) {
        var blueprint=definition.getBlueprint();
        var rule=definition.getPlacement();
        var size=new BlockPos(blueprint.getSize().getX(),blueprint.getSize().getY(),blueprint.getSize().getZ());
        var origin=new BlockPos(blueprint.getOrigin().getX(),0,blueprint.getOrigin().getZ());
        var rotations=rule.getRotations().stream().map(r->Rotation.valueOf(r.name())).toList();
        return new WorldsmithStructureLayout.Member(pack.structureKey(definition.getId()).identifier(),pack.id(),
            rule.getSpacingChunks(),rule.getSeparationChunks(),salt(pack.id()+":"+definition.getId()),
            WorldsmithStructureLayout.envelope(size,origin,rotations,rule.getClearanceBlocks()),
            WorldsmithStructureAnchor.resolve(pack,rule.getAnchor()));
    }

    static int salt(String value) {
        try { return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).getInt() & Integer.MAX_VALUE; }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
