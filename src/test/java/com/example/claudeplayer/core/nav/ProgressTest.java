package com.example.claudeplayer.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.claudeplayer.core.nav.Progress.Decision;
import org.junit.jupiter.api.Test;

/** Stuck detection for move_to: jump after 1 s without progress, re-plan after 2 s, give up after 3 re-plans. */
class ProgressTest {

    /** Feeds a body that stands still at {@code dist} from node {@code index}, from tick {@code from} to {@code to}. */
    private static Decision lastDecision(Progress p, int from, int to, double dist, int index) {
        Decision last = Decision.NONE;
        for (int t = from; t <= to; t++) {
            Decision d = p.update(t, dist, index);
            if (d != Decision.NONE) last = d;
        }
        return last;
    }

    @Test
    void closingInNeverTriggersAnything() {
        Progress p = new Progress(0);
        double dist = 10;
        for (int t = 1; t <= 200; t++) {
            dist -= 0.2;
            assertEquals(Decision.NONE, p.update(t, dist, 0), "tick " + t);
        }
    }

    @Test
    void aStandstillJumpsAfterAboutOneSecond() {
        Progress p = new Progress(0);
        p.update(1, 5.0, 0); // baseline
        for (int t = 2; t < Progress.JUMP_AFTER; t++) assertEquals(Decision.NONE, p.update(t, 5.0, 0), "tick " + t);
        assertEquals(Decision.JUMP, p.update(Progress.JUMP_AFTER, 5.0, 0));
    }

    @Test
    void theJumpIsAskedOncePerStall() {
        Progress p = new Progress(0);
        p.update(1, 5.0, 0);
        assertEquals(Decision.JUMP, p.update(21, 5.0, 0));
        for (int t = 22; t < 40; t++) assertEquals(Decision.NONE, p.update(t, 5.0, 0), "tick " + t);
    }

    @Test
    void aStandstillReplansAfterAboutTwoSeconds() {
        Progress p = new Progress(0);
        p.update(1, 5.0, 0);
        assertEquals(Decision.JUMP, p.update(21, 5.0, 0));
        assertEquals(Decision.REPLAN, p.update(41, 5.0, 0));
        assertEquals(1, p.replans());
    }

    @Test
    void afterTheThirdReplanTheNextStallGivesUp() {
        Progress p = new Progress(0);
        p.update(1, 5.0, 0);
        assertEquals(Decision.REPLAN, lastDecision(p, 2, 41, 5.0, 0)); // 1st
        assertEquals(Decision.REPLAN, lastDecision(p, 42, 81, 5.0, 0)); // 2nd
        assertEquals(Decision.REPLAN, lastDecision(p, 82, 121, 5.0, 0)); // 3rd
        assertEquals(3, p.replans());
        assertEquals(Decision.GIVE_UP, lastDecision(p, 122, 161, 5.0, 0));
    }

    @Test
    void progressResetsTheCountersAndTheReplanBudget() {
        Progress p = new Progress(0);
        p.update(1, 5.0, 0);
        lastDecision(p, 2, 81, 5.0, 0); // two re-plans
        assertEquals(2, p.replans());
        assertEquals(Decision.NONE, p.update(82, 4.0, 0)); // closing in again
        assertEquals(0, p.replans());
        assertEquals(Decision.NONE, lastDecision(p, 83, 100, 4.0, 0)); // the stall clock restarted at 82
        assertEquals(Decision.JUMP, p.update(102, 4.0, 0));
    }

    @Test
    void advancingToTheNextNodeCountsAsProgressEvenIfTheDistanceGrew() {
        Progress p = new Progress(0);
        p.update(1, 0.5, 0);
        lastDecision(p, 2, 19, 0.5, 0); // 18 ticks stalled
        assertEquals(Decision.NONE, p.update(20, 1.5, 1)); // node 1 is farther than node 0 was
        assertEquals(Decision.NONE, lastDecision(p, 21, 38, 1.5, 1));
    }

    @Test
    void tinyJitterIsNotProgress() {
        Progress p = new Progress(0);
        p.update(1, 5.0, 0);
        for (int t = 2; t < 20; t++) assertEquals(Decision.NONE, p.update(t, 5.0 - 0.01 * (t % 2), 0));
        assertEquals(Decision.JUMP, p.update(20, 5.0, 0));
    }

    @Test
    void aReplanRestartsTheStallClockButKeepsTheBudget() {
        Progress p = new Progress(0);
        p.update(1, 5.0, 0);
        assertEquals(Decision.REPLAN, lastDecision(p, 2, 41, 5.0, 0));
        // the new path starts at node 0 again, at a different distance: a new baseline, not progress
        assertEquals(Decision.NONE, p.update(42, 7.0, 0));
        assertEquals(1, p.replans());
        assertEquals(Decision.JUMP, lastDecision(p, 43, 62, 7.0, 0));
    }

    @Test
    void aNewPathWithALowerIndexIsABaselineNotProgress() {
        Progress p = new Progress(0);
        p.update(1, 1.0, 5);
        assertEquals(Decision.NONE, p.update(2, 3.0, 0)); // path replaced: index fell back to 0
        assertEquals(Decision.JUMP, lastDecision(p, 3, 22, 3.0, 0)); // and the stall clock kept running from tick 1
    }

    @Test
    void resetClearsEverything() {
        Progress p = new Progress(0);
        p.update(1, 5.0, 0);
        lastDecision(p, 2, 81, 5.0, 0);
        p.reset(100);
        assertEquals(0, p.replans());
        assertEquals(Decision.NONE, lastDecision(p, 101, 118, 5.0, 0));
        assertEquals(Decision.JUMP, p.update(121, 5.0, 0));
    }
}
