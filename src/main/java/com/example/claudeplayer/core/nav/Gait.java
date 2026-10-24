package com.example.claudeplayer.core.nav;

import com.example.claudeplayer.core.nav.PathSmoother.Cell;
import java.util.List;

/**
 * How the body moves, as decisions on plain numbers: when to sprint along a straight run, and how to swim. The game
 * side measures (food, air, the run ahead, hazards near) and applies the answer; the hazard probe still runs every
 * tick whatever this says.
 */
public final class Gait {
    /** Sprinting needs food above this (vanilla's own rule). */
    public static final int SPRINT_FOOD_ABOVE = 6;
    /** A straight run at least this long (blocks) is worth starting a sprint on. */
    public static final double SPRINT_START_RUN = 8.0;
    /** A sprint stops with this much of the run left: the body (about 0.3 blocks of slide) is walking by the turn. */
    public static final double SPRINT_STOP_RUN = 3.0;

    private Gait() {}

    /**
     * Sprint now? {@code runAhead} is the straight distance left before the path turns, climbs, or ends;
     * {@code hazardNear} is any lava, fire or drop on or beside that run. Hysteresis: starting needs a long run,
     * carrying on needs only a short one.
     */
    public static boolean sprint(boolean sprintingNow, int food, boolean inWater, double runAhead, boolean hazardNear) {
        if (inWater || hazardNear || food <= SPRINT_FOOD_ABOVE) return false;
        return runAhead >= (sprintingNow ? SPRINT_STOP_RUN : SPRINT_START_RUN);
    }

    /**
     * The last index of the straight run that starts at {@code from}: consecutive steps in one horizontal direction
     * at one height. Returns {@code from} when the next step turns, climbs or drops (or there is none).
     */
    public static int straightRunEnd(List<Cell> nodes, int from) {
        if (from < 0 || from + 1 >= nodes.size()) return from;
        Cell a = nodes.get(from);
        Cell b = nodes.get(from + 1);
        int dx = b.x() - a.x();
        int dz = b.z() - a.z();
        if (b.y() != a.y() || (dx == 0 && dz == 0)) return from;
        int end = from + 1;
        while (end + 1 < nodes.size()) {
            Cell p = nodes.get(end);
            Cell q = nodes.get(end + 1);
            if (q.y() != p.y() || q.x() - p.x() != dx || q.z() - p.z() != dz) break;
            end++;
        }
        return end;
    }

    public enum Swim {
        /** Not swimming: on land, or wading with the head out and feet on the bottom. */
        NONE,
        /** Hold jump to float and head for the exit. */
        STROKE,
        /** Air is low and there is a way up: hold jump and stop walking until the head is out. */
        RISE;

        public boolean holdsJump() {
            return this != NONE;
        }

        public boolean walks() {
            return this != RISE;
        }
    }

    /**
     * Swim decision. Low air is under a third of the maximum; with the head under and open water above, rise first.
     * Wading (feet on the bottom, head out) is plain walking.
     */
    public static Swim swim(boolean inWater, boolean eyesUnder, boolean onGround, int air, int maxAir, boolean wayUp) {
        if (!inWater) return Swim.NONE;
        if (eyesUnder && wayUp && air * 3 < maxAir) return Swim.RISE;
        if (onGround && !eyesUnder) return Swim.NONE;
        return Swim.STROKE;
    }
}
