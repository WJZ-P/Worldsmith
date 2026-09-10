package com.wjz.worldsmith.content.quest.server;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** A local simulation of slots 0..35 only: no cursor, equipment, offhand, crafting grid or ground drops. */
final class QuestInventoryTransaction {
    private static final int MAIN_SLOTS = 36;
    private final Inventory inventory;
    private final List<ItemStack> original;
    private final List<ItemStack> planned;

    QuestInventoryTransaction(ServerPlayer player) {
        inventory = player.getInventory();
        if (inventory.getNonEquipmentItems().size() < MAIN_SLOTS) throw new IllegalStateException("Quest actions require a 36-slot main inventory");
        original = new ArrayList<>(MAIN_SLOTS); planned = new ArrayList<>(MAIN_SLOTS);
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            original.add(stack.copy()); planned.add(stack.copy());
        }
    }

    int consume(Predicate<ItemStack> matches, int maximum) {
        int remaining = maximum;
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            ItemStack stack = planned.get(slot);
            if (stack.isEmpty() || !matches.test(stack)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take); remaining -= take;
            if (stack.isEmpty()) planned.set(slot, ItemStack.EMPTY);
        }
        return maximum - remaining;
    }

    boolean insert(ItemStack reward) {
        ItemStack remaining = reward.copy();
        for (int slot = 0; slot < MAIN_SLOTS && !remaining.isEmpty(); slot++) {
            ItemStack existing = planned.get(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) continue;
            int amount = Math.min(remaining.getCount(), Math.max(0, inventory.getMaxStackSize(existing) - existing.getCount()));
            existing.grow(amount); remaining.shrink(amount);
        }
        for (int slot = 0; slot < MAIN_SLOTS && !remaining.isEmpty(); slot++) {
            if (!planned.get(slot).isEmpty()) continue;
            int amount = Math.min(remaining.getCount(), inventory.getMaxStackSize(remaining));
            if (amount <= 0) continue;
            planned.set(slot, remaining.copyWithCount(amount)); remaining.shrink(amount);
        }
        return remaining.isEmpty();
    }

    void assertUnchanged() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            if (!ItemStack.matches(original.get(slot), inventory.getItem(slot)))
                throw new IllegalStateException("Main inventory changed while preparing a quest action");
        }
    }

    void apply() { for (int slot = 0; slot < MAIN_SLOTS; slot++) inventory.setItem(slot, planned.get(slot).copy()); }
    void rollback() { for (int slot = 0; slot < MAIN_SLOTS; slot++) inventory.setItem(slot, original.get(slot).copy()); }
}
