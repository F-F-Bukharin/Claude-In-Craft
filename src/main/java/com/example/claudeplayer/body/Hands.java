package com.example.claudeplayer.body;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Inventory moves for the block tools. Items only ever swap places; nothing is dropped or destroyed. */
final class Hands {
    private Hands() {}

    /** The main-inventory slot holding this item (short id such as "dirt" or full "minecraft:dirt"), or -1. */
    static int find(Inventory inv, String item) {
        String want = item.toLowerCase(Locale.ROOT);
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && (want.equals(SnapshotBuilder.itemId(s)) || want.equals(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()))) return i;
        }
        return -1;
    }

    /** Puts the stack in this slot in the main hand: selects it on the hotbar, or swaps it into the selected slot. */
    static void hold(Inventory inv, int slot) {
        if (Inventory.isHotbarSlot(slot)) {
            inv.selected = slot;
            return;
        }
        ItemStack held = inv.items.get(inv.selected);
        inv.items.set(inv.selected, inv.items.get(slot));
        inv.items.set(slot, held);
    }

    /** The slot of the item that digs this block best (one that makes it drop first, then the fastest); -1 if the held item is as good. */
    static int bestTool(Inventory inv, BlockState state) {
        int best = -1;
        ItemStack bestStack = inv.getSelected();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && better(s, bestStack, state)) {
                best = i;
                bestStack = s;
            }
        }
        return best;
    }

    private static boolean better(ItemStack a, ItemStack b, BlockState state) {
        boolean dropsA = a.isCorrectToolForDrops(state);
        boolean dropsB = b.isCorrectToolForDrops(state);
        if (dropsA != dropsB) return dropsA;
        return a.getDestroySpeed(state) > b.getDestroySpeed(state);
    }

    /** Item counts by short id, to report what a dig picked up. */
    static Map<String, Integer> counts(Inventory inv) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) counts.merge(SnapshotBuilder.itemId(s), s.getCount(), Integer::sum);
        }
        return counts;
    }
}
