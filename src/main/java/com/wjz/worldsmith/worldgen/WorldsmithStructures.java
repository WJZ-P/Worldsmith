package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.structure.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Compiles Core plans to small native placement records; NBT assets use the same catalog. */
public final class WorldsmithStructures {
    private WorldsmithStructures() {}

    public static void bootstrap(CompiledPack pack, BootstrapContext<Structure> context) {
        var biomes=context.lookup(Registries.BIOME);
        // Check every palette entry and generated state, even in a low-weight variant.
        pack.structures().getBlueprints().values().forEach(b->b.getPalette().values().forEach(WorldsmithStructureTemplates::resolve));
        pack.structures().getTemplates().values().forEach(variants->variants.forEach(g->g.getVoxels().stream().map(StructureVoxel::getMaterial).distinct().forEach(WorldsmithStructureTemplates::resolve)));
        for(var definition:pack.pack().getStructures().getStructures()) {
            var site=site(pack,definition);
            var plans=pack.structures().getPlans().get(definition.getId()).stream().map(p->plan(pack,definition,p)).toList();
            var rotations=definition.getPlacement().getRotations().stream().map(r->Rotation.valueOf(r.name())).toList();
            var settings=new WorldsmithTemplateStructure.Settings(plans,rotations,site,layout(pack,definition));
            var allowed=HolderSet.direct(definition.getPlacement().getBiomes().stream().map(id->biomes.getOrThrow(pack.biomeKey(id))).toList());
            // This codec check also protects direct exporter callers that bypassed the MCP validator.
            WorldsmithTemplateStructure.Settings.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,settings).getOrThrow();
            context.register(pack.structureKey(definition.getId()),new WorldsmithTemplateStructure(new Structure.StructureSettings(allowed),settings));
        }
    }

    static WorldsmithStructureSite site(CompiledPack pack,WorldStructureDefinition d) {
        var fit=d.getPlacement().getTerrainFit();var foundation=fit.getFoundation();var terrain=pack.terrain();
        var block=foundation.getMaterial()==null?Blocks.STONE.defaultBlockState():WorldsmithStructureTemplates.resolve(d.getBlueprint().getPalette().get(foundation.getMaterial()));
        var range=fit.getVerticalRange();
        int min=range==null?terrain.getMinY():Math.max(terrain.getMinY(),range.getMinY());
        int max=range==null?terrain.getMinY()+terrain.getHeight()-1:Math.min(terrain.getMinY()+terrain.getHeight()-1,range.getMaxY());
        return new WorldsmithStructureSite(fit.getSurface().name(),min,max,fit.getLayer(),fit.getSearchRadius(),fit.getMinAirBelow(),fit.getMaxHeightDifference(),
            foundation.getMode().name(),block,foundation.getMaxDepth(),fit.getEarthwork()==null?0:fit.getEarthwork().getMaxCut(),
            fit.getEarthwork()==null?4096:fit.getEarthwork().getMaxBlocks());
    }

    static WorldsmithStructurePlan plan(CompiledPack pack,WorldStructureDefinition definition,CompiledStructurePlan plan) {
        List<WorldsmithStructurePlan.Part> parts=new ArrayList<>();
        var footprint=new LinkedHashMap<Long,BlockPos>();var supports=new LinkedHashMap<Long,BlockPos>();
        for(var p:plan.getParts()) {
            var geometry=p.getGeometry();
            List<BoundingBox> reserved=new ArrayList<>();
            reserved.add(BoundingBox.encapsulatingPositions(geometry.getVoxels().stream().map(v->pos(v.getPosition())).toList()).orElseThrow());
            geometry.getKeepClear().forEach(b->reserved.add(box(b)));
            parts.add(new WorldsmithStructurePlan.Part(pack.structureTemplateId(p.getBlueprintId(),p.getVariant()),pos(p.getOffset()),Rotation.valueOf(p.getRotation().name()),pos(geometry.getSize()),List.copyOf(reserved)));
            for(var voxel:geometry.getVoxels()) {
                BlockPos at=pos(StructureCatalogCompiler.transform(voxel.getPosition(),p));
                long key=WorldsmithStructurePlan.columnKey(at);
                footprint.merge(key,at,(a,b)->a.getY()>=b.getY()?a:b);
                // Upper storeys are connected pieces, not separate columns filled
                // through the rooms below. Only the lowest assembled datum gets foundations.
                if(at.getY()==0 && !voxel.getMaterial().isAir())supports.put(key,at);
            }
        }
        if(definition.getPlacement().getTerrainFit().getFoundation().getMode()==FoundationMode.PILLARS) {
            supports.clear();var root=plan.getParts().getFirst();
            for(var p:definition.getPlacement().getTerrainFit().getFoundation().getSupports()) {
                var at=pos(StructureCatalogCompiler.transform(p,root));supports.put(WorldsmithStructurePlan.columnKey(at),at);
            }
        }
        var bounds=box(plan.getBounds());
        var result=new WorldsmithStructurePlan(List.copyOf(parts),List.copyOf(footprint.values()),List.copyOf(supports.values()),bounds.maxY()+1,bounds);
        WorldsmithStructurePlan.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,result).getOrThrow();
        return result;
    }

    public static void bootstrapSets(CompiledPack pack, BootstrapContext<StructureSet> context) {
        var structures=context.lookup(Registries.STRUCTURE);
        for(var definition:pack.pack().getStructures().getStructures())context.register(pack.structureSetKey(definition.getId()),new StructureSet(
            structures.getOrThrow(pack.structureKey(definition.getId())),layout(pack,definition).placement()));
    }

    static WorldsmithStructureLayout.Member layout(CompiledPack pack,WorldStructureDefinition definition) {
        var rule=definition.getPlacement();int padding=rule.getClearanceBlocks()+rule.getTerrainFit().getSearchRadius();
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        var compiledPlans=pack.structures().getPlans().get(definition.getId());
        if(compiledPlans==null)throw new IllegalArgumentException("Structure is not in this compiled pack: "+definition.getId());
        for(var plan:compiledPlans) {
            var b=plan.getBounds();
            for(var rotation:rule.getRotations())for(int x:new int[]{b.getFrom().getX(),b.getTo().getX()})for(int z:new int[]{b.getFrom().getZ(),b.getTo().getZ()}) {
                var p=new BlockPos(x,0,z).rotate(Rotation.valueOf(rotation.name()));
                minX=Math.min(minX,p.getX());minZ=Math.min(minZ,p.getZ());maxX=Math.max(maxX,p.getX());maxZ=Math.max(maxZ,p.getZ());
            }
        }
        return new WorldsmithStructureLayout.Member(pack.structureKey(definition.getId()).identifier(),pack.id(),rule.getSpacingChunks(),rule.getSeparationChunks(),salt(pack.id()+":"+definition.getId()),
            new BoundingBox(minX-padding,0,minZ-padding,maxX+padding,0,maxZ+padding),WorldsmithStructureAnchor.resolve(pack,rule.getAnchor()));
    }

    static BlockPos pos(BuildPos p){return new BlockPos(p.getX(),p.getY(),p.getZ());}
    static BoundingBox box(BuildBox b){return new BoundingBox(b.getFrom().getX(),b.getFrom().getY(),b.getFrom().getZ(),b.getTo().getX(),b.getTo().getY(),b.getTo().getZ());}
    static int salt(String value) {
        try{return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).getInt()&Integer.MAX_VALUE;}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static long mixSeed(long value) {
        value=(value^(value>>>30))*0xBF58476D1CE4E5B9L;
        value=(value^(value>>>27))*0x94D049BB133111EBL;
        return value^(value>>>31);
    }
}
