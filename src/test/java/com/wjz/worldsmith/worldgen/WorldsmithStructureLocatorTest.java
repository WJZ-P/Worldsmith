package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldsmithStructureLocatorTest {
    private static final long SEED = 9123L;
    @TempDir Path temp;
    @BeforeAll static void bootstrap() { WorldsmithTestBootstrap.bootStrap(); }

    @Test void biomePrecheckScansEveryHeightTheFitCanReachAndNoOther() {
        Predicate<BlockPos> band = p -> p.getY() >= 100 && p.getY() <= 103;
        assertTrue(WorldsmithTemplateStructure.anyHeight(BlockPos.ZERO, -64, 319, band));
        assertFalse(WorldsmithTemplateStructure.anyHeight(BlockPos.ZERO, -64, 99, band));
        assertFalse(WorldsmithTemplateStructure.anyHeight(BlockPos.ZERO, 104, 319, band));
        // A range starting inside a biome cell still tests that whole cell.
        assertTrue(WorldsmithTemplateStructure.anyHeight(BlockPos.ZERO, 103, 103, p -> p.getY() == 100));
    }

    @Test void aCandidateInAnUnallowedBiomeSamplesNoTerrain() throws Exception {
        var fixture = fixture();
        try (var storage = LevelStorageSource.createDefault(temp).createAccess("biome-first")) {
            var manager = fixture.templates(storage);
            AtomicInteger reads = new AtomicInteger();
            var generator = new FlatLevelSource(fixture.flat) {
                @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
                    reads.incrementAndGet();
                    return super.getBaseColumn(x, z, height, random);
                }
            };
            var chunk = fixture.structure.templateSettings().layout().randomPlacement().getPotentialStructureChunk(SEED, 0, 0);
            var height = LevelHeightAccessor.create(-64, 384);
            var rejected = new Structure.GenerationContext(fixture.registries, generator, generator.getBiomeSource(), fixture.random, manager, SEED, chunk, height, b -> false);
            assertTrue(fixture.structure.findGenerationPoint(rejected).isEmpty());
            assertEquals(0, reads.get(), "terrain was sampled for a site no height of which is in an allowed biome");
            var accepted = new Structure.GenerationContext(fixture.registries, generator, generator.getBiomeSource(), fixture.random, manager, SEED, chunk, height, b -> true);
            assertTrue(fixture.structure.findGenerationPoint(accepted).isPresent());
            assertTrue(reads.get() > 0);
        }
    }

    @Test void anInterruptedCheckLeavesNoCachedAbsenceBehind() throws Exception {
        var fixture = fixture();
        try (var storage = LevelStorageSource.createDefault(temp).createAccess("interrupted")) {
            var manager = fixture.templates(storage);
            var generator = new FlatLevelSource(fixture.flat);
            var check = new StructureCheck((pos, visitor) -> CompletableFuture.completedFuture(null), fixture.registries, manager, Level.OVERWORLD,
                generator, fixture.random, LevelHeightAccessor.create(-64, 384), generator.getBiomeSource(), SEED, DataFixers.getDataFixer());
            var layout = fixture.structure.templateSettings().layout();
            var chunk = layout.randomPlacement().getPotentialStructureChunk(SEED, 0, 0);
            assertThrows(WorldsmithStructureLocator.BudgetSpent.class, () -> WorldsmithStructureLocator.within(System.nanoTime() - 1,
                () -> check.checkStart(chunk, fixture.structure, layout.placement(), false)));
            // Vanilla memoises this answer per chunk; an interrupted check must not have stored "absent".
            assertEquals(StructureCheckResult.CHUNK_LOAD_NEEDED, check.checkStart(chunk, fixture.structure, layout.placement(), false));
        }
    }

    @Test void nearbySitesShareTheirWaterSamples() {
        var region = new WorldsmithStructureRegion(1, 1024, 0, 1, 1, "NEAR", 48);
        Set<Long> first = new HashSet<>(), both = new HashSet<>();
        region.waterMatches(new BlockPos(3, 0, -5), (x, z) -> { first.add(key(x, z)); both.add(key(x, z)); return new WorldsmithTerrainProbe.Column(64, 64); });
        region.waterMatches(new BlockPos(9, 0, 1), (x, z) -> { both.add(key(x, z)); return new WorldsmithTerrainProbe.Column(64, 64); });
        assertTrue(first.size() > 100);
        assertTrue((both.size() - first.size()) * 4 < first.size(), "a neighbouring site resampled most of its disc");

        WorldsmithTerrainProbe.Sampler lake = (x, z) -> x == 40 && z == 0 ? new WorldsmithTerrainProbe.Column(60, 64) : new WorldsmithTerrainProbe.Column(64, 64);
        assertTrue(region.waterMatches(new BlockPos(3, 0, 5), lake));
        assertFalse(new WorldsmithStructureRegion(1, 1024, 0, 1, 1, "AWAY", 48).waterMatches(new BlockPos(3, 0, 5), lake));
        assertFalse(region.waterMatches(new BlockPos(-20, 0, 0), lake), "water 60 blocks away counted as near");
    }

    @Test void onlyWorldsmithTemplateStructuresLeaveTheVanillaSearch() throws Exception {
        var fixture = fixture();
        var state = ChunkGeneratorStructureState.createForFlat(fixture.random, SEED, new FixedBiomeSource(fixture.biome), Stream.empty());
        Holder<Structure> ours = Holder.direct(fixture.structure);
        Holder<Structure> village = VanillaRegistries.createLookup().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.VILLAGE_PLAINS);
        assertNull(WorldsmithStructureLocator.split(state, HolderSet.direct(village)));
        var split = WorldsmithStructureLocator.split(state, HolderSet.direct(List.of(village, ours)));
        assertEquals(List.of(ours), split.ours().stream().toList());
        assertEquals(List.of(village), split.others().stream().toList());
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    private record Fixture(CompiledPack pack, WorldsmithTemplateStructure structure, Holder<net.minecraft.world.level.biome.Biome> biome,
        FlatLevelGeneratorSettings flat, RandomState random, RegistryAccess registries) {
        StructureTemplateManager templates(LevelStorageSource.LevelStorageAccess storage) {
            var manager = new StructureTemplateManager(ResourceManager.Empty.INSTANCE, storage, DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
            var lookup = WorldsmithPackExporter.compilePatch(pack, VanillaRegistries.createLookup()).full();
            for (int i = 0; i < 4; i++) manager.getOrCreate(pack.structureTemplateId("wayfarer_lodge", i)).load(BuiltInRegistries.BLOCK,
                WorldsmithStructureTemplates.encode(pack.structures().getTemplates().get("wayfarer_lodge").get(i), lookup, pack));
            return manager;
        }
    }

    private static Fixture fixture() throws Exception {
        var pack = WorldsmithStructureExpansionTest.pack("wayfarer_lodge");
        var lookup = WorldsmithPackExporter.compilePatch(pack, VanillaRegistries.createLookup()).full();
        var structure = (WorldsmithTemplateStructure) lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(pack.structureKey("wayfarer_lodge")).value();
        var biome = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(pack.biomes().getFirst().key());
        var flat = new FlatLevelGeneratorSettings(Optional.empty(), biome, List.of());
        flat.getLayersInfo().add(new FlatLayerInfo(129, Blocks.STONE));
        flat.updateLayers();
        return new Fixture(pack, structure, biome, flat, RandomState.create(lookup, pack.noiseSettingsKey(), SEED),
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
