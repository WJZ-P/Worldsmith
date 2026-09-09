package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.structure.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Direction;
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
        for(var artifact:pack.pack().getStructures().getArtifacts().values()) {
            int current=net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
            if(artifact.getTargetDataVersion()!=current || !artifact.getNativeCompilerVersion().equals(com.wjz.worldsmith.core.drawhost.DrawingVersions.NATIVE_COMPILER))
                throw new IllegalArgumentException("Frozen drawing "+artifact.getId()+" targets data version "+artifact.getTargetDataVersion()+" / "+artifact.getNativeCompilerVersion()+
                    "; this native exporter is "+current+" / "+com.wjz.worldsmith.core.drawhost.DrawingVersions.NATIVE_COMPILER+". Rebuild explicitly for this target; loading a pack never executes its source.");
        }
        // Check every palette entry and generated state, even in a low-weight variant.
        pack.structures().getBlueprints().values().forEach(b->b.getPalette().values().forEach(m->WorldsmithStructureTemplates.resolve(m,pack.blockResolver())));
        pack.structures().getTemplates().values().forEach(variants->variants.forEach(g->g.getVoxels().stream().map(StructureVoxel::getMaterial).distinct().forEach(m->WorldsmithStructureTemplates.resolve(m,pack.blockResolver()))));
        for(var definition:pack.pack().getStructures().getStructures()) {
            var site=site(pack,definition);
            var plans=pack.structures().getPlans().get(definition.getId()).stream().map(p->plan(pack,definition,p)).toList();
            var rotations=definition.getPlacement().getRotations().stream().map(r->Rotation.valueOf(r.name())).toList();
            var settings=new WorldsmithTemplateStructure.Settings(plans,rotations,site,layout(pack,definition),roads(pack,definition));
            var allowed=HolderSet.direct(definition.getPlacement().getBiomes().stream().map(id->biomes.getOrThrow(pack.biomeKey(id))).toList());
            // This codec check also protects direct exporter callers that bypassed the MCP validator.
            WorldsmithTemplateStructure.Settings.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,settings).getOrThrow();
            context.register(pack.structureKey(definition.getId()),new WorldsmithTemplateStructure(new Structure.StructureSettings(allowed),settings));
        }
    }

    static WorldsmithStructureSite site(CompiledPack pack,WorldStructureDefinition d) {
        var fit=d.getPlacement().getTerrainFit();var foundation=fit.getFoundation();var terrain=pack.terrain();
        var block=foundation.getMaterial()==null?Blocks.STONE.defaultBlockState():WorldsmithStructureTemplates.resolve(d.getBlueprint().getPalette().get(foundation.getMaterial()),pack.blockResolver());
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
            var localFootprint=new LinkedHashMap<Long,BlockPos>();var localSupports=new ArrayList<BlockPos>();
            for(var voxel:geometry.getVoxels()) {
                var at=pos(voxel.getPosition());localFootprint.merge(WorldsmithStructurePlan.columnKey(at),at,(a,b)->a.getY()>=b.getY()?a:b);
                if(at.getY()==0&&!voxel.getMaterial().isAir())localSupports.add(at);
            }
            var blueprint=pack.structures().getBlueprints().get(p.getBlueprintId());
            List<WorldsmithInstanceProcessor.Patch> patches=new ArrayList<>();
            for(var patch:blueprint.getVariation().getInstancePatches()) {
                List<net.minecraft.world.level.block.state.BlockState> states=new ArrayList<>();
                for(String key:patch.getMaterials()) {
                    states.add(WorldsmithStructureTemplates.resolve(blueprint.getPalette().get(key),pack.blockResolver()));
                    var alternatives=blueprint.getVariation().getMaterials().get(key);
                    if(alternatives!=null)for(var option:alternatives)states.add(WorldsmithStructureTemplates.resolve(blueprint.getPalette().get(option.getMaterial()),pack.blockResolver()));
                }
                var byBlock=new LinkedHashMap<net.minecraft.world.level.block.Block,net.minecraft.world.level.block.state.BlockState>();
                for(var state:states){if(!WorldsmithInstanceProcessor.stable(state))throw new IllegalArgumentException("Instance source must be a stable full cube");byBlock.putIfAbsent(state.getBlock(),state);}
                patches.add(new WorldsmithInstanceProcessor.Patch(List.copyOf(byBlock.values()),WorldsmithStructureTemplates.resolve(blueprint.getPalette().get(patch.getReplacement()),pack.blockResolver()),patch.getProbability(),patch.getScale()));
            }
            if(geometry.getLighting()!=null)for(var light:geometry.getLighting().getSources()) {
                var voxel=geometry.getVoxels().stream().filter(v->v.getPosition().equals(light.getAt())).findFirst().orElseThrow();
                var block=WorldsmithStructureTemplates.resolve(voxel.getMaterial(),pack.blockResolver()).getBlock();
                if(patches.stream().anyMatch(patch->patch.sources().stream().anyMatch(state->state.getBlock()==block)&&patch.replacement().getLightEmission()<light.getLevel()))
                    throw new IllegalArgumentException("Instance patches must preserve declared lighting at "+light.getAt());
            }
            var detail=new WorldsmithInstanceProcessor.Config(List.copyOf(patches),geometry.getProtectedAreas().stream().map(WorldsmithStructures::box).toList());
            var baseId=pack.structureTemplateId(p.getBlueprintId(),p.getVariant());
            List<WorldsmithStructurePlan.Tile> tiles=new ArrayList<>();
            if(geometry.getDrawingSource()) {
                var fragments=StructureTiling.tiles(geometry);
                for(int i=0;i<fragments.size();i++) {var t=fragments.get(i);tiles.add(new WorldsmithStructurePlan.Tile(WorldsmithStructureTemplates.tileId(baseId,i),pos(t.getOffset()),pos(t.getGeometry().getSize())));}
            }
            parts.add(new WorldsmithStructurePlan.Part(tiles.isEmpty()?baseId:tiles.getFirst().template(),pos(p.getOffset()),Rotation.valueOf(p.getRotation().name()),pos(geometry.getSize()),List.copyOf(reserved),List.copyOf(localFootprint.values()),List.copyOf(localSupports),detail,List.copyOf(tiles)));
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
                var min=root.getGeometry().getSourceMin();
                var normalized=new BuildPos(p.getX()-min.getX(),p.getY()-min.getY(),p.getZ()-min.getZ());
                var at=pos(StructureCatalogCompiler.transform(normalized,root));supports.put(WorldsmithStructurePlan.columnKey(at),at);
            }
        }
        var bounds=box(plan.getBounds());
        List<WorldsmithStructurePlan.Link> links=new ArrayList<>();
        for(var link:plan.getConnections()) {
            var a=plan.getParts().get(link.getFromPart()).getGeometry().getPorts().stream().filter(v->v.getId().equals(link.getFromPort())).findFirst().orElseThrow();
            var b=plan.getParts().get(link.getToPart()).getGeometry().getPorts().stream().filter(v->v.getId().equals(link.getToPort())).findFirst().orElseThrow();
            links.add(new WorldsmithStructurePlan.Link(link.getFromPart(),pos(a.getAt()),Direction.valueOf(a.getFacing().name()),link.getToPart(),pos(b.getAt()),Direction.valueOf(b.getFacing().name()),a.getPassage()));
        }
        var result=new WorldsmithStructurePlan(List.copyOf(parts),List.copyOf(footprint.values()),List.copyOf(supports.values()),bounds.maxY()+1,bounds,List.copyOf(links));
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
        if(definition.getAssembly()!=null&&definition.getAssembly().getRoads()!=null){int r=definition.getAssembly().getMaxRadius();minX=-r;maxX=r;minZ=-r;maxZ=r;}
        var r=rule.getRegion();
        var region=r==null?Optional.<WorldsmithStructureRegion>empty():Optional.of(new WorldsmithStructureRegion(salt(pack.id()+":region:"+r.getGroup()),r.getCellSize(),r.getMinInfluence(),r.getMaxInfluence(),r.getChance(),r.getWater().name(),r.getWaterRadius()));
        return new WorldsmithStructureLayout.Member(pack.structureKey(definition.getId()).identifier(),pack.id(),rule.getSpacingChunks(),rule.getSeparationChunks(),salt(pack.id()+":"+definition.getId()),
            new BoundingBox(minX-padding,0,minZ-padding,maxX+padding,0,maxZ+padding),WorldsmithStructureAnchor.resolve(pack,rule.getAnchor()),region);
    }
    static Optional<WorldsmithRoadSettings> roads(CompiledPack pack,WorldStructureDefinition d) {
        var a=d.getAssembly();if(a==null||a.getRoads()==null)return Optional.empty();var r=a.getRoads();var palette=d.getBlueprint().getPalette();
        var stairs=Optional.ofNullable(r.getStairMaterial()).map(k->{var state=WorldsmithStructureTemplates.resolve(palette.get(k),pack.blockResolver());if(!(state.getBlock() instanceof net.minecraft.world.level.block.StairBlock))throw new IllegalArgumentException("Road stairMaterial must be stairs");return state.setValue(net.minecraft.world.level.block.StairBlock.HALF,net.minecraft.world.level.block.state.properties.Half.BOTTOM).setValue(net.minecraft.world.level.block.StairBlock.SHAPE,net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT);});
        return Optional.of(new WorldsmithRoadSettings(WorldsmithStructureTemplates.resolve(palette.get(r.getMaterial()),pack.blockResolver()),stairs,Optional.ofNullable(r.getBridgeMaterial()).map(k->WorldsmithStructureTemplates.resolve(palette.get(k),pack.blockResolver())),r.getWidth(),r.getMaxSpan(),r.getMaxCut(),a.getMaxRadius(),a.getTerrainFollowing(),a.getMaxElevationDifference()));
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
