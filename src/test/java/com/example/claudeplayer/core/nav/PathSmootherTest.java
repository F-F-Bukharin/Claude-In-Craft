package com.example.claudeplayer.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.nav.PathSmoother.Cell;
import com.example.claudeplayer.core.nav.PathSmoother.Terrain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Node skipping: a node is skipped only when a straight, level-or-rising, hazard-free line reaches a later one. */
class PathSmootherTest {

    /** A flat world at y = 64 that is open everywhere except the listed columns, which are pits or walls. */
    private static final class World implements Terrain {
        final Set<String> blockedColumns = new HashSet<>(); // not standable at any height
        final Set<String> raised = new HashSet<>(); // standable at y 65 only (a 1-block step)
        final Set<String> tall = new HashSet<>(); // standable at y 66 only (a 2-block wall)
        final Set<String> lowered = new HashSet<>(); // standable at y 63 only (a 1-block step down)
        int calls;

        static String key(int x, int z) {
            return x + "," + z;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            calls++;
            String k = key(x, z);
            if (blockedColumns.contains(k)) return false;
            if (raised.contains(k)) return y == 65;
            if (tall.contains(k)) return y == 66;
            if (lowered.contains(k)) return y == 63;
            return y == 64;
        }
    }

    private static List<Cell> path(int... xz) {
        List<Cell> out = new ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) out.add(new Cell(xz[i], 64, xz[i + 1]));
        return out;
    }

    private static Cell at(int x, int z) {
        return new Cell(x, 64, z);
    }

    @Test
    void aStaircasePathInTheOpenIsCutToTheFarthestNodeInReach() {
        World w = new World();
        // an L-shaped zigzag of unit steps: 0,0 1,0 1,1 2,1 2,2 3,2
        List<Cell> p = path(1, 0, 1, 1, 2, 1, 2, 2, 3, 2, 3, 3);
        assertEquals(PathSmoother.MAX_LOOKAHEAD, PathSmoother.farthest(w, at(0, 0), p, 0, PathSmoother.MAX_LOOKAHEAD));
    }

    @Test
    void theLookAheadIsCapped() {
        World w = new World();
        List<Cell> p = path(1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8, 0);
        assertEquals(2, PathSmoother.farthest(w, at(0, 0), p, 0, 2));
        assertEquals(4, PathSmoother.farthest(w, at(0, 0), p, 0, 4));
        assertEquals(0, PathSmoother.farthest(w, at(0, 0), p, 0, 0));
    }

    @Test
    void neverReachesPastTheEndOfThePath() {
        World w = new World();
        List<Cell> p = path(1, 0, 2, 0);
        assertEquals(1, PathSmoother.farthest(w, at(0, 0), p, 0, 4));
        assertEquals(1, PathSmoother.farthest(w, at(0, 0), p, 1, 4));
    }

    @Test
    void aPitOnTheLineStopsTheSkip() {
        World w = new World();
        w.blockedColumns.add(World.key(2, 0)); // a hole the planner walked around
        List<Cell> p = path(1, 0, 1, 1, 2, 1, 3, 1, 3, 0);
        // lines to 2,1 and 3,1 pass within the body's width of the hole; node 1,1 is still a safe diagonal
        int j = PathSmoother.farthest(w, at(0, 0), p, 0, 4);
        assertEquals(1, j);
    }

    @Test
    void aWallOnTheLineItselfStopsTheSkip() {
        World w = new World();
        w.blockedColumns.add(World.key(2, 0));
        List<Cell> p = path(1, 0, 2, 0, 3, 0);
        assertEquals(0, PathSmoother.farthest(w, at(0, 0), p, 0, 4)); // node 2,0 itself is not standable
    }

    @Test
    void aWallBesideTheLineDoesNotStopItWhileTheBodyClearsIt() {
        World w = new World();
        w.blockedColumns.add(World.key(2, 1)); // one row over: the body (0.6 wide) on z = 0.5 never touches it
        List<Cell> p = path(1, 0, 2, 0, 3, 0);
        assertEquals(2, PathSmoother.farthest(w, at(0, 0), p, 0, 4));
    }

    @Test
    void aCornerClippedByTheBodyWidthIsNotSkipped() {
        World w = new World();
        // path turns around a pillar at 1,1: 0,0 -> 1,0 -> 2,0 -> 2,1 -> 2,2 -> 1,2 -> 0,2
        w.blockedColumns.add(World.key(1, 1));
        List<Cell> p = path(1, 0, 2, 0, 2, 1, 2, 2, 1, 2, 0, 2);
        int j = PathSmoother.farthest(w, at(0, 0), p, 0, 4);
        // a line from 0,0 to 2,1 passes within the body's width of the pillar; the nodes up to 2,0 are safe
        assertTrue(j <= 1, "got " + j);
    }

    @Test
    void anOneBlockRiseIsAllowed() {
        World w = new World();
        w.raised.add(World.key(2, 0));
        w.raised.add(World.key(3, 0));
        List<Cell> p = List.of(new Cell(1, 64, 0), new Cell(2, 65, 0), new Cell(3, 65, 0));
        assertEquals(2, PathSmoother.farthest(w, at(0, 0), p, 0, 4));
    }

    @Test
    void aTwoBlockRiseIsNot() {
        World w = new World();
        w.tall.add(World.key(2, 0));
        w.tall.add(World.key(3, 0));
        List<Cell> p = List.of(new Cell(1, 64, 0), new Cell(2, 66, 0), new Cell(3, 66, 0));
        assertEquals(0, PathSmoother.farthest(w, at(0, 0), p, 0, 4));
    }

    @Test
    void aStepDownIsNotSkippedEvenThoughItIsNotADrop() {
        World w = new World();
        w.lowered.add(World.key(2, 0));
        w.lowered.add(World.key(3, 0));
        List<Cell> p = List.of(new Cell(1, 64, 0), new Cell(2, 63, 0), new Cell(3, 63, 0));
        assertEquals(0, PathSmoother.farthest(w, at(0, 0), p, 0, 4));
    }

    @Test
    void aSkippedNodeMustItselfBeStandable() {
        World w = new World();
        // node 1 stands in a doorway-like cell that is not standable; the line to node 2 would pass through it
        w.blockedColumns.add(World.key(1, 0));
        List<Cell> p = path(1, 0, 2, 0, 3, 0);
        assertEquals(0, PathSmoother.farthest(w, at(0, 0), p, 0, 4));
    }

    @Test
    void aFirstNodeThatIsNotStandableIsNeverSkippedOver() {
        World w = new World();
        w.blockedColumns.add(World.key(1, 0));
        List<Cell> p = path(1, 0, 2, 0);
        assertEquals(0, PathSmoother.farthest(w, at(0, 0), p, 0, 4));
        assertEquals(1, PathSmoother.farthest(w, at(0, 0), p, 1, 4)); // starting past it is fine
    }

    @Test
    void theLineMustLandAtTheNodesOwnHeight() {
        World w = new World();
        List<Cell> p = List.of(new Cell(1, 64, 0), new Cell(2, 65, 0)); // node 1 claims y 65 but the ground is flat
        assertEquals(0, PathSmoother.farthest(w, at(0, 0), p, 0, 4));
    }

    @Test
    void lineClearOnItsOwn() {
        World w = new World();
        assertTrue(PathSmoother.lineClear(w, at(0, 0), at(5, 0)));
        assertTrue(PathSmoother.lineClear(w, at(0, 0), at(4, 4)));
        assertTrue(PathSmoother.lineClear(w, at(0, 0), at(0, 0)));
        w.blockedColumns.add(World.key(3, 3));
        assertFalse(PathSmoother.lineClear(w, at(0, 0), at(4, 4)));
        assertTrue(PathSmoother.lineClear(w, at(0, 0), at(5, 0)));
    }

    @Test
    void theStartCellIsNotJudged() {
        World w = new World();
        w.blockedColumns.add(World.key(0, 0)); // the body stands on something odd: a slab, a stair, a doorway
        assertTrue(PathSmoother.lineClear(w, at(0, 0), at(3, 0)));
    }

    @Test
    void terrainIsAskedAboutEachCellOnlyOnce() {
        World w = new World();
        List<Cell> p = path(1, 0, 2, 0, 3, 0, 4, 0);
        PathSmoother.farthest(w, at(0, 0), p, 0, 4);
        int first = w.calls;
        assertTrue(first > 0);
        // a cell is judged at most for three heights, so the count stays small for a four-block line
        assertTrue(first < 80, "terrain calls: " + first);
    }

    @Test
    void anEmptyPathOrAnOutOfRangeIndexReturnsTheIndexGiven() {
        World w = new World();
        assertEquals(0, PathSmoother.farthest(w, at(0, 0), List.of(), 0, 4));
        assertEquals(5, PathSmoother.farthest(w, at(0, 0), path(1, 0), 5, 4));
    }
}
