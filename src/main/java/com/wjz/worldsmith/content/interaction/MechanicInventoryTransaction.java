package com.wjz.worldsmith.content.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Offers come only from the selected hand. Outputs use the 36 main slots, never ground drops. */
final class MechanicInventoryTransaction {
    interface Slots {
        ItemStack get(int slot);
        void set(int slot, ItemStack stack);
        int selected();
        int limit(ItemStack stack);
    }

    private final Slots slots;
    private final int selected;
    private final List<ItemStack> original = new ArrayList<>(36);
    private final List<ItemStack> planned = new ArrayList<>(36);

    MechanicInventoryTransaction(Inventory inventory) {
        this(new Slots() {
            public ItemStack get(int slot) { return inventory.getItem(slot); }
            public void set(int slot, ItemStack stack) { inventory.setItem(slot, stack); }
            public int selected() { return inventory.getSelectedSlot(); }
            public int limit(ItemStack stack) { return inventory.getMaxStackSize(stack); }
        });
    }

    MechanicInventoryTransaction(Slots slots) {
        this.slots = slots;
        selected = slots.selected();
        if (selected < 0 || selected >= 9) throw new IllegalArgumentException("Mechanic input requires a selected hotbar slot");
        for (int i = 0; i < 36; i++) {
            original.add(slots.get(i).copy());
            planned.add(slots.get(i).copy());
        }
    }

    boolean offer(Predicate<ItemStack> matches, int count) {
        if (count < 1 || count > 64) throw new IllegalArgumentException("Invalid offering count");
        ItemStack hand = planned.get(selected);
        if (hand.isEmpty() || hand.getCount() < count || !matches.test(hand)) return false;
        hand.shrink(count);
        if (hand.isEmpty()) planned.set(selected, ItemStack.EMPTY);
        return true;
    }

    boolean insert(ItemStack output) {
        ItemStack remaining = output.copy();
        for (int i = 0; i < 36 && !remaining.isEmpty(); i++) {
            ItemStack current = planned.get(i);
            if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, remaining)) continue;
            int take = Math.min(remaining.getCount(), Math.max(0, slots.limit(current) - current.getCount()));
            current.grow(take); remaining.shrink(take);
        }
        for (int i = 0; i < 36 && !remaining.isEmpty(); i++) {
            if (!planned.get(i).isEmpty()) continue;
            int take = Math.min(remaining.getCount(), slots.limit(remaining));
            if (take <= 0) continue;
            planned.set(i, remaining.copyWithCount(take)); remaining.shrink(take);
        }
        return remaining.isEmpty();
    }

    void assertUnchanged() {
        if (slots.selected() != selected) throw new IllegalStateException("Selected offering slot changed during mechanic planning");
        for (int i = 0; i < 36; i++) if (!ItemStack.matches(slots.get(i), original.get(i)))
            throw new IllegalStateException("Inventory changed during mechanic planning");
    }

    void apply() { for (int i = 0; i < 36; i++) slots.set(i, planned.get(i).copy()); }
    void rollback() { for (int i = 0; i < 36; i++) slots.set(i, original.get(i).copy()); }
}
