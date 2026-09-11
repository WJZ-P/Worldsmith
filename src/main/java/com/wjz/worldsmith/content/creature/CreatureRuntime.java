package com.wjz.worldsmith.content.creature;

import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldRewardItems;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.validation.Diagnostic;
import com.wjz.worldsmith.core.validation.DiagnosticSeverity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/** Static native hosts plus explicit, immutable, per-world logical bindings. No global draft activation. */
public final class CreatureRuntime {
    public static final String PASSIVE_ID = "worldsmith:content/creature/passive";
    public static final String HOSTILE_ID = "worldsmith:content/creature/hostile";
    public static final String ENCOUNTER_BOSS_ID = "worldsmith:content/creature/encounter_boss";
    private static final Map<ServerLevel, Snapshot> WORLDS = new ConcurrentHashMap<>();
    private static volatile Snapshot clientSnapshot;
    private static EntityType<CreatureEntity> passive;
    private static EntityType<HostileCreatureEntity> hostile;
    private static EntityType<EncounterBossCreatureEntity> encounterBoss;

    private CreatureRuntime() {}

    public static synchronized void register() {
        if (passive != null) return;
        var passiveKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse(PASSIVE_ID));
        var hostileKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse(HOSTILE_ID));
        var encounterKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse(ENCOUNTER_BOSS_ID));
        passive = Registry.register(BuiltInRegistries.ENTITY_TYPE, passiveKey,
            EntityType.Builder.of(CreatureEntity::new, MobCategory.CREATURE).sized(0.8F, 1.4F)
                .clientTrackingRange(10).updateInterval(2).noLootTable().build(passiveKey));
        hostile = Registry.register(BuiltInRegistries.ENTITY_TYPE, hostileKey,
            EntityType.Builder.of(HostileCreatureEntity::new, MobCategory.MONSTER).sized(0.8F, 1.4F)
                .clientTrackingRange(10).updateInterval(2).noLootTable().notInPeaceful().build(hostileKey));
        encounterBoss = Registry.register(BuiltInRegistries.ENTITY_TYPE, encounterKey,
            EntityType.Builder.of(EncounterBossCreatureEntity::new, MobCategory.MONSTER).sized(0.8F, 1.4F)
                .clientTrackingRange(10).updateInterval(2).noLootTable().notInPeaceful().build(encounterKey));
        FabricDefaultAttributeRegistry.register(passive, CreatureEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(hostile, CreatureEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(encounterBoss, CreatureEntity.createAttributes());
        SpawnPlacements.register(passive, SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CreatureRuntime::checkSpawn);
        SpawnPlacements.register(hostile, SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CreatureRuntime::checkSpawn);
        // Only our typed spawner's explicit CustomSpawnRules may create this host; no natural selection.
        SpawnPlacements.register(encounterBoss, SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (type, level, reason, pos, random) -> false);
    }

    public static EntityType<CreatureEntity> passiveType() { return Objects.requireNonNull(passive, "CreatureRuntime.register must run during bootstrap"); }
    public static EntityType<HostileCreatureEntity> hostileType() { return Objects.requireNonNull(hostile, "CreatureRuntime.register must run during bootstrap"); }
    public static EntityType<EncounterBossCreatureEntity> encounterBossType() { return Objects.requireNonNull(encounterBoss, "CreatureRuntime.register must run during bootstrap"); }

    /** Existing natural/creative hosts remain valid; the separate encounter host is Boss-only. */
    public static boolean matchesHost(EntityType<?> type, CreatureDefinition definition) {
        return definition.getCategory() == CreatureCategory.HOSTILE
            ? type == hostile || type == encounterBoss && definition.getBoss() != null : type == passive;
    }

    public static Snapshot prepare(String bundleHash, CreatureLibrary library) { return prepare(bundleHash, library, Map.of()); }

    public static Snapshot prepare(String bundleHash, CreatureLibrary library, Map<String, String> biomeBindings) {
        return prepare(bundleHash, library, biomeBindings, null, null);
    }

    /** Full publication preparation resolves reward stacks now, not when a creature later dies. */
    public static Snapshot prepare(String bundleHash, CreatureLibrary library, Map<String, String> biomeBindings,
                                   CustomItemRuntime.Snapshot items, WorldBlockBindings.Resolver blocks) {
        if (!bundleHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Creature snapshot needs an immutable SHA-256 bundle identity");
        if (items != null && !items.bundleHash().equals(bundleHash) || blocks != null && !blocks.snapshot().getScope().equals(bundleHash))
            throw new IllegalArgumentException("Creature reward resolvers must belong to the same immutable bundle");
        var diagnostics = CustomCreatureValidator.validate(library);
        if (!diagnostics.isEmpty()) throw new IllegalArgumentException("Invalid creature library: " + diagnostics);
        // Detach and freeze every authoring collection, including Java-visible nested lists.
        var frozen = CustomCreatureValidator.freeze(library);
        var nativeDiagnostics = nativeAttributeDiagnostics(frozen);
        if (!nativeDiagnostics.isEmpty()) throw new IllegalArgumentException("Creature attributes exceed native runtime limits: " + nativeDiagnostics);
        var definitions = new LinkedHashMap<String, CreatureDefinition>();
        frozen.getCreatures().forEach(d -> definitions.put(d.getId(), d));
        biomeBindings.values().forEach(Identifier::parse);
        return new Snapshot(bundleHash, definitions, biomeBindings, items, blocks);
    }

    /**
     * Keep versioned Core documents intact, but reject values that this actual game version would silently
     * sanitize. This deliberately runs before any snapshot is published or native entity is initialized.
     * Width/height are EntityDimensions, not native attributes, and retain their existing Core validation.
     */
    static List<Diagnostic> nativeAttributeDiagnostics(CreatureLibrary library) {
        var diagnostics = new ArrayList<Diagnostic>();
        for (int index = 0; index < library.getCreatures().size(); index++) {
            var definition = library.getCreatures().get(index);
            String path = "creatures.creatures[" + index + "]";
            var attributes = definition.getAttributes();
            checkNativeAttribute(diagnostics, definition.getId(), path + ".attributes.health", Attributes.MAX_HEALTH, attributes.getHealth());
            checkNativeAttribute(diagnostics, definition.getId(), path + ".attributes.speed", Attributes.MOVEMENT_SPEED, attributes.getSpeed());
            checkNativeAttribute(diagnostics, definition.getId(), path + ".attributes.followRange", Attributes.FOLLOW_RANGE, attributes.getFollowRange());
            checkNativeAttribute(diagnostics, definition.getId(), path + ".attributes.attackDamage", Attributes.ATTACK_DAMAGE, attributes.getAttackDamage());
            checkNativeAttribute(diagnostics, definition.getId(), path + ".attributes.knockbackResistance", Attributes.KNOCKBACK_RESISTANCE, attributes.getKnockbackResistance());
            var boss = definition.getBoss();
            if (boss == null) continue;
            for (int phaseIndex = 0; phaseIndex < boss.getPhases().size(); phaseIndex++) {
                var phase = boss.getPhases().get(phaseIndex);
                String phasePath = path + ".boss.phases[" + phaseIndex + "]";
                checkNativeAttribute(diagnostics, definition.getId(), phasePath + ".speedMultiplier", Attributes.MOVEMENT_SPEED,
                    attributes.getSpeed() * phase.getSpeedMultiplier());
                checkNativeAttribute(diagnostics, definition.getId(), phasePath + ".damageMultiplier", Attributes.ATTACK_DAMAGE,
                    attributes.getAttackDamage() * phase.getDamageMultiplier());
            }
        }
        return List.copyOf(diagnostics);
    }

    private static void checkNativeAttribute(List<Diagnostic> diagnostics, String creatureId, String path,
                                            Holder<Attribute> holder, double requested) {
        Attribute attribute = holder.value();
        double sanitized = attribute.sanitizeValue(requested);
        if (Double.isFinite(requested) && Double.compare(requested, sanitized) == 0) return;
        String bounds = attribute instanceof RangedAttribute ranged
            ? " (native range " + ranged.getMinValue() + ".." + ranged.getMaxValue() + ")" : "";
        diagnostics.add(new Diagnostic(path, "creature.native_attribute_out_of_range", DiagnosticSeverity.ERROR,
            "Creature '" + creatureId + "' requests " + requested + " for " + BuiltInRegistries.ATTRIBUTE.getKey(attribute)
                + ", but this game version sanitizes it to " + sanitized + bounds + "; correct the authored value before publication"));
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

    /** Rare, repeatable natural encounters. Only loaded live entities participate; this is not a unique-world ledger. */
    static boolean allowNaturalBoss(ServerLevel level, Snapshot snapshot, CreatureDefinition definition, BlockPos pos, RandomSource random) {
        var boss=definition.getBoss();
        if(boss==null)return true;
        if(WORLDS.get(level)!=snapshot || random.nextDouble()>=boss.getNaturalSpawnChance())return false;
        double spacing=boss.getNaturalSpacingBlocks();
        return level.getEntitiesOfClass(CreatureEntity.class,new AABB(pos).inflate(spacing),other -> other.isAlive()
            && snapshot.bundleHash().equals(other.bundleHash()) && definition.getId().equals(other.creatureId())
            && other.distanceToSqr(pos.getX()+.5,pos.getY(),pos.getZ()+.5)<spacing*spacing).isEmpty();
    }

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
        private final Map<String, List<PreparedDrop>> rewards;
        private final CustomItemRuntime.Snapshot items;
        private final WorldBlockBindings.Resolver blocks;
        private final boolean needsItemContext;
        private final boolean needsBlockContext;
        private Snapshot(String hash, Map<String, CreatureDefinition> definitions, Map<String, String> biomes,
                         CustomItemRuntime.Snapshot items, WorldBlockBindings.Resolver blocks) {
            this.bundleHash = hash; this.definitions = Collections.unmodifiableMap(definitions); this.biomeBindings = Map.copyOf(biomes);
            this.items = items; this.blocks = blocks;
            Map<String, List<PreparedDrop>> prepared = new LinkedHashMap<>(); boolean customItems = false, customBlocks = false;
            for (var definition : definitions.values()) {
                List<PreparedDrop> entries = new ArrayList<>();
                for (var drop : definition.getDrops()) {
                    // Resolving the maximum validates the true per-item stack limit as well as registry identity.
                    WorldRewardItems.stack(drop.getItem(), drop.getMaxCount(), blocks, items);
                    entries.add(new PreparedDrop(drop));
                    customItems |= drop.getItem().startsWith("worldsmith:item/");
                    customBlocks |= drop.getItem().startsWith("worldsmith:content/");
                }
                prepared.put(definition.getId(), List.copyOf(entries));
            }
            rewards = Map.copyOf(prepared); needsItemContext = customItems; needsBlockContext = customBlocks;
        }
        public String bundleHash() { return bundleHash; }
        public Map<String, CreatureDefinition> definitions() { return definitions; }
        public Map<String, String> biomeBindings() { return biomeBindings; }

        /** Death caller must be the owning server world; fresh canonical stacks retain all item components. */
        public List<ItemStack> deathDrops(ServerLevel level, String creatureId, boolean killedByPlayer, RandomSource random) {
            if (WORLDS.get(level) != this) throw new IllegalStateException("Creature rewards requested outside their bound server world");
            if (!definitions.containsKey(creatureId)) throw new IllegalArgumentException("Missing creature reward definition: " + creatureId);
            if (needsItemContext) {
                var current = CustomItemRuntime.snapshot(level);
                if (current == null || !current.bundleHash().equals(bundleHash) || !current.definitions().equals(items.definitions()))
                    throw new IllegalStateException("The creature's immutable custom-item reward context is no longer active");
            }
            if (needsBlockContext && !Objects.equals(WorldBlockBindings.active(), blocks.snapshot()))
                throw new IllegalStateException("The creature's immutable block-item reward context is no longer active");
            List<ItemStack> result = new ArrayList<>();
            for (var entry : rewards.get(creatureId)) {
                CreatureDrop rule = entry.rule;
                if (rule.getRequirePlayerKill() && !killedByPlayer || rule.getChance() <= 0 || rule.getChance() < 1 && random.nextDouble() >= rule.getChance()) continue;
                int count = rule.getMinCount() == rule.getMaxCount() ? rule.getMinCount() : rule.getMinCount() + random.nextInt(rule.getMaxCount() - rule.getMinCount() + 1);
                // The immutable factory is reused, not a shallow-copied mutable text/lore component object.
                result.add(WorldRewardItems.stack(rule.getItem(), count, blocks, items));
            }
            return List.copyOf(result);
        }

        private static final class PreparedDrop {
            final CreatureDrop rule;
            PreparedDrop(CreatureDrop rule) { this.rule = rule; }
        }
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
