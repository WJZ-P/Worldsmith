package com.wjz.worldsmith.content;

import com.wjz.worldsmith.content.item.CustomItemRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** One explicit compilation/definition context for both creature and structure rewards. */
public final class WorldRewardItems {
    private WorldRewardItems() {}

    public static ItemStack stack(String reference, int count, WorldBlockBindings.Resolver blocks, CustomItemRuntime.Snapshot items) {
        if (reference == null || count < 1 || count > 64) throw new IllegalArgumentException("Reward item references and counts must be bounded");
        if (blocks != null && items != null && !blocks.snapshot().getScope().equals(items.bundleHash()))
            throw new IllegalArgumentException("Reward block and item resolvers belong to different immutable worlds");
        if (WorldsmithCustomBlocks.isReservedNativeId(reference) || CustomItemRuntime.isReservedNativeId(reference))
            throw new IllegalArgumentException("Rewards use logical world references, never reserved native hosts: " + reference);
        ItemStack stack;
        if (reference.startsWith("worldsmith:item/")) {
            if (items == null) throw new IllegalArgumentException("Custom item rewards require an explicit immutable world-item resolver: " + reference);
            stack = items.stack(reference, count);
        } else if (reference.startsWith("worldsmith:content/")) {
            if (blocks == null) throw new IllegalArgumentException("Custom block-item rewards require an explicit immutable world-block resolver: " + reference);
            stack = new ItemStack(blocks.resolveItem(reference), count);
        } else {
            Identifier id = Identifier.tryParse(reference);
            var item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null || item == Items.AIR) throw new IllegalArgumentException("Unknown or empty reward item: " + reference);
            stack = new ItemStack(item, count);
        }
        if (stack.isEmpty() || count > stack.getMaxStackSize()) throw new IllegalArgumentException("Reward count exceeds this item's actual stack limit: " + reference);
        return stack;
    }
}
