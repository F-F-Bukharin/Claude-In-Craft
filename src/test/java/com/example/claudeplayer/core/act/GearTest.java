package com.example.claudeplayer.core.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Gearing up before a fight: the best weapon, the best piece per armor slot, and a shield only into an empty off-hand. */
class GearTest {

    private static Gear.Candidate c(int slot, double main, double tiebreak) {
        return new Gear.Candidate(slot, main, tiebreak);
    }

    @Test
    void theWeaponWithTheMostDamageWins() {
        // held stick, wooden sword, iron axe, stone sword
        assertEquals(7, Gear.bestWeapon(List.of(c(0, 0, 4), c(3, 3, 1.6), c(7, 8, 0.9), c(12, 4, 1.6))));
    }

    @Test
    void equalDamageGoesToTheFasterWeapon() {
        // a trident and an iron sword both deal 6 here; the sword swings faster
        assertEquals(21, Gear.bestWeapon(List.of(c(0, 0, 4), c(5, 6, 1.1), c(21, 6, 1.6))));
    }

    @Test
    void theHeldWeaponStaysWhenNothingIsBetter() {
        assertEquals(2, Gear.bestWeapon(List.of(c(2, 6, 1.6), c(9, 6, 1.6), c(11, 3, 1.6))), "a tie keeps the held one");
        assertEquals(-1, Gear.bestWeapon(List.of()), "nothing to choose from");
    }

    @Test
    void armorGoesByArmorThenToughness() {
        // worn iron chestplate (6, 0) against leather (3, 0) and diamond (8, 2)
        assertEquals(30, Gear.bestArmor(List.of(c(Gear.WORN, 6, 0), c(4, 3, 0), c(30, 8, 2))));
        // diamond (3, 2) against netherite (3, 3) boots
        assertEquals(8, Gear.bestArmor(List.of(c(Gear.WORN, 3, 2), c(8, 3, 3))));
    }

    @Test
    void wornArmorStaysUnlessSomethingBeatsIt() {
        assertEquals(Gear.WORN, Gear.bestArmor(List.of(c(Gear.WORN, 6, 0), c(4, 2, 0))), "a worse piece doesn't replace it");
        assertEquals(Gear.WORN, Gear.bestArmor(List.of(c(Gear.WORN, 6, 0), c(4, 6, 0))), "an equal piece doesn't either");
        assertEquals(4, Gear.bestArmor(List.of(c(Gear.WORN, 0, 0), c(4, 1, 0))), "an empty slot takes anything");
    }

    @Test
    void aShieldGoesOnlyIntoAnEmptyOffHand() {
        assertTrue(Gear.takesShield(true, 14));
        assertFalse(Gear.takesShield(false, 14), "the off-hand holds something: leave it");
        assertFalse(Gear.takesShield(true, -1), "no shield carried");
    }
}
