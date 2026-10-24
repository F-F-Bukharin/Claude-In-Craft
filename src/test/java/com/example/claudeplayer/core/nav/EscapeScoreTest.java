package com.example.claudeplayer.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.nav.EscapeScore.Point;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Escape candidates: the direction away from the crowd, the ring around it, and the order to path-check. */
class EscapeScoreTest {
    private static final double ESCAPE = 8;
    private static final double REACH = 10;

    private static Point p(double x, double z) {
        return new Point(x, z);
    }

    private static List<Point> order(List<Point> threats) {
        double away = EscapeScore.awayAngle(0, 0, threats);
        return EscapeScore.order(0, 0, threats, EscapeScore.ring(0, 0, away, REACH, 12), ESCAPE);
    }

    @Test
    void oneThreatMeansStraightAwayFirst() {
        List<Point> ordered = order(List.of(p(-3, 0)));
        assertEquals(REACH, ordered.get(0).x(), 1e-9);
        assertEquals(0, ordered.get(0).z(), 1e-9);
    }

    @Test
    void oneThreatMeansTurningTowardItLast() {
        List<Point> ordered = order(List.of(p(-3, 0)));
        for (int i = 1; i < ordered.size(); i++) {
            double before = Math.abs(Math.atan2(ordered.get(i - 1).z(), ordered.get(i - 1).x()));
            double after = Math.abs(Math.atan2(ordered.get(i).z(), ordered.get(i).x()));
            assertTrue(after >= before - 1e-9, "candidate " + i + " turns back toward the threat");
        }
    }

    @Test
    void awayAngleOfOneThreatIsStraightAway() {
        assertEquals(0, EscapeScore.awayAngle(0, 0, List.of(p(-4, 0))), 1e-9);
        assertEquals(Math.PI / 2, EscapeScore.awayAngle(0, 0, List.of(p(0, -4))), 1e-9);
    }

    @Test
    void awayAngleLeansAwayFromTheCloserThreat() {
        double angle = EscapeScore.awayAngle(0, 0, List.of(p(-2, 0), p(0, 20)));
        assertTrue(angle < 0 && angle > -0.05, "mostly away from the near one, nudged off the far one: " + angle);
    }

    @Test
    void awayAngleOfACrowdOnOneSideIsAwayFromTheCrowd() {
        double angle = EscapeScore.awayAngle(0, 0, List.of(p(-4, -2), p(-4, 2), p(-6, 0)));
        assertEquals(0, angle, 1e-9);
    }

    @Test
    void awayAngleSurvivesAThreatOnTopOfTheBody() {
        double angle = EscapeScore.awayAngle(5, 5, List.of(p(5, 5)));
        assertFalse(Double.isNaN(angle));
    }

    @Test
    void twoThreatsOnOppositeSidesPickAPerpendicularDirection() {
        List<Point> ordered = order(List.of(p(-5, 0), p(5, 0)));
        assertFalse(ordered.isEmpty());
        assertEquals(0, ordered.get(0).x(), 1e-6, "the best way out is across, not along, the line of threats");
        assertEquals(REACH, Math.abs(ordered.get(0).z()), 1e-6);
    }

    @Test
    void aCandidateNearAFarThreatIsRejected() {
        List<Point> threats = List.of(p(-3, 0), p(12, 0));
        List<Point> ordered = EscapeScore.order(0, 0, threats, List.of(p(10, 0), p(5, 8)), ESCAPE);
        assertEquals(List.of(p(5, 8)), ordered, "(10, 0) is 2 blocks from the far threat even though the near one is behind");
    }

    @Test
    void aCandidateExactlyAtTheEscapeDistanceIsKept() {
        assertEquals(List.of(p(8, 0)), EscapeScore.order(0, 0, List.of(p(0, 0)), List.of(p(8, 0)), ESCAPE));
        assertTrue(EscapeScore.order(0, 0, List.of(p(0, 0)), List.of(p(7.9, 0)), ESCAPE).isEmpty());
    }

    @Test
    void theFarthestFromThreatsComesFirstWhateverTheInputOrder() {
        List<Point> ordered = EscapeScore.order(0, 0, List.of(p(-3, 0)), List.of(p(0, 9), p(8, 0)), ESCAPE);
        assertEquals(p(8, 0), ordered.get(0));
        assertEquals(p(0, 9), ordered.get(1));
    }

    @Test
    void equalScoresKeepTheInputOrder() {
        List<Point> ordered = EscapeScore.order(0, 0, List.of(p(-3, 0)), List.of(p(8, 5), p(8, -5)), ESCAPE);
        assertEquals(List.of(p(8, 5), p(8, -5)), ordered);
    }

    @Test
    void everyCandidateNearAThreatMeansNone() {
        List<Point> surrounded = List.of(p(5, 0), p(-5, 0), p(0, 5), p(0, -5), p(4, 4), p(-4, 4), p(4, -4), p(-4, -4));
        assertTrue(order(surrounded).isEmpty(), "ringed by threats, no ring point is 8 from all of them");
    }

    @Test
    void noThreatsMeansNoCandidatesToFilterButNothingBreaks() {
        assertEquals(List.of(p(8, 0)), EscapeScore.order(0, 0, List.of(), List.of(p(8, 0)), ESCAPE));
    }

    @Test
    void theRingStartsAtTheAngleAndAlternatesSides() {
        List<Point> ring = EscapeScore.ring(1, 2, 0, 10, 8);
        assertEquals(8, ring.size());
        assertEquals(11, ring.get(0).x(), 1e-9);
        assertEquals(2, ring.get(0).z(), 1e-9);
        assertTrue(ring.get(1).z() > 2 && ring.get(2).z() < 2, "+45 degrees, then -45");
        for (Point point : ring) {
            assertEquals(10, Math.hypot(point.x() - 1, point.z() - 2), 1e-9);
        }
    }

    @Test
    void clearMeansEveryThreatIsAtLeastTheEscapeDistanceAway() {
        List<Point> threats = List.of(p(0, 0), p(20, 0));
        assertTrue(EscapeScore.clear(10, 10, threats, ESCAPE));
        assertFalse(EscapeScore.clear(14, 0, threats, ESCAPE));
        assertTrue(EscapeScore.clear(3, 3, List.of(), ESCAPE));
    }

    @Test
    void aPathThatRunsPastAThreatIsRejected() {
        Point body = new Point(0.5, 0.5);
        List<Point> threats = List.of(new Point(5.5, 0.5));
        List<Point> through = List.of(new Point(0.5, 0.5), new Point(3.5, 0.5), new Point(6.5, 0.5), new Point(10.5, 0.5));
        List<Point> away = List.of(new Point(0.5, 0.5), new Point(-3.5, 0.5), new Point(-8.5, 0.5));
        List<Point> wide = List.of(new Point(0.5, 0.5), new Point(3.5, 3.5), new Point(5.5, 3.5), new Point(9.5, 3.5));
        List<Point> grazing = List.of(new Point(0.5, 0.5), new Point(5.5, 2.5), new Point(9.5, 2.5));
        assertTrue(EscapeScore.runsPast(body, through, threats));
        assertFalse(EscapeScore.runsPast(body, away, threats));
        assertFalse(EscapeScore.runsPast(body, wide, threats), "3 blocks off the threat is past the 2.5 gap");
        assertTrue(EscapeScore.runsPast(body, grazing, threats), "2 blocks off it is too close");
        assertTrue(EscapeScore.runsPast(body, away, List.of(new Point(-6.5, 1.5), new Point(5.5, 0.5))), "any threat counts");
    }

    @Test
    void aThreatAlreadyBesideTheBodyOnlyRejectsPathsThatGetCloser() {
        Point body = new Point(0.5, 0.5);
        List<Point> threats = List.of(new Point(2, 0.5));
        assertFalse(EscapeScore.runsPast(body, List.of(new Point(0.5, 0.5), new Point(-0.5, 0.5), new Point(-4.5, 0.5)), threats),
                "starting 1.5 away and backing off is fine");
        assertTrue(EscapeScore.runsPast(body, List.of(new Point(0.5, 0.5), new Point(1.5, 0.5), new Point(5.5, 0.5)), threats));
    }

}
