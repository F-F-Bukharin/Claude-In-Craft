package com.example.claudeplayer.core.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The fight routine's pure rules: what the policy's verdict means for the fight, and when a swing is ready. */
class FightRulesTest {

    private static CautionPolicy.Situation s(double distance, boolean escape) {
        return new CautionPolicy.Situation(true, false, distance, distance <= 2.0, 1.0, escape, false);
    }

    private static FightRules.Step step(CautionLevel level, CautionPolicy.Situation s) {
        return FightRules.step(CautionPolicy.check(level, s), s.distance(), 3.0);
    }

    @Test
    void allowedWithinReachStrikesAndBeyondItApproaches() {
        assertEquals(FightRules.Step.STRIKE, step(CautionLevel.READY, s(2.5, true)));
        assertEquals(FightRules.Step.APPROACH, step(CautionLevel.READY, s(5, true)), "out of reach is not a denial: walk closer");
        assertEquals(FightRules.Step.APPROACH, step(CautionLevel.GUNS_BLAZING, s(15, true)), "level 0 pursues");
    }

    @Test
    void leavingTheLevelsRangeEndsTheFightAsOk() {
        assertEquals(FightRules.Step.LEFT_RANGE, step(CautionLevel.GUNS_BLAZING, s(17, true)));
        assertEquals(FightRules.Step.LEFT_RANGE, step(CautionLevel.READY, s(6.5, true)), "level 2 holds within 6");
        assertEquals(FightRules.Step.LEFT_RANGE, step(CautionLevel.REASONABLE, s(2.5, false)), "level 3 fights only adjacent");
        assertEquals(FightRules.Step.LEFT_RANGE, step(CautionLevel.CORNERED, s(2.5, false)));
    }

    @Test
    void anyOtherDenialEndsTheFight() {
        assertEquals(FightRules.Step.DENIED, step(CautionLevel.REASONABLE, s(1.5, true)), "retreat_first");
        assertEquals(FightRules.Step.DENIED, step(CautionLevel.CORNERED, s(1.5, true)), "escape_first");
        assertEquals(FightRules.Step.DENIED, step(CautionLevel.INNOCENT, s(1.5, false)), "innocent");
        CautionPolicy.Situation player = new CautionPolicy.Situation(true, true, 1.5, true, 1.0, false, false);
        for (CautionLevel level : CautionLevel.values()) {
            assertEquals(FightRules.Step.DENIED, step(level, player), "players are never targets: " + level);
        }
        CautionPolicy.Situation hurt = new CautionPolicy.Situation(true, false, 2, true, 0.1, true, false);
        assertEquals(FightRules.Step.DENIED, step(CautionLevel.GUNS_BLAZING, hurt), "low_hp_retreat");
    }

    @Test
    void cornerCasesOfTheAdjacentLevels() {
        assertEquals(FightRules.Step.STRIKE, step(CautionLevel.REASONABLE, s(1.5, false)), "adjacent with no way out");
        CautionPolicy.Situation fled = new CautionPolicy.Situation(true, false, 1.5, true, 1.0, true, true);
        assertEquals(FightRules.Step.STRIKE, step(CautionLevel.CORNERED, fled), "hurt while fleeing: escape failed");
    }

    @Test
    void aSwingIsReadyWithinReachAtNinetyPercentCharge() {
        assertTrue(FightRules.swingReady(2.9, 3.0, 0.9f));
        assertTrue(FightRules.swingReady(3.0, 3.0, 1.0f), "3 blocks is in reach");
        assertFalse(FightRules.swingReady(2.0, 3.0, 0.89f), "still charging");
        assertFalse(FightRules.swingReady(3.1, 3.0, 1.0f), "out of reach");
    }

    @Test
    void theReportSaysWhoAndWhatItCost() {
        assertEquals("won: zombie #213, took 4 HP", FightRules.won("zombie #213", 4.0, ""));
        assertEquals("won: skeleton #9, took 2.5 HP; equipped iron_sword, shield", FightRules.won("skeleton #9", 2.5, "iron_sword, shield"));
        assertEquals("won: zombie #1, took 0 HP", FightRules.won("zombie #1", -1.0, ""), "healing during the fight is not a negative cost");
    }
}
