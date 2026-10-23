package com.example.claudeplayer.core.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.safety.Hunger.Food;
import com.example.claudeplayer.core.safety.Hunger.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The auto-eat reflex's decision and the eat tool's choice of food. */
class HungerTest {

    @Test
    void eatsWhenHungryIdleAndSafe() {
        assertTrue(Hunger.shouldAutoEat(14, false, true, false, 0));
        assertFalse(Hunger.shouldAutoEat(15, false, true, false, 0), "not hungry yet");
        assertFalse(Hunger.shouldAutoEat(Hunger.FULL, true, true, false, 0), "full");
    }

    @Test
    void hurtRaisesTheThresholdToEighteen() {
        assertTrue(Hunger.shouldAutoEat(18, true, true, false, 0));
        assertFalse(Hunger.shouldAutoEat(19, true, true, false, 0));
        assertFalse(Hunger.shouldAutoEat(18, false, true, false, 0), "full health waits for 14");
    }

    @Test
    void needsPlainFoodAndNoHostileNear() {
        assertFalse(Hunger.shouldAutoEat(10, false, false, false, 0), "no plain food");
        assertFalse(Hunger.shouldAutoEat(10, false, true, true, 0), "hostile near");
        assertFalse(Hunger.shouldAutoEat(3, false, true, true, 0), "starving still waits for safety");
    }

    @Test
    void aBusyQueueWaitsUnlessStarving() {
        assertFalse(Hunger.shouldAutoEat(10, false, true, false, 1));
        assertFalse(Hunger.shouldAutoEat(7, true, true, false, 2));
        assertTrue(Hunger.shouldAutoEat(Hunger.STARVING, false, true, false, 1));
        assertTrue(Hunger.shouldAutoEat(2, false, true, false, 2));
    }

    @Test
    void neverDuringALevelThreeAction() {
        assertFalse(Hunger.shouldAutoEat(1, true, true, false, 3));
        assertFalse(Hunger.shouldAutoEat(12, false, true, false, 3));
    }

    @Test
    void bestFoodPrefersPlainThenNutritionThenSaturation() {
        Food bread = new Food(4, 5, 6.0f, Kind.PLAIN);
        Food steak = new Food(7, 8, 12.8f, Kind.PLAIN);
        Food porkchop = new Food(2, 8, 12.8f, Kind.PLAIN);
        Food goldenApple = new Food(1, 4, 9.6f, Kind.SPECIAL);
        Food rottenFlesh = new Food(0, 4, 0.8f, Kind.HARMFUL);
        assertEquals(2, Hunger.best(List.of(bread, steak, porkchop, goldenApple, rottenFlesh)), "ties go to the lower slot");
        assertEquals(4, Hunger.best(List.of(goldenApple, bread, rottenFlesh)), "golden apples are kept");
        assertEquals(1, Hunger.best(List.of(rottenFlesh, goldenApple)));
        assertEquals(0, Hunger.best(List.of(rottenFlesh)), "harmful only when nothing else");
        assertEquals(-1, Hunger.best(List.of()));
    }
}
