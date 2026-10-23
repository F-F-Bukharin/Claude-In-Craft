package com.example.claudeplayer.body;

import com.example.claudeplayer.core.act.Gear;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.common.ToolActions;
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

    /**
     * Before a fight (Gear picks): the best melee weapon into the main hand, the best piece into each armor slot
     * (the worn one swaps into its old slot; a binding curse stays on), a shield into an empty off-hand. Returns the
     * ids put on, for the report.
     */
    static List<String> gearUp(ServerPlayer p) {
        Inventory inv = p.getInventory();
        List<String> on = new ArrayList<>();
        List<Gear.Candidate> weapons = new ArrayList<>();
        weapons.add(weapon(inv.selected, inv.getSelected())); // the held item first: a tie keeps it
        for (int i = 0; i < inv.items.size(); i++) {
            Gear.Candidate c = weapon(i, inv.items.get(i));
            if (i != inv.selected && c.main() > 0) weapons.add(c);
        }
        int w = Gear.bestWeapon(weapons);
        if (w != inv.selected) {
            on.add(SnapshotBuilder.itemId(inv.items.get(w)));
            hold(inv, w);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.ARMOR) continue;
            ItemStack worn = p.getItemBySlot(slot);
            if (EnchantmentHelper.hasBindingCurse(worn)) continue;
            List<Gear.Candidate> pieces = new ArrayList<>();
            pieces.add(armor(Gear.WORN, worn, slot));
            for (int i = 0; i < inv.items.size(); i++) {
                ItemStack s = inv.items.get(i);
                if (!s.isEmpty() && LivingEntity.getEquipmentSlotForItem(s) == slot) pieces.add(armor(i, s, slot));
            }
            int a = Gear.bestArmor(pieces);
            if (a == Gear.WORN) continue;
            ItemStack piece = inv.items.get(a);
            inv.items.set(a, worn);
            p.setItemSlot(slot, piece);
            on.add(SnapshotBuilder.itemId(piece));
        }
        int shield = -1;
        for (int i = 0; i < inv.items.size() && shield < 0; i++) {
            if (inv.items.get(i).canPerformAction(ToolActions.SHIELD_BLOCK)) shield = i;
        }
        if (Gear.takesShield(p.getOffhandItem().isEmpty(), shield)) {
            ItemStack s = inv.items.get(shield);
            inv.items.set(shield, ItemStack.EMPTY);
            p.setItemSlot(EquipmentSlot.OFFHAND, s);
            on.add(SnapshotBuilder.itemId(s));
        }
        return on;
    }

    private static Gear.Candidate weapon(int slot, ItemStack s) {
        return new Gear.Candidate(slot, added(s, EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE),
                added(s, EquipmentSlot.MAINHAND, Attributes.ATTACK_SPEED));
    }

    private static Gear.Candidate armor(int slot, ItemStack s, EquipmentSlot wear) {
        return new Gear.Candidate(slot, added(s, wear, Attributes.ARMOR), added(s, wear, Attributes.ARMOR_TOUGHNESS));
    }

    /** The sum of the item's flat (addition) modifiers to one attribute when worn or held in {@code slot}. */
    private static double added(ItemStack s, EquipmentSlot slot, Attribute attribute) {
        if (s.isEmpty()) return 0;
        double total = 0;
        for (AttributeModifier m : s.getAttributeModifiers(slot).get(attribute)) {
            if (m.getOperation() == AttributeModifier.Operation.ADDITION) total += m.getAmount();
        }
        return total;
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
