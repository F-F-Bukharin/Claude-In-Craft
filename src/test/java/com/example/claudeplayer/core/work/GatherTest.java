package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** gather's pure parts: the group table and the stop rule. */
class GatherTest {
    private static final double NONE = Double.POSITIVE_INFINITY;

    private static boolean matches(String target, String id) {
        return GatherTargets.resolve(target).matches().test(id);
    }

    @Test
    void stoneIsEveryOverworldStoneButNotCobblestone() {
        for (String id : new String[] {"stone", "deepslate", "andesite", "diorite", "granite", "tuff"}) assertTrue(matches("stone", id), id);
        assertFalse(matches("stone", "cobblestone"), "cobblestone is man-made");
        assertTrue(GatherTargets.resolve("stone").group());
    }

    @Test
    void oreGroupsTakeTheDeepslateOreToo() {
        assertTrue(matches("iron", "iron_ore"));
        assertTrue(matches("iron", "deepslate_iron_ore"));
        assertFalse(matches("iron", "raw_iron_block"));
        assertTrue(matches("lapis", "deepslate_lapis_ore"));
        assertTrue(matches("Diamond", "diamond_ore"), "names are case-blind");
        assertFalse(matches("iron_ore", "deepslate_iron_ore"), "a single id is only that block");
    }

    @Test
    void logsAreAnyTrunkButNotStrippedOrPlantStems() {
        for (String target : new String[] {"log", "logs", "wood"}) {
            assertTrue(matches(target, "oak_log"), target);
            assertTrue(matches(target, "mangrove_log"), target);
            assertTrue(matches(target, "crimson_stem"), target);
            assertTrue(matches(target, "warped_stem"), target);
            assertTrue(matches(target, "somemod:redwood_log"), target);
            assertFalse(matches(target, "stripped_oak_log"), target);
            assertFalse(matches(target, "melon_stem"), target);
            assertFalse(matches(target, "mushroom_stem"), target);
            assertFalse(matches(target, "oak_planks"), target);
        }
    }

    @Test
    void singleIdsAreNormalizedAndJunkIsUnknown() {
        GatherTargets.Target t = GatherTargets.resolve("minecraft:Oak_Log");
        assertEquals("oak_log", t.name());
        assertFalse(t.group());
        assertTrue(t.matches().test("oak_log"));
        assertEquals("somemod:ruby_ore", GatherTargets.resolve("somemod:ruby_ore").name());
        assertNull(GatherTargets.resolve("iron ore please"));
        assertNull(GatherTargets.resolve("<script>"));
    }

    @Test
    void stopsWhenTheCountIsReached() {
        assertEquals("done", GatherRule.stop(16, 16, 10, NONE, 1.0, false));
        assertEquals("done", GatherRule.stop(18, 16, 10, NONE, 1.0, false), "a block can drop more than one");
        assertNull(GatherRule.stop(15, 16, 10, NONE, 1.0, false));
        assertEquals("done", GatherRule.stop(16, 16, 10, 2.0, 0.1, true), "finishing beats every other stop");
    }

    @Test
    void stopsOnDangerAtSixBlocksOrUnderFortyPercent() {
        assertEquals("danger", GatherRule.stop(0, 16, 10, 6.0, 1.0, false));
        assertNull(GatherRule.stop(0, 16, 10, 6.01, 1.0, false));
        assertEquals("danger", GatherRule.stop(0, 16, 10, NONE, 0.39, false));
        assertNull(GatherRule.stop(0, 16, 10, NONE, 0.4, false));
    }

    @Test
    void stopsWhenFullOrAfterTwoMinutes() {
        assertEquals("inventory_full", GatherRule.stop(3, 16, 10, NONE, 1.0, true));
        assertNull(GatherRule.stop(3, 16, GatherRule.LIMIT_TICKS - 1, NONE, 1.0, false));
        assertEquals("timeout", GatherRule.stop(3, 16, GatherRule.LIMIT_TICKS, NONE, 1.0, false));
        assertEquals(2400, GatherRule.LIMIT_TICKS);
    }

    @Test
    void summaryCountsDropsAndBlocks() {
        Map<String, Integer> gained = new LinkedHashMap<>();
        gained.put("oak_log", 9);
        gained.put("birch_log", 7);
        assertEquals("gathered 9 oak_log, 7 birch_log (16 blocks); stopped: done", GatherRule.summary(gained, 16, "done"));
        assertEquals("gathered 5 raw_iron (1 block); stopped: none_reachable", GatherRule.summary(Map.of("raw_iron", 5), 1, "none_reachable"));
        assertEquals("gathered nothing (0 blocks); stopped: needs_tool", GatherRule.summary(Map.of(), 0, "needs_tool"));
    }
}
