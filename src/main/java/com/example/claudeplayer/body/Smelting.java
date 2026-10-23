package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.craft.SmeltPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;

/**
 * smelt: loads the nearest furnace, smoker or blast furnace that can cook the item with the input and enough fuel
 * from the inventory, then returns at once; the furnace does the rest and take collects the output. How much goes
 * in and which fuel burns is {@link SmeltPlan}; this side reads the slots and moves stacks.
 */
final class Smelting {
    private static final int SLOT_INPUT = 0, SLOT_FUEL = 1, SLOT_RESULT = 2;

    private Smelting() {}

    static ActionResult load(AgentRuntime rt, AgentPlayer p, Action.Smelt s) {
        Item item = Crafting.item(s.item());
        if (item == null) return ActionResult.failed("unknown_item");
        List<BlockPos> furnaces = furnacesNear(p);
        if (furnaces.isEmpty()) return ActionResult.failed("no_furnace");
        ActionResult busy = null;
        for (BlockPos pos : furnaces) {
            AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) p.serverLevel().getBlockEntity(pos);
            RecipeType<? extends AbstractCookingRecipe> type = furnace instanceof SmokerBlockEntity ? RecipeType.SMOKING
                    : furnace instanceof BlastFurnaceBlockEntity ? RecipeType.BLASTING : RecipeType.SMELTING;
            Optional<? extends AbstractCookingRecipe> recipe =
                    p.serverLevel().getRecipeManager().getRecipeFor(type, new SimpleContainer(new ItemStack(item)), p.serverLevel());
            if (recipe.isEmpty()) continue;
            ActionResult result = load(rt, p, s, item, pos, furnace, type, recipe.get());
            if (!"furnace_busy".equals(result.reason())) return result;
            busy = result; // try the next furnace that can cook it
        }
        return busy != null ? busy : ActionResult.failed("cant_smelt");
    }

    private static ActionResult load(AgentRuntime rt, AgentPlayer p, Action.Smelt s, Item item, BlockPos pos,
                                     AbstractFurnaceBlockEntity furnace, RecipeType<?> type, AbstractCookingRecipe recipe) {
        ItemStack in = furnace.getItem(SLOT_INPUT), fuel = furnace.getItem(SLOT_FUEL), out = furnace.getItem(SLOT_RESULT);
        ItemStack result = recipe.getResultItem(p.serverLevel().registryAccess());
        if (!in.isEmpty() && !in.is(item)) return ActionResult.failed("furnace_busy");
        if (!out.isEmpty() && !ItemStack.isSameItemSameTags(out, result)) return ActionResult.failed("furnace_busy");
        int inputCount = in.getCount(); // 0 when empty
        int inputRoom = Math.min(new ItemStack(item).getMaxStackSize(), furnace.getMaxStackSize()) - inputCount;
        int outputRoom = (result.getMaxStackSize() - out.getCount()) / result.getCount();
        int fuelSmelts = fuel.isEmpty() ? 0 : SmeltPlan.smelts(fuel.getCount(), ForgeHooks.getBurnTime(fuel, type));
        Inventory inv = p.getInventory();
        SmeltPlan.Plan plan = SmeltPlan.plan(Crafting.fullId(new ItemStack(item)), s.count(), count(inv, item),
                new SmeltPlan.Furnace(inputCount, inputRoom, outputRoom, fuelSmelts), fuels(inv, fuel, type));
        if (plan.failure() != null) return ActionResult.failed(plan.failure());

        Crafting.take(inv, item, plan.load(), null);
        if (in.isEmpty()) furnace.setItem(SLOT_INPUT, new ItemStack(item, plan.load()));
        else in.grow(plan.load());
        if (plan.fuelId() != null) {
            Item fuelItem = Crafting.item(plan.fuelId());
            Crafting.take(inv, fuelItem, plan.fuelItems(), null);
            if (fuel.isEmpty()) furnace.setItem(SLOT_FUEL, new ItemStack(fuelItem, plan.fuelItems()));
            else fuel.grow(plan.fuelItems());
        }
        furnace.setChanged();

        String at = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        String output = SnapshotBuilder.itemId(result);
        int ready = out.getCount() + plan.total() * result.getCount();
        String text = SnapshotBuilder.blockId(p.serverLevel().getBlockState(pos)) + " at " + at + ": smelting " + plan.total()
                + " " + SnapshotBuilder.itemId(new ItemStack(item)) + ", ready in about " + SmeltPlan.seconds(plan.total(), recipe.getCookingTime())
                + " s; collect with take(" + at + ",\"" + output + "\"," + ready + ")"
                + (out.isEmpty() ? "" : "; " + out.getCount() + " " + output + " already done");
        rt.memory().note("furnace@" + at, text);
        return ActionResult.ok(text);
    }

    /** Furnace kinds within reach, nearest first. */
    private static List<BlockPos> furnacesNear(AgentPlayer p) {
        BlockPos feet = p.blockPosition();
        int r = (int) Math.ceil(ActionExecutor.CONTAINER_REACH);
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
            if (p.position().distanceTo(Vec3.atCenterOf(pos)) > ActionExecutor.CONTAINER_REACH) continue;
            if (p.serverLevel().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity) found.add(pos.immutable());
        }
        found.sort(Comparator.comparingDouble(pos -> p.position().distanceTo(Vec3.atCenterOf(pos))));
        return found;
    }

    private static int count(Inventory inv, Item item) {
        return inv.items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    /** Every burnable kind in the main inventory, with the room the fuel slot has for it. */
    private static List<SmeltPlan.Fuel> fuels(Inventory inv, ItemStack slot, RecipeType<?> type) {
        Map<Item, ItemStack> kinds = new LinkedHashMap<>();
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : inv.items) {
            if (stack.isEmpty()) continue;
            kinds.putIfAbsent(stack.getItem(), stack);
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        List<SmeltPlan.Fuel> fuels = new ArrayList<>();
        kinds.forEach((item, stack) -> {
            int room = slot.isEmpty() ? stack.getMaxStackSize() : slot.is(item) ? slot.getMaxStackSize() - slot.getCount() : 0;
            fuels.add(new SmeltPlan.Fuel(Crafting.fullId(stack), ForgeHooks.getBurnTime(stack, type), counts.get(item), room,
                    stack.is(ItemTags.PLANKS), stack.is(ItemTags.LOGS), stack.isDamageableItem()));
        });
        return fuels;
    }
}
