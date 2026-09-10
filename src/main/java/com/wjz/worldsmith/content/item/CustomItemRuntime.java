package com.wjz.worldsmith.content.item;

import com.wjz.worldsmith.core.content.CustomItemDefinition;
import com.wjz.worldsmith.core.content.CustomItemLibrary;
import com.wjz.worldsmith.core.content.CustomItemValidation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** One registered item host, with identity and appearance carried by each actual stack, not mutable global slots. */
public final class CustomItemRuntime {
    public static final String HOST_ID = "worldsmith:content/item/host";
    public static final String LOGICAL_PREFIX = "worldsmith:item/";
    private static final Map<ServerLevel, Snapshot> WORLDS = new ConcurrentHashMap<>();
    private static volatile Snapshot clientSnapshot;
    private static DataComponentType<WorldItemIdentity> identity;
    private static Item host;

    private CustomItemRuntime() {}

    public static synchronized void register() {
        if (host != null) return;
        identity = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Identifier.parse("worldsmith:item_identity"),
            DataComponentType.<WorldItemIdentity>builder().persistent(WorldItemIdentity.CODEC)
                .networkSynchronized(WorldItemIdentity.STREAM_CODEC).build());
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.parse(HOST_ID));
        host = Registry.register(BuiltInRegistries.ITEM, key, new ResourceItem(new Item.Properties().setId(key).stacksTo(64)));
    }

    public static Item host() { return Objects.requireNonNull(host, "CustomItemRuntime.register must run during bootstrap"); }
    public static DataComponentType<WorldItemIdentity> identityComponent() { return Objects.requireNonNull(identity, "Item identity component is not registered"); }
    public static boolean isReservedNativeId(String id) { return id != null && id.startsWith("worldsmith:content/item/"); }
    public static boolean isLogicalId(String id) { return id != null && id.startsWith(LOGICAL_PREFIX); }

    public static Snapshot prepare(String bundleHash, CustomItemLibrary library) {
        if (bundleHash == null || !bundleHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Item snapshot needs an immutable SHA-256 bundle identity");
        var diagnostics = CustomItemValidation.validate(library);
        if (!diagnostics.isEmpty()) throw new IllegalArgumentException("Invalid custom item library: " + diagnostics);
        var frozen = CustomItemValidation.freeze(library);
        Map<String, CustomItemDefinition> definitions = new LinkedHashMap<>();
        frozen.getItems().stream().sorted(java.util.Comparator.comparing(CustomItemDefinition::getId)).forEach(item -> definitions.put(item.getId(), item));
        return new Snapshot(bundleHash, definitions);
    }

    public static void bind(ServerLevel level, Snapshot snapshot) {
        Objects.requireNonNull(level); Objects.requireNonNull(snapshot);
        Snapshot previous = WORLDS.putIfAbsent(level, snapshot);
        if (previous != null && (!previous.bundleHash.equals(snapshot.bundleHash) || !previous.definitions.equals(snapshot.definitions)))
            throw new IllegalStateException("A running world already owns different immutable item definitions");
    }
    public static void unbind(ServerLevel level) { WORLDS.remove(level); }
    public static void activateClient(Snapshot snapshot) { clientSnapshot = Objects.requireNonNull(snapshot); }
    public static void clearClient() { clientSnapshot = null; }
    public static Snapshot clientSnapshot() { return clientSnapshot; }
    public static Snapshot snapshot(Level level) { return level instanceof ServerLevel server ? WORLDS.get(server) : clientSnapshot; }

    /** A foreign or missing identity stays unresolved; its persisted name/model are never replaced by the new world's names. */
    public static CustomItemDefinition definition(Level level, ItemStack stack) {
        if (host == null || stack.getItem() != host) return null;
        WorldItemIdentity key = stack.get(identity);
        Snapshot current = snapshot(level);
        return key != null && current != null && current.bundleHash.equals(key.bundleHash()) ? current.definitions.get(key.itemId()) : null;
    }

    public static final class Snapshot {
        private final String bundleHash;
        private final Map<String, CustomItemDefinition> definitions;
        private Snapshot(String bundleHash, Map<String, CustomItemDefinition> definitions) {
            this.bundleHash = bundleHash;
            this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        }
        public String bundleHash() { return bundleHash; }
        public String scope() { return bundleHash; }
        public Map<String, CustomItemDefinition> definitions() { return definitions; }

        public CustomItemDefinition requireDefinition(String logicalId) {
            if (!isLogicalId(logicalId)) throw new IllegalArgumentException("Use a logical worldsmith:item/<id> reference, not a native item host: " + logicalId);
            String id = logicalId.substring(LOGICAL_PREFIX.length());
            if (!CustomItemValidation.validId(id)) throw new IllegalArgumentException("Invalid logical item reference: " + logicalId);
            CustomItemDefinition definition = definitions.get(id);
            if (definition == null) throw new IllegalArgumentException("Unknown item in immutable world " + bundleHash + ": " + logicalId);
            return definition;
        }
        public int maxStackSize(String logicalId) { return requireDefinition(logicalId).getMaxStackSize(); }

        /** No shared prototype or mutable Component escapes: each request constructs fresh canonical components. */
        public ItemStack stack(String logicalId, int count) {
            CustomItemDefinition definition = requireDefinition(logicalId);
            if (count < 1 || count > definition.getMaxStackSize()) throw new IllegalArgumentException("Item count exceeds its definition's stack limit: " + logicalId);
            ItemStack stack = new ItemStack(host(), count);
            stack.set(identityComponent(), new WorldItemIdentity(bundleHash, definition.getId()));
            stack.set(DataComponents.ITEM_NAME, Component.literal(definition.getDisplayName()));
            stack.set(DataComponents.MAX_STACK_SIZE, definition.getMaxStackSize());
            stack.set(DataComponents.RARITY, Rarity.valueOf(definition.getRarity().name()));
            stack.set(DataComponents.ITEM_MODEL, GeneratedItemResources.modelId(bundleHash, definition.getId()));
            List<Component> lines = definition.getDescription().isEmpty() ? List.of()
                : definition.getDescription().lines().map(line -> (Component)Component.literal(line)).toList();
            // A 2048-character description may contain more newlines than the native lore component accepts.
            if (lines.size() > ItemLore.MAX_LINES) throw new IllegalArgumentException("Item description exceeds the native lore line limit: " + logicalId);
            stack.set(DataComponents.LORE, new ItemLore(lines));
            return stack;
        }

        public boolean isCanonical(ItemStack stack) {
            if (host == null || stack.getItem() != host || stack.isEmpty()) return false;
            WorldItemIdentity key = stack.get(identity);
            if (key == null || !bundleHash.equals(key.bundleHash()) || !definitions.containsKey(key.itemId())) return false;
            var definition = definitions.get(key.itemId());
            return stack.getCount() <= definition.getMaxStackSize() && ItemStack.isSameItemSameComponents(stack, stack(key.logicalId(), stack.getCount()));
        }
    }

    /** Resource/relic v1 is inert: no food, equipment, tools or client-controlled consumable behavior. */
    private static final class ResourceItem extends Item {
        ResourceItem(Properties properties) { super(properties); }
        @Override public InteractionResult useOn(UseOnContext context) { return InteractionResult.PASS; }
        @Override public InteractionResult use(Level level, Player player, InteractionHand hand) { return InteractionResult.PASS; }
        @Override public float getDestroySpeed(ItemStack stack, BlockState state) { return 1.0F; }
        @Override public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) { return false; }
    }
}
