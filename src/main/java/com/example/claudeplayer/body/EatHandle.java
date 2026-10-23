package com.example.claudeplayer.body;

import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.safety.Hunger;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

/**
 * eat: swaps the best food into the selected hotbar slot and eats it like a player, through vanilla item use when
 * the body ticks it, otherwise by finishing the item after the same 1.6 s. The swapped slots go back afterwards,
 * also on cancel, so nothing is lost. {@link Hunger} picks the food.
 */
public final class EatHandle implements ActionRunner.Handle {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int GRACE_TICKS = 10; // vanilla use still running this long after its duration: do it by hand

    private final AgentPlayer body;
    private final boolean auto;
    private final int slot;
    private final int selected;
    private final String item;
    private final int duration;
    private final int foodBefore;
    private boolean vanilla;
    private int ticks;

    private EatHandle(AgentPlayer body, int slot, boolean auto) {
        this.body = body;
        this.auto = auto;
        this.slot = slot;
        Inventory inv = body.getInventory();
        this.selected = inv.selected;
        swap();
        ItemStack food = inv.getItem(selected);
        this.item = SnapshotBuilder.itemId(food);
        this.duration = food.getUseDuration();
        this.foodBefore = body.getFoodData().getFoodLevel();
        body.stopUsingItem();
        body.startUsingItem(InteractionHand.MAIN_HAND);
        this.vanilla = body.isUsingItem();
    }

    /** {@code failed:not_hungry} when food is full, {@code failed:no_food} with nothing edible in the inventory. */
    public static ActionRunner.Handle start(AgentPlayer body, boolean auto) {
        if (body.getFoodData().getFoodLevel() >= Hunger.FULL) return ActionRunner.Handle.done(ActionResult.failed("not_hungry"));
        int slot = Hunger.best(foods(body));
        if (slot < 0) return ActionRunner.Handle.done(ActionResult.failed("no_food"));
        return new EatHandle(body, slot, auto);
    }

    /** Every food in the main inventory (hotbar included), for {@link Hunger}. */
    public static List<Hunger.Food> foods(AgentPlayer body) {
        List<Hunger.Food> foods = new ArrayList<>();
        Inventory inv = body.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            FoodProperties props = stack.isEdible() ? stack.getFoodProperties(body) : null;
            if (props != null) foods.add(new Hunger.Food(i, props.getNutrition(), props.getSaturationModifier(), kind(stack, props)));
        }
        return foods;
    }

    private static Hunger.Kind kind(ItemStack stack, FoodProperties props) {
        // chorus fruit teleports; a suspicious stew's effect is hidden
        if (stack.is(Items.CHORUS_FRUIT) || stack.is(Items.SUSPICIOUS_STEW)) return Hunger.Kind.HARMFUL;
        if (props.getEffects().isEmpty()) return Hunger.Kind.PLAIN;
        boolean harmful = props.getEffects().stream().anyMatch(e -> e.getFirst().getEffect().getCategory() == MobEffectCategory.HARMFUL);
        return harmful ? Hunger.Kind.HARMFUL : Hunger.Kind.SPECIAL;
    }

    @Override
    public ActionResult poll() {
        ticks++;
        if (!body.isAlive()) {
            swap();
            return ActionResult.failed("died");
        }
        if (vanilla && body.isUsingItem()) {
            if (ticks <= duration + GRACE_TICKS) return null;
            body.stopUsingItem(); // the body isn't ticking item use: finish it by hand
            vanilla = false;
        }
        if (!vanilla) {
            if (ticks < duration) return null;
            ItemStack held = body.getInventory().getItem(selected);
            if (held.isEdible()) body.getInventory().setItem(selected, held.finishUsingItem(body.level(), body)); // bowls and bottles come back
        }
        swap();
        int food = body.getFoodData().getFoodLevel();
        if (food <= foodBefore) return ActionResult.failed("interrupted");
        if (auto) LOG.info("auto-eat {} food {}->{}", item, foodBefore, food);
        return ActionResult.ok("ate " + item + "; food " + food + "/" + Hunger.FULL);
    }

    @Override
    public void cancel() {
        body.stopUsingItem();
        swap();
    }

    @Override
    public int timeoutTicks() {
        return duration + GRACE_TICKS + 20;
    }

    /** Exchanges the food's slot with the selected one; called once to start and once to put things back. */
    private void swap() {
        if (slot == selected) return;
        Inventory inv = body.getInventory();
        ItemStack held = inv.getItem(selected);
        inv.setItem(selected, inv.getItem(slot));
        inv.setItem(slot, held);
    }
}
