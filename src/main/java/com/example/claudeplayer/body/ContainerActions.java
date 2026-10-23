package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.safety.WorldText;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * inspect_container, store and take, on containers within reach (a double chest counts as one). Each refreshes
 * the container's short-term note, so the model keeps an exact picture of what is inside.
 */
final class ContainerActions {
    private static final double REACH = 6.0;
    private static final int FURNACE_RESULT_SLOT = 2;

    private final AgentRuntime rt;

    ContainerActions(AgentRuntime rt) {
        this.rt = rt;
    }

    /** Reads a container without opening it, and keeps the exact contents as a short-term note. */
    ActionResult inspect(AgentPlayer p, BlockPos pos) {
        if (!inReach(p, pos)) return ActionResult.failed("too_far");
        if (p.serverLevel().getBlockState(pos).is(Blocks.ENDER_CHEST)) return ActionResult.failed("ender_chest_personal"); // each player sees their own
        Container container = find(p, pos);
        if (container == null) return ActionResult.failed("not_a_container");
        return ActionResult.ok(note(p, pos, container));
    }

    /** Inventory to container; all skips the held item, food and worn armor (armor isn't in the main inventory). */
    ActionResult store(AgentPlayer p, Action.Store s) {
        BlockPos pos = new BlockPos(s.x(), s.y(), s.z());
        if (!inReach(p, pos)) return ActionResult.failed("out_of_reach");
        Container container = find(p, pos);
        if (container == null) return ActionResult.failed("not_a_container");
        boolean all = Action.ALL.equals(s.item());
        Inventory inv = p.getInventory();
        Map<String, Integer> moved = new LinkedHashMap<>();
        int left = all ? Integer.MAX_VALUE : s.count();
        boolean found = false;
        boolean full = false;
        for (int i = 0; i < inv.items.size() && left > 0; i++) {
            ItemStack stack = inv.items.get(i);
            if (stack.isEmpty() || (all ? i == inv.selected || stack.isEdible() : !SnapshotBuilder.itemId(stack).equals(s.item()))) continue;
            found = true;
            String id = SnapshotBuilder.itemId(stack);
            int want = Math.min(left, stack.getCount());
            int n = insert(container, stack, want);
            if (n < want) full = true;
            if (n == 0) continue;
            left -= n;
            moved.merge(id, n, Integer::sum);
        }
        if (!found) return ActionResult.failed("missing_item");
        if (moved.isEmpty()) return ActionResult.failed("container_full");
        container.setChanged();
        inv.setChanged();
        note(p, pos, container);
        return ActionResult.ok("stored " + listed(moved) + " in " + where(p, pos) + (full ? "; container full" : ""));
    }

    /** Container to inventory, from any slot (a furnace's output too, with its experience, as a player gets). */
    ActionResult take(AgentPlayer p, Action.Take t) {
        BlockPos pos = new BlockPos(t.x(), t.y(), t.z());
        if (!inReach(p, pos)) return ActionResult.failed("out_of_reach");
        Container container = find(p, pos);
        if (container == null) return ActionResult.failed("not_a_container");
        boolean all = Action.ALL.equals(t.item());
        Inventory inv = p.getInventory();
        Map<String, Integer> moved = new LinkedHashMap<>();
        int left = all ? Integer.MAX_VALUE : t.count();
        boolean found = false;
        boolean full = false;
        boolean smelted = false;
        for (int i = 0; i < container.getContainerSize() && left > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || (!all && !SnapshotBuilder.itemId(stack).equals(t.item()))) continue;
            found = true;
            String id = SnapshotBuilder.itemId(stack);
            int want = Math.min(left, stack.getCount());
            ItemStack part = stack.copyWithCount(want);
            inv.add(part); // leaves in part what didn't fit
            int n = want - part.getCount();
            if (n < want) full = true;
            if (n == 0) continue;
            container.removeItem(i, n);
            if (i == FURNACE_RESULT_SLOT && container instanceof AbstractFurnaceBlockEntity) smelted = true;
            left -= n;
            moved.merge(id, n, Integer::sum);
        }
        if (!found) return ActionResult.failed("missing_item");
        if (moved.isEmpty()) return ActionResult.failed("inventory_full");
        if (smelted) ((AbstractFurnaceBlockEntity) container).awardUsedRecipesAndPopExperience(p);
        container.setChanged();
        inv.setChanged();
        note(p, pos, container);
        return ActionResult.ok("took " + listed(moved) + " from " + where(p, pos) + (full ? "; inventory full" : ""));
    }

    private static boolean inReach(AgentPlayer p, BlockPos pos) {
        return p.position().distanceTo(Vec3.atCenterOf(pos)) <= REACH;
    }

    private static @Nullable Container find(AgentPlayer p, BlockPos pos) {
        BlockState state = p.serverLevel().getBlockState(pos);
        BlockEntity be = p.serverLevel().getBlockEntity(pos);
        return state.getBlock() instanceof ChestBlock chest
                ? ChestBlock.getContainer(chest, state, p.serverLevel(), pos, true) // both halves of a double chest
                : be instanceof Container c ? c : null;
    }

    /** Moves up to {@code max} of {@code stack} into the container: onto matching stacks first, then empty slots. */
    private static int insert(Container container, ItemStack stack, int max) {
        int moved = 0;
        int limit = Math.min(container.getMaxStackSize(), stack.getMaxStackSize());
        for (int pass = 0; pass < 2 && moved < max; pass++) {
            for (int i = 0; i < container.getContainerSize() && moved < max; i++) {
                ItemStack there = container.getItem(i);
                boolean fits = pass == 0 ? !there.isEmpty() && ItemStack.isSameItemSameTags(there, stack) : there.isEmpty();
                if (!fits || !container.canPlaceItem(i, stack)) continue;
                int n = Math.min(max - moved, limit - there.getCount());
                if (n <= 0) continue;
                if (there.isEmpty()) container.setItem(i, stack.copyWithCount(n));
                else there.grow(n);
                stack.shrink(n);
                moved += n;
            }
        }
        return moved;
    }

    /** "chest at 10, 64, 5" (with its custom name, cleaned). */
    private static String where(AgentPlayer p, BlockPos pos) {
        BlockEntity be = p.serverLevel().getBlockEntity(pos);
        String name = be instanceof Nameable n && n.hasCustomName()
                ? " \"" + WorldText.clean(n.getCustomName().getString(), WorldText.NAME_MAX) + "\"" : "";
        return SnapshotBuilder.blockId(p.serverLevel().getBlockState(pos)) + name + " at " + pos.toShortString();
    }

    private static String listed(Map<String, Integer> counts) {
        return counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
    }

    /** Notes the exact contents under {@code container@x,y,z} and returns the same summary. */
    private String note(AgentPlayer p, BlockPos pos, Container container) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int used = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            used++;
            counts.merge(SnapshotBuilder.itemId(stack), stack.getCount(), Integer::sum);
        }
        String summary = where(p, pos) + ": " + (counts.isEmpty() ? "empty" : listed(counts))
                + " (" + used + "/" + container.getContainerSize() + " slots used)";
        rt.memory().note("container@" + pos.getX() + "," + pos.getY() + "," + pos.getZ(), summary);
        return summary;
    }
}
