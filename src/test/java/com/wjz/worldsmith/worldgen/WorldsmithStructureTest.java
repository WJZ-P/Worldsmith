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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Stream;
import java.lang.reflect.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldsmithStructureTest {
    @TempDir Path temp;
    @BeforeAll static void bootstrap() { WorldsmithTestBootstrap.bootStrap(); }

    static StructureBlueprint example() throws Exception {
        try(var input=WorldsmithStructureTest.class.getClassLoader().getResourceAsStream("worldsmith/structures/forest_shrine.json")) {
            return WorldsmithJson.INSTANCE.getFormat().decodeFromString(StructureBlueprint.Companion.serializer(),new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
    }

    @Test void exactBlockStatesRejectUnknownIdsAndProperties() {
        assertThrows(IllegalArgumentException.class,()->WorldsmithStructureTemplates.resolve(new BuildMaterial("minecraft:missing",Map.of())));
        assertThrows(IllegalArgumentException.class,()->WorldsmithStructureTemplates.resolve(new BuildMaterial("minecraft:stone",Map.of("axis","x"))));
        assertThrows(IllegalArgumentException.class,()->WorldsmithStructureTemplates.resolve(new BuildMaterial("minecraft:oak_stairs",Map.of("facing","up"))));
        assertEquals(Direction.Axis.X,WorldsmithStructureTemplates.resolve(new BuildMaterial("minecraft:oak_log",Map.of("axis","x"))).getValue(RotatedPillarBlock.AXIS));
    }

    @Test void blueprintBecomesRealNbtWithAirAndMaterialStates() throws Exception {
        var geometry=StructureGeometryCompiler.compile(example());
        CompoundTag nbt=WorldsmithStructureTemplates.encode(geometry);
        assertEquals(geometry.getVoxels().size(),nbt.getListOrEmpty("blocks").size());
        assertTrue(nbt.getListOrEmpty("palette").toString().contains("minecraft:air"));
        assertTrue(nbt.getListOrEmpty("palette").toString().contains("facing"));
        StructureTemplate template=new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK,nbt);
        assertEquals(new Vec3i(15,13,19),template.getSize());
    }

    @Test void structureAndSetExportThroughMinecraftCodecsAlongsideTemplateAsset() throws Exception {
        CompiledPack pack=pack();
        var vanilla=VanillaRegistries.createLookup();
        var compiled=WorldsmithPackExporter.compilePatch(pack,vanilla);
        Path output=temp.resolve("export");
        int written=WorldsmithPackExporter.write(pack,compiled.patches(),output);
        assertEquals(55,written,"normal 52 files plus structure, structure set and NBT");
        Identifier id=pack.structureTemplateId("forest_shrine");
        Path nbt=output.resolve("data/worldsmith/structure/"+id.getPath()+".nbt");
        assertTrue(Files.size(nbt)>100);
        CompoundTag template=NbtIo.readCompressed(nbt,NbtAccounter.unlimitedHeap());
        assertTrue(template.getListOrEmpty("blocks").size()>500);
        var ops=compiled.full().createSerializationContext(JsonOps.INSTANCE);
        var structureJson=JsonParser.parseString(Files.readString(output.resolve("data/worldsmith/worldgen/structure/"+pack.structureKey("shrine").identifier().getPath()+".json")));
        var setJson=JsonParser.parseString(Files.readString(output.resolve("data/worldsmith/worldgen/structure_set/"+pack.structureSetKey("shrine").identifier().getPath()+".json")));
        assertInstanceOf(WorldsmithTemplateStructure.class,Structure.DIRECT_CODEC.parse(ops,structureJson).getOrThrow());
        assertNotNull(StructureSet.DIRECT_CODEC.parse(ops,setJson).getOrThrow());
    }

    @Test void templateAssetsParticipateInDatagenCaching() throws Exception {
        Map<Path,byte[]> cached=new LinkedHashMap<>();
        int written=WorldsmithStructureTemplates.write(pack(),temp,(path,bytes,hash)->{
            assertNotNull(hash);
            cached.put(path,bytes);
        });
        assertEquals(1,written);
        assertEquals(1,cached.size());
        assertTrue(cached.keySet().iterator().next().toString().endsWith("forest_shrine.nbt"));
        assertTrue(cached.values().iterator().next().length>100);
    }

    @Test void realGeneratorPlansAStartAndPiecePersistsItsGeometry() throws Exception {
        CompiledPack pack=pack();
        var compiled=WorldsmithPackExporter.compilePatch(pack,VanillaRegistries.createLookup()).full();
        var settings=compiled.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(pack.noiseSettingsKey());
        var biome=compiled.lookupOrThrow(Registries.BIOME).getOrThrow(pack.biomes().getFirst().key());
        ChunkGenerator generator=new NoiseBasedChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(biome),settings);
        RandomState state=RandomState.create(compiled,pack.noiseSettingsKey(),54L);
        var structure=(WorldsmithTemplateStructure)compiled.lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey("shrine")).value();
        try(var storage=LevelStorageSource.createDefault(temp).createAccess("structure-test")) {
            var manager=new StructureTemplateManager(ResourceManager.Empty.INSTANCE,storage,DataFixers.getDataFixer(),BuiltInRegistries.BLOCK);
            manager.getOrCreate(pack.structureTemplateId("forest_shrine")).load(BuiltInRegistries.BLOCK,WorldsmithStructureTemplates.encode(StructureGeometryCompiler.compile(example())));
            Structure.GenerationStub stub=null;
            for(int x=0;x<8 && stub==null;x++) {
                var context=new Structure.GenerationContext(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),generator,generator.getBiomeSource(),state,manager,54L,new ChunkPos(x*8,0),LevelHeightAccessor.create(-64,384),b->true);
                stub=structure.findGenerationPoint(context).orElse(null);
            }
            assertNotNull(stub,"flat inland terrain should offer at least one valid building site");
            var piece=(WorldsmithTemplatePiece)stub.getPiecesBuilder().build().pieces().getFirst();
            var context=new StructurePieceSerializationContext(ResourceManager.Empty.INSTANCE,RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),manager);
            var restored=new WorldsmithTemplatePiece(context,piece.createTag(context));
            assertEquals(piece.getBoundingBox(),restored.getBoundingBox());
            assertEquals(piece.getRotation(),restored.getRotation());
            assertEquals(piece.templatePosition(),restored.templatePosition());
        }
    }

    @Test void piecesClipWritesToEachChunkAndRemainStableInReverseOrder() throws Exception {
        var geometry=StructureGeometryCompiler.compile(example());
        Identifier id=Identifier.fromNamespaceAndPath("worldsmith","test/shrine");
        var config=new WorldsmithTemplateStructure.Settings(id,new BlockPos(15,13,19),new BlockPos(7,0,9),List.of(Rotation.NONE),"LAND_SURFACE",4,"FILL",Blocks.STONE_BRICKS.defaultBlockState(),6,List.of(BlockPos.ZERO),List.of(new BoundingBox(0,0,0,14,12,18)),List.of(BlockPos.ZERO),testLayout(id,new BlockPos(15,13,19),new BlockPos(7,0,9)));
        try(var storage=LevelStorageSource.createDefault(temp).createAccess("piece-test")) {
            var manager=new StructureTemplateManager(ResourceManager.Empty.INSTANCE,storage,DataFixers.getDataFixer(),BuiltInRegistries.BLOCK);
            manager.getOrCreate(id).load(BuiltInRegistries.BLOCK,WorldsmithStructureTemplates.encode(geometry));
            var a=new WorldsmithTemplatePiece(manager,config,new BlockPos(9,70,9),Rotation.NONE,List.of(new BoundingBox(10,68,10,10,69,10)));
            var b=new WorldsmithTemplatePiece(manager,config,new BlockPos(9,70,9),Rotation.NONE,List.of(new BoundingBox(10,68,10,10,69,10)));
            List<ChunkPos> chunks=List.of(new ChunkPos(0,0),new ChunkPos(1,0),new ChunkPos(0,1),new ChunkPos(1,1));
            FlatWorld forward=new FlatWorld(),reverse=new FlatWorld();
            for(ChunkPos chunk:chunks)place(a,forward,chunk);
            for(ChunkPos chunk:chunks.reversed())place(b,reverse,chunk);
            assertEquals(forward.states,reverse.states);
            assertTrue(forward.states.size()>500);
            assertTrue(a.blocksDecoration(new BoundingBox(10,71,10,11,75,11)));
            assertFalse(a.blocksDecoration(new BoundingBox(10,10,10,11,20,11)),"sky structure should not exclude ground vegetation below it");
        }
    }

    @Test void terrainFitIsBoundedAndRejectsWaterCliffsAndMissingSupports() {
        var config=new WorldsmithTemplateStructure.Settings(Identifier.fromNamespaceAndPath("worldsmith","test/probe"),new BlockPos(5,8,5),new BlockPos(2,0,2),List.of(Rotation.NONE),"LAND_SURFACE",4,"FILL",Blocks.STONE.defaultBlockState(),3,List.of(new BlockPos(0,0,0),new BlockPos(4,0,4)),List.of(new BoundingBox(0,0,0,4,7,4)),List.of(new BlockPos(0,0,0),new BlockPos(4,0,4)),testLayout(Identifier.fromNamespaceAndPath("worldsmith","test/probe"),new BlockPos(5,8,5),new BlockPos(2,0,2)));
        var slope=WorldsmithTerrainProbe.probe(config,BlockPos.ZERO,Rotation.NONE,-64,319,(x,z)->new WorldsmithTerrainProbe.Column(x<0?65:67,x<0?65:67));
        assertTrue(slope.accepted());
        assertEquals(67,slope.plan().position().getY());
        assertEquals(1,slope.plan().foundations().size());
        assertEquals(65,slope.plan().foundations().getFirst().minY());
        assertEquals(66,slope.plan().foundations().getFirst().maxY());
        assertEquals(WorldsmithTerrainProbe.Rejection.WRONG_FLUID,WorldsmithTerrainProbe.probe(config,BlockPos.ZERO,Rotation.NONE,-64,319,(x,z)->new WorldsmithTerrainProbe.Column(58,63)).rejection());
        assertEquals(WorldsmithTerrainProbe.Rejection.EXCESSIVE_SLOPE,WorldsmithTerrainProbe.probe(config,BlockPos.ZERO,Rotation.NONE,-64,319,(x,z)->new WorldsmithTerrainProbe.Column(x<0?60:80,x<0?60:80)).rejection());
        assertEquals(WorldsmithTerrainProbe.Rejection.MISSING_SUPPORT,WorldsmithTerrainProbe.probe(config,BlockPos.ZERO,Rotation.NONE,-64,319,(x,z)->new WorldsmithTerrainProbe.Column(x<0?63:67,x<0?63:67)).rejection());
        assertEquals(WorldsmithTerrainProbe.Rejection.OUTSIDE_WORLD,WorldsmithTerrainProbe.probe(config,BlockPos.ZERO,Rotation.NONE,-64,319,(x,z)->new WorldsmithTerrainProbe.Column(318,318)).rejection());
    }

    @Test void moduleRotationsUseMinecraftStateTransformsRatherThanStringGuesses() {
        var blueprint=new StructureBlueprint(1,"rotated_beam",new BuildPos(6,6,6),new BuildPos(0,0,0),Map.of("beam",new BuildMaterial("minecraft:oak_log",Map.of("axis","x"))),
            List.of(new BuildOperation.Instance("instance","beam",new BuildPos(3,1,1),BuildRotation.CLOCKWISE_90)),
            Map.of("beam",List.of(new BuildOperation.Line("log",new BuildPos(0,0,0),new BuildPos(2,0,0),"beam"))),List.of());
        var nbt=WorldsmithStructureTemplates.encode(StructureGeometryCompiler.compile(blueprint));
        assertTrue(nbt.getListOrEmpty("palette").toString().contains("z"));
        var state=NbtUtils.readBlockState(BuiltInRegistries.BLOCK,nbt.getListOrEmpty("palette").getCompoundOrEmpty(0));
        assertEquals(Direction.Axis.Z,state.getValue(RotatedPillarBlock.AXIS));
    }

    private static WorldsmithStructureLayout.Member testLayout(Identifier id,BlockPos size,BlockPos origin) {
        return new WorldsmithStructureLayout.Member(id,"test",24,8,43,
            WorldsmithStructureLayout.envelope(size,origin,List.of(Rotation.NONE),2),Optional.empty());
    }

    private static void place(WorldsmithTemplatePiece piece,FlatWorld world,ChunkPos chunk) {
        world.clip=new BoundingBox(chunk.getMinBlockX(),-64,chunk.getMinBlockZ(),chunk.getMaxBlockX(),319,chunk.getMaxBlockZ());
        piece.postProcess(world.level,null,null,RandomSource.create(2L),world.clip,chunk,BlockPos.ZERO);
    }

    private static CompiledPack pack() throws Exception {
        WorldsmithPack source=WorldsmithPacks.builtin();
        TerrainPlan base=source.getTerrain();
        TerrainPlan terrain=new TerrainPlan(base.getSchemaVersion(),54L,-64,384,1,2,63,base.getDefaultBlock(),base.getDefaultFluid(),WorldsmithTerrainSamplingTest.shape(.97,2,.1,1,0,0,.1,0),true,true,false,base.getSpawnTargets());
        var placement=new com.wjz.worldsmith.core.structure.StructurePlacement(source.getBiomes().getBiomes().stream().map(BiomeDefinition::getId).toList(),24,8,List.of(BuildRotation.NONE,BuildRotation.CLOCKWISE_90),new StructureTerrainFit(StructureSurface.LAND_SURFACE,4,new StructureFoundation(FoundationMode.FILL,"foundation",6,List.of())),2,null);
        var library=new StructureLibrary(1,List.of(new WorldStructureDefinition("shrine",example(),placement)));
        String id="d".repeat(64);
        var m=source.getManifest();
        return CompiledPack.scoped(new WorldsmithPack(new WorldsmithPackManifest(1,id,"Shrine fixture","Test",m.getFiles()),terrain,source.getBiomes(),source.getFeatures(),id,library));
    }

    private static final class FlatWorld implements InvocationHandler {
        final Map<BlockPos,BlockState> states=new LinkedHashMap<>();
        BoundingBox clip;
        final WorldGenLevel level=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},this);
        BlockState state(BlockPos pos) {return states.getOrDefault(pos,pos.getY()<68?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());}
        @Override @SuppressWarnings("unchecked") public Object invoke(Object p,Method m,Object[] args) throws Throwable {
            String name=m.getName();
            if(name.equals("getBlockState"))return state((BlockPos)args[0]);
            if(name.equals("getFluidState"))return state((BlockPos)args[0]).getFluidState();
            if(name.equals("setBlock")) {BlockPos pos=((BlockPos)args[0]).immutable();assertTrue(clip.isInside(pos),"template wrote outside current chunk: "+pos);states.put(pos,(BlockState)args[1]);return true;}
            if(name.equals("getBlockEntity"))return null;
            if(name.equals("getRandom"))return RandomSource.create(1L);
            if(name.equals("getMinY"))return -64;
            if(name.equals("getMaxY"))return 319;
            if(name.equals("getHeight"))return args==null || args.length==0?384:68;
            if(name.equals("isStateAtPosition"))return ((java.util.function.Predicate<BlockState>)args[1]).test(state((BlockPos)args[0]));
            if(name.equals("ensureCanWrite"))return true;
            if(m.isDefault())return InvocationHandler.invokeDefault(p,m,args);
            Class<?> t=m.getReturnType();
            if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;if(t==float.class)return 0F;if(t==double.class)return 0D;
            if(t==Optional.class)return Optional.empty();if(t==List.class)return List.of();if(t==Set.class)return Set.of();if(t==Stream.class)return Stream.empty();return null;
        }
    }
}
