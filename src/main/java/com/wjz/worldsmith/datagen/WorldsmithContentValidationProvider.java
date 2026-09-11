package com.wjz.worldsmith.datagen;

import com.google.common.hash.Hashing;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wjz.worldsmith.content.GeneratedBlockResources;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.WorldsmithCustomBlocks;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.core.content.CreatureLibrary;
import com.wjz.worldsmith.core.content.CustomBlockLibrary;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import com.wjz.worldsmith.core.structure.*;
import com.wjz.worldsmith.worldgen.CompiledPack;
import com.wjz.worldsmith.worldgen.WorldsmithPacks;
import com.wjz.worldsmith.worldgen.WorldsmithStructureTemplates;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A bounded, repeatable probe inside real Fabric bootstrap. No server/world/entity is constructed,
 * no live world bindings are committed, and no author config or user save is read or written.
 */
public final class WorldsmithContentValidationProvider implements DataProvider {
    private static final String LOGICAL_BLOCK = "worldsmith:content/probe_stone";
    private final FabricPackOutput output;
    private final CompletableFuture<HolderLookup.Provider> registries;

    public WorldsmithContentValidationProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        this.output = output; this.registries = registries;
    }

    @Override public CompletableFuture<?> run(CachedOutput cache) {
        return registries.thenAcceptAsync(provider -> {
            try { validateAndWrite(cache, provider); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        });
    }

    @Override public String getName() { return "Worldsmith Native Content Runtime Validation"; }

    private void validateAndWrite(CachedOutput cache, HolderLookup.Provider registryLookup) throws IOException {
        var originalBlockBindings = WorldBlockBindings.active();
        var originalCreatureClient = CreatureRuntime.clientSnapshot();
        String originalRuntimeScope = WorldContentRuntime.activeScope();
        int originalLevelCount = WorldContentRuntime.boundLevelCount();
        int registeredHosts = 0;
        int registeredItems = 0;
        for (var entry : WorldsmithCustomBlocks.hosts().entrySet()) {
            var id = Identifier.parse(entry.getKey());
            require(BuiltInRegistries.BLOCK.getOptional(id).orElse(null) == entry.getValue(), "Native block host not registered: " + id);
            require(entry.getValue().getStateDefinition().getProperty("light") == WorldsmithCustomBlocks.LIGHT, "Host light property not predeclared");
            require(entry.getValue().getStateDefinition().getPossibleStates().size() == 16, "Host must predeclare all 16 light states");
            var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            require(item instanceof BlockItem blockItem && blockItem.getBlock() == entry.getValue() && entry.getValue().asItem() == item,
                "Native host lacks its matching BlockItem: " + id);
            registeredHosts++; registeredItems++;
        }
        require(registeredHosts == 128 && registeredItems == 128, "Expected 128 bounded block hosts and 128 block items");
        require(BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(CreatureRuntime.PASSIVE_ID)).orElse(null) == CreatureRuntime.passiveType(), "Passive creature host missing");
        require(BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(CreatureRuntime.HOSTILE_ID)).orElse(null) == CreatureRuntime.hostileType(), "Hostile creature host missing");
        require(BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(CreatureRuntime.ENCOUNTER_BOSS_ID)).orElse(null) == CreatureRuntime.encounterBossType(), "Landmark Boss host missing");
        require(CreatureRuntime.passiveType().getCategory() == MobCategory.CREATURE && CreatureRuntime.hostileType().getCategory() == MobCategory.MONSTER,
            "Creature hosts must preserve distinct native spawn-cap categories");
        require(CreatureRuntime.encounterBossType().getCategory() == MobCategory.MONSTER, "Landmark Boss host must retain hostile peaceful-mode behavior");
        require(DefaultAttributes.hasSupplier(CreatureRuntime.passiveType()) && DefaultAttributes.hasSupplier(CreatureRuntime.hostileType())
            && DefaultAttributes.hasSupplier(CreatureRuntime.encounterBossType()), "Creature native attribute suppliers missing");

        WorldsmithPack bundle = fixture();
        CompiledPack compiled = CompiledPack.scoped(bundle);
        WorldContentRuntime.Prepared prepared = WorldContentRuntime.prepare(compiled);
        var state = compiled.blockResolver().resolve(LOGICAL_BLOCK);
        var item = compiled.blockResolver().resolveItem(LOGICAL_BLOCK);
        require(state.getValue(WorldsmithCustomBlocks.LIGHT) == 9 && state.getLightEmission() == 9, "Logical light did not survive native resolution");
        require(item instanceof BlockItem blockItem && blockItem.getBlock() == state.getBlock(), "Logical item did not resolve to the same bound native block");
        require(prepared.blockBindings().equals(compiled.blockBindings()), "Publication changed compilation's slot assignment");

        var geometry = StructureGeometryCompiler.compile(bundle.getStructures().getStructures().getFirst().getBlueprint());
        require(geometry.getVoxels().size() == 9, "Probe geometry should contain exactly nine custom-block cells");
        CompoundTag encoded = WorldsmithStructureTemplates.encode(geometry, registryLookup, compiled);
        var compressed = new ByteArrayOutputStream(); NbtIo.writeCompressed(encoded, compressed);
        require(compressed.size() <= 64 * 1024, "Probe NBT unexpectedly exceeded its tiny budget");
        CompoundTag decoded = NbtIo.readCompressed(new ByteArrayInputStream(compressed.toByteArray()), NbtAccounter.create(1024 * 1024));
        StructureTemplate nativeTemplate = new StructureTemplate(); nativeTemplate.load(BuiltInRegistries.BLOCK, decoded);
        CompoundTag saved = nativeTemplate.save(new CompoundTag());
        require(nativeTemplate.getSize().getX() == 3 && nativeTemplate.getSize().getY() == 1 && nativeTemplate.getSize().getZ() == 3, "Native template dimensions changed");
        require(saved.getListOrEmpty("blocks").size() == 9 && saved.getListOrEmpty("palette").size() == 1, "Native template cells/palette changed");
        var readState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, saved.getListOrEmpty("palette").getCompoundOrEmpty(0));
        require(readState.equals(state), "Compressed native NBT roundtrip lost custom block identity/light");

        String biome = compiled.biomeKey(compiled.definitions().getFirst().getId()).identifier().toString();
        var spawns = CreatureRuntime.perBiomeSpawnEntries(prepared.creatures(), biome);
        require(spawns.size() == 2 && spawns.stream().map(CreatureRuntime.SpawnEntry::entityType).distinct().count() == 2, "Probe biome needs both native creature categories");
        require(prepared.creatures().candidates(biome, com.wjz.worldsmith.core.content.CreatureCategory.HOSTILE, 15).isEmpty(), "Hostile light filter ignored");
        require(prepared.creatures().candidates(biome, com.wjz.worldsmith.core.content.CreatureCategory.PASSIVE, 15).size() == 1, "Passive light filter ignored");

        Path root = output.getOutputFolder().toAbsolutePath().normalize(); Files.createDirectories(root);
        Path scratch = Files.createTempDirectory(root, ".worldsmith-content-smoke-");
        int embeddedFiles;
        try {
            var files = prepared.serverResources();
            require(files.size() <= 64 && files.values().stream().mapToLong(bytes -> bytes.length).sum() <= 8 * 1024 * 1024,
                "Probe portable bundle exceeds the fixed 64-file/8-MiB smoke budget");
            embeddedFiles = files.size();
            for (var entry : files.entrySet()) {
                Path target = scratch.resolve(entry.getKey()).normalize();
                require(target.startsWith(scratch), "Generated fixture escaped the temporary datapack");
                Files.createDirectories(target.getParent()); Files.write(target, entry.getValue());
            }
            var loaded = WorldContentRuntime.loadEmbedded(scratch).orElseThrow(() -> new IllegalStateException("Embedded fixture not found"));
            require(loaded.pack().getComputedId().equals(bundle.getManifest().getId()), "Portable native datapack lost immutable bundle identity");
            require(loaded.blockBindings().equals(prepared.blockBindings()), "Portable native datapack lost slot mapping");
            require(loaded.pack().getCreatures().equals(bundle.getCreatures()), "Portable native datapack lost creature definitions");
            var restored = WorldContentRuntime.prepare(loaded);
            require(restored.creatures().biomeBindings().equals(prepared.creatures().biomeBindings()), "Restored native biome bindings changed");
            require(restored.blockResolver().resolve(LOGICAL_BLOCK).equals(state), "Restored logical block resolves differently");
        } finally { deleteScratch(root, scratch); }

        require(WorldBlockBindings.active() == originalBlockBindings && CreatureRuntime.clientSnapshot() == originalCreatureClient
            && Objects.equals(WorldContentRuntime.activeScope(), originalRuntimeScope) && WorldContentRuntime.boundLevelCount() == originalLevelCount,
            "Validation must not mutate live world-content bindings");

        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1); report.addProperty("probe", "native_fabric_datagen_content_runtime"); report.addProperty("passed", true);
        report.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().name());
        report.addProperty("dataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        report.addProperty("nativeBlockHosts", registeredHosts); report.addProperty("nativeBlockItems", registeredItems); report.addProperty("nativeCreatureHosts", 3);
        report.addProperty("nativeCreatureAttributeSuppliers", true); report.addProperty("logicalBlock", LOGICAL_BLOCK);
        report.addProperty("resolvedNativeBlock", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()); report.addProperty("nativeLightEmission", state.getLightEmission());
        report.addProperty("nativeTemplateVoxels", 9); report.addProperty("nativeTemplateCompressedBytes", compressed.size()); report.addProperty("nativeTemplateReadback", true);
        report.addProperty("bundleId", bundle.getManifest().getId()); report.addProperty("portableEmbeddedFiles", embeddedFiles); report.addProperty("portableBundleReadback", true);
        report.addProperty("nativeSpawnCategoryEntries", spawns.size()); report.addProperty("spawnLightFiltering", true); report.addProperty("activeBindingsUnchanged", true);
        report.addProperty("worldsCreated", 0); report.addProperty("entitiesInstantiated", 0); report.addProperty("serverTicksExecuted", 0); report.addProperty("clientRenderingExercised", false);
        JsonArray limits = new JsonArray(); limits.add("No user save or game world was opened"); limits.add("No entity ticking, combat or visual acceptance is claimed"); report.add("verificationBoundary", limits);
        byte[] bytes = new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8);
        cache.writeIfNeeded(root.resolve("worldsmith-validation/content-runtime.json"), bytes, Hashing.sha1().hashBytes(bytes));
    }

    private static WorldsmithPack fixture() throws IOException {
        var base = WorldsmithPacks.builtin();
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, ((x + y) & 3) == 0 ? 0xffbdd2ff : 0xff536591);
        ByteArrayOutputStream png = new ByteArrayOutputStream(); require(ImageIO.write(image, "png", png), "No PNG writer available");
        String hash = GeneratedBlockResources.sha256(png.toByteArray());
        var blocks = WorldsmithJson.INSTANCE.getFormat().decodeFromString(CustomBlockLibrary.Companion.serializer(), """
            {"blocks":[{"id":"probe_stone","displayName":"Probe Moonstone","profile":"STONE","textureAsset":"%s","light":9,"themeRole":"Native boundary probe"}]}
            """.formatted(hash));
        String biome = base.getBiomes().getBiomes().getFirst().getId();
        var creatures = WorldsmithJson.INSTANCE.getFormat().decodeFromString(CreatureLibrary.Companion.serializer(), """
            {"creatures":[
              {"id":"probe_stag","displayName":"Probe Stag","category":"PASSIVE","model":{"texture":"%s","textureWidth":16,"textureHeight":16,
                "bones":[{"id":"body","pivot":{"y":24},"cubes":[{"origin":{"x":-2,"y":-4,"z":-2},"size":{"x":4,"y":4,"z":4}}]}]},"spawn":{"biomes":["%s"],"minLight":8,"maxLight":15}},
              {"id":"probe_guard","displayName":"Probe Guard","category":"HOSTILE","model":{"texture":"%s","textureWidth":16,"textureHeight":16,
                "bones":[{"id":"body","pivot":{"y":24},"cubes":[{"origin":{"x":-2,"y":-4,"z":-2},"size":{"x":4,"y":4,"z":4}}]}]},"spawn":{"biomes":["%s"],"minLight":0,"maxLight":7}}
            ]}
            """.formatted(hash, biome, hash, biome));
        StructureBlueprint blueprint = WorldsmithJson.INSTANCE.getFormat().decodeFromString(StructureBlueprint.Companion.serializer(), """
            {"id":"native_content_probe","size":{"x":3,"y":1,"z":3},"origin":{"x":0,"y":0,"z":0},
             "palette":{"stone":{"block":"worldsmith:content/probe_stone"}},
             "build":[{"op":"FILL","id":"base","from":{"x":0,"y":0,"z":0},"to":{"x":2,"y":0,"z":2},"material":"stone"}],
             "lighting":{"mode":"EXTERIOR_ONLY"}}
            """);
        var structures = new StructureLibrary(1, List.of(new WorldStructureDefinition("native_content_probe", blueprint, new StructurePlacement(List.of(biome)))));
        return WorldContentBundleIO.create("Native Content Probe", "Bounded datagen-only fixture; not a generated gameplay world", base.getTerrain(), base.getBiomes(),
            base.getFeatures(), structures, base.getTheme(), blocks, creatures, Map.of(hash, png.toByteArray()));
    }

    private static void require(boolean value, String detail) { if (!value) throw new IllegalStateException(detail); }

    private static void deleteScratch(Path root, Path scratch) throws IOException {
        Path target = scratch.toAbsolutePath().normalize();
        if (!target.startsWith(root) || !target.getFileName().toString().startsWith(".worldsmith-content-smoke-") || target.equals(root))
            throw new IOException("Unexpected datagen smoke cleanup path");
        if (!Files.exists(target)) return;
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(target)) throw new IOException("Smoke cleanup escaped its temporary directory");
                Files.deleteIfExists(path);
            }
        }
    }
}
