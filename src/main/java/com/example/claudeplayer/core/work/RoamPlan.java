package com.example.claudeplayer.core.work;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * roam's brain, with no game objects: where to wander next, when to turn back, what was found and what to say.
 *
 * <p>The ground around the start is cut into the 8 compass sectors (N is -z, E is +x). Each sector is scored by how
 * much of it the body has walked (distinct 8x8 cells), how many waypoints were already sent there and how many
 * failed; the least-seen one gets the next waypoint, between half the radius and nearly all of it from the start.
 * A sector whose waypoints can't be reached twice is dropped; with nothing reached after several failures the plan
 * gives up. Positions are plain ints, so the game side only converts.
 */
public final class RoamPlan {
    public static final int SECTORS = 8;
    public static final int MAX_FAILS_PER_SECTOR = 2;
    /** Failures in a row with nothing reached yet that make the plan give up (failed:no_path). */
    public static final int GIVE_UP_FAILS = 6;
    public static final int MAX_WAYPOINTS = 40;
    /** One note per kind per 16-block cell, at most this many per kind and per roam, so memory isn't flooded. */
    public static final int MAX_NOTES_PER_KIND = 2;
    public static final int MAX_NOTES = 12;

    /** Closer than this to the start belongs to no direction. */
    private static final int MIN_SECTOR_DIST = 4;
    /** Waypoint distance as a fraction of the radius, by how many waypoints that sector had: far first, nearer on a retry. */
    private static final double[] FRACTIONS = {0.85, 0.55, 0.7};
    private static final double[] JITTER_DEGREES = {0, 10, -10};
    private static final int SCORE_PER_PICK = 4;
    private static final int SCORE_PER_FAIL = 8;
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** Where to go: ground coordinates (the game side picks the height) and the sector it lies in. */
    public record Waypoint(int x, int z, int sector) {}

    /** A short-term note to write: unique subject, text with the position. */
    public record Note(String subject, String text) {}

    private static final class Kind {
        final Map<Long, Integer> cells = new HashMap<>(); // 16-block cell to the biggest count seen there
        int notes;
        long nearestSq = Long.MAX_VALUE;
        int nx;
        int ny;
        int nz;
    }

    private final int startX;
    private final int startY;
    private final int startZ;
    private final int radius;
    private final int totalTicks;
    private final int[] cells = new int[SECTORS];
    private final int[] picks = new int[SECTORS];
    private final int[] fails = new int[SECTORS];
    private final Set<Long> seen = new HashSet<>();
    private final Map<String, Kind> found = new LinkedHashMap<>();
    private int pref;
    private int issued;
    private int reached;
    private int failsInARow;
    private int notes;

    /** {@code firstSector}: which direction wins a tie first (0 = N); the game side passes a random one. */
    public RoamPlan(int startX, int startY, int startZ, int radius, double minutes, int firstSector) {
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.radius = radius;
        this.totalTicks = (int) Math.round(minutes * 60 * 20);
        this.pref = Math.floorMod(firstSector, SECTORS);
    }

    /** The sector (0 = N, clockwise) of an offset from the start, or -1 when it is too close to tell. */
    public static int sectorOf(int dx, int dz) {
        if (dx * dx + dz * dz < MIN_SECTOR_DIST * MIN_SECTOR_DIST) return -1;
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        return Math.floorMod((int) Math.round(degrees / 45.0), SECTORS);
    }

    public static String sectorName(int sector) {
        return NAMES[Math.floorMod(sector, SECTORS)];
    }

    public int totalTicks() {
        return totalTicks;
    }

    /** True once half the time is gone: head back to the start. */
    public boolean turnBack(int elapsedTicks) {
        return elapsedTicks * 2L >= totalTicks;
    }

    /** The body stood here: that part of its sector counts as seen. */
    public void visit(int x, int z) {
        int sector = sectorOf(x - startX, z - startZ);
        if (sector < 0) return;
        if (seen.add(((long) (x >> 3) << 32) ^ (z >> 3 & 0xffffffffL))) cells[sector]++;
    }

    /** The next waypoint, or null when there is nowhere left to go (see {@link #gaveUp}). */
    public Waypoint next() {
        if (gaveUp() || issued >= MAX_WAYPOINTS) return null;
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int k = 0; k < SECTORS; k++) {
            int s = (pref + k) % SECTORS;
            if (fails[s] >= MAX_FAILS_PER_SECTOR) continue;
            int score = cells[s] + SCORE_PER_PICK * picks[s] + SCORE_PER_FAIL * fails[s];
            if (score < bestScore) {
                best = s;
                bestScore = score;
            }
        }
        if (best < 0) return null;
        int i = picks[best]++ % FRACTIONS.length;
        issued++;
        pref = (best + 3) % SECTORS;
        double theta = Math.toRadians(best * 45.0 + JITTER_DEGREES[i]);
        double d = radius * FRACTIONS[i];
        return new Waypoint(startX + (int) Math.round(Math.sin(theta) * d), startZ - (int) Math.round(Math.cos(theta) * d), best);
    }

    /** The body got there. */
    public void reached(Waypoint w) {
        reached++;
        failsInARow = 0;
        visit(w.x(), w.z());
    }

    /** No way to get there (no path, hazard, unloaded, too slow): skipped, and its direction counts against. */
    public void unreachable(Waypoint w) {
        fails[w.sector()]++;
        failsInARow++;
    }

    public int reachedCount() {
        return reached;
    }

    /** Nothing was ever reached and the first few waypoints failed, or every direction is closed. */
    public boolean gaveUp() {
        if (reached == 0 && failsInARow >= GIVE_UP_FAILS) return true;
        for (int f : fails) {
            if (f < MAX_FAILS_PER_SECTOR) return false;
        }
        return true;
    }

    /**
     * The roam ends failed:no_path: it {@link #gaveUp} and nothing was ever reached. Running out of time before the
     * first waypoint is not this; the body walks back and reports what it saw.
     */
    public boolean noPath() {
        return reached == 0 && gaveUp();
    }

    /**
     * Records something seen ({@code count} of it, e.g. a herd). Returns the note to write when it is a new find
     * (kind plus 16-block cell) and the note limits allow, else null. Nearest-to-start and the biggest count are
     * kept for the summary either way.
     */
    public Note see(String kind, int x, int y, int z, int count) {
        Kind k = found.computeIfAbsent(kind, key -> new Kind());
        long dx = x - startX;
        long dy = y - startY;
        long dz = z - startZ;
        long sq = dx * dx + dy * dy + dz * dz;
        if (sq < k.nearestSq) {
            k.nearestSq = sq;
            k.nx = x;
            k.ny = y;
            k.nz = z;
        }
        int n = Math.max(1, count);
        long cell = ((long) (x >> 4) & 0x3ffffffL) << 38 | ((long) (y >> 4) & 0xfffL) << 26 | ((long) (z >> 4) & 0x3ffffffL);
        Integer old = k.cells.get(cell);
        if (old != null) {
            if (n > old) k.cells.put(cell, n);
            return null;
        }
        k.cells.put(cell, n);
        if (notes >= MAX_NOTES || k.notes >= MAX_NOTES_PER_KIND) return null;
        k.notes++;
        notes++;
        String where = x + "," + y + "," + z;
        return new Note("roam:" + kind + "@" + where, kind + (n > 1 ? " x" + n : "") + " at " + where);
    }

    /** "found tree x3 (nearest 10,64,-3), iron_ore x1 (nearest ...)", or "found nothing". */
    public String summary() {
        if (found.isEmpty()) return "found nothing";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Kind> e : found.entrySet()) {
            Kind k = e.getValue();
            int total = k.cells.values().stream().mapToInt(Integer::intValue).sum();
            parts.add(e.getKey() + " x" + total + " (nearest " + k.nx + "," + k.ny + "," + k.nz + ")");
        }
        return "found " + String.join(", ", parts);
    }
}
