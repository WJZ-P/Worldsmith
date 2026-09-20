package com.wjz.worldsmith.worldgen;

import com.mojang.datafixers.util.Pair;
import com.wjz.worldsmith.core.examples.ImmersiveVillageExample;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/** Native export + terrain fitting evidence; deliberately separate from actual placed-block walking evidence. */
public final class StoryWorldgenChecks {
    private StoryWorldgenChecks() {}

    public static void verify(Path output) throws Exception {
        Files.createDirectories(output);
        var evidence=new ArrayList<String>();
        for(long seed:new long[]{20260917L,42L,-7919L}) {
            var source=ImmersiveVillageExample.create(seed);
            var pack=CompiledPack.scoped(source);
            var patch=WorldsmithPackExporter.compilePatch(pack,VanillaRegistries.createLookup());
            var lookup=patch.full();
            var destination=output.resolve("seed-"+seed);
            int written=WorldsmithPackExporter.write(pack,patch,destination.resolve("native"));
            check(written>0,"Native story export was empty");
            var id=pack.structureTemplateId(ImmersiveVillageExample.STRUCTURE);
            check(Files.isRegularFile(destination.resolve("native/data/"+id.getNamespace()+"/structure/"+id.getPath()+".nbt")),"Export omitted the actual village template");
            var entries=pack.biomes().stream().flatMap(b -> b.climates().stream()
                .map(c -> Pair.of(c,(Holder<Biome>)lookup.lookupOrThrow(Registries.BIOME).getOrThrow(b.key())))).toList();
            var generator=new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(entries)),
                lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(pack.noiseSettingsKey()));
            var random=RandomState.create(lookup,pack.noiseSettingsKey(),seed);
            var spawn=random.sampler().findSpawnPosition();
            check(spawn.getX()==0&&spawn.getZ()==0,"Story climate spawn search did not select origin region for seed "+seed);
            var height=LevelHeightAccessor.create(source.getTerrain().getMinY(),source.getTerrain().getHeight());
            var registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var holder=lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey(ImmersiveVillageExample.STRUCTURE));
            var structure=(WorldsmithTemplateStructure)holder.value();
            try(var storage=LevelStorageSource.createDefault(destination.resolve("test-storage")).createAccess("native-fit")) {
                var manager=new StructureTemplateManager(ResourceManager.Empty.INSTANCE,storage,DataFixers.getDataFixer(),BuiltInRegistries.BLOCK);
                var geometry=pack.structures().getTemplates().get(ImmersiveVillageExample.STRUCTURE).getFirst();
                var nbt=WorldsmithStructureTemplates.encode(geometry,lookup,pack);
                var entities=nbt.getListOrEmpty("entities");
                check(entities.size()==5,"Native village must preserve two place markers and three resident anchors");
                manager.getOrCreate(id).load(BuiltInRegistries.BLOCK,nbt);
                var context=new Structure.GenerationContext(registries,generator,generator.getBiomeSource(),random,manager,seed,new ChunkPos(0,0),height,
                    b -> pack.biomes().stream().anyMatch(c -> b.is(c.key())));
                var stub=structure.findGenerationPoint(context).orElseThrow(() -> new IllegalStateException("No feasible village origin for seed "+seed));
                check(stub.position().getX()==0&&stub.position().getZ()==0,"Village moved away from origin for seed "+seed);
                var start=structure.generate(holder,Level.OVERWORLD,registries,generator,generator.getBiomeSource(),random,manager,seed,new ChunkPos(0,0),0,height,
                    b -> pack.biomes().stream().anyMatch(c -> b.is(c.key())));
                check(start.isValid(),"Native village generation rejected seed "+seed);
                evidence.add("seed="+seed+"; scope="+source.getComputedId()+"; spawnCandidate="+spawn+"; structureStart="+stub.position()+"; markerCount="+entities.size()+"; exportedFiles="+written);
            }
        }
        Files.write(output.resolve("generation-evidence.txt"),evidence,StandardCharsets.UTF_8);
        evidence.forEach(line -> System.out.println("[StoryGeneration] "+line));
    }
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
