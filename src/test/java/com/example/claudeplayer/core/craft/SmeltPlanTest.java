package com.example.claudeplayer.core.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Smelt planning: fuel arithmetic, which fuel burns, and how much input goes in. */
class SmeltPlanTest {
    private static final String IRON = "minecraft:raw_iron";
    private static final SmeltPlan.Furnace EMPTY = new SmeltPlan.Furnace(0, 64, 64, 0);

    private static SmeltPlan.Fuel coal(int count) {
        return new SmeltPlan.Fuel("minecraft:coal", 1600, count, 64, false, false, false);
    }

    private static SmeltPlan.Fuel planks(int count) {
        return new SmeltPlan.Fuel("minecraft:oak_planks", 300, count, 64, true, false, false);
    }

    private static SmeltPlan.Fuel log(String id, int count) {
        return new SmeltPlan.Fuel(id, 300, count, 64, false, true, false);
    }

    @Test
    void fuelItemsRoundUpAndSmeltsRoundDown() {
        assertEquals(1, SmeltPlan.itemsFor(8, 1600));
        assertEquals(2, SmeltPlan.itemsFor(9, 1600));
        assertEquals(6, SmeltPlan.itemsFor(8, 300), "a plank smelts 1.5 items");
        assertEquals(1, SmeltPlan.smelts(1, 300));
        assertEquals(3, SmeltPlan.smelts(2, 300));
        assertEquals(0, SmeltPlan.smelts(1, 100), "half a smelt is no smelt");
        assertEquals(80, SmeltPlan.seconds(8, 200));
        assertEquals(40, SmeltPlan.seconds(8, 100));
    }

    @Test
    void coalBeatsPlanksBeatsLogsBeatsTheRest() {
        SmeltPlan.Fuel stick = new SmeltPlan.Fuel("minecraft:stick", 100, 64, 64, false, false, false);
        assertEquals(0, SmeltPlan.rank(coal(1)));
        assertEquals(0, SmeltPlan.rank(new SmeltPlan.Fuel("minecraft:charcoal", 1600, 1, 64, false, false, false)));
        assertEquals(1, SmeltPlan.rank(planks(1)));
        assertEquals(2, SmeltPlan.rank(log("minecraft:oak_log", 1)));
        assertEquals(3, SmeltPlan.rank(stick));
        assertEquals("minecraft:coal", SmeltPlan.choose(List.of(stick, log("minecraft:oak_log", 64), planks(64), coal(5)), IRON, 8).id());
    }

    @Test
    void fuelThatCoversTheWholeJobBeatsABetterRankThatDoesnt() {
        assertEquals("minecraft:oak_planks", SmeltPlan.choose(List.of(coal(1), planks(64)), IRON, 16).id());
        assertEquals("minecraft:coal", SmeltPlan.choose(List.of(coal(1), planks(4)), IRON, 16).id(), "neither covers: most smelts");
    }

    @Test
    void neverBurnsTheInputToolsOrAFullFuelSlot() {
        SmeltPlan.Fuel pickaxe = new SmeltPlan.Fuel("minecraft:wooden_pickaxe", 200, 1, 64, false, false, true);
        SmeltPlan.Fuel blocked = new SmeltPlan.Fuel("minecraft:coal", 1600, 10, 0, false, false, false);
        assertNull(SmeltPlan.choose(List.of(log("minecraft:oak_log", 10), pickaxe, blocked), "minecraft:oak_log", 4));
    }

    @Test
    void loadsTheInputWithJustEnoughFuel() {
        SmeltPlan.Plan p = SmeltPlan.plan(IRON, 8, 20, EMPTY, List.of(planks(64), coal(5)));
        assertEquals(new SmeltPlan.Plan(8, "minecraft:coal", 1, 8, null), p);
    }

    @Test
    void fuelAlreadyInTheSlotCountsAndInputAlreadyInTheSlotNeedsFuelToo() {
        assertEquals(new SmeltPlan.Plan(8, null, 0, 8, null),
                SmeltPlan.plan(IRON, 8, 8, new SmeltPlan.Furnace(0, 64, 64, 8), List.of()));
        assertEquals(new SmeltPlan.Plan(8, "minecraft:coal", 1, 12, null),
                SmeltPlan.plan(IRON, 8, 8, new SmeltPlan.Furnace(4, 60, 64, 8), List.of(coal(3))));
    }

    @Test
    void shortOnFuelLoadsOnlyWhatWillSmelt() {
        assertEquals(new SmeltPlan.Plan(3, "minecraft:oak_planks", 2, 3, null), SmeltPlan.plan(IRON, 8, 8, EMPTY, List.of(planks(2))));
    }

    @Test
    void failuresNameTheReason() {
        assertEquals("missing:raw_ironx8", SmeltPlan.plan(IRON, 8, 0, EMPTY, List.of(coal(1))).failure());
        assertEquals("no_fuel", SmeltPlan.plan(IRON, 8, 8, EMPTY, List.of()).failure());
        assertEquals("furnace_busy", SmeltPlan.plan(IRON, 8, 8, new SmeltPlan.Furnace(64, 0, 64, 0), List.of(coal(1))).failure());
        assertEquals("furnace_busy", SmeltPlan.plan(IRON, 8, 8, new SmeltPlan.Furnace(0, 64, 0, 0), List.of(coal(1))).failure(),
                "output slot full");
    }

    @Test
    void inputIsCappedByWhatTheInventoryAndOutputSlotHold() {
        assertEquals(5, SmeltPlan.plan(IRON, 8, 5, EMPTY, List.of(coal(1))).load());
        assertEquals(2, SmeltPlan.plan(IRON, 8, 8, new SmeltPlan.Furnace(0, 64, 2, 0), List.of(coal(1))).load());
    }
}
