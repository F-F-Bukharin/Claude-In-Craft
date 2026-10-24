package com.example.claudeplayer.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** follow_player's gap rules: when to walk, when to stand, and when the path is stale. */
class FollowGapTest {

    @Test
    void startsWalkingOnlyPastTheStartGap() {
        assertFalse(FollowGap.walking(false, 3.0), "inside the band a standing body stays put");
        assertFalse(FollowGap.walking(false, FollowGap.START), "exactly at the start gap is not past it");
        assertTrue(FollowGap.walking(false, FollowGap.START + 0.01));
    }

    @Test
    void stopsOnlyUnderTheStopGap() {
        assertTrue(FollowGap.walking(true, 3.0), "inside the band a walking body keeps walking");
        assertTrue(FollowGap.walking(true, FollowGap.STOP), "exactly at the stop gap is not under it");
        assertFalse(FollowGap.walking(true, FollowGap.STOP - 0.01));
    }

    @Test
    void theBandIsWideEnoughNotToJitter() {
        assertTrue(FollowGap.START - FollowGap.STOP >= 1.0);
        assertTrue(FollowGap.STOP >= 2.0 && FollowGap.START <= 3.5);
    }

    @Test
    void aGapSwingingAcrossTheBandChangesStateOncePerCrossing() {
        boolean walking = false;
        int changes = 0;
        double[] gaps = {2.5, 3.2, 3.4, 3.6, 3.3, 2.9, 2.4, 2.1, 1.9, 2.4, 3.0, 3.3, 3.6, 3.1};
        for (double gap : gaps) {
            boolean now = FollowGap.walking(walking, gap);
            if (now != walking) changes++;
            walking = now;
        }
        assertEquals(3, changes, "start at 3.6, stop at 1.9, start at 3.6: three flips, no chatter");
    }

    @Test
    void noPathRetriesOnlyAtTheRateLimit() {
        assertFalse(FollowGap.replan(false, 0, 0, 0, 0, 0, 0, 0));
        assertFalse(FollowGap.replan(false, FollowGap.RETRY_TICKS - 1, 0, 0, 0, 0, 0, 0));
        assertTrue(FollowGap.replan(false, FollowGap.RETRY_TICKS, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void aFreshStartPlansAtOnce() {
        assertTrue(FollowGap.replan(false, FollowGap.NEVER, 0, 0, 0, 5, 0, 5));
    }

    @Test
    void aTargetThatMovedFarReplansOnceTheMinimumHasPassed() {
        assertTrue(FollowGap.replan(true, FollowGap.MIN_TICKS, 0, 64, 0, 3.5, 64, 0));
        assertTrue(FollowGap.replan(true, FollowGap.MIN_TICKS, 0, 64, 0, 0, 64, -6));
    }

    @Test
    void aTargetThatMovedLittleKeepsThePath() {
        assertFalse(FollowGap.replan(true, FollowGap.MIN_TICKS, 0, 64, 0, 3.0, 64, 0), "exactly 3 blocks is not more than 3");
        assertFalse(FollowGap.replan(true, FollowGap.MIN_TICKS, 0, 64, 0, 1, 64, 1));
    }

    @Test
    void neverReplansFasterThanTheMinimum() {
        assertFalse(FollowGap.replan(true, FollowGap.MIN_TICKS - 1, 0, 64, 0, 40, 64, 40), "a sprinting target cannot cause a search every tick");
        assertFalse(FollowGap.replan(true, 0, 0, 64, 0, 40, 64, 40));
    }

    @Test
    void aStalePathIsReplannedEvenIfTheTargetStoodStill() {
        assertFalse(FollowGap.replan(true, FollowGap.FALLBACK_TICKS - 1, 0, 64, 0, 0, 64, 0));
        assertTrue(FollowGap.replan(true, FollowGap.FALLBACK_TICKS, 0, 64, 0, 0, 64, 0));
    }

    @Test
    void heightCountsTowardTheMove() {
        assertTrue(FollowGap.replan(true, FollowGap.MIN_TICKS, 0, 64, 0, 0, 68.5, 0), "the player went up a tower");
    }
}
