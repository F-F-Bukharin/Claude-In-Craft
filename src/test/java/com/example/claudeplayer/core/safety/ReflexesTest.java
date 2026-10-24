package com.example.claudeplayer.core.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Reflex decisions: which move a threat provokes at each caution level, and what counts as a hazard. */
class ReflexesTest {

    private static CautionPolicy.Situation s(double distance, double hp, boolean escape) {
        return new CautionPolicy.Situation(true, false, distance, distance <= 2.0, hp, escape, false);
    }

    @Test
    void horizontalIgnoresHeight() {
        assertEquals(5.0, Reflexes.horizontal(3, 4), 1e-9);
    }

    @Test
    void aLitCreeperMeansFleeAtEveryLevel() {
        for (CautionLevel level : CautionLevel.values()) {
            assertEquals(Reflexes.Move.FLEE, Reflexes.decide(level, s(2, 1.0, true), true, true), level.name());
        }
    }

    @Test
    void fleeStancesFlee() {
        assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.INNOCENT, s(10, 1.0, true), false, true));
        assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.INNOCENT, s(10, 0.2, true), false, true), "flee and call for help");
        assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.REASONABLE, s(8, 1.0, true), false, true));
    }

    @Test
    void fightStartsTheRoutineInOrOutOfReach() {
        assertEquals(Reflexes.Move.FIGHT, Reflexes.decide(CautionLevel.READY, s(2.5, 1.0, true), false, true));
        assertEquals(Reflexes.Move.FIGHT, Reflexes.decide(CautionLevel.READY, s(5, 1.0, true), false, true), "the fight walks to it");
        assertEquals(Reflexes.Move.FIGHT, Reflexes.decide(CautionLevel.GUNS_BLAZING, s(14, 1.0, true), false, true), "level 0 pursues");
        assertEquals(Reflexes.Move.FIGHT, Reflexes.decide(CautionLevel.REASONABLE, s(1.5, 1.0, false), false, true), "adjacent, no way out");
    }

    @Test
    void fightWithoutAttacksBecomesFlee() {
        assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.READY, s(2.5, 1.0, true), false, false));
    }

    @Test
    void aThreatBeyondHostileRangeGetsNoReflex() {
        // flee and fight only work on hostiles within hostile_range: a ghast's fireball from 30 blocks would scrap
        // routine work for a flee that answers ok:no_threats
        assertEquals(Reflexes.Move.NONE, Reflexes.decide(CautionLevel.REASONABLE, s(30, 1.0, true), false, true, 16), "flee stance");
        assertEquals(Reflexes.Move.NONE, Reflexes.decide(CautionLevel.INNOCENT, s(16.5, 0.2, true), false, true, 16));
        assertEquals(Reflexes.Move.NONE, Reflexes.decide(CautionLevel.GUNS_BLAZING, s(20, 1.0, true), false, true, 16), "fight stance");
        assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.REASONABLE, s(16, 1.0, true), false, true, 16), "16 is in range");
        assertEquals(Reflexes.Move.FIGHT, Reflexes.decide(CautionLevel.GUNS_BLAZING, s(14, 1.0, true), false, true, 16));
    }

    @Test
    void holdDoesNothing() {
        assertEquals(Reflexes.Move.NONE, Reflexes.decide(CautionLevel.READY, s(10, 1.0, true), false, true));
    }

    @Test
    void playersAndNonHostilesGetNoReflex() {
        CautionPolicy.Situation player = new CautionPolicy.Situation(false, true, 1.5, true, 1.0, true, false);
        CautionPolicy.Situation wolf = new CautionPolicy.Situation(false, false, 1.5, true, 1.0, true, false);
        for (CautionLevel level : CautionLevel.values()) {
            assertEquals(Reflexes.Move.NONE, Reflexes.decide(level, player, false, true), level.name());
            assertEquals(Reflexes.Move.NONE, Reflexes.decide(level, wolf, false, true), level.name());
        }
    }

    @Test
    void aPlayerFlaggedHostileStillGetsNoReflex() {
        CautionPolicy.Situation player = new CautionPolicy.Situation(true, true, 1.5, true, 1.0, true, false);
        for (CautionLevel level : CautionLevel.values()) {
            assertEquals(Reflexes.Move.NONE, Reflexes.decide(level, player, false, true), level.name());
        }
    }

    @Test
    void hazards() {
        assertEquals("lava", Reflexes.hazard("minecraft:lava", "minecraft:stone", 0, false, 20));
        assertEquals("lava", Reflexes.hazard("minecraft:air", "minecraft:lava", 0, false, 20));
        assertEquals("fire", Reflexes.hazard("minecraft:fire", "minecraft:stone", 0, false, 20));
        assertEquals("fire", Reflexes.hazard("minecraft:soul_fire", "minecraft:stone", 0, false, 20));
        assertEquals("drop", Reflexes.hazard("minecraft:air", "minecraft:air", 23, false, 20), "20 damage at 20 HP");
        assertNull(Reflexes.hazard("minecraft:air", "minecraft:air", 22, false, 20));
        assertNull(Reflexes.hazard("minecraft:air", "minecraft:air", 23, true, 20), "water breaks the fall");
        assertEquals("drop", Reflexes.hazard("minecraft:air", "minecraft:air", 6, false, 3));
        assertNull(Reflexes.hazard("minecraft:air", "minecraft:air", 3, false, 3), "3 blocks never hurt");
    }

    @Test
    void aChaseIsDueWithinSixBlocksOnceItsWaitIsOver() {
        assertTrue(Reflexes.chaseDue(4.0, 1000, 900, true, false), "in range and due");
        assertTrue(Reflexes.chaseDue(6.0, 1000, 900, true, false), "6 blocks is in range");
        assertFalse(Reflexes.chaseDue(6.5, 1000, 900, true, false), "too far");
        assertFalse(Reflexes.chaseDue(4.0, 1000, 900, true, true), "a level 3 is running");
        assertFalse(Reflexes.chaseDue(4.0, 1000, 1001, true, false), "due next tick");
        assertTrue(Reflexes.chaseDue(4.0, 1000, 1000, true, false), "due this tick");
    }

    @Test
    void aMoveWaitsTwoSecondsAndNoneHalfASecond() {
        assertEquals(40, Reflexes.chaseWait(true), "a flee or a fight");
        assertEquals(10, Reflexes.chaseWait(false), "NONE: a HOLD at 4 blocks gets its FIGHT soon once adjacent");
        long due = 1000 + Reflexes.chaseWait(false); // NONE at 4 blocks on tick 1000
        assertFalse(Reflexes.chaseDue(1.5, 1009, due, true, false));
        assertTrue(Reflexes.chaseDue(1.5, 1010, due, true, false), "adjacent 10 ticks later, rechecked then, not at 40");
    }

    @Test
    void aMobTheModelNeverHeardAboutNeverChases() {
        assertFalse(Reflexes.chaseDue(1.0, 1000, Long.MIN_VALUE, false, false), "never stamped, right next to the body");
        assertFalse(Reflexes.chaseDue(4.0, 1000, 900, false, false), "in range and past the gap");
    }

    @Test
    void litFusesAreNotHeldBackByTheGap() {
        ThreatTracker fuses = new ThreatTracker(0, 60);
        assertTrue(fuses.report("lit:1", 100));
        assertTrue(fuses.report("lit:2", 101), "a second creeper a tick later still counts");
        assertFalse(fuses.report("lit:1", 102), "one report per fuse");
    }

    @Test
    void aForgottenFuseReportsAgainAtOnce() {
        ThreatTracker fuses = new ThreatTracker(0, 60);
        assertTrue(fuses.report("lit:1", 100));
        assertFalse(fuses.report("lit:1", 102), "same fuse, still lit");
        fuses.forget("lit:1"); // seen unlit
        assertTrue(fuses.report("lit:1", 104), "lit again counts as a new fuse");
    }
}
