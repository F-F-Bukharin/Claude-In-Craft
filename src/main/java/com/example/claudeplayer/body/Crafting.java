package com.example.claudeplayer.body;

import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner.Handle;
import com.example.claudeplayer.core.craft.CraftRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * craft: picks a crafting recipe for the item that the inventory can fill, then crafts once every 0.25 s,
 * taking ingredients from the body's inventory and giving back the result and any remainders (empty buckets).
 * Which item fills which slot is {@link CraftRules}; this side reads recipes and moves stacks.
 */
final class Crafting {
    private static final double TABLE_REACH = 4.5;
    private static final int TICKS_PER_CRAFT = 5;

    private Crafting() {}

    static Handle start(AgentPlayer p, Action.Craft c) {
        Item item = item(c.item());
        if (item == null) return Handle.done(ActionResult.failed("unknown_item"));
        boolean table = tableNear(p);
        boolean needsTable = false;
        CraftRules.Allocation closest = null;
        for (CraftingRecipe recipe : p.serverLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack result = recipe.getResultItem(p.serverLevel().registryAccess());
            if (recipe.isSpecial() || result.isEmpty() || !result.is(item)) continue;
            CraftRules.Allocation a = allocate(p.getInventory(), recipe);
            if (a.complete() && (table || recipe.canCraftInDimensions(2, 2))) {
                return new CraftHandle(p, recipe, result, c.count());
            }
            if (a.complete()) needsTable = true;
            else if (closest == null || a.missingCount() < closest.missingCount()) closest = a;
        }
        if (needsTable) return Handle.done(ActionResult.failed("needs_crafting_table"));
        if (closest == null) return Handle.done(ActionResult.failed("no_recipe"));
        return Handle.done(ActionResult.failed(CraftRules.missing(closest.missing())));
    }

    /** "oak_planks" or "minecraft:oak_planks" to the item, or null when there is no such item. */
    static @Nullable Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(CraftRules.fullId(id));
        return key == null ? null : BuiltInRegistries.ITEM.getOptional(key).filter(i -> i != Items.AIR).orElse(null);
    }

    static String fullId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Removes up to {@code n} of the item from the main inventory, collecting craft remainders when asked to. */
    static int take(Inventory inv, Item item, int n, @Nullable List<ItemStack> remainders) {
        int left = n;
        for (ItemStack stack : inv.items) {
            if (left == 0) break;
            if (!stack.is(item)) continue;
            int k = Math.min(left, stack.getCount());
            if (remainders != null && stack.hasCraftingRemainingItem()) {
                for (int i = 0; i < k; i++) remainders.add(stack.getCraftingRemainingItem());
            }
            stack.shrink(k);
            left -= k;
        }
        inv.setChanged();
        return n - left;
    }

    private static boolean tableNear(AgentPlayer p) {
        BlockPos feet = p.blockPosition();
        int r = (int) Math.ceil(TABLE_REACH);
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
            if (p.position().distanceTo(Vec3.atCenterOf(pos)) <= TABLE_REACH
                    && p.serverLevel().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    private static CraftRules.Allocation allocate(Inventory inv, CraftingRecipe recipe) {
        Map<String, Integer> have = new LinkedHashMap<>();
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        for (ItemStack stack : inv.items) {
            if (stack.isEmpty()) continue;
            have.merge(fullId(stack), stack.getCount(), Integer::sum);
            samples.putIfAbsent(fullId(stack), stack);
        }
        List<CraftRules.Slot> slots = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue; // a gap in a shaped recipe
            List<String> accepts = samples.entrySet().stream().filter(e -> ingredient.test(e.getValue())).map(Map.Entry::getKey).toList();
            String label = Arrays.stream(ingredient.getItems()).findFirst().map(SnapshotBuilder::itemId).orElse("unknown");
            slots.add(new CraftRules.Slot(accepts, label));
        }
        return CraftRules.allocate(slots, have);
    }

    /** One craft every {@link #TICKS_PER_CRAFT} ticks until enough are made or the ingredients run out. */
    private static final class CraftHandle implements Handle {
        private final AgentPlayer p;
        private final CraftingRecipe recipe;
        private final ItemStack result;
        private final int wanted;
        private final int crafts;
        private final Map<String, Integer> used = new LinkedHashMap<>();
        private int done;
        private int dropped;
        private int ticks;

        CraftHandle(AgentPlayer p, CraftingRecipe recipe, ItemStack result, int wanted) {
            this.p = p;
            this.recipe = recipe;
            this.result = result.copy();
            this.wanted = wanted;
            this.crafts = CraftRules.craftsFor(wanted, result.getCount());
        }

        @Override
        public ActionResult poll() {
            if (++ticks < TICKS_PER_CRAFT) return null;
            ticks = 0;
            CraftRules.Allocation a = allocate(p.getInventory(), recipe);
            if (!a.complete()) {
                return done == 0 ? ActionResult.failed(CraftRules.missing(a.missing())) : ActionResult.ok(summary(true));
            }
            List<ItemStack> out = new ArrayList<>();
            a.used().forEach((id, n) -> take(p.getInventory(), item(id), n, out));
            a.used().forEach((id, n) -> used.merge(id, n, Integer::sum));
            ItemStack made = result.copy();
            made.onCraftedBy(p.serverLevel(), p, made.getCount());
            out.add(0, made);
            for (ItemStack stack : out) {
                p.getInventory().add(stack); // shrinks the stack by what fit
                if (!stack.isEmpty()) {
                    if (stack.is(result.getItem())) dropped += stack.getCount();
                    p.drop(stack, false);
                }
            }
            return ++done >= crafts ? ActionResult.ok(summary(false)) : null;
        }

        @Override
        public int timeoutTicks() {
            return crafts * TICKS_PER_CRAFT + 20;
        }

        private String summary(boolean ranOut) {
            return CraftRules.summary(SnapshotBuilder.itemId(result), done * result.getCount(), wanted, ranOut, used, dropped);
        }
    }
}
