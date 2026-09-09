package com.wjz.worldsmith.content;

import com.wjz.worldsmith.core.content.CustomBlockProfile;
import com.wjz.worldsmith.core.content.CustomBlockValidation;
import net.minecraft.core.Registry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded native hosts registered once at startup. Terrain blocks use no block entities. */
public final class WorldsmithCustomBlocks {
    public static final String HOST_ID_PREFIX = "worldsmith:content/block/";
    public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 15);
    private static final Map<String, Block> HOSTS = new LinkedHashMap<>();
    private static boolean initialized;

    private WorldsmithCustomBlocks() {}

    public static synchronized void initialize() {
        if (initialized) return;
        for (CustomBlockProfile profile : CustomBlockProfile.values()) {
            for (int slot = 0; slot < CustomBlockValidation.SLOTS_PER_PROFILE; slot++) {
                String path = "content/block/" + profile.name().toLowerCase(Locale.ROOT) + "/" + String.format(Locale.ROOT, "%02d", slot);
                Identifier id = Identifier.fromNamespaceAndPath("worldsmith", path);
                ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
                BlockBehaviour.Properties properties = properties(profile).setId(blockKey).lightLevel(state -> state.getValue(LIGHT));
                Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, new HostBlock(properties, id.toString(), profile));
                ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
                BlockItem item = new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix());
                item.registerBlocks(Item.BY_BLOCK, item);
                Registry.register(BuiltInRegistries.ITEM, itemKey, item);
                HOSTS.put(id.toString(), block);
            }
        }
        initialized = true;
    }

    private static BlockBehaviour.Properties properties(CustomBlockProfile profile) {
        return switch (profile) {
            case STONE -> BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(2.0F, 6.0F).sound(SoundType.STONE).requiresCorrectToolForDrops();
            case WOOD -> BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F, 3.0F).sound(SoundType.WOOD).ignitedByLava();
            case METAL -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F, 6.0F).sound(SoundType.METAL).requiresCorrectToolForDrops();
            case GLASS -> BlockBehaviour.Properties.of().mapColor(MapColor.NONE).strength(0.3F).sound(SoundType.GLASS).noOcclusion().isViewBlocking((state, level, pos) -> false);
        };
    }

    public static Block host(String nativeId) {
        Block block = HOSTS.get(nativeId);
        if (block == null) throw new IllegalStateException("Custom block host not registered: " + nativeId);
        return block;
    }

    public static Map<String, Block> hosts() { return Collections.unmodifiableMap(HOSTS); }

    /** Portable authoring documents must use logical aliases, never this implementation namespace. */
    public static boolean isReservedNativeId(String id) { return id != null && id.startsWith(HOST_ID_PREFIX); }

    private static final class HostBlock extends Block {
        private final String nativeId;
        private final CustomBlockProfile profile;
        HostBlock(Properties properties, String nativeId, CustomBlockProfile profile) {
            super(properties);
            this.nativeId = nativeId;
            this.profile = profile;
            registerDefaultState(stateDefinition.any().setValue(LIGHT, 0));
        }

        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(LIGHT); }

        @Override protected boolean skipRendering(BlockState state, BlockState neighbor, Direction direction) {
            return profile == CustomBlockProfile.GLASS && neighbor.is(this) || super.skipRendering(state, neighbor, direction);
        }

        @Override protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return profile == CustomBlockProfile.GLASS ? Shapes.empty() : super.getVisualShape(state, level, pos, context);
        }

        @Override protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
            return profile == CustomBlockProfile.GLASS ? 1.0F : super.getShadeBrightness(state, level, pos);
        }

        @Override protected boolean propagatesSkylightDown(BlockState state) {
            return profile == CustomBlockProfile.GLASS || super.propagatesSkylightDown(state);
        }

        @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
            // Placement is gated on the active world mapping. An unbound slot item is not a new species.
            Integer light = WorldBlockBindings.activeLight(nativeId);
            return light == null ? null : defaultBlockState().setValue(LIGHT, light);
        }
    }
}
