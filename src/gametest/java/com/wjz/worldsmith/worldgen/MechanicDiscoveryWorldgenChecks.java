package com.wjz.worldsmith.worldgen;

import com.mojang.datafixers.util.Pair;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Called only after real Fabric bootstrap: the full example includes registered custom item identities. */
public final class MechanicDiscoveryWorldgenChecks {
    private MechanicDiscoveryWorldgenChecks() {}

    public static void verify(WorldsmithPack source, Path output) throws Exception {
        var pack = CompiledPack.scoped(source);
        var patch = WorldsmithPackExporter.compilePatch(pack, VanillaRegistries.createLookup());
        var lookup = patch.full();
        int written = WorldsmithPackExporter.write(pack, patch, output.resolve("native"));
        check(written > 0, "Normal native export produced no resources");
        var id = pack.structureTemplateId("lantern_court");
        check(Files.isRegularFile(output.resolve("native/data/" + id.getNamespace() + "/structure/" + id.getPath() + ".nbt")),
            "Normal native export is missing the courtyard NBT");
        var entries = pack.biomes().stream().flatMap(b -> b.climates().stream()
            .map(c -> Pair.of(c, (Holder<Biome>) lookup.lookupOrThrow(Registries.BIOME).getOrThrow(b.key())))).toList();
        var generator = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(entries)),
            lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(pack.noiseSettingsKey()));
        long seed = source.getTerrain().getSeed();
        var random = RandomState.create(lookup, pack.noiseSettingsKey(), seed);
        // Native safe-feet search may choose a nearby column, so this is not an exact respawn promise.
        var spawnCandidate = random.sampler().findSpawnPosition();
        check(spawnCandidate.getX() == 0 && spawnCandidate.getZ() == 0, "Native climate spawn search did not choose the advertised origin area");
        var height = LevelHeightAccessor.create(source.getTerrain().getMinY(), source.getTerrain().getHeight());
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var holder = lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey("lantern_court"));
        var structure = (WorldsmithTemplateStructure) holder.value();
        try (var storage = LevelStorageSource.createDefault(output.resolve("test-storage")).createAccess("origin-court")) {
            var manager = new StructureTemplateManager(ResourceManager.Empty.INSTANCE, storage, DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
            var geometry = pack.structures().getTemplates().get("lantern_court").getFirst();
            manager.getOrCreate(id).load(BuiltInRegistries.BLOCK, WorldsmithStructureTemplates.encode(geometry, lookup, pack));
            var context = new Structure.GenerationContext(registries, generator, generator.getBiomeSource(), random,
                manager, seed, new ChunkPos(0, 0), height, b -> pack.biomes().stream().anyMatch(c -> b.is(c.key())));
            var stub = structure.findGenerationPoint(context).orElseThrow(() -> new IllegalStateException("Authored seed has no feasible courtyard at its advertised origin anchor"));
            check(stub.position().getX() == 0 && stub.position().getZ() == 0, "Native courtyard start moved away from the fixed origin anchor");
            check(structure.generate(holder, Level.OVERWORLD, registries, generator, generator.getBiomeSource(), random,
                manager, seed, new ChunkPos(0, 0), 0, height, b -> pack.biomes().stream().anyMatch(c -> b.is(c.key()))).isValid(),
                "Native courtyard generation rejected the authored seed and biome");
            System.out.println("[MechanicExploration] Full native export verified: " + output.resolve("native").toAbsolutePath()
                + "; spawn candidate=" + spawnCandidate + "; actual origin structure start=" + stub.position());
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
