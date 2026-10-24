package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.work.StorePlan.Kind;
import com.example.claudeplayer.core.work.StorePlan.Placed;
import com.example.claudeplayer.core.work.StorePlan.Stack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** go_home's pure parts: what to keep and what to store, and the one-line report. */
class StorePlanTest {
    private static Stack stack(String id, int count, Kind kind) {
        return new Stack(id, count, kind, 64);
    }

    private static Stack block(String id, int count) {
        return stack(id, count, Kind.BUILDING_BLOCK);
    }

    private static Stack other(String id, int count) {
        return stack(id, count, Kind.OTHER);
    }

    private static Map<String, Integer> map(Object... pairs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    private static List<String> order(Map<String, Integer> m) {
        return new ArrayList<>(m.keySet());
    }

    @Test
    void emptyInventoryStoresNothing() {
        assertEquals(Map.of(), StorePlan.toStore(List.of()));
    }

    @Test
    void toolsWeaponsArmorFoodAndTorchesAreAllKept() {
        List<Stack> inv = List.of(
                new Stack("iron_pickaxe", 1, Kind.TOOL, 1),
                new Stack("diamond_sword", 1, Kind.WEAPON, 1),
                stack("arrow", 40, Kind.WEAPON),
                new Stack("iron_chestplate", 1, Kind.ARMOR, 1),
                stack("cooked_beef", 30, Kind.FOOD),
                stack("torch", 50, Kind.TORCH));
        assertEquals(Map.of(), StorePlan.toStore(inv));
    }

    @Test
    void everythingElseIsStoredWhole() {
        List<Stack> inv = List.of(other("iron_ore", 12), other("gravel", 90), other("bone", 5));
        assertEquals(map("iron_ore", 12, "gravel", 90, "bone", 5), StorePlan.toStore(inv));
    }

    @Test
    void theMostCarriedBuildingBlockKeepsOneStackAndTheRestIsStored() {
        List<Stack> inv = List.of(block("cobblestone", 64), block("cobblestone", 64), block("cobblestone", 22), block("dirt", 40));
        assertEquals(map("cobblestone", 86, "dirt", 40), StorePlan.toStore(inv));
    }

    @Test
    void aBuildingBlockUnderOneStackIsKeptWhole() {
        List<Stack> inv = List.of(block("cobblestone", 40), block("dirt", 10), other("flint", 3));
        assertEquals(map("dirt", 10, "flint", 3), StorePlan.toStore(inv));
    }

    @Test
    void mostCarriedCountsTheTotalOverAllStacksOfAnId() {
        List<Stack> inv = List.of(block("cobblestone", 64), block("dirt", 30), block("dirt", 30), block("dirt", 30));
        // dirt 90 beats cobblestone 64, so dirt keeps a stack and cobblestone goes
        assertEquals(map("cobblestone", 64, "dirt", 26), StorePlan.toStore(inv));
    }

    @Test
    void aTieKeepsTheFirstInTheInventory() {
        List<Stack> inv = List.of(block("stone", 20), block("dirt", 20));
        assertEquals(map("dirt", 20), StorePlan.toStore(inv));
        assertEquals(map("stone", 20), StorePlan.toStore(List.of(block("dirt", 20), block("stone", 20))));
    }

    @Test
    void otherKindsNeverCountAsTheMostCarriedBlock() {
        List<Stack> inv = List.of(other("iron_ore", 200), block("cobblestone", 10));
        assertEquals(map("iron_ore", 200), StorePlan.toStore(inv));
    }

    @Test
    void noBuildingBlocksIsFine() {
        assertEquals(map("bone", 2), StorePlan.toStore(List.of(other("bone", 2), stack("bread", 3, Kind.FOOD))));
    }

    @Test
    void theKeptStackIsTheItemsOwnMaxStack() {
        List<Stack> inv = List.of(new Stack("ice_block", 40, Kind.BUILDING_BLOCK, 16));
        assertEquals(map("ice_block", 24), StorePlan.toStore(inv));
    }

    @Test
    void oneBedIsKeptAndTheRestStored() {
        assertEquals(map("white_bed", 2), StorePlan.toStore(List.of(stack("white_bed", 3, Kind.BED))));
        assertEquals(Map.of(), StorePlan.toStore(List.of(stack("white_bed", 1, Kind.BED))));
    }

    @Test
    void ofSeveralBedColoursTheMostCarriedIsKeptAndATieKeepsTheFirst() {
        List<Stack> inv = List.of(stack("red_bed", 1, Kind.BED), stack("blue_bed", 2, Kind.BED), stack("green_bed", 1, Kind.BED));
        assertEquals(map("red_bed", 1, "blue_bed", 1, "green_bed", 1), StorePlan.toStore(inv));
        List<Stack> tie = List.of(stack("red_bed", 1, Kind.BED), stack("blue_bed", 1, Kind.BED));
        assertEquals(map("blue_bed", 1), StorePlan.toStore(tie));
    }

    @Test
    void theOrderFollowsTheFirstAppearanceInTheInventory() {
        List<Stack> inv = List.of(other("gravel", 5), block("cobblestone", 70), other("bone", 1), other("gravel", 2));
        Map<String, Integer> out = StorePlan.toStore(inv);
        assertEquals(List.of("gravel", "cobblestone", "bone"), order(out));
        assertEquals(7, out.get("gravel"));
        assertEquals(6, out.get("cobblestone"));
    }

    @Test
    void emptyIdsAndNonPositiveCountsAreIgnored() {
        List<Stack> inv = List.of(other("", 5), other("bone", 0), other("flint", -3), other("coal", 4));
        assertEquals(map("coal", 4), StorePlan.toStore(inv));
    }

    @Test
    void theResultNeverTouchesTheInput() {
        List<Stack> inv = List.of(block("cobblestone", 100));
        StorePlan.toStore(inv);
        assertEquals(100, inv.get(0).count());
    }

    @Test
    void reportNamesEachContainerAndWhatWentIn() {
        List<Placed> placed = List.of(
                new Placed("chest at 10, 64, 5", map("cobblestone", 40, "dirt", 12)),
                new Placed("barrel at 12, 64, 5", map("gravel", 7)));
        assertEquals("stored 40 cobblestone, 12 dirt in chest at 10, 64, 5; 7 gravel in barrel at 12, 64, 5",
                StorePlan.report(placed, Map.of(), 0));
    }

    @Test
    void reportSaysWhatDidNotFitAndWhatCouldNotBeReached() {
        List<Placed> placed = List.of(new Placed("chest at 1, 2, 3", map("dirt", 9)));
        String report = StorePlan.report(placed, map("gravel", 5, "bone", 2), 1);
        assertEquals("stored 9 dirt in chest at 1, 2, 3; didn't fit: 5 gravel, 2 bone; could not reach 1 container", report);
        assertTrue(StorePlan.report(placed, Map.of(), 2).endsWith("could not reach 2 containers"));
    }

    @Test
    void reportWhenNothingFit() {
        assertEquals("stored nothing; didn't fit: 5 gravel", StorePlan.report(List.of(), map("gravel", 5), 0));
    }

    @Test
    void reportSkipsContainersThatTookNothing() {
        List<Placed> placed = List.of(new Placed("chest at 1, 2, 3", Map.of()), new Placed("barrel at 4, 5, 6", map("dirt", 1)));
        assertEquals("stored 1 dirt in barrel at 4, 5, 6", StorePlan.report(placed, Map.of(), 0));
    }
}
