package com.wjz.worldsmith.datagen;

import com.google.common.hash.Hashing;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wjz.worldsmith.content.GeneratedWorldResourcePack;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader;
import com.wjz.worldsmith.worldgen.CompiledPack;
import com.wjz.worldsmith.worldgen.WorldsmithPackExporter;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Opt-in export of a real saved authoring bundle through the same compiler as Create World.
 * Does not open a world, commit content bindings, or record an MCP native-activation receipt. */
public final class WorldsmithAuthoredPackExportProvider implements DataProvider {
    public static final String INPUT_PROPERTY = "worldsmith.authoredPack";
    private final FabricPackOutput output;
    private final CompletableFuture<HolderLookup.Provider> registries;

    public WorldsmithAuthoredPackExportProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        this.output = output;
        this.registries = registries;
    }

    @Override public String getName() { return "Worldsmith Authored Bundle Native Export"; }

    @Override public CompletableFuture<?> run(CachedOutput cache) {
        return registries.thenAcceptAsync(lookup -> {
            try { export(cache, lookup); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        });
    }

    private void export(CachedOutput cache, HolderLookup.Provider lookup) throws IOException {
        Path input = Path.of(System.getProperty(INPUT_PROPERTY)).toAbsolutePath().normalize();
        var bundle = WorldsmithPackLoader.loadDirectory(input);
        // Fabric datagen runs before WorldLoader's component-application phase. Use the same native
        // initializers over its complete mod+vanilla lookup, never placeholder/default-empty components.
        var itemLookup = lookup.lookupOrThrow(Registries.ITEM);
        for (var item : BuiltInRegistries.ITEM) require(
            itemLookup.getOrThrow(item.builtInRegistryHolder().key()) == item.builtInRegistryHolder(),
            "Datagen item lookup differs from the actual native item holder");
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup)
            .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        for (var item : BuiltInRegistries.ITEM)
            require(item.builtInRegistryHolder().areComponentsBound(), "Native item components remain unbound: " + item);
        var originalBlocks = WorldBlockBindings.active();
        var originalCreatures = CreatureRuntime.clientSnapshot();
        String originalScope = WorldContentRuntime.activeScope();
        int originalLevels = WorldContentRuntime.boundLevelCount();
        CompiledPack compiled = CompiledPack.scoped(bundle);
        var prepared = WorldContentRuntime.prepare(compiled);
        var resolved = WorldsmithPackExporter.compilePatch(compiled, lookup);
        Path root = output.getOutputFolder().toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path scratch = Files.createTempDirectory(root, ".worldsmith-authored-export-");
        try {
            Path nativeRoot = scratch.resolve("native-datapack");
            int nativeFiles = WorldsmithPackExporter.write(compiled, resolved, nativeRoot);
            int registryReadbacks = 0;
            for (var data : RegistryDataLoader.WORLDGEN_REGISTRIES)
                registryReadbacks += readRegistry(nativeRoot, resolved.full(), data);
            require(registryReadbacks >= bundle.getBiomes().getBiomes().size(), "Missing exported biome registry entries");

            int templates = 0;
            int bossSpawners = 0;
            long cells = 0;
            Path structures = nativeRoot.resolve("data/worldsmith/structure");
            if (Files.isDirectory(structures)) try (var walk = Files.walk(structures)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".nbt")).toList()) {
                    CompoundTag encoded = NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024 * 1024));
                    var template = new StructureTemplate();
                    template.load(BuiltInRegistries.BLOCK, encoded);
                    var readback = template.save(new CompoundTag());
                    require(encoded.getListOrEmpty("blocks").size() == readback.getListOrEmpty("blocks").size(), "Native template cell count changed: " + file);
                    require(encoded.getListOrEmpty("size").equals(readback.getListOrEmpty("size")), "Native template size changed: " + file);
                    for (var block : readback.getListOrEmpty("blocks")) if (block instanceof CompoundTag cell) {
                        var spawn = cell.getCompoundOrEmpty("nbt").getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity");
                        if (CreatureRuntime.ENCOUNTER_BOSS_ID.equals(spawn.getStringOr("id", ""))) {
                            var creature = prepared.creatures().definitions().get(spawn.getStringOr("WorldsmithCreature", ""));
                            require(creature != null && creature.getBoss() != null && bundle.getComputedId().equals(spawn.getStringOr("WorldsmithBundle", ""))
                                && spawn.getBooleanOr(com.wjz.worldsmith.content.creature.EncounterBossCreatureEntity.INITIALIZE_TAG, false),
                                "Native template lost its typed Boss spawn identity: " + file);
                            bossSpawners++;
                        }
                    }
                    templates++;
                    cells += readback.getListOrEmpty("blocks").size();
                }
            }
            require(bundle.getStructures().getStructures().isEmpty() || templates > 0, "Missing native structure templates");
            long expectedSpawners = compiled.structures().getTemplates().values().stream().flatMap(java.util.List::stream)
                .flatMap(geometry -> geometry.getInteractions().stream())
                .filter(interaction -> interaction instanceof com.wjz.worldsmith.core.structure.StructureInteraction.BossSpawner).count();
            require(bossSpawners == expectedSpawners, "Typed Boss spawner count changed during native template export");

            var embedded = WorldContentRuntime.loadEmbedded(nativeRoot).orElseThrow(() -> new IllegalStateException("Missing embedded authored bundle"));
            require(embedded.pack().getComputedId().equals(bundle.getComputedId()), "Embedded bundle digest changed");
            require(embedded.blockBindings().equals(prepared.blockBindings()), "Embedded block slots changed");
            var restored = WorldContentRuntime.prepare(embedded);
            require(restored.creatures().definitions().equals(prepared.creatures().definitions()), "Embedded creatures changed");
            require(restored.items().definitions().equals(prepared.items().definitions()), "Embedded items changed");
            require(restored.quests().definitions().equals(prepared.quests().definitions()), "Embedded main line changed");
            var resources = new GeneratedWorldResourcePack(prepared.scope(), prepared.clientResources());
            require(resources.contentHash().equals(new GeneratedWorldResourcePack(restored.scope(), restored.clientResources()).contentHash()), "Restored client assets changed");

            Path clientRoot = scratch.resolve("client-resourcepack");
            for (var entry : prepared.clientResources().entrySet()) writeInside(clientRoot, entry.getKey(), entry.getValue());
            writeInside(clientRoot, GeneratedWorldResourcePack.SENTINEL_PATH, resources.sentinelBytes());
            var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
            JsonArray version = new JsonArray(); version.add(format.major()); version.add(format.minor());
            JsonObject metadata = new JsonObject(), packMetadata = new JsonObject();
            packMetadata.addProperty("description", "Worldsmith authored content: " + bundle.getManifest().getDisplayName());
            packMetadata.add("min_format", version); packMetadata.add("max_format", version.deepCopy());
            metadata.add("pack", packMetadata);
            writeInside(clientRoot, "pack.mcmeta", jsonBytes(metadata));

            require(WorldBlockBindings.active() == originalBlocks && CreatureRuntime.clientSnapshot() == originalCreatures
                && Objects.equals(WorldContentRuntime.activeScope(), originalScope) && WorldContentRuntime.boundLevelCount() == originalLevels,
                "Offline export changed active world-content bindings");
            JsonObject report = new JsonObject();
            report.addProperty("schemaVersion", 1);
            report.addProperty("passed", true);
            report.addProperty("stage", "NATIVE_EXPORT_READBACK");
            report.addProperty("sourceBundle", input.toString());
            report.addProperty("bundleId", bundle.getComputedId());
            report.addProperty("title", bundle.getManifest().getDisplayName());
            report.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().name());
            report.addProperty("nativeFiles", nativeFiles);
            report.addProperty("nativeDefaultComponentsInitialized", true);
            report.addProperty("nativeRegistryCodecReadbacks", registryReadbacks);
            report.addProperty("nativeTemplateReadbacks", templates);
            report.addProperty("nativeBossSpawnerReadbacks", bossSpawners);
            report.addProperty("nativeTemplateCells", cells);
            report.addProperty("biomes", bundle.getBiomes().getBiomes().size());
            report.addProperty("structures", bundle.getStructures().getStructures().size());
            report.addProperty("blocks", bundle.getBlocks().getBlocks().size());
            report.addProperty("items", prepared.items().definitions().size());
            report.addProperty("creatures", prepared.creatures().definitions().size());
            report.addProperty("bosses", prepared.creatures().definitions().values().stream().filter(c -> c.getBoss() != null).count());
            report.addProperty("quests", prepared.quests().questCount());
            report.addProperty("clientResourceFiles", resources.hashes().size() + 1);
            report.addProperty("clientContentHash", resources.contentHash());
            report.addProperty("embeddedBundleReadback", true);
            report.addProperty("activeBindingsUnchanged", true);
            report.addProperty("nativeActivationVerified", false);
            report.addProperty("worldsCreated", 0);
            report.addProperty("serverTicksExecuted", 0);
            report.addProperty("clientRenderingExercised", false);
            JsonArray boundaries = new JsonArray();
            boundaries.add("Real saved bundle exported and read back with native codecs; not a synthetic fixture");
            boundaries.add("No game world, entity combat, resource reload or activation receipt is claimed");
            report.add("verificationBoundary", boundaries);
            writeInside(scratch, "export-report.json", jsonBytes(report));
            Path destination = root.resolve("worldsmith-authored").resolve(bundle.getComputedId());
            try (var files = Files.walk(scratch)) {
                for (Path source : files.filter(Files::isRegularFile).sorted().toList()) {
                    byte[] bytes = Files.readAllBytes(source);
                    Path target = destination.resolve(scratch.relativize(source));
                    cache.writeIfNeeded(target, bytes, Hashing.sha1().hashBytes(bytes));
                }
            }
            System.out.println("Worldsmith authored export verified: " + destination.resolve("export-report.json"));
        } finally {
            require(scratch.getParent().equals(root) && scratch.getFileName().toString().startsWith(".worldsmith-authored-export-"), "Unexpected export scratch directory");
            try (var paths = Files.walk(scratch)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static <T> int readRegistry(Path root, HolderLookup.Provider lookup, RegistryDataLoader.RegistryData<T> data) throws IOException {
        Path directory = root.resolve("data/worldsmith").resolve(Registries.elementsDirPath(data.key()));
        if (!Files.isDirectory(directory)) return 0;
        int count = 0;
        var ops = lookup.createSerializationContext(JsonOps.INSTANCE);
        try (var walk = Files.walk(directory)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".json")).toList()) {
                data.elementCodec().parse(ops, JsonParser.parseString(Files.readString(file))).getOrThrow(
                    message -> new IllegalStateException("Native registry readback failed for " + file + ": " + message));
                count++;
            }
        }
        return count;
    }

    private static byte[] jsonBytes(JsonObject value) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    private static void writeInside(Path root, String relative, byte[] bytes) throws IOException {
        Path target = root.resolve(relative).normalize();
        require(target.startsWith(root) && !target.equals(root), "Invalid export resource path");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static void require(boolean valid, String detail) {
        if (!valid) throw new IllegalStateException(detail);
    }
}
