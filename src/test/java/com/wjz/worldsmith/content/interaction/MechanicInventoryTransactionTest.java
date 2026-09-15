package com.wjz.worldsmith.content.interaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MechanicInventoryTransactionTest {
    @BeforeAll static void bootstrap() { MechanicTestBootstrap.initialize(); }
    private static final class Inventory implements MechanicInventoryTransaction.Slots {
        final List<ItemStack> items = new ArrayList<>(Collections.nCopies(36, ItemStack.EMPTY));
        int selected;
        public ItemStack get(int slot) { return items.get(slot); }
        public void set(int slot, ItemStack stack) { items.set(slot, stack); }
        public int selected() { return selected; }
        public int limit(ItemStack stack) { return stack.getMaxStackSize(); }
    }

    @Test void inputComesOnlyFromSelectedHandAndIsNotChargedDuringPlanning() {
        var inventory = new Inventory(); inventory.set(0, new ItemStack(Items.EMERALD, 2)); inventory.set(1, new ItemStack(Items.EMERALD, 64));
        var transaction = new MechanicInventoryTransaction(inventory);
        assertFalse(transaction.offer(stack -> stack.is(Items.EMERALD), 3));
        assertEquals(2, inventory.get(0).getCount()); assertEquals(64, inventory.get(1).getCount());
        assertTrue(transaction.offer(stack -> stack.is(Items.EMERALD), 2));
        assertEquals(2, inventory.get(0).getCount());
        assertTrue(transaction.insert(new ItemStack(Items.DIAMOND, 1)));
        transaction.apply(); assertTrue(inventory.get(0).is(Items.DIAMOND)); assertEquals(64, inventory.get(1).getCount());
    }

    @Test void fullInventoryCanReuseTheSlotFreedByTheOffering() {
        var inventory = new Inventory();
        for (int i = 0; i < 36; i++) inventory.set(i, new ItemStack(Items.COBBLESTONE, 64));
        inventory.set(0, new ItemStack(Items.EMERALD, 3));
        var transaction = new MechanicInventoryTransaction(inventory);
        assertTrue(transaction.offer(stack -> stack.is(Items.EMERALD), 3));
        assertTrue(transaction.insert(new ItemStack(Items.DIAMOND, 1)));
        transaction.assertUnchanged(); transaction.apply();
        assertTrue(inventory.get(0).is(Items.DIAMOND)); assertEquals(1, inventory.get(0).getCount());
    }

    @Test void insufficientOutputRoomPreservesEveryOriginalItem() {
        var inventory = new Inventory();
        for (int i = 0; i < 36; i++) inventory.set(i, new ItemStack(Items.COBBLESTONE, 64));
        inventory.set(0, new ItemStack(Items.EMERALD, 4));
        var transaction = new MechanicInventoryTransaction(inventory);
        assertTrue(transaction.offer(stack -> stack.is(Items.EMERALD), 3));
        assertFalse(transaction.insert(new ItemStack(Items.DIAMOND, 1)));
        assertEquals(4, inventory.get(0).getCount());
        assertTrue(inventory.get(1).is(Items.COBBLESTONE));
    }

    @Test void inventoryAndSelectedSlotChangesInvalidateThePlan() {
        var inventory = new Inventory(); inventory.set(0, new ItemStack(Items.EMERALD, 4));
        var transaction = new MechanicInventoryTransaction(inventory);
        inventory.selected = 1; assertThrows(IllegalStateException.class, transaction::assertUnchanged);
        inventory.selected = 0; inventory.get(0).shrink(1); assertThrows(IllegalStateException.class, transaction::assertUnchanged);
    }

    @Test void rollbackRestoresIndependentCopiesAfterApplyingMultipleOutputs() {
        var inventory = new Inventory(); inventory.set(0, new ItemStack(Items.EMERALD, 4));
        var transaction = new MechanicInventoryTransaction(inventory);
        assertTrue(transaction.offer(stack -> stack.is(Items.EMERALD), 2));
        assertTrue(transaction.insert(new ItemStack(Items.DIAMOND, 3))); assertTrue(transaction.insert(new ItemStack(Items.IRON_SWORD, 1)));
        transaction.apply(); assertEquals(2, inventory.get(0).getCount());
        transaction.rollback(); assertEquals(4, inventory.get(0).getCount()); assertTrue(inventory.get(1).isEmpty());
        inventory.get(0).shrink(1); transaction.rollback(); assertEquals(4, inventory.get(0).getCount());
    }

    @Test void outputsRespectNativeItemStackLimits() {
        var inventory = new Inventory(); var transaction = new MechanicInventoryTransaction(inventory);
        assertTrue(transaction.insert(new ItemStack(Items.IRON_SWORD, 2))); transaction.apply();
        assertEquals(1, inventory.get(0).getCount()); assertEquals(1, inventory.get(1).getCount());
    }
}
