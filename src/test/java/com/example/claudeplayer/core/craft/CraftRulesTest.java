package com.example.claudeplayer.core.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Craft bookkeeping: ids, craft counts, which inventory items fill which ingredient slots, and the result text. */
class CraftRulesTest {

    private static CraftRules.Slot slot(String label, String... accepts) {
        return new CraftRules.Slot(List.of(accepts), label);
    }

    @Test
    void idsGainOrLoseTheVanillaNamespace() {
        assertEquals("minecraft:oak_planks", CraftRules.fullId(" Oak_Planks "));
        assertEquals("minecraft:stick", CraftRules.fullId("minecraft:stick"));
        assertEquals("othermod:gear", CraftRules.fullId("othermod:gear"));
        assertEquals("stick", CraftRules.shortId("minecraft:stick"));
        assertEquals("othermod:gear", CraftRules.shortId("othermod:gear"));
    }

    @Test
    void craftsRoundUpToCoverTheWantedCount() {
        assertEquals(1, CraftRules.craftsFor(1, 4));
        assertEquals(1, CraftRules.craftsFor(4, 4));
        assertEquals(2, CraftRules.craftsFor(5, 4));
        assertEquals(64, CraftRules.craftsFor(64, 1));
    }

    @Test
    void eachSlotTakesOneItem() {
        CraftRules.Allocation a = CraftRules.allocate(
                List.of(slot("oak_planks", "minecraft:oak_planks"), slot("oak_planks", "minecraft:oak_planks")),
                Map.of("minecraft:oak_planks", 3));
        assertTrue(a.complete());
        assertEquals(Map.of("minecraft:oak_planks", 2), a.used());
    }

    @Test
    void narrowSlotsChooseFirstSoAnyPlanksLeavesTheOak() {
        // slot 1 takes any planks, slot 2 only oak; one of each in the inventory
        CraftRules.Allocation a = CraftRules.allocate(
                List.of(slot("oak_planks", "minecraft:oak_planks", "minecraft:birch_planks"), slot("oak_planks", "minecraft:oak_planks")),
                Map.of("minecraft:oak_planks", 1, "minecraft:birch_planks", 1));
        assertTrue(a.complete(), a.toString());
        assertEquals(Map.of("minecraft:oak_planks", 1, "minecraft:birch_planks", 1), a.used());
    }

    @Test
    void missingItemsAreCountedPerSlotUnderTheirLabel() {
        CraftRules.Allocation a = CraftRules.allocate(
                List.of(slot("cobblestone", "minecraft:cobblestone"), slot("cobblestone", "minecraft:cobblestone"),
                        slot("cobblestone", "minecraft:cobblestone"), slot("stick"), slot("stick")),
                Map.of("minecraft:cobblestone", 2));
        assertFalse(a.complete());
        assertEquals(3, a.missingCount());
        assertEquals("missing:stickx2,cobblestonex1", CraftRules.missing(a.missing()));
    }

    @Test
    void summarySaysWhatWasMadeAndUsed() {
        Map<String, Integer> used = new LinkedHashMap<>();
        used.put("minecraft:oak_log", 1);
        assertEquals("crafted 4 oak_planks (used 1 oak_log)", CraftRules.summary("oak_planks", 4, 4, false, used, 0));
        assertEquals("crafted 4 of 8 oak_planks (used 1 oak_log), ran out of ingredients, 2 dropped at my feet (inventory full)",
                CraftRules.summary("oak_planks", 4, 8, true, used, 2));
    }
}
