package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.work.RoamPlan.Waypoint;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** roam's planner: which direction to explore next, skipping the unreachable, the turn back, and the report. */
class RoamPlanTest {
    private static RoamPlan plan(int radius, double minutes) {
        return new RoamPlan(100, 64, -50, radius, minutes, 0);
    }

    private static double dist(Waypoint w) {
        return Math.hypot(w.x() - 100, w.z() + 50);
    }

    /** A point {@code d} blocks from the start (100,-50) in the middle of the sector. */
    private static int[] inSector(int sector, int d) {
        double theta = Math.toRadians(sector * 45.0);
        return new int[] {100 + (int) Math.round(Math.sin(theta) * d), -50 - (int) Math.round(Math.cos(theta) * d)};
    }

    @Test
    void sectorsAreTheCompassWithNorthTowardMinusZ() {
        assertEquals(0, RoamPlan.sectorOf(0, -10), "north is -z");
        assertEquals(1, RoamPlan.sectorOf(10, -10));
        assertEquals(2, RoamPlan.sectorOf(10, 0), "east is +x");
        assertEquals(4, RoamPlan.sectorOf(0, 10), "south is +z");
        assertEquals(6, RoamPlan.sectorOf(-10, 0));
        assertEquals(7, RoamPlan.sectorOf(-10, -10));
        assertEquals(-1, RoamPlan.sectorOf(0, 0));
        assertEquals("N", RoamPlan.sectorName(0));
        assertEquals("SW", RoamPlan.sectorName(5));
    }

    @Test
    void firstWaypointStartsAtTheGivenSector() {
        assertEquals(0, plan(32, 2).next().sector());
        assertEquals(3, new RoamPlan(0, 64, 0, 32, 2, 3).next().sector());
    }

    @Test
    void choosesTheLeastSeenSector() {
        RoamPlan p = plan(32, 2);
        for (int s = 0; s < RoamPlan.SECTORS; s++) {
            if (s == 5) continue;
            int[] at = inSector(s, 20);
            p.visit(at[0], at[1]);
        }
        assertEquals(5, p.next().sector(), "south-west is the only one never visited");
    }

    @Test
    void moreVisitsMeansLessWantedAndRepeatedPicksSpreadOut() {
        RoamPlan p = plan(32, 2);
        for (int i = 0; i < 6; i++) {
            int[] at = inSector(0, 8 + 8 * i); // a long walk north
            p.visit(at[0], at[1]);
        }
        Set<Integer> picked = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            Waypoint w = p.next();
            assertNotNull(w);
            picked.add(w.sector());
            p.reached(w);
        }
        assertEquals(7, picked.size(), "seven picks, seven different directions");
        assertFalse(picked.contains(0), "north was already walked");
    }

    @Test
    void waypointsStayWithinTheRadiusAndFarEnoughOut() {
        for (int radius : new int[] {8, 9, 16, 32, 64}) {
            RoamPlan p = plan(radius, 2);
            for (int i = 0; i < 20; i++) {
                Waypoint w = p.next();
                assertNotNull(w);
                assertTrue(dist(w) <= radius, "radius " + radius + " but " + dist(w));
                assertTrue(dist(w) >= radius / 2.0 - 1, "radius " + radius + " but only " + dist(w));
                assertEquals(w.sector(), RoamPlan.sectorOf(w.x() - 100, w.z() + 50), "the waypoint lies in the sector it claims");
                p.reached(w);
            }
        }
    }

    @Test
    void anUnreachableWaypointIsSkippedAndItsSectorNotRepeatedAtOnce() {
        RoamPlan p = plan(32, 2);
        Waypoint first = p.next();
        p.unreachable(first);
        Waypoint second = p.next();
        assertNotNull(second);
        assertNotEquals(first.sector(), second.sector());
        assertEquals(0, p.reachedCount());
        assertFalse(p.gaveUp());
    }

    @Test
    void aSectorThatFailsTwiceIsDroppedForGood() {
        RoamPlan p = plan(32, 2);
        Waypoint a = p.next();
        p.unreachable(a);
        // keep failing every other waypoint but come back to the same sector by making the rest look busy
        for (int s = 0; s < RoamPlan.SECTORS; s++) {
            if (s == a.sector()) continue;
            for (int i = 0; i < 40; i++) {
                int[] at = inSector(s, 20 + i * 8);
                p.visit(at[0], at[1]);
            }
        }
        Waypoint b = p.next();
        assertEquals(a.sector(), b.sector(), "the only sector with no visits is the one that failed once");
        p.unreachable(b);
        for (int i = 0; i < 10; i++) {
            Waypoint w = p.next();
            if (w == null) break;
            assertNotEquals(a.sector(), w.sector(), "two failures and it is out");
            p.reached(w);
        }
    }

    @Test
    void givesUpWhenNothingIsReachable() {
        RoamPlan p = plan(32, 2);
        int tries = 0;
        Waypoint w;
        while ((w = p.next()) != null) {
            p.unreachable(w);
            assertTrue(++tries <= RoamPlan.SECTORS * RoamPlan.MAX_FAILS_PER_SECTOR, "it must stop");
        }
        assertTrue(p.gaveUp());
        assertEquals(0, p.reachedCount());
        assertNull(p.next());
        assertTrue(tries >= 2, "it tried more than once before giving up");
    }

    @Test
    void oneReachedWaypointKeepsItGoingThroughLaterFailures() {
        RoamPlan p = plan(32, 2);
        p.reached(p.next());
        for (int i = 0; i < 6; i++) {
            Waypoint w = p.next();
            assertNotNull(w, "failure " + i);
            p.unreachable(w);
        }
        assertFalse(p.gaveUp(), "something was reached, so it can still go home with a report");
        assertEquals(1, p.reachedCount());
    }

    @Test
    void turnsBackAtHalfTime() {
        RoamPlan p = plan(32, 2);
        assertEquals(2400, p.totalTicks(), "2 minutes");
        assertFalse(p.turnBack(0));
        assertFalse(p.turnBack(1199));
        assertTrue(p.turnBack(1200));
        assertTrue(p.turnBack(2400));
        assertEquals(600, plan(8, 0.5).totalTicks());
        assertEquals(6000, plan(64, 5).totalTicks());
        assertTrue(plan(8, 0.5).turnBack(300));
    }

    @Test
    void anEmptyRoamSaysSo() {
        assertEquals("found nothing", plan(32, 2).summary());
    }

    @Test
    void summaryListsKindsWithCountAndNearestToTheStart() {
        RoamPlan p = plan(32, 2);
        p.see("tree", 130, 64, -50, 1); // 30 away
        p.see("tree", 110, 64, -53, 1); // 10 away
        p.see("tree", 100, 64, 20, 1);  // 70 away
        p.see("iron_ore", 95, 40, -50, 1);
        p.see("cow", 100, 64, -30, 4);
        assertEquals("found tree x3 (nearest 110,64,-53), iron_ore x1 (nearest 95,40,-50), cow x4 (nearest 100,64,-30)", p.summary());
    }

    @Test
    void nearestUsesTheHeightToo() {
        RoamPlan p = plan(32, 2);
        p.see("iron_ore", 100, 20, -50, 1); // 44 below
        p.see("iron_ore", 100 + 40, 64, -50, 1); // 40 beside, in another cell
        assertEquals("found iron_ore x2 (nearest 140,64,-50)", p.summary());
    }

    @Test
    void theSameSpotIsOneFindAndKeepsTheLargerCount() {
        RoamPlan p = plan(32, 2);
        assertNotNull(p.see("cow", 120, 64, -50, 2));
        assertNull(p.see("cow", 119, 64, -49, 3), "same 16-block cell, same kind: no second note");
        assertEquals("found cow x3 (nearest 119,64,-49)", p.summary(), "the bigger herd and the nearer sighting win");
        assertNotNull(p.see("cow", 160, 64, -50, 1), "another cell is a new find");
        assertNotNull(p.see("pig", 120, 64, -50, 1), "another kind in the same cell is a new find");
    }

    @Test
    void noteSubjectIsUniquePerFindAndTextHasThePosition() {
        RoamPlan p = plan(32, 2);
        RoamPlan.Note n = p.see("iron_ore", 95, 40, -50, 1);
        assertNotNull(n);
        assertEquals("roam:iron_ore@95,40,-50", n.subject());
        assertEquals("iron_ore at 95,40,-50", n.text());
        assertEquals("cow x4 at 100,64,-30", p.see("cow", 100, 64, -30, 4).text());
    }

    @Test
    void notesAreCappedPerKindAndPerRoamButTheSummaryStillCounts() {
        RoamPlan p = plan(64, 5);
        int notes = 0;
        for (int i = 0; i < 6; i++) {
            if (p.see("tree", 100 + 16 * i, 64, -50, 1) != null) notes++;
        }
        assertEquals(RoamPlan.MAX_NOTES_PER_KIND, notes, "a forest doesn't flood memory");
        assertTrue(p.summary().startsWith("found tree x6 "), p.summary());

        RoamPlan q = plan(64, 5);
        int total = 0;
        for (int k = 0; k < 20; k++) {
            for (int i = 0; i < 2; i++) {
                if (q.see("kind" + k, 100 + 16 * i, 64, -50, 1) != null) total++;
            }
        }
        assertEquals(RoamPlan.MAX_NOTES, total);
    }

    @Test
    void closeToTheStartCountsForNoSector() {
        RoamPlan p = plan(32, 2);
        p.visit(100, -50);
        p.visit(101, -51);
        assertEquals(0, p.next().sector(), "nothing was learned about any direction yet");
    }
}
