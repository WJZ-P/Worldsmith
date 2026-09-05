package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.*;
import com.wjz.worldsmith.core.structure.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldsmithStructureAnchorTest {
    private static final long SEED = 981723L;
    private static CompiledPack pack;
    private static HolderLookup.Provider lookup;
    @TempDir Path temp;

    @BeforeAll static void bootstrap() {
        WorldsmithTestBootstrap.bootStrap();
        pack=pack(new AnchorPlacement.Fixed(-31,17),new StructureAnchorTarget("holy_peak",5,-2,0.5));
        lookup=WorldsmithPackExporter.compilePatch(pack,VanillaRegistries.createLookup()).full();
    }

    @Test void optionalRandomModeStillUsesTheNativePlacementAndUnchangedCandidateArithmetic() {
        var source=pack(new AnchorPlacement.Fixed(-31,17),null);
        var definition=new WorldStructureDefinition("house",blueprint(),new com.wjz.worldsmith.core.structure.StructurePlacement(
            List.of(source.biomes().getFirst().definition().getId()),24,8,List.of(BuildRotation.NONE),new StructureTerrainFit(StructureSurface.LAND_SURFACE,3,new StructureFoundation(FoundationMode.NONE,null,0,List.of())),2,null));
        var layout=WorldsmithStructures.layout(source,source.pack().getStructures().getStructures().getFirst());
        assertTrue(layout.anchor().isEmpty());
        var nativePlacement=assertInstanceOf(RandomSpreadStructurePlacement.class,layout.placement());
        for (int x:new int[]{-49,-24,-1,0,27,91}) {
            var expected=new RandomSpreadStructurePlacement(24,8,net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType.LINEAR,layout.salt())
                .getPotentialStructureChunk(SEED,x,-x);
            assertEquals(expected,nativePlacement.getPotentialStructureChunk(SEED,x,-x));
        }
    }

    @Test void fixedAnchorUsesTheContainingChunkButKeepsItsExactBlockPivot() {
        var target=new WorldsmithStructureAnchor.Fixed(-31,17);
        var placement=new WorldsmithAnchorStructurePlacement(target,37);
        var state=state();
        assertTrue(placement.isStructureChunk(state,-2,1));
        assertFalse(placement.isStructureChunk(state,-1,1));
        assertEquals(new BlockPos(-31,0,17),target.inChunk(new ChunkPos(-2,1),null).orElseThrow());
        assertNotEquals(new BlockPos(-24,0,24),target.inChunk(new ChunkPos(-2,1),null).orElseThrow());
    }

    @Test void scatteredSitesMatchTheTerrainLatticeAtTwoDifferentWorldSeeds() {
        var grid=new WorldsmithStructureAnchor.Scattered(512,0.85,13,-9);
        var a=WorldsmithAnchorStructurePlacement.noise(RandomState.create(lookup,pack.noiseSettingsKey(),SEED));
        var b=WorldsmithAnchorStructurePlacement.noise(RandomState.create(lookup,pack.noiseSettingsKey(),SEED+1));
        boolean changed=false;
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
            var point=grid.cell(x,z,a);
            var repeated=grid.inChunk(ChunkPos.containing(point),a).orElseThrow();
            assertEquals(point,repeated);
            assertTrue(WorldsmithAnchorFields.latticeDistance(point.getX()-13,point.getZ()+9,512,0.85,a)<=Math.sqrt(0.5)+1e-6);
            changed|=!point.equals(grid.cell(x,z,b));
        }
        assertTrue(changed,"scattered placement must follow the seeded terrain noise, not a second unrelated RNG");
    }

    @Test void maximumJitterDeduplicatesBoundaryInstancesConsistentlyAcrossQuerySizes() {
        var grid=new WorldsmithStructureAnchor.Scattered(64,1,0,0);
        WorldsmithAnchorFields.NoiseSampler noise=(x,y,z)->Math.floorMod((int)((y==0?x:z)/64),2)==0?1:-1;
        assertEquals(grid.cell(0,0,noise),grid.cell(1,1,noise));
        var sites=grid.sitesIn(new BoundingBox(-256,0,-256,256,0,256),noise);
        assertFalse(sites.isEmpty());
        assertEquals(sites.size(),sites.stream().map(ChunkPos::containing).distinct().count());
        for(var point:sites) assertEquals(point,grid.inChunk(ChunkPos.containing(point),noise).orElseThrow());
    }

    @Test void anchorsTakePriorityOverRandomSitesWithoutDependingOnIterationOrder() {
        var id=Identifier.fromNamespaceAndPath("worldsmith","house");
        var envelope=new BoundingBox(-10,0,-10,10,0,10);
        var random=new WorldsmithStructureLayout.Member(id,"pack",24,8,8,envelope,Optional.empty());
        var site=WorldsmithStructureLayout.middle(random.randomPlacement().getPotentialStructureChunk(SEED,0,0));
        var point=site.offset(1,0,-1);
        var anchor=new WorldsmithStructureLayout.Member(Identifier.fromNamespaceAndPath("worldsmith","temple"),"pack",24,8,92,envelope,
            Optional.of(new WorldsmithStructureAnchor.Fixed(point.getX(),point.getZ())));
        for(var members:List.of(List.of(random,anchor),List.of(anchor,random))) {
            assertFalse(WorldsmithStructureLayout.accepts(random,site,SEED,null,members));
            assertTrue(WorldsmithStructureLayout.accepts(anchor,point,SEED,null,members));
        }
    }

    @Test void lineReferencesResolveTheirChosenPointAndOffsetsWithoutCreatingAnEntireRowOfBuildings() {
        var line=pack(new AnchorPlacement.Line(-100,-60,300,140),new StructureAnchorTarget("holy_peak",10,-7,0.25));
        var target=WorldsmithStructures.layout(line,line.pack().getStructures().getStructures().getFirst()).anchor().orElseThrow();
        assertEquals(new WorldsmithStructureAnchor.Fixed(10,-17),target);
        assertEquals(1,target.sitesIn(new BoundingBox(-500,0,-500,500,0,500),null).size());
    }

    @Test void nativePlacementAndExportedStructuresRoundTripThroughMinecraftCodecs() throws Exception {
        var compiled=WorldsmithPackExporter.compilePatch(pack,VanillaRegistries.createLookup());
        var ops=compiled.full().createSerializationContext(JsonOps.INSTANCE);
        var set=compiled.full().lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(pack.structureSetKey("temple")).value();
        var json=StructureSet.DIRECT_CODEC.encodeStart(ops,set).getOrThrow();
        assertTrue(json.toString().contains("worldsmith:anchor"));
        var roundtrip=StructureSet.DIRECT_CODEC.parse(ops,json).getOrThrow();
        assertEquals(new WorldsmithStructureAnchor.Fixed(-26,15),assertInstanceOf(WorldsmithAnchorStructurePlacement.class,roundtrip.placement()).anchor());
        var grid=new WorldsmithAnchorStructurePlacement(new WorldsmithStructureAnchor.Scattered(512,0.73,-17,11),123);
        var encoded=net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC.encodeStart(ops,grid).getOrThrow();
        assertEquals(grid.anchor(),assertInstanceOf(WorldsmithAnchorStructurePlacement.class,
            net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC.parse(ops,encoded).getOrThrow()).anchor());
        assertEquals(55,WorldsmithPackExporter.write(pack,compiled,temp));
        assertTrue(Files.exists(temp.resolve("data/worldsmith/structure/"+pack.structureTemplateId("floor").getPath()+".nbt")));
    }

    @Test void aRealAnchorStartKeepsTheExactPivotAndStillHonoursBiomeEligibility() throws Exception {
        var holder=lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey("temple"));
        var structure=(WorldsmithTemplateStructure)holder.value();
        var biome=lookup.lookupOrThrow(Registries.BIOME).getOrThrow(pack.biomes().getFirst().key());
        var generator=new NoiseBasedChunkGenerator(new FixedBiomeSource(biome),lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(pack.noiseSettingsKey()));
        var random=RandomState.create(lookup,pack.noiseSettingsKey(),SEED);
        var height=LevelHeightAccessor.create(-64,384);
        var registryAccess=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        try(var storage=LevelStorageSource.createDefault(temp).createAccess("anchor-start")) {
            var manager=new StructureTemplateManager(ResourceManager.Empty.INSTANCE,storage,DataFixers.getDataFixer(),BuiltInRegistries.BLOCK);
            manager.getOrCreate(pack.structureTemplateId("floor")).load(BuiltInRegistries.BLOCK,WorldsmithStructureTemplates.encode(StructureGeometryCompiler.compile(blueprint())));
            var context=new Structure.GenerationContext(registryAccess,generator,generator.getBiomeSource(),random,manager,SEED,new ChunkPos(-2,0),height,b->true);
            var stub=structure.findGenerationPoint(context).orElseThrow();
            assertEquals(-26,stub.position().getX());assertEquals(15,stub.position().getZ());
            var piece=(WorldsmithTemplatePiece)stub.getPiecesBuilder().build().pieces().getFirst();
            var pivot=piece.templatePosition().subtract(structure.templateSettings().plans().getFirst().parts().getFirst().offset().rotate(piece.getRotation()));
            assertEquals(stub.position(),pivot);
            var serial=new net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext(ResourceManager.Empty.INSTANCE,registryAccess,manager);
            var restored=new WorldsmithTemplatePiece(serial,piece.createTag(serial));
            assertEquals(piece.templatePosition(),restored.templatePosition());
            assertFalse(structure.generate(holder,Level.OVERWORLD,registryAccess,generator,generator.getBiomeSource(),random,manager,SEED,new ChunkPos(-2,0),0,height,b->false).isValid());
            assertTrue(structure.generate(holder,Level.OVERWORLD,registryAccess,generator,generator.getBiomeSource(),random,manager,SEED,new ChunkPos(-2,0),0,height,b->true).isValid());
            assertTrue(structure.findGenerationPoint(new Structure.GenerationContext(registryAccess,generator,generator.getBiomeSource(),random,manager,SEED,new ChunkPos(-1,0),height,b->true)).isEmpty());
        }
    }

    @Test void locateEnumeratesExactAnchorCandidatesAndBoundsScatteredQueries() {
        var holder=lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey("temple"));
        var wanted=HolderSet.<Structure>direct(holder);
        var points=WorldsmithAnchorStructureLocator.candidates(state(),wanted,BlockPos.ZERO,100);
        assertEquals(1,points.size());assertEquals(new BlockPos(-26,0,15),points.getFirst().pivot());
        var biome=lookup.lookupOrThrow(Registries.BIOME).getOrThrow(pack.biomes().getFirst().key());
        var scattered=new WorldsmithAnchorStructurePlacement(new WorldsmithStructureAnchor.Scattered(512,0.8,0,0),42);
        var state=ChunkGeneratorStructureState.createForFlat(RandomState.create(lookup,pack.noiseSettingsKey(),SEED),SEED,new FixedBiomeSource(biome),
            java.util.stream.Stream.of(Holder.direct(new StructureSet(holder,scattered))));
        var candidates=WorldsmithAnchorStructureLocator.candidates(state,wanted,BlockPos.ZERO,100);
        assertEquals(WorldsmithAnchorStructureLocator.MAX_SITE_CHECKS,candidates.size());
        for(int i=1;i<candidates.size();i++)assertTrue(BlockPos.ZERO.distSqr(candidates.get(i-1).pivot())<=BlockPos.ZERO.distSqr(candidates.get(i).pivot()));
        for(var site:candidates)assertTrue(scattered.isStructureChunk(state,ChunkPos.containing(site.pivot()).x(),ChunkPos.containing(site.pivot()).z()));
    }

    private static ChunkGeneratorStructureState state() {
        var biome=lookup.lookupOrThrow(Registries.BIOME).getOrThrow(pack.biomes().getFirst().key());
        // Datagen lookup contains unresolved vanilla tag placeholders. Supply
        // this fixture's exported set only; anchor placement does not use rings.
        return ChunkGeneratorStructureState.createForFlat(RandomState.create(lookup,pack.noiseSettingsKey(),SEED),SEED,new FixedBiomeSource(biome),
            java.util.stream.Stream.of(lookup.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(pack.structureSetKey("temple"))));
    }

    private static StructureBlueprint blueprint() {
        return new StructureBlueprint(1,"floor",new BuildPos(3,2,3),new BuildPos(1,0,1),Map.of("stone",new BuildMaterial("minecraft:stone_bricks",Map.of())),
            List.of(new BuildOperation.Fill("floor",new BuildPos(0,0,0),new BuildPos(2,0,2),"stone")),Map.of(),List.of());
    }

    private static CompiledPack pack(AnchorPlacement anchor, StructureAnchorTarget target) {
        var source=WorldsmithPacks.builtin();var base=source.getTerrain();
        var s=WorldsmithTerrainSamplingTest.shape(.98,2,.05,1,0,0,.1,0);
        var shape=new TerrainShape.Procedural(s.getLandRatio(),s.getContinentScale(),s.getCoastRoughness(),s.getRelief(),s.getVerticalScale(),s.getCaves(),s.getHydrology(),s.getBands(),
            List.of(new Anchor("holy_peak",anchor,128,32,1,null)));
        var terrain=new TerrainPlan(1,SEED,-64,384,1,2,63,base.getDefaultBlock(),base.getDefaultFluid(),shape,true,true,false,base.getSpawnTargets());
        var placement=new com.wjz.worldsmith.core.structure.StructurePlacement(source.getBiomes().getBiomes().stream().map(BiomeDefinition::getId).toList(),24,8,
            List.of(BuildRotation.NONE,BuildRotation.CLOCKWISE_90),new StructureTerrainFit(StructureSurface.LAND_SURFACE,12,new StructureFoundation(FoundationMode.FILL,"stone",16,List.of())),2,target);
        var structures=new StructureLibrary(1,List.of(new WorldStructureDefinition("temple",blueprint(),placement)));
        String id="8".repeat(64);
        return CompiledPack.scoped(new WorldsmithPack(new WorldsmithPackManifest(1,id,"Anchor structure","Test",source.getManifest().getFiles()),terrain,source.getBiomes(),source.getFeatures(),id,structures));
    }
}
