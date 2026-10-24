package com.example.claudeplayer.core.nav;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Node skipping for a planned path. A planner's path hugs the block grid (staircases of unit steps); a body that
 * walks it node by node zigzags. This decides when the walker may head straight for a later node instead: only when
 * the straight line to it is safe, meaning every column the body's width touches can be stood in, and each step
 * along the line is level or one block up (never down, so a drop is never cut across). The game side answers "can
 * the body stand with its feet in this cell" (headroom, solid floor, nothing deadly); this class does the geometry.
 */
public final class PathSmoother {
    /** Nodes beyond the next one that may be skipped to. */
    public static final int MAX_LOOKAHEAD = 4;

    private static final double BODY_HALF_WIDTH = 0.3;
    private static final double SAMPLE = 0.25;

    /** A block position, as a path node or the cell the body stands in. */
    public record Cell(int x, int y, int z) {}

    public interface Terrain {
        /** True when a body can stand with its feet in this cell: free for two blocks, solid floor, nothing deadly. */
        boolean standable(int x, int y, int z);
    }

    private PathSmoother() {}

    /**
     * The farthest index in {@code [first, first + lookahead]} (within the path) that the body can walk to in a
     * straight line from {@code from}; {@code first} when no later node qualifies. Every node skipped over must itself
     * be standable, so a doorway, a slab or a bit of water on the path is never cut past.
     */
    public static int farthest(Terrain terrain, Cell from, List<Cell> nodes, int first, int lookahead) {
        if (first < 0 || first >= nodes.size()) return first;
        Terrain memo = new Memo(terrain);
        int last = Math.min(first + Math.max(0, lookahead), nodes.size() - 1);
        int clear = first; // nodes first..clear are standable
        if (!memo.standable(nodes.get(first).x(), nodes.get(first).y(), nodes.get(first).z())) return first;
        while (clear < last && memo.standable(nodes.get(clear + 1).x(), nodes.get(clear + 1).y(), nodes.get(clear + 1).z())) {
            clear++;
        }
        for (int j = clear; j > first; j--) {
            if (lineClear(memo, from, nodes.get(j))) return j;
        }
        return first;
    }

    /** Whether the body can walk the straight line from {@code from} to {@code to}, level or climbing, never down. */
    public static boolean lineClear(Terrain terrain, Cell from, Cell to) {
        double x0 = from.x() + 0.5;
        double z0 = from.z() + 0.5;
        double dx = to.x() + 0.5 - x0;
        double dz = to.z() + 0.5 - z0;
        double length = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(length / SAMPLE));
        int cx = from.x();
        int cz = from.z();
        int cy = from.y();
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double px = x0 + dx * t;
            double pz = z0 + dz * t;
            int ix = (int) Math.floor(px);
            int iz = (int) Math.floor(pz);
            if (ix != cx || iz != cz) { // entered a new column: it must be level or one higher
                if (terrain.standable(ix, cy, iz)) {
                    // level
                } else if (terrain.standable(ix, cy + 1, iz)) {
                    cy++;
                } else {
                    return false;
                }
                cx = ix;
                cz = iz;
            }
            // the rest of the body's footprint: a column it overlaps must be stood in (one lower, level, or one higher)
            int minX = (int) Math.floor(px - BODY_HALF_WIDTH);
            int maxX = (int) Math.floor(px + BODY_HALF_WIDTH);
            int minZ = (int) Math.floor(pz - BODY_HALF_WIDTH);
            int maxZ = (int) Math.floor(pz + BODY_HALF_WIDTH);
            for (int fx = minX; fx <= maxX; fx++) {
                for (int fz = minZ; fz <= maxZ; fz++) {
                    if (fx == cx && fz == cz) continue;
                    if (fx == from.x() && fz == from.z()) continue; // where the body stands now is not judged
                    if (!(terrain.standable(fx, cy, fz) || terrain.standable(fx, cy - 1, fz)
                            || terrain.standable(fx, cy + 1, fz))) {
                        return false;
                    }
                }
            }
        }
        return cy == to.y();
    }

    /** Remembers answers for one search, so a cell is judged once however many lines cross it. */
    private static final class Memo implements Terrain {
        private final Terrain inner;
        private final Map<Long, Boolean> seen = new HashMap<>();

        Memo(Terrain inner) {
            this.inner = inner;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            long key = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
            return seen.computeIfAbsent(key, k -> inner.standable(x, y, z));
        }
    }
}
