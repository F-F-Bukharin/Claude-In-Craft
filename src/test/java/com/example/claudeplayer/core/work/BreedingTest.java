package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** breed's pure parts: which food each animal takes, which food to use, and whether a pair can be bred. */
class BreedingTest {
    @Test
    void cowAndSheepTakeWheat() {
        assertEquals(List.of("wheat"), Breeding.foods("cow"));
        assertEquals(List.of("wheat"), Breeding.foods("sheep"));
    }

    @Test
    void pigTakesCarrotPotatoOrBeetroot() {
        assertEquals(List.of("carrot", "potato", "beetroot"), Breeding.foods("pig"));
    }

    @Test
    void chickenTakesAnySeeds() {
        for (String seeds : new String[] {"wheat_seeds", "melon_seeds", "pumpkin_seeds", "beetroot_seeds", "torchflower_seeds", "pitcher_pod"}) {
            assertTrue(Breeding.isFood("chicken", seeds), seeds);
        }
        assertFalse(Breeding.isFood("chicken", "wheat"), "wheat is not seeds");
    }

    @Test
    void rabbitTakesCarrotGoldenCarrotOrDandelion() {
        assertEquals(List.of("carrot", "golden_carrot", "dandelion"), Breeding.foods("rabbit"));
    }

    @Test
    void wrongFoodIsRefused() {
        assertFalse(Breeding.isFood("cow", "carrot"));
        assertFalse(Breeding.isFood("sheep", "wheat_seeds"));
        assertFalse(Breeding.isFood("pig", "wheat"));
        assertFalse(Breeding.isFood("rabbit", "potato"));
        assertFalse(Breeding.isFood("cow", ""));
    }

    @Test
    void anUnknownAnimalHasNoFood() {
        assertTrue(Breeding.foods("zombie").isEmpty());
        assertTrue(Breeding.foods("").isEmpty());
        assertFalse(Breeding.known("horse"), "horses need golden food, not part of this tool");
        assertFalse(Breeding.isFood("zombie", "wheat"));
        for (String a : new String[] {"cow", "pig", "sheep", "chicken", "rabbit"}) assertTrue(Breeding.known(a), a);
    }

    @Test
    void prefixedAndMixedCaseIdsAreAccepted() {
        assertEquals(List.of("wheat"), Breeding.foods("minecraft:cow"));
        assertTrue(Breeding.isFood("minecraft:pig", "minecraft:beetroot"));
        assertTrue(Breeding.isFood("Chicken", "MINECRAFT:Melon_Seeds"));
        assertFalse(Breeding.isFood("minecraft:cow", "minecraft:carrot"));
    }

    @Test
    void foodListsCannotBeChangedByTheCaller() {
        try {
            Breeding.foods("cow").add("bread");
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("the table must be immutable");
    }

    @Test
    void twoAdultsReadyIsAPair() {
        assertNull(Breeding.check(2, 2));
        assertNull(Breeding.check(5, 4));
    }

    @Test
    void fewerThanTwoAdultsIsNoPair() {
        assertEquals("no_pair", Breeding.check(0, 0));
        assertEquals("no_pair", Breeding.check(1, 1));
        assertEquals("no_pair", Breeding.check(1, 0));
    }

    @Test
    void twoAdultsButFewerThanTwoReadyIsNotReady() {
        assertEquals("not_ready", Breeding.check(2, 1));
        assertEquals("not_ready", Breeding.check(2, 0));
        assertEquals("not_ready", Breeding.check(6, 1));
    }

    @Test
    void readyAdultsMakePairsTwoByTwo() {
        assertEquals(0, Breeding.pairsAvailable(1));
        assertEquals(1, Breeding.pairsAvailable(2));
        assertEquals(1, Breeding.pairsAvailable(3));
        assertEquals(2, Breeding.pairsAvailable(4));
        assertEquals(0, Breeding.pairsAvailable(-1));
    }

    @Test
    void theFoodCarriedMostIsChosen() {
        Map<String, Integer> have = new LinkedHashMap<>();
        have.put("carrot", 3);
        have.put("potato", 9);
        have.put("wheat", 40);
        assertEquals("potato", Breeding.chooseFood("pig", have));
    }

    @Test
    void aTieGoesToTheFirstFoodInTheTable() {
        assertEquals("carrot", Breeding.chooseFood("pig", Map.of("carrot", 4, "potato", 4, "beetroot", 4)));
    }

    @Test
    void noFoodCarriedIsMissingFood() {
        assertNull(Breeding.chooseFood("cow", Map.of()));
        assertNull(Breeding.chooseFood("cow", Map.of("carrot", 10, "wheat_seeds", 10)));
        assertNull(Breeding.chooseFood("cow", Map.of("wheat", 0)));
        assertNull(Breeding.chooseFood("zombie", Map.of("wheat", 10)));
    }

    @Test
    void prefixedInventoryKeysCount() {
        assertEquals("wheat", Breeding.chooseFood("cow", Map.of("minecraft:wheat", 2)));
        assertEquals(3, Breeding.carried("pig", Map.of("minecraft:carrot", 1, "potato", 2, "wheat", 50)));
        assertEquals(0, Breeding.carried("pig", Map.of()));
    }

    @Test
    void aPairNeedsTwoFeeds() {
        assertEquals(2, Breeding.foodFor(1));
        assertEquals(8, Breeding.foodFor(4));
    }

    @Test
    void theSummaryNamesPairsAndFoodUsed() {
        assertEquals("bred 2 cow pairs (used 4 wheat)", Breeding.summary("cow", 2, Map.of("wheat", 4)));
        assertEquals("bred 1 pig pair (used 2 carrot)", Breeding.summary("pig", 1, Map.of("carrot", 2)));
        Map<String, Integer> mixed = new LinkedHashMap<>();
        mixed.put("carrot", 2);
        mixed.put("potato", 2);
        assertEquals("bred 2 pig pairs (used 2 carrot, 2 potato)", Breeding.summary("minecraft:pig", 2, mixed));
    }

    @Test
    void runningOutOfFoodReportsHowManyPairsWereBred() {
        assertEquals("bred 1 of 3 cow pairs (used 2 wheat)", Breeding.shortfall("cow", 1, 3, Map.of("wheat", 2)));
        assertEquals("bred 0 of 2 sheep pairs (used nothing)", Breeding.shortfall("sheep", 0, 2, Map.of()));
        assertEquals("bred 0 of 1 pig pair (used nothing)", Breeding.shortfall("pig", 0, 1, Map.of()));
    }

    @Test
    void theLimitsAreSane() {
        assertTrue(Breeding.SEARCH_RANGE >= 8 && Breeding.SEARCH_RANGE <= 32);
        assertTrue(Breeding.LIMIT_TICKS >= 600);
    }
}
