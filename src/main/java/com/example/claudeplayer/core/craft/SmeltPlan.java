package com.example.claudeplayer.core.craft;

import java.util.List;

/**
 * The arithmetic behind smelt: how much input goes into the furnace, and which fuel and how much of it. Burn
 * ticks are the vanilla values (coal 1600); 200 of them smelt one item in every furnace kind, because smokers and
 * blast furnaces cook twice as fast and burn fuel twice as fast. Ids are full ids ("minecraft:coal").
 */
public final class SmeltPlan {
    public static final int TICKS_PER_ITEM = 200;

    private SmeltPlan() {}

    /**
     * One kind of fuel in the inventory: its burn ticks, how many there are, and how many the fuel slot can still
     * take (0 when the slot holds something else).
     */
    public record Fuel(String id, int burnTicks, int count, int room, boolean planks, boolean log, boolean damageable) {}

    /**
     * The furnace's slots, in items: input already loaded (the same item), room left in the input slot, smelts the
     * output slot can still absorb (input already loaded included), and smelts the fuel slot already covers.
     */
    public record Furnace(int inputCount, int inputRoom, int outputRoom, int fuelSmelts) {}

    /**
     * Load {@code load} items and {@code fuelItems} of {@code fuelId} (null when the slot already has enough);
     * {@code total} items will then be smelting. {@code failure} is the reason code when nothing can be loaded.
     */
    public record Plan(int load, String fuelId, int fuelItems, int total, String failure) {
        static Plan failed(String reason) {
            return new Plan(0, null, 0, 0, reason);
        }
    }

    /** Fuel items needed for {@code smelts} items. */
    public static int itemsFor(int smelts, int burnTicks) {
        return (smelts * TICKS_PER_ITEM + burnTicks - 1) / burnTicks;
    }

    /** Whole items {@code items} fuel items smelt. */
    public static int smelts(int items, int burnTicks) {
        return items * burnTicks / TICKS_PER_ITEM;
    }

    /** Seconds until {@code total} items are done, at {@code cookTicks} each. */
    public static int seconds(int total, int cookTicks) {
        return (total * cookTicks + 19) / 20;
    }

    /** Lower burns first: coal and charcoal, then planks, then logs (worth four planks), then anything else. */
    public static int rank(Fuel f) {
        if (f.id().equals("minecraft:coal") || f.id().equals("minecraft:charcoal")) return 0;
        if (f.planks()) return 1;
        if (f.log()) return 2;
        return 3;
    }

    public static Plan plan(String input, int wanted, int have, Furnace furnace, List<Fuel> fuels) {
        if (have <= 0) return Plan.failed("missing:" + CraftRules.shortId(input) + "x" + wanted);
        int load = Math.min(Math.min(wanted, have), Math.min(furnace.inputRoom(), furnace.outputRoom() - furnace.inputCount()));
        if (load <= 0) return Plan.failed("furnace_busy");
        int need = furnace.inputCount() + load - furnace.fuelSmelts();
        if (need <= 0) return new Plan(load, null, 0, furnace.inputCount() + load, null);
        Fuel fuel = choose(fuels, input, need);
        int items = fuel == null ? 0 : usable(fuel, need);
        int covered = furnace.fuelSmelts() + (fuel == null ? 0 : smelts(items, fuel.burnTicks()));
        load = Math.min(load, covered - furnace.inputCount()); // short on fuel: load only what it will smelt
        if (load <= 0) return Plan.failed("no_fuel");
        return new Plan(load, fuel == null ? null : fuel.id(), items, furnace.inputCount() + load, null);
    }

    /**
     * The fuel to burn for {@code need} smelts, or null: never the item being smelted, nor tools, weapons or armor.
     * One that covers the whole need beats one that doesn't; then the lower rank wins.
     */
    static Fuel choose(List<Fuel> fuels, String input, int need) {
        Fuel best = null;
        int bestSmelts = 0;
        for (Fuel f : fuels) {
            if (f.id().equals(input) || f.damageable() || f.burnTicks() <= 0) continue;
            int s = smelts(usable(f, need), f.burnTicks());
            if (s <= 0) continue;
            if (best == null || better(f, s, best, bestSmelts, need)) {
                best = f;
                bestSmelts = s;
            }
        }
        return best;
    }

    private static boolean better(Fuel f, int smelts, Fuel best, int bestSmelts, int need) {
        boolean covers = smelts >= need;
        if (covers != bestSmelts >= need) return covers;
        if (!covers && smelts != bestSmelts) return smelts > bestSmelts;
        return rank(f) < rank(best);
    }

    private static int usable(Fuel f, int need) {
        return Math.min(itemsFor(need, f.burnTicks()), Math.min(f.count(), f.room()));
    }
}
