package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.*;
import com.wjz.worldsmith.core.structure.*;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.Container;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.loot.LootTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldsmithStructureExpansionTest {
    @TempDir Path temp;
    @BeforeAll static void boot(){WorldsmithTestBootstrap.bootStrap();}
    private static WorldsmithStructurePlan plan() {
        var part=new WorldsmithStructurePlan.Part(Identifier.fromNamespaceAndPath("worldsmith","test"),BlockPos.ZERO,Rotation.NONE,new BlockPos(3,5,3),List.of(new BoundingBox(0,0,0,2,4,2)));
        List<BlockPos> columns=new ArrayList<>(),supports=new ArrayList<>();
        for(int x=0;x<3;x++)for(int z=0;z<3;z++){columns.add(new BlockPos(x,4,z));supports.add(new BlockPos(x,0,z));}
        return new WorldsmithStructurePlan(List.of(part),columns,supports,5,new BoundingBox(0,0,0,2,4,2));
    }
    private static WorldsmithStructureSite site(String surface,int min,int max,int layer,String foundation,int depth,int cut,int budget) {
        return new WorldsmithStructureSite(surface,min,max,layer,0,8,8,foundation,Blocks.STONE_BRICKS.defaultBlockState(),depth,cut,budget);
    }
    private static WorldsmithTerrainProbe.Result probe(WorldsmithStructureSite site,WorldsmithTerrainProbe.Sampler sampler) {
        return WorldsmithTerrainProbe.probe(plan(),site,BlockPos.ZERO,Rotation.NONE,-64,319,sampler);
    }
    private static NoiseColumn noise(java.util.function.IntFunction<BlockState> states) {
        BlockState[] array=new BlockState[384];for(int y=-64;y<=319;y++)array[y+64]=states.apply(y);return new NoiseColumn(-64,array);
    }

    @Test void skySurfaceCanChooseALowerIslandInsteadOfTheHighestHeightmap() {
        var noise=noise(y->y<=60 || y>=171&&y<=185 || y>=240&&y<=252?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        var lower=site("SKY_SURFACE",140,280,1,"NONE",0,0,4096);
        var column=WorldsmithTerrainProbe.readColumn(noise,-64,319,lower,5);
        assertEquals(253,column.groundY());
        assertEquals(186,probe(lower,(x,z)->column).plan().position().getY());
        assertEquals(253,probe(site("SKY_SURFACE",140,280,0,"NONE",0,0,4096),(x,z)->column).plan().position().getY());
        var ordinary=WorldsmithTerrainProbe.readColumn(noise(y->y<=185?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState()),-64,319,lower,5);
        assertEquals(WorldsmithTerrainProbe.Rejection.NO_SURFACE,probe(lower,(x,z)->ordinary).rejection());
    }
    @Test void caveFloorsAndCeilingsIncludeNegativeOneWithoutASentinelCollision() {
        var noise=noise(y->y>=-1&&y<16?Blocks.AIR.defaultBlockState():Blocks.STONE.defaultBlockState());
        var floor=site("CAVE_FLOOR",-32,40,0,"NONE",0,0,4096);
        var ceiling=site("CAVE_CEILING",-32,40,0,"NONE",0,0,4096);
        assertEquals(-1,probe(floor,(x,z)->WorldsmithTerrainProbe.readColumn(noise,-64,319,floor,5)).plan().position().getY());
        assertEquals(11,probe(ceiling,(x,z)->WorldsmithTerrainProbe.readColumn(noise,-64,319,ceiling,5)).plan().position().getY());
    }
    @Test void waterPlatformsCanFloatOrUseBoundedPillarsIncludingUndergroundLakes() {
        var water=site("WATER_SURFACE",-64,319,0,"NONE",0,0,4096);
        var result=probe(water,(x,z)->new WorldsmithTerrainProbe.Column(58,63));
        assertTrue(result.accepted());assertEquals(63,result.plan().position().getY());assertTrue(result.plan().foundations().isEmpty());
        var pillars=probe(site("WATER_SURFACE",-64,319,0,"PILLARS",8,0,4096),(x,z)->new WorldsmithTerrainProbe.Column(58,63));
        assertEquals(45,pillars.plan().foundations().stream().mapToInt(BoundingBox::getYSpan).sum());
        var lake=noise(y->y>=40&&y<=45?Blocks.WATER.defaultBlockState():y>=46&&y<=70?Blocks.AIR.defaultBlockState():Blocks.STONE.defaultBlockState());
        var bounded=site("WATER_SURFACE",20,80,0,"NONE",0,0,4096);
        assertEquals(46,probe(bounded,(x,z)->WorldsmithTerrainProbe.readColumn(lake,-64,319,bounded,5)).plan().position().getY());
    }
    @Test void earthworkUsesAMedianDatumWithExplicitCutAndVolumeLimits() {
        WorldsmithTerrainProbe.Sampler slope=(x,z)->new WorldsmithTerrainProbe.Column(x==0?68:65,x==0?68:65);
        var result=probe(site("LAND_SURFACE",-64,319,0,"FILL",6,3,100),slope);
        assertTrue(result.accepted());assertEquals(65,result.plan().position().getY());
        assertEquals(9,result.plan().cuts().stream().mapToInt(BoundingBox::getYSpan).sum());
        assertEquals(WorldsmithTerrainProbe.Rejection.EXCESSIVE_SLOPE,probe(site("LAND_SURFACE",-64,319,0,"FILL",6,2,100),slope).rejection());
        assertEquals(WorldsmithTerrainProbe.Rejection.FOUNDATION_BUDGET,probe(site("LAND_SURFACE",-64,319,0,"FILL",6,3,5),slope).rejection());
        assertEquals(68,probe(site("LAND_SURFACE",-64,319,0,"FILL",6,0,4096),slope).plan().position().getY());
    }
    @Test void caveFittingChecksTheEntireBuildingHeightAcrossColumns() {
        var s=site("CAVE_FLOOR",40,100,0,"FILL",6,0,4096);
        var result=probe(s,(x,z)->new WorldsmithTerrainProbe.Column(100,100,false,List.of(
            new WorldsmithTerrainProbe.AirSpan(x==1?70:65,x==2?72:90,true,false,x==1?70:65,0))));
        assertEquals(WorldsmithTerrainProbe.Rejection.OBSTRUCTED,result.rejection());
    }
    @Test void nearbySearchAndSamplingAreBoundedAndDeterministic() {
        var origin=new BlockPos(-31,0,17);var sites=WorldsmithTerrainProbe.sites(origin,16);
        assertTrue(sites.size()<=16);assertEquals(origin,sites.getFirst());assertTrue(sites.contains(origin.offset(16,0,0)));
        assertTrue(sites.stream().allMatch(p->p.distSqr(origin)<=256));assertEquals(sites,WorldsmithTerrainProbe.sites(origin,16));
        assertEquals(List.of(origin),WorldsmithTerrainProbe.sites(origin,0));
        var cache=new WorldsmithTerrainProbe.CachedSampler((x,z)->new WorldsmithTerrainProbe.Column(65,65));
        for(int i=0;i<WorldsmithTerrainProbe.MAX_COLUMNS;i++)cache.sample(i,0);
        assertThrows(WorldsmithTerrainProbe.ProbeBudgetExceeded.class,()->cache.sample(-1,0));
    }

    private static String resource(String id)throws Exception {
        try(var stream=WorldsmithStructureExpansionTest.class.getClassLoader().getResourceAsStream("worldsmith/structures/"+id+".json")){return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
    }
    private static StructureBlueprint blueprint(String id)throws Exception {
        return WorldsmithJson.INSTANCE.getFormat().decodeFromString(StructureBlueprint.Companion.serializer(),resource(id));
    }
    private static CompiledPack pack(String id)throws Exception {
        var base=WorldsmithPacks.builtin();WorldStructureDefinition definition;
        if(id.equals("connected_courtyard"))definition=WorldsmithJson.INSTANCE.getFormat().decodeFromString(WorldStructureDefinition.Companion.serializer(),resource(id));
        else definition=new WorldStructureDefinition(id,blueprint(id),new com.wjz.worldsmith.core.structure.StructurePlacement(List.of(),24,8,List.of(BuildRotation.NONE),new StructureTerrainFit(),2,null));
        var p=definition.getPlacement();
        var placement=new com.wjz.worldsmith.core.structure.StructurePlacement(base.getBiomes().getBiomes().stream().map(BiomeDefinition::getId).toList(),p.getSpacingChunks(),p.getSeparationChunks(),p.getRotations(),p.getTerrainFit(),p.getClearanceBlocks(),p.getAnchor());
        definition=new WorldStructureDefinition(definition.getId(),definition.getBlueprint(),placement,definition.getAssembly());
        String hash="a".repeat(64);
        return CompiledPack.scoped(new WorldsmithPack(new WorldsmithPackManifest(1,hash,"Structure showcase","Test",base.getManifest().getFiles()),base.getTerrain(),base.getBiomes(),base.getFeatures(),hash,new StructureLibrary(1,List.of(definition))));
    }
    @Test void signsContainersAndBannersUseActualMinecraftBlockEntityReadback()throws Exception {
        var lookup=VanillaRegistries.createLookup();
        for(String id:List.of("wayfarer_lodge","arcane_observatory")) {
            var pack=pack(id);var geometry=pack.structures().getTemplates().get(id).getFirst();
            var tag=WorldsmithStructureTemplates.encode(geometry,lookup,pack);
            int contents=0;
            for(var block:tag.getListOrEmpty("blocks")) {
                var entry=(CompoundTag)block;if(!entry.contains("nbt"))continue;contents++;
                var state=NbtUtils.readBlockState(BuiltInRegistries.BLOCK,tag.getListOrEmpty("palette").getCompoundOrEmpty(entry.getIntOr("state",0)));
                var entity=BlockEntity.loadStatic(BlockPos.ZERO,state,entry.getCompoundOrEmpty("nbt"),lookup);
                assertNotNull(entity);
                if(entity instanceof SignBlockEntity sign){assertEquals("林间旅舍",sign.getFrontText().getMessage(0,false).getString());assertNull(sign.getFrontText().getMessage(0,false).getStyle().getClickEvent());}
                if(entity instanceof RandomizableContainerBlockEntity chest)assertNotNull(chest.getLootTable());
                if(entity instanceof BannerBlockEntity banner)assertEquals(2,banner.getPatterns().layers().size());
            }
            assertEquals(geometry.getInteractions().size(),contents);
        }
    }
    @Test void explicitItemsRespectTheActualBlockCapacityAndStackLimit() {
        var lookup=VanillaRegistries.createLookup();
        var contents=new StructureInteraction.Container(new BuildPos(0,0,0),null,List.of(new StructureItem(0,"minecraft:apple",3)));
        var tag=WorldsmithStructureInteractions.encode(contents,Blocks.BARREL.defaultBlockState(),BlockPos.ZERO,lookup,null);
        var entity=(Container)BlockEntity.loadStatic(BlockPos.ZERO,Blocks.BARREL.defaultBlockState(),tag,lookup);
        assertEquals(3,entity.getItem(0).getCount());
        assertThrows(IllegalArgumentException.class,()->WorldsmithStructureInteractions.encode(new StructureInteraction.Container(new BuildPos(0,0,0),null,List.of(new StructureItem(40,"minecraft:apple",1))),Blocks.BARREL.defaultBlockState(),BlockPos.ZERO,lookup,null));
        assertThrows(IllegalArgumentException.class,()->WorldsmithStructureInteractions.encode(new StructureInteraction.Container(new BuildPos(0,0,0),null,List.of(new StructureItem(0,"minecraft:diamond_sword",2))),Blocks.BARREL.defaultBlockState(),BlockPos.ZERO,lookup,null));
        assertThrows(IllegalArgumentException.class,()->WorldsmithStructureInteractions.encode(contents,Blocks.STONE.defaultBlockState(),BlockPos.ZERO,lookup,null));
    }
    @Test void inlineLootAndEveryVariantAreExportedAsLoadableNativeResources()throws Exception {
        var pack=pack("wayfarer_lodge");var compiled=WorldsmithPackExporter.compilePatch(pack,VanillaRegistries.createLookup());
        assertEquals(59,WorldsmithPackExporter.write(pack,compiled,temp)); // 52 + structure/set + 4 templates + loot
        for(int i=0;i<4;i++)assertTrue(Files.exists(temp.resolve("data/worldsmith/structure/"+pack.structureTemplateId("wayfarer_lodge",i).getPath()+".nbt")));
        var loot=temp.resolve("data/worldsmith/loot_table/"+pack.structureLootId("wayfarer_lodge",0).getPath()+".json");
        assertNotNull(LootTable.DIRECT_CODEC.parse(compiled.full().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString(Files.readString(loot))).getOrThrow());
        var structure=compiled.full().lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey("wayfarer_lodge")).value();
        assertEquals(4,((WorldsmithTemplateStructure)structure).templateSettings().plans().size());
    }
    @Test void multiPiecePlansReallyPlaceAcrossChunksAndRemainStableInReverseOrder()throws Exception {
        var pack=pack("connected_courtyard");var compiled=WorldsmithPackExporter.compilePatch(pack,VanillaRegistries.createLookup());
        var lookup=compiled.full();assertEquals(57,WorldsmithPackExporter.write(pack,compiled,temp.resolve("pack")));
        var structure=(WorldsmithTemplateStructure)lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey("connected_courtyard")).value();
        var biome=lookup.lookupOrThrow(Registries.BIOME).getOrThrow(pack.biomes().getFirst().key());
        var settings=new FlatLevelGeneratorSettings(Optional.empty(),biome,List.of());settings.getLayersInfo().add(new FlatLayerInfo(129,Blocks.STONE));settings.updateLayers();
        var generator=new FlatLevelSource(settings);var random=RandomState.create(lookup,pack.noiseSettingsKey(),64L);
        var registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        try(var storage=LevelStorageSource.createDefault(temp).createAccess("compound")) {
            var manager=new StructureTemplateManager(ResourceManager.Empty.INSTANCE,storage,DataFixers.getDataFixer(),BuiltInRegistries.BLOCK);
            for(var entry:pack.structures().getTemplates().entrySet())for(int i=0;i<entry.getValue().size();i++)manager.getOrCreate(pack.structureTemplateId(entry.getKey(),i)).load(BuiltInRegistries.BLOCK,WorldsmithStructureTemplates.encode(entry.getValue().get(i),lookup,pack));
            var chunk=structure.templateSettings().layout().randomPlacement().getPotentialStructureChunk(64L,0,0);
            var ctx=new Structure.GenerationContext(registries,generator,generator.getBiomeSource(),random,manager,64L,chunk,LevelHeightAccessor.create(-64,384),b->true);
            var stub=structure.findGenerationPoint(ctx).orElseThrow();var pieces=stub.getPiecesBuilder().build().pieces().stream().map(p->(WorldsmithTemplatePiece)p).toList();
            assertEquals(5,pieces.size());
            var serial=new StructurePieceSerializationContext(ResourceManager.Empty.INSTANCE,registries,manager);
            var restored=pieces.stream().map(p->new WorldsmithTemplatePiece(serial,p.createTag(serial))).toList();
            var bounds=BoundingBox.encapsulatingBoxes(pieces.stream().map(WorldsmithTemplatePiece::getBoundingBox).toList()).orElseThrow();
            List<ChunkPos> chunks=new ArrayList<>();for(int x=Math.floorDiv(bounds.minX(),16);x<=Math.floorDiv(bounds.maxX(),16);x++)for(int z=Math.floorDiv(bounds.minZ(),16);z<=Math.floorDiv(bounds.maxZ(),16);z++)chunks.add(new ChunkPos(x,z));
            var forward=new WorldsmithStructureTest.FlatWorld(65,registries);var reverse=new WorldsmithStructureTest.FlatWorld(65,registries);
            for(var c:chunks)for(var piece:pieces)WorldsmithStructureTest.place(piece,forward,c);
            for(var c:chunks.reversed())for(var piece:restored)WorldsmithStructureTest.place(piece,reverse,c);
            assertTrue(forward.states.size()>4000);assertEquals(forward.states,reverse.states);
            assertEquals(pieces.stream().map(WorldsmithTemplatePiece::templatePosition).toList(),restored.stream().map(WorldsmithTemplatePiece::templatePosition).toList());
        }
    }
    @Test void runtimeSelectsVariantsAndPersistsStableLootAcrossChunkOrder()throws Exception {
        var pack=pack("wayfarer_lodge");var lookup=WorldsmithPackExporter.compilePatch(pack,VanillaRegistries.createLookup()).full();
        var structure=(WorldsmithTemplateStructure)lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey("wayfarer_lodge")).value();
        var biome=lookup.lookupOrThrow(Registries.BIOME).getOrThrow(pack.biomes().getFirst().key());
        var flat=new FlatLevelGeneratorSettings(Optional.empty(),biome,List.of());flat.getLayersInfo().add(new FlatLayerInfo(129,Blocks.STONE));flat.updateLayers();
        var generator=new FlatLevelSource(flat);var random=RandomState.create(lookup,pack.noiseSettingsKey(),9123L);
        var registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        try(var storage=LevelStorageSource.createDefault(temp).createAccess("variants")) {
            var manager=new StructureTemplateManager(ResourceManager.Empty.INSTANCE,storage,DataFixers.getDataFixer(),BuiltInRegistries.BLOCK);
            for(int i=0;i<4;i++)manager.getOrCreate(pack.structureTemplateId("wayfarer_lodge",i)).load(BuiltInRegistries.BLOCK,WorldsmithStructureTemplates.encode(pack.structures().getTemplates().get("wayfarer_lodge").get(i),lookup,pack));
            var serial=new StructurePieceSerializationContext(ResourceManager.Empty.INSTANCE,registries,manager);Set<String> selected=new HashSet<>();WorldsmithTemplatePiece first=null,second=null;
            for(int i=0;i<12;i++) {
                var chunk=structure.templateSettings().layout().randomPlacement().getPotentialStructureChunk(9123L,i*24,0);
                var ctx=new Structure.GenerationContext(registries,generator,generator.getBiomeSource(),random,manager,9123L,chunk,LevelHeightAccessor.create(-64,384),b->true);
                var piece=(WorldsmithTemplatePiece)structure.findGenerationPoint(ctx).orElseThrow().getPiecesBuilder().build().pieces().getFirst();
                selected.add(piece.createTag(serial).getCompoundOrEmpty("Part").getStringOr("template",""));
                if(first==null)first=piece;else if(second==null)second=piece;
            }
            assertTrue(selected.size()>1,"each site selected the same catalog variant");
            var saved=first.createTag(serial);var restored=new WorldsmithTemplatePiece(serial,saved);
            assertEquals(first.planOrigin(),restored.planOrigin());
            var bounds=first.getBoundingBox();List<ChunkPos> chunks=new ArrayList<>();
            for(int x=Math.floorDiv(bounds.minX(),16);x<=Math.floorDiv(bounds.maxX(),16);x++)for(int z=Math.floorDiv(bounds.minZ(),16);z<=Math.floorDiv(bounds.maxZ(),16);z++)chunks.add(new ChunkPos(x,z));
            var forward=new WorldsmithStructureTest.FlatWorld(65,registries);var reverse=new WorldsmithStructureTest.FlatWorld(65,registries);
            for(var c:chunks)WorldsmithStructureTest.place(first,forward,c);
            for(var c:chunks.reversed())WorldsmithStructureTest.place(restored,reverse,c);
            assertEquals(forward.states,reverse.states);
            assertFalse(forward.entities.isEmpty());assertEquals(forward.entities.keySet(),reverse.entities.keySet());
            for(var entry:forward.entities.entrySet())assertEquals(entry.getValue().saveWithFullMetadata(lookup),reverse.entities.get(entry.getKey()).saveWithFullMetadata(lookup));
            assertTrue(forward.entities.values().stream().filter(e->e instanceof RandomizableContainerBlockEntity).allMatch(e->((RandomizableContainerBlockEntity)e).getLootTableSeed()!=0));
            var other=new WorldsmithStructureTest.FlatWorld(65,registries);var otherBox=second.getBoundingBox();
            for(int x=Math.floorDiv(otherBox.minX(),16);x<=Math.floorDiv(otherBox.maxX(),16);x++)for(int z=Math.floorDiv(otherBox.minZ(),16);z<=Math.floorDiv(otherBox.maxZ(),16);z++)WorldsmithStructureTest.place(second,other,new ChunkPos(x,z));
            long a=forward.entities.values().stream().filter(e->e instanceof RandomizableContainerBlockEntity).mapToLong(e->((RandomizableContainerBlockEntity)e).getLootTableSeed()).findFirst().orElseThrow();
            long b=other.entities.values().stream().filter(e->e instanceof RandomizableContainerBlockEntity).mapToLong(e->((RandomizableContainerBlockEntity)e).getLootTableSeed()).findFirst().orElseThrow();
            assertNotEquals(a,b,"start-chunk and placement-chunk keys must not XOR-cancel across different instances");
        }
    }
}
