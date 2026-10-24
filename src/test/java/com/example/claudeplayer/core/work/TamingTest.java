package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** tame and pet's pure parts: the food table, the food choice, the give-up rule, the stops, the pet command and the texts. */
class TamingTest {
    @Test
    void eachAnimalHasItsFoods() {
        assertEquals(List.of("bone"), Taming.foods("wolf"));
        assertEquals(List.of("cod", "salmon"), Taming.foods("cat"));
        assertEquals(List.of("wheat_seeds", "melon_seeds", "pumpkin_seeds", "beetroot_seeds", "torchflower_seeds", "pitcher_pod"),
                Taming.foods("parrot"));
    }

    @Test
    void aParrotAlsoTakesTorchflowerSeedsAndPitcherPods() {
        assertTrue(Taming.isFood("parrot", "torchflower_seeds"));
        assertTrue(Taming.isFood("parrot", "minecraft:pitcher_pod"));
        assertEquals("pitcher_pod", Taming.chooseFood("parrot", Map.of("pitcher_pod", 2, "cookie", 5)));
        assertEquals("tamed parrot #3 after 2 pitcher_pods", Taming.tamed("parrot", 3, Map.of("pitcher_pod", 2)));
        assertEquals("tamed parrot #3 after 1 pitcher_pod", Taming.tamed("parrot", 3, Map.of("pitcher_pod", 1)));
    }

    @Test
    void unknownAnimalsHaveNoFoods() {
        assertEquals(List.of(), Taming.foods("cow"));
        assertEquals(List.of(), Taming.foods(""));
        assertEquals(List.of(), Taming.foods(null));
        assertFalse(Taming.isAnimal("horse"));
        assertTrue(Taming.isAnimal("wolf") && Taming.isAnimal("cat") && Taming.isAnimal("parrot"));
    }

    @Test
    void onlyTheRightFoodWorks() {
        assertTrue(Taming.isFood("wolf", "bone"));
        assertFalse(Taming.isFood("wolf", "cod"));
        assertTrue(Taming.isFood("cat", "cod"));
        assertTrue(Taming.isFood("cat", "salmon"));
        assertFalse(Taming.isFood("cat", "cooked_cod"), "only raw fish tames");
        assertFalse(Taming.isFood("cat", "bone"));
        assertTrue(Taming.isFood("parrot", "beetroot_seeds"));
        assertFalse(Taming.isFood("parrot", "cookie"), "a cookie kills a parrot, never offered");
        assertFalse(Taming.isFood("parrot", "wheat"));
        assertFalse(Taming.isFood("cow", "wheat"));
    }

    @Test
    void prefixedAndMixedCaseIdsAreAccepted() {
        assertEquals(List.of("bone"), Taming.foods("minecraft:wolf"));
        assertEquals(List.of("bone"), Taming.foods("Wolf"));
        assertTrue(Taming.isAnimal("minecraft:cat"));
        assertTrue(Taming.isFood("minecraft:cat", "minecraft:salmon"));
        assertTrue(Taming.isFood("parrot", "MINECRAFT:pumpkin_seeds"));
        assertFalse(Taming.isFood("minecraft:wolf", "minecraft:cod"));
    }

    @Test
    void chooseFoodTakesTheFirstCarriedOne() {
        assertEquals("bone", Taming.chooseFood("wolf", Map.of("bone", 3, "cod", 9)));
        assertEquals("salmon", Taming.chooseFood("cat", Map.of("salmon", 2, "bone", 9)));
        assertEquals("cod", Taming.chooseFood("cat", Map.of("salmon", 2, "cod", 1)), "table order: cod before salmon");
        assertEquals("melon_seeds", Taming.chooseFood("parrot", Map.of("melon_seeds", 4, "wheat", 8)));
        assertEquals("bone", Taming.chooseFood("minecraft:wolf", Map.of("bone", 1)));
    }

    @Test
    void chooseFoodIsNullWhenNothingFits() {
        assertNull(Taming.chooseFood("wolf", Map.of()));
        assertNull(Taming.chooseFood("wolf", Map.of("cod", 5, "bone", 0)), "a zero count is not carried");
        assertNull(Taming.chooseFood("cat", Map.of("cooked_cod", 5)));
        assertNull(Taming.chooseFood("dragon", Map.of("bone", 5)));
    }

    @Test
    void missingTextNamesWhatToBring() {
        assertEquals("wolf needs bone", Taming.missing("wolf"));
        assertEquals("cat needs cod or salmon", Taming.missing("cat"));
        assertEquals("parrot needs wheat_seeds, melon_seeds, pumpkin_seeds, beetroot_seeds, torchflower_seeds or pitcher_pod",
                Taming.missing("parrot"));
    }

    @Test
    void givesUpAfterFifteenFeeds() {
        assertEquals(15, Taming.MAX_TRIES);
        assertFalse(Taming.giveUp(0, 0));
        assertFalse(Taming.giveUp(14, 0));
        assertTrue(Taming.giveUp(15, 0));
        assertTrue(Taming.giveUp(16, 0));
    }

    @Test
    void givesUpOnTooManyRefusals() {
        assertFalse(Taming.giveUp(2, Taming.MAX_REFUSALS - 1));
        assertTrue(Taming.giveUp(2, Taming.MAX_REFUSALS), "a cat that never takes the food, or an angry wolf");
    }

    @Test
    void stopsOnDangerLowHealthAndTimeout() {
        assertNull(Taming.stop(100, Double.POSITIVE_INFINITY, 1.0));
        assertNull(Taming.stop(100, 6.1, 1.0));
        assertEquals("danger", Taming.stop(100, 6.0, 1.0));
        assertEquals("danger", Taming.stop(100, 2.0, 1.0));
        assertEquals("danger", Taming.stop(100, Double.POSITIVE_INFINITY, 0.39));
        assertNull(Taming.stop(100, Double.POSITIVE_INFINITY, 0.4));
        assertNull(Taming.stop(Taming.LIMIT_TICKS - 1, Double.POSITIVE_INFINITY, 1.0));
        assertEquals("timeout", Taming.stop(Taming.LIMIT_TICKS, Double.POSITIVE_INFINITY, 1.0));
        assertEquals("danger", Taming.stop(Taming.LIMIT_TICKS, 3.0, 1.0), "danger is reported before the timeout");
    }

    @Test
    void constantsAreSane() {
        assertEquals(16.0, Taming.SEARCH_RANGE);
        assertTrue(Taming.FEED_REACH < 3.0, "a cat takes food only from a player closer than 3 blocks");
        assertTrue(Taming.LIMIT_TICKS > Taming.MAX_TRIES * Taming.FEED_GAP_TICKS);
    }

    @Test
    void petCommandsMapToTheSitOrder() {
        assertEquals(Boolean.TRUE, Taming.sitting("sit"));
        assertEquals(Boolean.FALSE, Taming.sitting("follow"));
        assertEquals(Boolean.TRUE, Taming.sitting(" Sit "));
        assertEquals(Boolean.FALSE, Taming.sitting("FOLLOW"));
        assertNull(Taming.sitting("stay"));
        assertNull(Taming.sitting(""));
        assertNull(Taming.sitting(null));
    }

    @Test
    void textsReportTheOutcome() {
        Map<String, Integer> used = new LinkedHashMap<>();
        used.put("bone", 3);
        assertEquals("tamed wolf #123 after 3 bones", Taming.tamed("wolf", 123, used));
        used.put("bone", 1);
        assertEquals("tamed wolf #123 after 1 bone", Taming.tamed("wolf", 123, used));
        assertEquals("gave up on wolf #7 after 1 bone", Taming.gaveUp("wolf", 7, used));
        assertEquals("ran out of food for wolf #7 after 1 bone", Taming.ranOut("wolf", 7, used));
        assertEquals("stopped (danger) with wolf #7 after 1 bone", Taming.interrupted("danger", "wolf", 7, used));
        Map<String, Integer> fish = new LinkedHashMap<>();
        fish.put("cod", 2);
        fish.put("salmon", 1);
        assertEquals("tamed cat #45 after 2 cod, 1 salmon", Taming.tamed("cat", 45, fish));
        Map<String, Integer> seeds = Map.of("wheat_seeds", 4);
        assertEquals("tamed parrot #9 after 4 wheat_seeds", Taming.tamed("parrot", 9, seeds));
        assertEquals("gave up on cat #4 before any food", Taming.gaveUp("cat", 4, Map.of()));
        assertEquals("wolf #123 was already tamed", Taming.already("wolf", 123));
    }

    @Test
    void petTextsSaySitOrFollow() {
        assertEquals("wolf #123 is sitting", Taming.petText("wolf", 123, true));
        assertEquals("cat #45 is following", Taming.petText("cat", 45, false));
    }
}
