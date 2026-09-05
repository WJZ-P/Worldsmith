package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.wjz.worldsmith.core.structure.StructureGeometryCompiler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldsmithStructureOptimizationTest {
    @TempDir Path temp;
    @BeforeAll static void bootstrap() { WorldsmithTestBootstrap.bootStrap(); }

    @Test void candidateReservationsNeverOverlapAndDoNotDependOnTraversalOrder() {
        var a=member("tower",3,1,14,new BlockPos(40,30,20),new BlockPos(0,0,19));
        var b=member("lodge",4,1,67,new BlockPos(32,20,48),new BlockPos(28,0,8));
        var c=member("shrine",5,2,156,new BlockPos(50,30,50),new BlockPos(25,0,25));
        List<WorldsmithStructureLayout.Member> members=List.of(a,b,c);
        record Site(WorldsmithStructureLayout.Member member,ChunkPos pos) {}
        List<Site> candidates=new ArrayList<>();
        for(var m:members)for(int x=-7;x<=7;x++)for(int z=-7;z<=7;z++) {
            candidates.add(new Site(m,m.placement().getPotentialStructureChunk(92841L,x*m.spacing(),z*m.spacing())));
        }
        var accepted=candidates.stream().filter(s->WorldsmithStructureLayout.accepts(s.member,s.pos,92841L,members)).toList();
        var reversed=candidates.reversed().stream().filter(s->WorldsmithStructureLayout.accepts(s.member,s.pos,92841L,members.reversed())).toList();
        assertTrue(accepted.size()>10,"arbitration discarded every useful site");
        assertEquals(new java.util.HashSet<>(accepted),new java.util.HashSet<>(reversed));
        for(int i=0;i<accepted.size();i++)for(int j=i+1;j<accepted.size();j++) {
            var x=accepted.get(i);var y=accepted.get(j);
            assertFalse(x.member.bounds(x.pos).intersects(y.member.bounds(y.pos)),"accepted buildings overlap: "+x+" and "+y);
        }
    }

    @Test void reservationEnvelopeContainsEveryAllowedRotationIncludingOffCentrePivots() {
        BlockPos size=new BlockPos(64,18,31),pivot=new BlockPos(63,0,0);
        var envelope=WorldsmithStructureLayout.envelope(size,pivot,List.of(Rotation.values()),3);
        for(Rotation r:Rotation.values())for(int x=0;x<size.getX();x++)for(int z=0;z<size.getZ();z++) {
            BlockPos relative=new BlockPos(x,0,z).rotate(r).subtract(pivot.rotate(r));
            assertTrue(envelope.isInside(relative));
            assertTrue(envelope.isInside(relative.offset(3,0,3)));
            assertTrue(envelope.isInside(relative.offset(-3,0,-3)));
        }
    }

    @Test void pillarsProbeTheRoomBetweenSupportsRatherThanOnlyTheirEndpoints() {
        var ends=List.of(new BlockPos(0,0,0),new BlockPos(4,0,4));
        var footprint=List.of(new BlockPos(0,0,0),new BlockPos(2,0,2),new BlockPos(4,0,4));
        var settings=settings(new BlockPos(5,8,5),"PILLARS",footprint,ends);
        var result=WorldsmithTerrainProbe.probe(settings,BlockPos.ZERO,Rotation.NONE,-64,319,(x,z)->
            new WorldsmithTerrainProbe.Column(x==2 && z==2?80:65,x==2 && z==2?80:65));
        assertEquals(WorldsmithTerrainProbe.Rejection.EXCESSIVE_SLOPE,result.rejection());
    }

    @Test void rotationsAndFoundationsReuseEachSampledColumn() {
        var points=List.of(new BlockPos(0,0,0),new BlockPos(1,0,0),new BlockPos(2,0,0));
        var settings=settings(new BlockPos(3,8,3),"FILL",points,points);
        AtomicInteger reads=new AtomicInteger();
        var sampler=new WorldsmithTerrainProbe.CachedSampler((x,z)->{
            reads.incrementAndGet();return new WorldsmithTerrainProbe.Column(65,65);
        });
        assertTrue(WorldsmithTerrainProbe.probe(settings,BlockPos.ZERO,Rotation.NONE,-64,319,sampler).accepted());
        assertEquals(3,reads.get(),"support fitting resampled the footprint");
        assertTrue(WorldsmithTerrainProbe.probe(settings,BlockPos.ZERO,Rotation.CLOCKWISE_90,-64,319,sampler).accepted());
        assertEquals(5,reads.get(),"rotated candidate did not share its origin sample");
    }

    @Test void foundationWorkHasABoundedRuntimeBudget() {
        List<BlockPos> footprint=new ArrayList<>();
        for(int x=0;x<64;x++)for(int z=0;z<64;z++)footprint.add(new BlockPos(x,0,z));
        var settings=settings(new BlockPos(64,8,64),"FILL",footprint,footprint);
        var result=WorldsmithTerrainProbe.probe(settings,BlockPos.ZERO,Rotation.NONE,-64,319,(x,z)->{
            int y=x==0 && z==0?68:64;
            return new WorldsmithTerrainProbe.Column(y,y);
        });
        assertEquals(WorldsmithTerrainProbe.Rejection.FOUNDATION_BUDGET,result.rejection());
    }

    @Test void singleColumnProbeMatchesBothRealMinecraftHeightmaps() {
        var pack=WorldsmithPacks.builtinCompiled();
        var registry=WorldsmithPackExporter.compilePatch(pack,VanillaRegistries.createLookup()).full();
        var biome=registry.lookupOrThrow(Registries.BIOME).getOrThrow(pack.biomes().getFirst().key());
        var settings=registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(pack.noiseSettingsKey());
        var generator=new NoiseBasedChunkGenerator(new FixedBiomeSource(biome),settings);
        var random=RandomState.create(registry,pack.noiseSettingsKey(),91254L);
        var height=LevelHeightAccessor.create(-64,384);
        for(int x:new int[]{-1037,-1,0,432,2834}) {
            int z=x/2;
            var actual=WorldsmithTerrainProbe.readColumn(generator.getBaseColumn(x,z,height,random),-64,319);
            assertEquals(generator.getFirstFreeHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,height,random),actual.groundY());
            assertEquals(generator.getFirstFreeHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,height,random),actual.surfaceY());
        }
    }

    @Test void decodedSettingsRejectUnprobedSupportsAndUndersizedReservations() {
        var points=List.of(new BlockPos(0,0,0),new BlockPos(4,0,4));
        var config=settings(new BlockPos(5,8,5),"FILL",points,points);
        var json=WorldsmithTemplateStructure.Settings.CODEC.encodeStart(JsonOps.INSTANCE,config).getOrThrow().getAsJsonObject();
        var missingSupport=json.deepCopy();
        var incomplete=new JsonArray();
        incomplete.add(json.getAsJsonArray("footprint").get(0));
        missingSupport.add("footprint",incomplete);
        assertTrue(WorldsmithTemplateStructure.Settings.CODEC.parse(JsonOps.INSTANCE,missingSupport).error().isPresent());
        var undersized=json.deepCopy();
        undersized.getAsJsonObject("layout").add("envelope",JsonParser.parseString("[0,0,0,1,0,1]"));
        assertTrue(WorldsmithTemplateStructure.Settings.CODEC.parse(JsonOps.INSTANCE,undersized).error().isPresent());
    }

    @Test void differentPackScopesDoNotSuppressEachOthersCandidates() {
        var a=member("a",24,8,37,new BlockPos(5,8,5),BlockPos.ZERO);
        var b=new WorldsmithStructureLayout.Member(Identifier.fromNamespaceAndPath("worldsmith","b"),"other_scope",
            a.spacing(),a.separation(),a.salt(),a.envelope());
        var site=a.placement().getPotentialStructureChunk(72L,0,0);
        assertTrue(WorldsmithStructureLayout.accepts(a,site,72L,List.of(a,b)));
        assertTrue(WorldsmithStructureLayout.accepts(b,site,72L,List.of(a,b)));
    }

    @Test void generationReadsPeerReservationsFromTheActualStructureRegistry() throws Exception {
        var pack=WorldsmithPacks.builtinCompiled();
        var lookup=WorldsmithPackExporter.compilePatch(pack,VanillaRegistries.createLookup()).full();
        var biome=lookup.lookupOrThrow(Registries.BIOME).getOrThrow(pack.biomes().getFirst().key());
        var noise=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(pack.noiseSettingsKey());
        var generator=new NoiseBasedChunkGenerator(new FixedBiomeSource(biome),noise);
        var random=RandomState.create(lookup,pack.noiseSettingsKey(),91254L);
        var height=LevelHeightAccessor.create(-64,384);
        var site=new ChunkPos(0,0);
        var column=WorldsmithTerrainProbe.readColumn(generator.getBaseColumn(8,8,height,random),-64,319);
        var surface=column.surfaceY()>column.groundY()?"OCEAN_FLOOR":"LAND_SURFACE";
        var template=Identifier.fromNamespaceAndPath("worldsmith","test/peer_template");
        var size=new BlockPos(15,13,19);
        var envelope=WorldsmithStructureLayout.envelope(size,BlockPos.ZERO,List.of(Rotation.NONE),2);
        var registry=new MappedRegistry<Structure>(Registries.STRUCTURE,Lifecycle.stable());
        List<WorldsmithTemplateStructure> peers=new ArrayList<>();
        for(String name:List.of("a","b")) {
            var id=Identifier.fromNamespaceAndPath("worldsmith",name);
            // One native candidate per 4096-chunk cell, same salt: both compete
            // at (0,0), with no nearby self-candidate. The id breaks the rank tie.
            var layout=new WorldsmithStructureLayout.Member(id,"same_pack",4096,4095,56,envelope);
            var config=new WorldsmithTemplateStructure.Settings(template,size,BlockPos.ZERO,List.of(Rotation.NONE),surface,0,"NONE",
                Blocks.STONE.defaultBlockState(),0,List.of(BlockPos.ZERO),List.of(new BoundingBox(0,0,0,14,12,18)),List.of(BlockPos.ZERO),layout);
            var structure=new WorldsmithTemplateStructure(new Structure.StructureSettings(HolderSet.direct(biome)),config);
            registry.register(ResourceKey.create(Registries.STRUCTURE,id),structure,RegistrationInfo.BUILT_IN);
            peers.add(structure);
        }
        var registries=new RegistryAccess.ImmutableRegistryAccess(List.of(registry)).freeze();
        try(var storage=LevelStorageSource.createDefault(temp).createAccess("peer-reservations")) {
            var manager=new StructureTemplateManager(ResourceManager.Empty.INSTANCE,storage,DataFixers.getDataFixer(),BuiltInRegistries.BLOCK);
            manager.getOrCreate(template).load(BuiltInRegistries.BLOCK,WorldsmithStructureTemplates.encode(StructureGeometryCompiler.compile(WorldsmithStructureTest.example())));
            for(int index:new int[]{1,0}) {
                var context=new Structure.GenerationContext(registries,generator,generator.getBiomeSource(),random,manager,91254L,site,height,b->true);
                assertEquals(index==0,peers.get(index).findGenerationPoint(context).isPresent(),"peer registry must reject only the losing candidate");
            }
        }
    }

    private static WorldsmithStructureLayout.Member member(String id,int spacing,int separation,int salt,BlockPos size,BlockPos origin) {
        return new WorldsmithStructureLayout.Member(Identifier.fromNamespaceAndPath("worldsmith",id),"scope",spacing,separation,salt,
            WorldsmithStructureLayout.envelope(size,origin,List.of(Rotation.values()),2));
    }

    private static WorldsmithTemplateStructure.Settings settings(BlockPos size,String mode,List<BlockPos> footprint,List<BlockPos> supports) {
        var id=Identifier.fromNamespaceAndPath("worldsmith","test/fit");
        var member=member("fit",24,8,146,size,BlockPos.ZERO);
        return new WorldsmithTemplateStructure.Settings(id,size,BlockPos.ZERO,List.of(Rotation.values()),"LAND_SURFACE",4,mode,
            Blocks.STONE_BRICKS.defaultBlockState(),6,supports,List.of(new BoundingBox(0,0,0,size.getX()-1,size.getY()-1,size.getZ()-1)),footprint,member);
    }
}
