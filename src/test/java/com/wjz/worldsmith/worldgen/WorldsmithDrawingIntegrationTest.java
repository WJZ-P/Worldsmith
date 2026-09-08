package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.wjz.worldsmith.core.draw.*;
import com.wjz.worldsmith.core.drawhost.*;
import com.wjz.worldsmith.core.model.*;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import com.wjz.worldsmith.core.structure.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real worker -> frozen SDK data -> native NBT/plan -> chunk writes and saved pieces. */
final class WorldsmithDrawingIntegrationTest {
    @TempDir Path temp;
    @BeforeAll static void boot() { WorldsmithTestBootstrap.bootStrap(); }
    private static WorldsmithTerrainProbe.Column column(int y) {
        return new WorldsmithTerrainProbe.Column(y,y,false,List.of(new WorldsmithTerrainProbe.AirSpan(y,320,true,false,y,0)));
    }
    private static final String SESSION="b".repeat(32);
    private static final String SOURCE="""
        import com.wjz.worldsmith.core.draw.*;
        import com.wjz.worldsmith.authoring.*;
        public class Monument implements StructureProgram {
          public AuthoredStructure generate(AuthoringContext context) {
            var c=context.canvas(Box.of(-40,0,-16,39,39,15));
            c.pen("stone_bricks").fill(Box.of(-40,0,-16,39,3,15));
            c.pen("air").fill(Box.of(-40,4,-16,39,38,15));
            c.pen("gold_block").set(0,39,0);
            c.pen("stone_brick_stairs[facing=north,half=bottom]").translate(0,4,0).rotateY(1).mirrorX().set(1,0,0);
            c.pen("red_bed[facing=east,part=foot]").set(-9,4,0);
            c.pen("red_bed[facing=east,part=head]").set(-8,4,0);
            c.pen("oak_door[facing=north,half=lower]").set(2,4,0);
            c.pen("oak_door[facing=north,half=upper]").set(2,5,0);
            c.pen("dirt").set(4,3,0);
            c.pen("sunflower[half=lower]").set(4,4,0);
            c.pen("sunflower[half=upper]").set(4,5,0);
            c.anchor("entry",new Vec3i(0,4,-16));
            context.origin(new Vec3i(0,0,0));
            context.material("path",BlockStateRef.of("stone_bricks"));
            context.material("stair",BlockStateRef.of("stone_brick_stairs"));
            context.entrance("north",new Vec3i(0,4,-16),"NORTH",BlockStateRef.of("stone_bricks"),2);
            context.entrance("east",new Vec3i(39,4,0),"EAST",BlockStateRef.of("stone_bricks"),2);
            return context.snapshot();
          }
        }
        """;

    private DrawingArtifact build(DrawingHost host) throws Exception {
        host.approve(SESSION);
        var request=new DrawingRequest("monument","native-1","Monument",Map.of("Monument.java",SOURCE),List.of(41L),Map.of());
        var job=host.submit(SESSION,request);long until=System.nanoTime()+60_000_000_000L;
        do {
            job=host.get(SESSION,job.getId());
            if(job.getStage()==DrawingJobStage.SUCCEEDED)return host.artifact(SESSION,job.getDrawingIds().getFirst(),false);
            assertNotEquals(DrawingJobStage.FAILED,job.getStage(),job.getMessage()+job.getLog()+job.getDiagnostics());
            Thread.sleep(30);
        }while(System.nanoTime()<until);
        throw new AssertionError("Worker timed out");
    }
    private static StructureBlueprint blueprint(String id,String drawing,boolean connected) {
        String ports=connected?"""
          [{"id":"north","at":{"x":0,"y":4,"z":-16},"facing":"NORTH","type":"walk","pool":"wings","required":true},
           {"id":"east","at":{"x":39,"y":4,"z":0},"facing":"EAST","type":"walk","pool":"wings","required":true}]
          """:"[]";
        return WorldsmithJson.INSTANCE.getFormat().decodeFromString(StructureBlueprint.Companion.serializer(),"""
          {"id":"%s","origin":{"x":0,"y":0,"z":0},"drawing":{"variants":["%s"]},
           "palette":{"path":{"block":"minecraft:stone_bricks"},"stair":{"block":"minecraft:stone_brick_stairs"}},
           "ports":%s,"lighting":{"mode":"EXTERIOR_ONLY"}}
          """.formatted(id,drawing,ports));
    }
    private static StructureBlueprint wing() {
        return WorldsmithJson.INSTANCE.getFormat().decodeFromString(StructureBlueprint.Companion.serializer(),"""
          {"id":"sdk_wing","size":{"x":9,"y":8,"z":9},"origin":{"x":4,"y":0,"z":4},
           "palette":{"stone":{"block":"minecraft:stone_bricks"}},
           "build":[{"op":"FILL","id":"floor","from":{"x":0,"y":0,"z":0},"to":{"x":8,"y":3,"z":8},"material":"stone"},
                    {"op":"CLEAR","id":"air","from":{"x":0,"y":4,"z":0},"to":{"x":8,"y":7,"z":8}}],
           "ports":[{"id":"entry","at":{"x":4,"y":4,"z":8},"facing":"SOUTH","type":"walk"}],
           "lighting":{"mode":"EXTERIOR_ONLY"}}
          """);
    }
    private static CompiledPack pack(DrawStructure drawing,String id,DrawingHost host) {
        var base=WorldsmithPacks.builtin();var placement=new StructurePlacement(base.getBiomes().getBiomes().stream().map(BiomeDefinition::getId).toList(),24,8,List.of(BuildRotation.NONE),new StructureTerrainFit(),2,null);
        var roads=WorldsmithJson.INSTANCE.getFormat().decodeFromString(StructureRoads.Companion.serializer(),"""
          {"material":"path","stairMaterial":"stair","bridgeMaterial":"path","maxSpan":16,"gap":4,"width":3}
          """);
        var assembly=new StructureAssembly(Map.of("sdk_wing",wing()),Map.of("wings",List.of(new AssemblyChoice("sdk_wing",1))),1,3,1,96,true,16,roads);
        var resolver=new AuthoredDraftResolver(host);
        java.util.function.BiFunction<String,Boolean,StructureBlueprint> authored=(name,connected)->{
            var json=new com.google.gson.JsonObject();json.addProperty("id",name);
            json.add("authored",com.google.gson.JsonParser.parseString("{\"variants\":[\""+id+"\"]}"));
            if(connected)json.add("portBindings",com.google.gson.JsonParser.parseString("{\"north\":{\"pool\":\"wings\",\"required\":true},\"east\":{\"pool\":\"wings\",\"required\":true}}"));
            var input=(kotlinx.serialization.json.JsonObject)WorldsmithJson.INSTANCE.getFormat().parseToJsonElement(json.toString());
            return WorldsmithJson.INSTANCE.getFormat().decodeFromJsonElement(StructureBlueprint.Companion.serializer(),resolver.blueprint(SESSION,input,false));
        };
        var group=new WorldStructureDefinition("sdk_group",authored.apply("sdk_hall",true),placement,assembly);
        var single=new WorldStructureDefinition("sdk_standalone",authored.apply("sdk_single",false),placement);
        var library=new StructureLibrary(2,List.of(group,single),null,Map.of(),Map.of(),Map.of(id,drawing));
        var hash="d".repeat(64);
        return CompiledPack.scoped(new WorldsmithPack(new WorldsmithPackManifest(2,hash,"SDK native smoke","Targeted integration fixture",base.getManifest().getFiles()),base.getTerrain(),base.getBiomes(),base.getFeatures(),hash,library));
    }

    @Test void workerDrawingRemainsThreeLogicalBuildingsAcrossTilesRotationsAndSaveReload() throws Exception {
        var runtime=new DrawingRuntime(Path.of(System.getProperty("worldsmith.workerRuntime")),Path.of(System.getProperty("java.home")),List.of("--limit-modules=java.base,java.compiler,java.logging,java.xml,java.desktop"));
        try(var host=new DrawingHost(temp.resolve("jobs"),runtime,SharedConstants.getCurrentVersion().dataVersion().version())) {
            var artifact=build(host);var drawing=host.drawing(artifact);var pack=pack(drawing,artifact.getId(),host);
            var catalog=pack.structures();var logical=catalog.getPlans().get("sdk_group").getFirst();
            assertEquals(3,logical.getParts().size());assertEquals(2,logical.getConnections().size());
            var geometry=logical.getParts().getFirst().getGeometry();
            assertEquals(new BuildPos(-40,0,-16),geometry.getSourceMin());
            assertEquals(new BuildPos(40,4,0),geometry.getAnchors().get("entry"));
            assertEquals(new BuildPos(40,4,0),geometry.getPorts().getFirst().getAt());
            var tiles=StructureTiling.tiles(geometry);assertEquals(6,tiles.size());
            assertEquals(geometry.getVoxels().size(),tiles.stream().mapToInt(t->t.getGeometry().getVoxels().size()).sum());
            assertTrue(tiles.stream().allMatch(t->t.getGeometry().getSize().getX()<=32&&t.getGeometry().getSize().getY()<=32&&t.getGeometry().getSize().getZ()<=32));
            var fullNbt=WorldsmithDrawExporter.encode(drawing);
            assertEquals(80,fullNbt.getListOrEmpty("size").getIntOr(0,-1));
            assertEquals(drawing.voxels().size(),fullNbt.getListOrEmpty("blocks").size());
            assertTrue(drawing.voxels().size()<80*40*32,"unwritten top cells must remain KEEP");
            var reflected=geometry.getVoxels().stream().filter(StructureVoxel::getMirrorX).findFirst().orElseThrow();
            BlockState expected=WorldsmithStructureTemplates.resolve(reflected.getMaterial()).mirror(Mirror.FRONT_BACK).rotate(Rotation.values()[reflected.getQuarterTurns()]);
            assertTrue(fullNbt.getListOrEmpty("palette").stream().map(t->NbtUtils.readBlockState(BuiltInRegistries.BLOCK,(CompoundTag)t)).anyMatch(expected::equals));
            var compiled=WorldsmithPackExporter.compilePatch(pack,VanillaRegistries.createLookup());
            Path output=temp.resolve("export");WorldsmithPackExporter.write(pack,compiled,output);
            String retained=System.getProperty("worldsmith.smokeOutput");
            if(retained!=null)WorldsmithPackExporter.write(pack,compiled,Path.of(retained));
            var lookup=compiled.full();var registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            try(var storage=LevelStorageSource.createDefault(temp).createAccess("native-sdk")) {
                var manager=new StructureTemplateManager(ResourceManager.Empty.INSTANCE,storage,DataFixers.getDataFixer(),BuiltInRegistries.BLOCK);
                for(var entry:catalog.getTemplates().entrySet())for(int v=0;v<entry.getValue().size();v++) {
                    var g=entry.getValue().get(v);var fragments=StructureTiling.tiles(g);var base=pack.structureTemplateId(entry.getKey(),v);
                    for(int i=0;i<fragments.size();i++) {
                        var name=g.getDrawingSource()?WorldsmithStructureTemplates.tileId(base,i):base;
                        var tag=NbtIo.readCompressed(output.resolve("data/worldsmith/structure/"+name.getPath()+".nbt"),NbtAccounter.unlimitedHeap());
                        manager.getOrCreate(name).load(BuiltInRegistries.BLOCK,tag);
                    }
                }
                var nativeStructure=(WorldsmithTemplateStructure)lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey("sdk_group")).value();
                var config=nativeStructure.templateSettings();var plan=config.plans().getFirst();
                assertEquals(3,plan.parts().size());assertEquals(8,plan.parts().stream().mapToInt(p->Math.max(1,p.tiles().size())).sum());
                // Each logical building gets its own terrain datum; its tiles share it.
                var fit=WorldsmithSettlementPlacement.fit(plan,config.site(),config.roads(),BlockPos.ZERO,Rotation.NONE,
                    (x,z)->column(x>=44?68:z<=-21?67:65),-64,319,p->true).orElseThrow();
                assertEquals(3,fit.parts().stream().map(p->p.position().getY()).distinct().count());
                for(var placed:fit.parts()) {
                    var fragments=WorldsmithBuildingPieces.create(manager,placed,Blocks.STONE.defaultBlockState(),17);
                    for(int i=0;i<placed.part().tiles().size();i++)assertEquals(placed.transform(placed.part().tiles().get(i).offset()),fragments.get(i).templatePosition());
                }
                var serial=new StructurePieceSerializationContext(ResourceManager.Empty.INSTANCE,registries,manager);
                if(retained!=null) {
                    long seed=9412306L;
                    var biome=lookup.lookupOrThrow(Registries.BIOME).getOrThrow(pack.biomes().getFirst().key());
                    var flat=new FlatLevelGeneratorSettings(Optional.empty(),biome,List.of());flat.getLayersInfo().add(new FlatLayerInfo(129,Blocks.STONE));flat.updateLayers();
                    var generator=new FlatLevelSource(flat);var random=RandomState.create(lookup,pack.noiseSettingsKey(),seed);
                    var cases=new ArrayList<Map<String,Object>>();
                    for(String structureId:List.of("sdk_group","sdk_standalone")) {
                        var structure=(WorldsmithTemplateStructure)lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey(structureId)).value();
                        var chunk=structure.templateSettings().layout().randomPlacement().getPotentialStructureChunk(seed,0,0);
                        var context=new Structure.GenerationContext(registries,generator,generator.getBiomeSource(),random,manager,seed,chunk,LevelHeightAccessor.create(-64,384),b->true);
                        var generated=structure.findGenerationPoint(context).orElseThrow().getPiecesBuilder().build().pieces();
                        var bounds=BoundingBox.encapsulatingBoxes(generated.stream().map(StructurePiece::getBoundingBox).toList()).orElseThrow();
                        cases.add(Map.of("id",pack.structureKey(structureId).identifier().toString(),"chunk",List.of(chunk.x(),chunk.z()),
                            "bounds",List.of(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.maxZ()),"pieceCount",generated.size()));
                    }
                    Files.writeString(Path.of(retained).getParent().resolve("cases.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("seed",seed,"biome",biome.unwrapKey().orElseThrow().identifier().toString(),"cases",cases)));
                }
                for(String structureId:List.of("sdk_group","sdk_standalone"))for(Rotation rotation:Rotation.values()) {
                    var structure=(WorldsmithTemplateStructure)lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey(structureId)).value();
                    var settings=structure.templateSettings();var source=settings.plans().getFirst();
                    var placed=WorldsmithSettlementPlacement.fit(source,settings.site(),settings.roads(),new BlockPos(7,0,9),rotation,
                        (x,z)->column(65),-64,319,p->true).orElseThrow();
                    var pieces=placed.parts().stream().flatMap(p->WorldsmithBuildingPieces.create(manager,p,Blocks.STONE.defaultBlockState(),41).stream()).toList();
                    var saved=pieces.stream().map(p->new WorldsmithTemplatePiece(serial,p.createTag(serial))).toList();
                    var bounds=BoundingBox.encapsulatingBoxes(pieces.stream().map(WorldsmithTemplatePiece::getBoundingBox).toList()).orElseThrow();
                    List<ChunkPos> chunks=new ArrayList<>();
                    for(int x=Math.floorDiv(bounds.minX(),16);x<=Math.floorDiv(bounds.maxX(),16);x++)for(int z=Math.floorDiv(bounds.minZ(),16);z<=Math.floorDiv(bounds.maxZ(),16);z++)chunks.add(new ChunkPos(x,z));
                    var forward=new WorldsmithStructureTest.FlatWorld(65,registries);var reverse=new WorldsmithStructureTest.FlatWorld(65,registries);
                    for(var c:chunks)for(var p:pieces)WorldsmithStructureTest.place(p,forward,c);
                    for(var c:chunks.reversed())for(var p:saved)WorldsmithStructureTest.place(p,reverse,c);
                    assertTrue(chunks.size()>5);assertTrue(forward.states.size()>10_000);assertEquals(forward.states,reverse.states,structureId+rotation);
                    assertEquals(pieces.stream().map(WorldsmithTemplatePiece::templatePosition).toList(),saved.stream().map(WorldsmithTemplatePiece::templatePosition).toList());
                }
            }
            assertEquals(1,host.list(SESSION).size(),"native export/load/placement must never submit another source job");
        }
    }

    @Test void nativeValidationRejectsBadStatesMissingPairsAndFakeEmission() {
        for(String block:List.of("stone[not_a_property=true]","oak_door[half=lower]","red_bed[part=foot]","sunflower[half=upper]")) {
            var c=DrawCanvas.sized(3,3,3);c.pen(block).set(1,1,1);
            assertThrows(IllegalArgumentException.class,()->WorldsmithDrawExporter.encode(c.snapshot()),block);
        }
        var c=DrawCanvas.sized(3,3,3);c.pen("stone").set(1,1,1);
        var lighting=new StructureLighting(StructureLightingMode.EXTERIOR_ONLY,List.of(),List.of(new StructureLightSource(new BuildPos(1,1,1),15)),8);
        var g=new CompiledStructure("fake",new BuildPos(3,3,3),new BuildPos(0,0,0),List.of(new StructureVoxel(new BuildPos(1,1,1),new BuildMaterial("minecraft:stone",Map.of()))),List.of(),1,List.of(),List.of(),lighting);
        assertThrows(IllegalArgumentException.class,()->WorldsmithStructureTemplates.encode(g));
    }
}
