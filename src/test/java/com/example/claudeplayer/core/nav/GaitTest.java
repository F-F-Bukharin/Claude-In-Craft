package com.example.claudeplayer.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.nav.Gait.Swim;
import com.example.claudeplayer.core.nav.PathSmoother.Cell;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Sprint and swim decisions, and the straight-run finder behind sprinting. */
class GaitTest {

    // ---- sprint -----------------------------------------------------------------------------------------------

    @Test
    void sprintsOnALongStraightRunWithFood() {
        assertTrue(Gait.sprint(false, 20, false, 8.0, false));
        assertTrue(Gait.sprint(false, 7, false, 12.0, false));
    }

    @Test
    void doesNotStartOnAShortRun() {
        assertFalse(Gait.sprint(false, 20, false, 7.9, false));
    }

    @Test
    void foodMustBeAboveSix() {
        assertFalse(Gait.sprint(false, 6, false, 20.0, false));
        assertFalse(Gait.sprint(true, 6, false, 20.0, false));
        assertFalse(Gait.sprint(false, 0, false, 20.0, false));
    }

    @Test
    void neverInWater() {
        assertFalse(Gait.sprint(false, 20, true, 20.0, false));
        assertFalse(Gait.sprint(true, 20, true, 20.0, false));
    }

    @Test
    void neverNearAHazard() {
        assertFalse(Gait.sprint(false, 20, false, 20.0, true));
        assertFalse(Gait.sprint(true, 20, false, 20.0, true));
    }

    @Test
    void keepsSprintingDownToThreeBlocksThenStopsBeforeTheTurn() {
        assertTrue(Gait.sprint(true, 20, false, 3.0, false));
        assertTrue(Gait.sprint(true, 20, false, 5.0, false));
        assertFalse(Gait.sprint(true, 20, false, 2.9, false));
        // and does not restart on the same short remainder
        assertFalse(Gait.sprint(false, 20, false, 5.0, false));
    }

    // ---- straight runs ----------------------------------------------------------------------------------------

    private static List<Cell> cells(int... xyz) {
        List<Cell> out = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) out.add(new Cell(xyz[i], xyz[i + 1], xyz[i + 2]));
        return out;
    }

    @Test
    void aStraightLineRunsToItsLastNode() {
        List<Cell> path = cells(0, 64, 0, 1, 64, 0, 2, 64, 0, 3, 64, 0, 4, 64, 0);
        assertEquals(4, Gait.straightRunEnd(path, 0));
        assertEquals(4, Gait.straightRunEnd(path, 2));
    }

    @Test
    void aTurnEndsTheRun() {
        List<Cell> path = cells(0, 64, 0, 1, 64, 0, 2, 64, 0, 2, 64, 1, 2, 64, 2);
        assertEquals(2, Gait.straightRunEnd(path, 0));
        assertEquals(4, Gait.straightRunEnd(path, 2));
    }

    @Test
    void aDiagonalLineIsStraightToo() {
        List<Cell> path = cells(0, 64, 0, 1, 64, 1, 2, 64, 2, 3, 64, 3);
        assertEquals(3, Gait.straightRunEnd(path, 0));
    }

    @Test
    void aHeightChangeEndsTheRun() {
        List<Cell> up = cells(0, 64, 0, 1, 64, 0, 2, 65, 0, 3, 65, 0);
        assertEquals(1, Gait.straightRunEnd(up, 0));
        List<Cell> startsWithAStep = cells(0, 64, 0, 1, 65, 0, 2, 65, 0);
        assertEquals(0, Gait.straightRunEnd(startsWithAStep, 0));
    }

    @Test
    void theRunOfAnEndNodeOrAnEmptyPathIsItself() {
        List<Cell> path = cells(0, 64, 0, 1, 64, 0);
        assertEquals(1, Gait.straightRunEnd(path, 1));
        assertEquals(0, Gait.straightRunEnd(cells(), 0));
    }

    @Test
    void aStaircasePathHasNoLongRun() {
        List<Cell> path = cells(0, 64, 0, 1, 64, 0, 1, 64, 1, 2, 64, 1, 2, 64, 2, 3, 64, 2);
        assertEquals(1, Gait.straightRunEnd(path, 0));
    }

    // ---- swimming ---------------------------------------------------------------------------------------------

    @Test
    void onDryLandThereIsNoSwimming() {
        assertEquals(Swim.NONE, Gait.swim(false, false, true, 300, 300, true));
    }

    @Test
    void floatingInWaterHoldsJumpAndStrokesTowardTheExit() {
        assertEquals(Swim.STROKE, Gait.swim(true, false, false, 300, 300, true));
        assertEquals(Swim.STROKE, Gait.swim(true, true, false, 300, 300, true));
    }

    @Test
    void wadingOnTheBottomWithTheHeadOutJustWalks() {
        assertEquals(Swim.NONE, Gait.swim(true, false, true, 300, 300, true));
    }

    @Test
    void standingOnTheBottomWithTheHeadUnderSwimsUp() {
        assertEquals(Swim.STROKE, Gait.swim(true, true, true, 300, 300, true));
    }

    @Test
    void lowAirUnderWaterMeansStraightUpWhenThereIsAWayUp() {
        assertEquals(Swim.RISE, Gait.swim(true, true, false, 99, 300, true));
        assertEquals(Swim.RISE, Gait.swim(true, true, false, 0, 300, true));
    }

    @Test
    void airAtAThirdIsNotYetLow() {
        assertEquals(Swim.STROKE, Gait.swim(true, true, false, 100, 300, true));
    }

    @Test
    void lowAirButNoWayUpKeepsSwimmingForTheExit() {
        assertEquals(Swim.STROKE, Gait.swim(true, true, false, 50, 300, false));
    }

    @Test
    void lowAirWithTheHeadAlreadyOutIsFine() {
        assertEquals(Swim.STROKE, Gait.swim(true, false, false, 50, 300, true));
    }

    @Test
    void swimmingHoldsJumpAndRiseStopsWalking() {
        assertFalse(Swim.NONE.holdsJump());
        assertTrue(Swim.STROKE.holdsJump());
        assertTrue(Swim.RISE.holdsJump());
        assertTrue(Swim.STROKE.walks());
        assertFalse(Swim.RISE.walks());
    }
}
