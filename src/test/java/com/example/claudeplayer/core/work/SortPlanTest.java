package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.work.SortPlan.Box;
import com.example.claudeplayer.core.work.SortPlan.Kind;
import com.example.claudeplayer.core.work.SortPlan.Move;
import com.example.claudeplayer.core.work.SortPlan.Plan;
import com.example.claudeplayer.core.work.SortPlan.Stack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** sort_chests' planner: which container keeps which kind, and the moves, never overfilling. */
class SortPlanTest {
    private static Stack s(String item, int count, Kind kind) {
        return new Stack(item, count, 64, kind);
    }

    private static Stack tool(String item) {
        return new Stack(item, 1, 1, Kind.GEAR);
    }

    private static Box box(int slots, Stack... stacks) {
        return new Box(slots, List.of(stacks));
    }

    private static Plan plan(Box... boxes) {
        return SortPlan.plan(List.of(boxes), Set.of());
    }

    private static Map<String, Integer> totals(List<Box> boxes) {
        Map<String, Integer> t = new HashMap<>();
        for (Box b : boxes) for (Stack st : b.stacks()) t.merge(st.item(), st.count(), Integer::sum);
        return t;
    }

    /** Applies the moves (all removals, then the inserts) and checks nothing overfills and nothing is lost. */
    private static List<Box> applied(List<Box> boxes, Plan plan) {
        List<Box> after = SortPlan.apply(boxes, plan.moves());
        for (int i = 0; i < after.size(); i++) {
            assertTrue(after.get(i).stacks().size() <= after.get(i).slots(), "box " + i + " overfilled: " + after.get(i));
            for (Stack st : after.get(i).stacks()) assertTrue(st.count() <= st.maxStack(), "stack over its max: " + st);
        }
        assertEquals(totals(boxes), totals(after), "moves conserve every item");
        return after;
    }

    @Test
    void twoMixedChestsBecomeOneBlocksAndOneFoodKeepingTheirMajority() {
        List<Box> boxes = List.of(
                box(27, s("cobblestone", 10, Kind.BLOCKS), s("dirt", 5, Kind.BLOCKS), s("bread", 3, Kind.FOOD)),
                box(27, s("bread", 4, Kind.FOOD), s("apple", 2, Kind.FOOD), s("stone", 1, Kind.BLOCKS)));
        Plan plan = SortPlan.plan(boxes, Set.of());
        assertEquals(Arrays.asList(Kind.BLOCKS, Kind.FOOD), plan.kinds());
        assertEquals(List.of(new Move(1, 0, "stone", 1), new Move(0, 1, "bread", 3)), plan.moves(), "kind by kind, blocks first");
        assertEquals(4, plan.items());
        List<Box> after = applied(boxes, plan);
        assertTrue(after.get(0).stacks().stream().allMatch(st -> st.kind() == Kind.BLOCKS));
        assertTrue(after.get(1).stacks().stream().allMatch(st -> st.kind() == Kind.FOOD));
    }

    @Test
    void majorityIsBySlotsWithTiesToTheFirstKind() {
        assertEquals(Kind.FOOD, SortPlan.majority(box(9, s("cobblestone", 64, Kind.BLOCKS), s("bread", 1, Kind.FOOD), s("apple", 1, Kind.FOOD))));
        assertEquals(Kind.BLOCKS, SortPlan.majority(box(9, s("bread", 1, Kind.FOOD), s("dirt", 1, Kind.BLOCKS))), "tie: blocks come first");
        assertNull(SortPlan.majority(box(9)));
    }

    @Test
    void alreadySortedStorageNeedsNoMoves() {
        Plan plan = plan(box(27, s("oak_log", 64, Kind.BLOCKS), s("dirt", 20, Kind.BLOCKS)),
                box(27, s("iron_ingot", 12, Kind.MATERIALS), s("diamond", 3, Kind.MATERIALS)),
                box(27, tool("iron_pickaxe"), tool("iron_sword")));
        assertEquals(List.of(), plan.moves());
        assertEquals(Arrays.asList(Kind.BLOCKS, Kind.MATERIALS, Kind.GEAR), plan.kinds());
    }

    @Test
    void planningThePlannedResultAgainIsANoOp() {
        List<Box> boxes = List.of(
                box(27, s("cobblestone", 64, Kind.BLOCKS), s("bread", 10, Kind.FOOD), tool("stone_axe"), s("string", 3, Kind.OTHER)),
                box(27, s("iron_ingot", 5, Kind.MATERIALS), s("cobblestone", 30, Kind.BLOCKS), s("carrot", 7, Kind.FOOD)),
                box(54, s("raw_iron", 9, Kind.MATERIALS), s("bone", 4, Kind.OTHER), tool("bow")),
                box(27));
        List<Box> after = applied(boxes, SortPlan.plan(boxes, Set.of()));
        assertEquals(List.of(), SortPlan.plan(after, Set.of()).moves());
    }

    @Test
    void aKindGoesIntoTheFewestContainers() {
        // Logs over a small box (full) and a big one: the big one holds them all, so the small one empties.
        List<Box> boxes = List.of(box(2, s("oak_log", 64, Kind.BLOCKS), s("oak_log", 64, Kind.BLOCKS)),
                box(5, s("oak_log", 64, Kind.BLOCKS)));
        Plan plan = SortPlan.plan(boxes, Set.of());
        assertEquals(Arrays.asList(null, Kind.BLOCKS), plan.kinds());
        assertEquals(List.of(new Move(0, 1, "oak_log", 128)), plan.moves());
        List<Box> after = applied(boxes, plan);
        assertEquals(List.of(), after.get(0).stacks());
    }

    @Test
    void aKindThatNeedsTwoContainersGetsTwo() {
        List<Box> boxes = List.of(box(2, s("dirt", 64, Kind.BLOCKS), s("dirt", 64, Kind.BLOCKS)),
                box(2, s("dirt", 64, Kind.BLOCKS), s("bread", 5, Kind.FOOD)),
                box(2, s("bread", 5, Kind.FOOD)));
        Plan plan = SortPlan.plan(boxes, Set.of());
        assertEquals(Arrays.asList(Kind.BLOCKS, Kind.BLOCKS, Kind.FOOD), plan.kinds());
        assertEquals(List.of(new Move(1, 2, "bread", 5)), plan.moves());
        List<Box> after = applied(boxes, plan);
        assertEquals(1, after.get(2).stacks().size(), "the two breads merge into one stack");
    }

    @Test
    void swapsWorkBecauseRemovalsComeFirst() {
        List<Box> boxes = List.of(box(2, s("dirt", 64, Kind.BLOCKS), s("bread", 5, Kind.FOOD)),
                box(2, s("apple", 3, Kind.FOOD), s("cobblestone", 64, Kind.BLOCKS)),
                box(2, s("carrot", 1, Kind.FOOD), s("carrot", 1, Kind.FOOD)));
        Plan plan = SortPlan.plan(boxes, Set.of());
        assertEquals(Arrays.asList(Kind.FOOD, Kind.BLOCKS, Kind.FOOD), plan.kinds(), "food needs 3 slots: the carrots' box and one more");
        assertEquals(List.of(new Move(0, 1, "dirt", 64), new Move(1, 0, "apple", 3)), plan.moves(), "a swap between two full boxes");
        applied(boxes, plan);
    }

    @Test
    void neverOverfillsAndWhatDoesNotFitStaysPut() {
        // Food has no room anywhere: two full boxes of blocks keep the bread they can't hand off.
        List<Box> boxes = List.of(box(2, s("cobblestone", 64, Kind.BLOCKS), s("cobblestone", 64, Kind.BLOCKS)),
                box(2, s("stone", 64, Kind.BLOCKS), s("bread", 10, Kind.FOOD)));
        Plan plan = SortPlan.plan(boxes, Set.of());
        assertEquals(Arrays.asList(Kind.BLOCKS, Kind.BLOCKS), plan.kinds());
        assertEquals(List.of(), plan.moves());
        // Non-stackables take a slot each: three swords, one free slot, so two stay.
        List<Box> gear = List.of(box(3, tool("iron_sword"), tool("iron_sword"), s("dirt", 1, Kind.BLOCKS)),
                box(2, tool("iron_sword"), s("bread", 1, Kind.FOOD)));
        Plan g = SortPlan.plan(gear, Set.of());
        applied(gear, g);
        for (Move m : g.moves()) assertNotEquals(m.from(), m.to(), "no self-moves");
    }

    @Test
    void partialStacksMergeUpToTheirMax() {
        List<Box> boxes = List.of(box(1, s("dirt", 40, Kind.BLOCKS)), box(3, s("dirt", 20, Kind.BLOCKS), s("bread", 2, Kind.FOOD), s("apple", 1, Kind.FOOD)));
        Plan plan = SortPlan.plan(boxes, Set.of());
        assertEquals(Arrays.asList(Kind.BLOCKS, Kind.FOOD), plan.kinds());
        assertEquals(List.of(new Move(1, 0, "dirt", 20)), plan.moves(), "40 + 20 fit in the one slot; 64 is the max");
        applied(boxes, plan);
    }

    @Test
    void fixedItemsNeverMove() {
        List<Box> boxes = List.of(box(27, s("dirt", 10, Kind.BLOCKS), tool("diamond_pickaxe")),
                box(27, tool("iron_sword"), tool("bow")));
        Plan plan = SortPlan.plan(boxes, Set.of("diamond_pickaxe"));
        assertTrue(plan.moves().stream().noneMatch(m -> m.item().equals("diamond_pickaxe")), plan.moves().toString());
        assertEquals(List.of(), plan.moves());
    }

    @Test
    void tiesAreDeterministic() {
        List<Box> boxes = List.of(box(9, s("dirt", 1, Kind.BLOCKS), s("bread", 1, Kind.FOOD)),
                box(9, s("bread", 1, Kind.FOOD), s("dirt", 1, Kind.BLOCKS)));
        Plan first = SortPlan.plan(boxes, Set.of());
        for (int i = 0; i < 5; i++) assertEquals(first, SortPlan.plan(boxes, Set.of()));
        assertEquals(Arrays.asList(Kind.BLOCKS, Kind.FOOD), first.kinds(), "blocks first, in container order");
    }

    @Test
    void randomStorageIsConservedNeverOverfilledAndHasNoSelfMoves() {
        String[][] items = {{"dirt", "BLOCKS"}, {"cobblestone", "BLOCKS"}, {"oak_log", "BLOCKS"}, {"iron_ingot", "MATERIALS"},
            {"coal", "MATERIALS"}, {"bread", "FOOD"}, {"carrot", "FOOD"}, {"string", "OTHER"}, {"bone", "OTHER"}};
        Random rnd = new Random(42);
        for (int round = 0; round < 300; round++) {
            List<Box> boxes = new ArrayList<>();
            int n = 2 + rnd.nextInt(4);
            for (int b = 0; b < n; b++) {
                int slots = 1 + rnd.nextInt(6);
                List<Stack> stacks = new ArrayList<>();
                int used = rnd.nextInt(slots + 1);
                for (int k = 0; k < used; k++) {
                    if (rnd.nextInt(6) == 0) stacks.add(tool(rnd.nextBoolean() ? "iron_sword" : "bow"));
                    else {
                        String[] it = items[rnd.nextInt(items.length)];
                        stacks.add(s(it[0], 1 + rnd.nextInt(64), Kind.valueOf(it[1])));
                    }
                }
                boxes.add(new Box(slots, stacks));
            }
            Set<String> fixed = rnd.nextInt(4) == 0 ? Set.of("bow") : Set.of();
            Plan plan = SortPlan.plan(boxes, fixed);
            applied(boxes, plan);
            for (Move m : plan.moves()) {
                assertNotEquals(m.from(), m.to(), "round " + round);
                assertTrue(m.count() > 0, "round " + round);
                assertFalse(fixed.contains(m.item()), "round " + round);
            }
        }
    }

    @Test
    void materialIdsAreOresIngotsAndGems() {
        for (String id : List.of("iron_ingot", "gold_nugget", "raw_copper", "deepslate_iron_ore", "nether_quartz_ore", "coal", "charcoal",
                "diamond", "emerald", "lapis_lazuli", "redstone", "quartz", "amethyst_shard", "netherite_scrap", "ancient_debris", "create:zinc_ingot")) {
            assertTrue(SortPlan.isMaterial(id), id);
        }
        for (String id : List.of("cobblestone", "iron_block", "bread", "iron_pickaxe", "stick", "coal_block", "redstone_torch")) {
            assertFalse(SortPlan.isMaterial(id), id);
        }
    }

    @Test
    void summaryNamesEachContainersKindAndStaysShort() {
        assertEquals("sorted 2 containers: moved 4 items in 2 moves; chest at 1, 64, 2: blocks; barrel at 3, 64, 2: food",
                SortPlan.summary(List.of("chest at 1, 64, 2", "barrel at 3, 64, 2"), Arrays.asList(Kind.BLOCKS, Kind.FOOD), 4, 2, ""));
        assertEquals("already sorted: 2 containers; chest at 1, 64, 2: ores and ingots; chest at 5, 64, 2: spare",
                SortPlan.summary(List.of("chest at 1, 64, 2", "chest at 5, 64, 2"), Arrays.asList(Kind.MATERIALS, null), 0, 0, ""));
        assertEquals("sorted 2 containers: moved 1 item in 1 move; a: tools and weapons; b: the rest; skipped: diamond_pickaxe (yours)",
                SortPlan.summary(List.of("a", "b"), Arrays.asList(Kind.GEAR, Kind.OTHER), 1, 1, "skipped: diamond_pickaxe (yours)"));
        List<String> names = new ArrayList<>();
        List<Kind> kinds = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            names.add("trapped_chest at 1000000, 64, " + i);
            kinds.add(Kind.BLOCKS);
        }
        String text = SortPlan.summary(names, kinds, 999, 99, "still carrying 3 dirt");
        assertTrue(text.length() <= SortPlan.SUMMARY_MAX, "length " + text.length());
        assertTrue(text.endsWith("still carrying 3 dirt"), "the tail is never cut: " + text);
    }
}
