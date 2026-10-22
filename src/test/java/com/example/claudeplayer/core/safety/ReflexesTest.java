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
            assertEquals(Reflexes.Move.FLEE, Reflexes.decide(level, s(2, 1.0, true), true, true, 3.0), level.name());
        }
    }

    @Test
    void fleeStancesFlee() {
        assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.INNOCENT, s(10, 1.0, true), false, true, 3.0));
        assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.INNOCENT, s(10, 0.2, true), false, true, 3.0), "flee and call for help");
        assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.REASONABLE, s(8, 1.0, true), false, true, 3.0));
    }

    @Test
    void fightOnlyWithinReach() {
        assertEquals(Reflexes.Move.FIGHT, Reflexes.decide(CautionLevel.READY, s(2.5, 1.0, true), false, true, 3.0));
        assertEquals(Reflexes.Move.NONE, Reflexes.decide(CautionLevel.READY, s(5, 1.0, true), false, true, 3.0), "Claude plans the approach");
    }

    @Test
    void fightWithoutAttacksBecomesFlee() {
        assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.READY, s(2.5, 1.0, true), false, false, 3.0));
    }

    @Test
    void holdDoesNothing() {
        assertEquals(Reflexes.Move.NONE, Reflexes.decide(CautionLevel.READY, s(10, 1.0, true), false, true, 3.0));
    }

    @Test
    void playersAndNonHostilesGetNoReflex() {
        CautionPolicy.Situation player = new CautionPolicy.Situation(false, true, 1.5, true, 1.0, true, false);
        CautionPolicy.Situation wolf = new CautionPolicy.Situation(false, false, 1.5, true, 1.0, true, false);
        for (CautionLevel level : CautionLevel.values()) {
            assertEquals(Reflexes.Move.NONE, Reflexes.decide(level, player, false, true, 3.0), level.name());
            assertEquals(Reflexes.Move.NONE, Reflexes.decide(level, wolf, false, true, 3.0), level.name());
        }
    }

    @Test
    void aPlayerFlaggedHostileStillGetsNoReflex() {
        CautionPolicy.Situation player = new CautionPolicy.Situation(true, true, 1.5, true, 1.0, true, false);
        for (CautionLevel level : CautionLevel.values()) {
            assertEquals(Reflexes.Move.NONE, Reflexes.decide(level, player, false, true, 3.0), level.name());
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
