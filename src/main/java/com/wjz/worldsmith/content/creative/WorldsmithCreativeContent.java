package com.wjz.worldsmith.content.creative;

import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldsmithCustomBlocks;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.content.CustomBlockBindingSnapshot;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackLinkedSet;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One world-scoped creative catalog. New content domains add a provider instead of another hard-coded tab. */
public final class WorldsmithCreativeContent {
    public static final ResourceKey<CreativeModeTab> TAB_KEY = ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("world_content"));
    public static DataComponentType<CreatureSpawnToken> CREATURE_SPAWN;
    public static CreatureSummonerItem CREATURE_SUMMONER;
    private static final int MAX_ENTRIES = 1024;
    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();
    private static boolean registered;
    private static long providerRevision;
    private static long revision;
    private static long publishedProviderRevision = -1;
    private static Context publishedContext;
    private static List<Entry> entries = List.of();

    private WorldsmithCreativeContent() {}

    public enum Kind { BLOCK, CREATURE, ITEM }

    /** Native stacks remain owned by the catalog; every consumer receives its own copy. */
    public record Entry(String id, Kind kind, ItemStack stack) {
        public Entry {
            if (!CreatureSpawnToken.validSpecies(id)) throw new IllegalArgumentException("Invalid creative content id");
            Objects.requireNonNull(kind); Objects.requireNonNull(stack);
            if (stack.isEmpty() || stack.getCount() != 1) throw new IllegalArgumentException("Creative content entries need exactly one real item");
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    public record Context(String scope, WorldBlockBindings.Resolver blocks, CreatureRuntime.Snapshot creatures, CustomItemRuntime.Snapshot items) {
        public Context {
            Objects.requireNonNull(scope); Objects.requireNonNull(blocks); Objects.requireNonNull(creatures); Objects.requireNonNull(items);
            if (!scope.equals(blocks.snapshot().getScope()) || !scope.equals(creatures.bundleHash()) || !scope.equals(items.bundleHash()))
                throw new IllegalArgumentException("Creative catalog domains belong to different worlds");
        }
    }

    @FunctionalInterface public interface Provider {
        List<Entry> entries(Context context);
    }

    /** Additional domains share this catalog; registering a provider does not mutate any world definition. */
    public static synchronized void registerProvider(String id, Provider provider) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_.-]{0,63}") || PROVIDERS.containsKey(id))
            throw new IllegalArgumentException("Invalid or duplicate creative content provider id: " + id);
        if (PROVIDERS.size() >= 32) throw new IllegalArgumentException("Too many creative content providers");
        PROVIDERS.put(id, Objects.requireNonNull(provider));
        providerRevision++;
    }

    public static synchronized void register() {
        if (registered) return;
        CREATURE_SPAWN = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id("creature_spawn"),
            DataComponentType.<CreatureSpawnToken>builder().persistent(CreatureSpawnToken.CODEC)
                .networkSynchronized(CreatureSpawnToken.STREAM_CODEC).build());
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id("creature_summoner"));
        CREATURE_SUMMONER = Registry.register(BuiltInRegistries.ITEM, itemKey,
            new CreatureSummonerItem(new Item.Properties().setId(itemKey).stacksTo(1)));
        registerProvider("blocks", context -> context.blocks.snapshot().getBindings().stream()
            .map(binding -> new Entry(binding.getId(), Kind.BLOCK, new ItemStack(context.blocks.resolveItem(binding.logicalId())))).toList());
        registerProvider("creatures", context -> context.creatures.definitions().values().stream()
            .sorted(java.util.Comparator.comparing(definition -> definition.getId()))
            .map(definition -> new Entry(definition.getId(), Kind.CREATURE, summonStack(context.scope, definition.getId()))).toList());
        registerProvider("items", context -> context.items.definitions().values().stream()
            .map(definition -> new Entry(definition.getId(), Kind.ITEM, context.items.stack(CustomItemRuntime.LOGICAL_PREFIX + definition.getId(), 1))).toList());
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB_KEY, FabricCreativeModeTab.builder()
            .title(Component.translatable("itemGroup.worldsmith.world_content"))
            .icon(() -> new ItemStack(Items.MAP))
            .displayItems((parameters, output) -> entries().forEach(entry -> output.accept(entry.stack())))
            .build());
        registered = true;
    }

    public static ItemStack summonStack(String scope, String species) {
        ItemStack stack = new ItemStack(Objects.requireNonNull(CREATURE_SUMMONER, "Creative content has not been registered"));
        stack.set(CREATURE_SPAWN, new CreatureSpawnToken(scope, species));
        return stack;
    }

    /** Client publication uses only active, resource-verified runtime snapshots, never authoring drafts. */
    public static synchronized void publish(CustomBlockBindingSnapshot blocks, CreatureRuntime.Snapshot creatures, CustomItemRuntime.Snapshot items) {
        if (!registered) return;
        if (publishedContext != null && publishedProviderRevision == providerRevision
            && publishedContext.scope.equals(creatures.bundleHash()) && publishedContext.blocks.snapshot().equals(blocks)
            && publishedContext.items.definitions().equals(items.definitions())) return;
        Context context = new Context(creatures.bundleHash(), WorldBlockBindings.resolver(blocks), creatures, items);
        Map<String, Entry> unique = new LinkedHashMap<>();
        Set<ItemStack> uniqueStacks = ItemStackLinkedSet.createTypeAndComponentsSet();
        Set<String> boundHosts = Set.copyOf(context.blocks.nativeIds().values());
        for (var provider : PROVIDERS.entrySet()) {
            List<Entry> additions = Objects.requireNonNull(provider.getValue().entries(context), "Creative provider returned no collection");
            if (additions.size() > MAX_ENTRIES) throw new IllegalArgumentException("Creative provider entry budget exceeded: " + provider.getKey());
            for (Entry entry : additions) {
                Objects.requireNonNull(entry);
                ItemStack stack = entry.stack();
                if (stack.getItem() instanceof BlockItem block) {
                    String nativeId = block.getBlock().builtInRegistryHolder().key().identifier().toString();
                    if (WorldsmithCustomBlocks.isReservedNativeId(nativeId) && !boundHosts.contains(nativeId))
                        throw new IllegalArgumentException("Creative provider exposed an unbound native block host");
                }
                if (stack.getItem() == CREATURE_SUMMONER) {
                    CreatureSpawnToken token = stack.get(CREATURE_SPAWN);
                    if (token == null || !context.scope.equals(token.bundleHash()) || !context.creatures.definitions().containsKey(token.species()))
                        throw new IllegalArgumentException("Creative provider exposed an undefined or foreign creature");
                }
                if (stack.getItem() == CustomItemRuntime.host()) {
                    var identity = stack.get(CustomItemRuntime.identityComponent());
                    if (!context.items.isCanonical(stack) || entry.kind != Kind.ITEM || identity == null || !entry.id.equals(identity.itemId()))
                        throw new IllegalArgumentException("Creative provider exposed an undefined, foreign or noncanonical ordinary item");
                }
                String key = entry.kind + ":" + entry.id;
                if (unique.putIfAbsent(key, entry) != null) throw new IllegalArgumentException("Duplicate logical creative entry: " + key);
                if (!uniqueStacks.add(stack)) unique.remove(key);
                if (unique.size() > MAX_ENTRIES) throw new IllegalArgumentException("World creative catalog entry budget exceeded");
            }
        }
        entries = List.copyOf(unique.values());
        publishedContext = context;
        publishedProviderRevision = providerRevision;
        revision++;
    }

    public static synchronized void clear() {
        if (publishedContext == null && entries.isEmpty()) return;
        publishedContext = null; entries = List.of(); revision++;
    }
    public static synchronized String scope() { return publishedContext == null ? null : publishedContext.scope; }
    public static synchronized long revision() { return revision; }
    public static synchronized long providerRevision() { return providerRevision; }
    public static synchronized List<Entry> entries() { return entries; }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("worldsmith", path); }
}
