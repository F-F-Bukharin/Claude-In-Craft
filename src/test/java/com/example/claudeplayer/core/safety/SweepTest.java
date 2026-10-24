package com.example.claudeplayer.core.safety;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Vanilla's sweep condition, and why the body's swings never sweep: Swing marks them as sprint hits. */
class SweepTest {
    @Test
    void aChargedSwordSwingOnTheGroundWithoutSprintSweeps() {
        assertTrue(Sweep.sweeps(1.0f, false, false, true, 0.0, 0.1, true));
    }

    @Test
    void aSprintSwingNeverSweeps() {
        float[] charges = {0.0f, 0.5f, 0.9f, 0.91f, 1.0f};
        boolean[] bools = {false, true};
        for (float charge : charges) {
            for (boolean critical : bools) {
                for (boolean onGround : bools) {
                    for (boolean tool : bools) {
                        for (double walked : new double[] {0.0, 0.05, 1.0}) {
                            assertFalse(Sweep.sweeps(charge, true, critical, onGround, walked, 0.1, tool),
                                    "charge " + charge + " crit " + critical + " ground " + onGround + " tool " + tool + " walked " + walked);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theOtherVanillaConditionsAlsoBlockIt() {
        assertFalse(Sweep.sweeps(0.9f, false, false, true, 0.0, 0.1, true), "charge must be above 0.9");
        assertFalse(Sweep.sweeps(1.0f, false, true, true, 0.0, 0.1, true), "a critical hit does not sweep");
        assertFalse(Sweep.sweeps(1.0f, false, false, false, 0.0, 0.1, true), "in the air");
        assertFalse(Sweep.sweeps(1.0f, false, false, true, 0.1, 0.1, true), "walked as far as the speed");
        assertFalse(Sweep.sweeps(1.0f, false, false, true, 0.0, 0.1, false), "not a sweeping tool");
    }
}
