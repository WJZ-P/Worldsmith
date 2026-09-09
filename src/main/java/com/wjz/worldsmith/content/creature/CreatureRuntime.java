package com.wjz.worldsmith.content.creature;

import com.wjz.worldsmith.core.content.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;

/** Static native hosts plus explicit, immutable, per-world logical bindings. No global draft activation. */
public final class CreatureRuntime {
    public static final String PASSIVE_ID = "worldsmith:content/creature/passive";
    public static final String HOSTILE_ID = "worldsmith:content/creature/hostile";
    private static final Map<ServerLevel, Snapshot> WORLDS = new ConcurrentHashMap<>();
    private static volatile Snapshot clientSnapshot;
    private static EntityType<CreatureEntity> passive;
    private static EntityType<HostileCreatureEntity> hostile;

    private CreatureRuntime() {}

    public static synchronized void register() {
        if (passive != null) return;
        var passiveKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse(PASSIVE_ID));
        var hostileKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse(HOSTILE_ID));
        passive = Registry.register(BuiltInRegistries.ENTITY_TYPE, passiveKey,
            EntityType.Builder.of(CreatureEntity::new, MobCategory.CREATURE).sized(0.8F, 1.4F)
                .clientTrackingRange(10).updateInterval(2).noLootTable().build(passiveKey));
        hostile = Registry.register(BuiltInRegistries.ENTITY_TYPE, hostileKey,
            EntityType.Builder.of(HostileCreatureEntity::new, MobCategory.MONSTER).sized(0.8F, 1.4F)
                .clientTrackingRange(10).updateInterval(2).noLootTable().notInPeaceful().build(hostileKey));
        FabricDefaultAttributeRegistry.register(passive, CreatureEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(hostile, CreatureEntity.createAttributes());
        SpawnPlacements.register(passive, SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CreatureRuntime::checkSpawn);
        SpawnPlacements.register(hostile, SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CreatureRuntime::checkSpawn);
    }

    public static EntityType<CreatureEntity> passiveType() { return Objects.requireNonNull(passive, "CreatureRuntime.register must run during bootstrap"); }
    public static EntityType<HostileCreatureEntity> hostileType() { return Objects.requireNonNull(hostile, "CreatureRuntime.register must run during bootstrap"); }

    public static Snapshot prepare(String bundleHash, CreatureLibrary library) { return prepare(bundleHash, library, Map.of()); }

    public static Snapshot prepare(String bundleHash, CreatureLibrary library, Map<String, String> biomeBindings) {
        if (!bundleHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Creature snapshot needs an immutable SHA-256 bundle identity");
        var diagnostics = CustomCreatureValidator.validate(library);
        if (!diagnostics.isEmpty()) throw new IllegalArgumentException("Invalid creature library: " + diagnostics);
        // Detach and freeze every authoring collection, including Java-visible nested lists.
        var frozen = CustomCreatureValidator.freeze(library);
        var definitions = new LinkedHashMap<String, CreatureDefinition>();
        frozen.getCreatures().forEach(d -> definitions.put(d.getId(), d));
        biomeBindings.values().forEach(Identifier::parse);
        return new Snapshot(bundleHash, definitions, biomeBindings);
    }

    public static void bind(ServerLevel level, Snapshot snapshot) {
        Objects.requireNonNull(level); Objects.requireNonNull(snapshot);
        Snapshot previous = WORLDS.putIfAbsent(level, snapshot);
        if (previous != null && !previous.bundleHash().equals(snapshot.bundleHash()))
            throw new IllegalStateException("Changing creature definitions of a running world requires closing and rebinding that world");
    }

    public static void unbind(ServerLevel level) { WORLDS.remove(level); }
    public static void clearServer() { WORLDS.clear(); }
    /** Called only after the matching generated resources have been activated for this client world. */
    public static void activateClient(Snapshot snapshot) { clientSnapshot = Objects.requireNonNull(snapshot); }
    public static void clearClient() { clientSnapshot = null; }
    public static Snapshot clientSnapshot() { return clientSnapshot; }
    public static Snapshot snapshot(Level level) { return level instanceof ServerLevel server ? WORLDS.get(server) : clientSnapshot; }

    public static CreatureDefinition definition(Level level, String bundleHash, String creatureId) {
        Snapshot snapshot = snapshot(level);
        return snapshot != null && snapshot.bundleHash.equals(bundleHash) ? snapshot.definitions.get(creatureId) : null;
    }

    private static boolean checkSpawn(EntityType<? extends CreatureEntity> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource random) {
        if (!Mob.checkMobSpawnRules(type, level, reason, pos, random)) return false;
        Snapshot snapshot = WORLDS.get(level.getLevel());
        return snapshot != null && !snapshot.candidates(nativeBiome(level, pos), category(type), level.getRawBrightness(pos, 0)).isEmpty();
    }

    static String nativeBiome(ServerLevelAccessor level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.identifier().toString()).orElse("");
    }

    static CreatureCategory category(EntityType<?> type) { return type.getCategory() == MobCategory.MONSTER ? CreatureCategory.HOSTILE : CreatureCategory.PASSIVE; }

    /** Common host entries have category-level group bounds; selection keeps an actual group one species. */
    public static List<SpawnEntry> perBiomeSpawnEntries(Snapshot snapshot, String nativeBiome) {
        var result = new ArrayList<SpawnEntry>();
        for (var category : CreatureCategory.values()) {
            var choices = snapshot.candidates(nativeBiome, category, -1);
            if (choices.isEmpty()) continue;
            int weight = choices.stream().mapToInt(d -> d.getSpawn().getWeight()).sum();
            int min = choices.stream().mapToInt(d -> d.getSpawn().getMinGroup()).min().orElseThrow();
            int max = choices.stream().mapToInt(d -> d.getSpawn().getMaxGroup()).max().orElseThrow();
            result.add(new SpawnEntry(category == CreatureCategory.HOSTILE ? HOSTILE_ID : PASSIVE_ID, category, weight, min, max));
        }
        return List.copyOf(result);
    }

    public record SpawnEntry(String entityType, CreatureCategory category, int weight, int minGroup, int maxGroup) {}

    public static final class Snapshot {
        private final String bundleHash;
        private final Map<String, CreatureDefinition> definitions;
        private final Map<String, String> biomeBindings;
        private Snapshot(String hash, Map<String, CreatureDefinition> definitions, Map<String, String> biomes) {
            this.bundleHash = hash; this.definitions = Collections.unmodifiableMap(definitions); this.biomeBindings = Map.copyOf(biomes);
        }
        public String bundleHash() { return bundleHash; }
        public Map<String, CreatureDefinition> definitions() { return definitions; }
        public Map<String, String> biomeBindings() { return biomeBindings; }
        public List<CreatureDefinition> candidates(String biome, CreatureCategory category, int light) {
            return definitions.values().stream().filter(d -> d.getCategory() == category)
                .filter(d -> d.getSpawn().getBiomes().stream().anyMatch(id -> biome.equals(biomeBindings.get(id))))
                .filter(d -> light < 0 || light >= d.getSpawn().getMinLight() && light <= d.getSpawn().getMaxLight()).toList();
        }
        CreatureDefinition select(String biome, CreatureCategory category, int light, RandomSource random) {
            var candidates = candidates(biome, category, light);
            int total = candidates.stream().mapToInt(d -> d.getSpawn().getWeight()).sum();
            if (total == 0) return null;
            int roll = random.nextInt(total);
            for (var d : candidates) { roll -= d.getSpawn().getWeight(); if (roll < 0) return d; }
            throw new IllegalStateException("Weighted creature choice did not resolve");
        }
    }
}
