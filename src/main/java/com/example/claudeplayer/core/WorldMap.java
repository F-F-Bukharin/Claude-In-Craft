package com.example.claudeplayer.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The model's eyes: a top-down text map around the body, read at the body's feet level. North (-z) is the top
 * row and west (-x) the left column; {@link View#originX}/{@link View#originZ} give the world position of the
 * top-left block of the top-left cell. The game side supplies a {@link Sampler} (and optionally {@link Heights}
 * to skip empty air); everything else is pure, so the reading of steps, drops, walls and hills is unit-tested.
 *
 * <p>Two refinements on the foot-level slice:
 * <ul>
 *   <li>Height: a cell that reads {@code #} at foot level looks up to {@link #UP_WINDOW} blocks above. A tree up
 *       there makes it {@code t}; solid ground whose top surface is 3 or more blocks above the feet makes it
 *       {@code ^}. Walkable cells never change, so routes still read right.</li>
 *   <li>Scale: up to radius {@link #FINE_RADIUS} one cell is one block. Past that the view stays at most
 *       {@link #MAX_CELLS} cells across and each cell covers {@code scale x scale} blocks: its centre column is
 *       read (heights from the heightmap), and the other columns only for containers, lava and water, so one tick's
 *       look stays cheap; the most important of those shows (see {@link #coarse}).</li>
 * </ul>
 */
public final class WorldMap {
    /** One block as the map cares about it. */
    public enum Kind { OPEN, SOLID, WATER, LAVA, TREE, CONTAINER, UNKNOWN,
        /** Walkable crops (no collision) and the farmland they grow on; farmland is solid ground. */
        CROP_RIPE, CROP_GROWING, FARMLAND }

    @FunctionalInterface
    public interface Sampler {
        Kind at(int x, int y, int z);
    }

    /**
     * The game side's cheap answer to "is anything up there?": the y of the first free block above the highest
     * motion-blocking block in the column (the vanilla heightmap value), or {@link Integer#MAX_VALUE} if unknown.
     */
    @FunctionalInterface
    public interface Heights {
        int topY(int x, int z);
    }

    /** No heightmap: the upward scan reads the whole window. */
    public static final Heights NO_HEIGHTS = (x, z) -> Integer.MAX_VALUE;

    /** A player, mob or other thing drawn over the terrain. */
    public record Marker(int x, int z, char symbol) {}

    /**
     * @param scale blocks per cell side (1 up to radius 24); origin is the world x/z of the top-left block of the top-left cell
     */
    public record View(int originX, int originZ, int scale, List<String> rows) {
        /** One block per cell. */
        public View(int originX, int originZ, List<String> rows) {
            this(originX, originZ, 1, rows);
        }

        /** For a tool result: a header with the scale and the origin, then the rows. */
        public String text() {
            String cell = scale == 1 ? "1 block" : scale + "x" + scale + " blocks";
            return "map, north up, 1 cell = " + cell + ", top-left cell at x=" + originX + " z=" + originZ + ":\n" + String.join("\n", rows);
        }
    }

    public static final String KEY = ". floor at your level, + one block up (jump), - one block down, v drop of 2 or more, "
            + "# wall, ^ high ground or cliff (top 3 or more blocks above you), T tree, t tree on higher ground, ~ water, "
            + "L lava or fire, C container, W ripe crop, w growing crop, = bare farmland, @ you, P player, ! hostile, space unknown";

    /** Up to this radius one cell is one block. */
    public static final int FINE_RADIUS = 24;
    /** A view is never wider than this many cells (49 is the one-block view at radius 24). */
    public static final int MAX_CELLS = 49;
    /** How far above the feet the upward scan looks. */
    public static final int UP_WINDOW = 12;

    private WorldMap() {}

    /** A crop is ripe once its age reaches the block's maximum; the game side feeds the age and max age. */
    public static Kind crop(int age, int maxAge) {
        return age >= maxAge ? Kind.CROP_RIPE : Kind.CROP_GROWING;
    }

    /** Blocks per cell side for a view of this radius: {@code ceil((2r+1) / 49)}, so 1 up to radius 24. */
    public static int scale(int radius) {
        return (2 * radius + 1 + MAX_CELLS - 1) / MAX_CELLS;
    }

    /**
     * Cells per side: odd, at most {@link #MAX_CELLS}, with the body's column in the middle cell. The centre cell
     * spans {@code scale} blocks starting {@code scale / 2} before the body; enough cells go on each side to
     * reach the radius.
     */
    public static int cells(int radius) {
        int scale = scale(radius);
        int west = scale / 2; // blocks of the centre cell on the body's west/north side
        int east = scale - 1 - west; // and on its east/south side
        int reach = Math.max(ceilDiv(radius - west, scale), ceilDiv(radius - east, scale));
        return 2 * Math.max(0, reach) + 1;
    }

    /** {@link #render(Sampler, Heights, int, int, int, int, List)} without a heightmap. */
    public static View render(Sampler s, int cx, int feetY, int cz, int radius, List<Marker> markers) {
        return render(s, NO_HEIGHTS, cx, feetY, cz, radius, markers);
    }

    /**
     * Markers outside the view are skipped; a marker lands in the cell that contains it. In one cell a hostile
     * ({@code !}) beats a player ({@code P}), which beats the terrain. {@code @} marks the centre cell and wins
     * over everything.
     */
    public static View render(Sampler s, Heights h, int cx, int feetY, int cz, int radius, List<Marker> markers) {
        int scale = scale(radius);
        int n = cells(radius);
        int mid = n / 2;
        int originX = cx - scale / 2 - mid * scale;
        int originZ = cz - scale / 2 - mid * scale;
        char[][] grid = new char[n][n];
        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                grid[row][col] = scale == 1
                        ? cell(s, h, originX + col, feetY, originZ + row)
                        : coarseCell(s, h, originX + col * scale, feetY, originZ + row * scale, scale);
            }
        }
        int[] rank = new int[n * n]; // marker priority per cell, 0 = none yet
        for (Marker m : markers) {
            int col = Math.floorDiv(m.x() - originX, scale), row = Math.floorDiv(m.z() - originZ, scale);
            if (col < 0 || col >= n || row < 0 || row >= n) continue;
            int r = m.symbol() == '!' ? 3 : m.symbol() == 'P' ? 1 : 2;
            if (r >= rank[row * n + col]) {
                rank[row * n + col] = r;
                grid[row][col] = m.symbol();
            }
        }
        grid[mid][mid] = '@';
        List<String> rows = new ArrayList<>(n);
        for (char[] r : grid) rows.add(new String(r));
        return new View(originX, originZ, scale, List.copyOf(rows));
    }

    /** What standing at feet level in this column would be like, without a heightmap. */
    static char cell(Sampler s, int x, int y, int z) {
        return cell(s, NO_HEIGHTS, x, y, z);
    }

    /** What standing at feet level in this column would be like. */
    static char cell(Sampler s, Heights h, int x, int y, int z) {
        return cell(s, h, x, y, z, false);
    }

    /** {@link #cell}; a coarse column reads a wall's height from the heightmap ({@link #heightOnly}) instead of scanning up. */
    private static char cell(Sampler s, Heights h, int x, int y, int z, boolean coarse) {
        char farm = farm(s, x, y, z);
        if (farm != 0) return farm;
        Kind feet = s.at(x, y, z);
        Kind head = s.at(x, y + 1, z);
        Kind below = s.at(x, y - 1, z);
        if (feet == Kind.UNKNOWN || head == Kind.UNKNOWN || below == Kind.UNKNOWN) return ' ';
        if (feet == Kind.LAVA || below == Kind.LAVA) return 'L';
        if (feet == Kind.WATER || below == Kind.WATER) return '~';
        if (feet == Kind.CONTAINER || head == Kind.CONTAINER) return 'C';
        if (feet == Kind.OPEN && head == Kind.OPEN) {
            if (solid(below)) return '.';
            Kind two = s.at(x, y - 2, z);
            if (two == Kind.LAVA) return 'L';
            if (two == Kind.WATER) return '~';
            return solid(two) ? '-' : two == Kind.UNKNOWN ? ' ' : 'v';
        }
        boolean tree = feet == Kind.TREE || head == Kind.TREE;
        if (solid(feet) && head == Kind.OPEN && s.at(x, y + 2, z) == Kind.OPEN) return tree ? 'T' : '+';
        if (tree) return 'T';
        return coarse ? heightOnly(s, h, x, y, z) : wallOrHeight(s, h, x, y, z);
    }

    /**
     * A coarse cell's wall, from the heightmap alone plus one read at the column's top: {@code ^} when the top block is
     * 2 to {@value #UP_WINDOW} above the feet ({@code t} when that block is a tree), else {@code #}. No heightmap, or a
     * top past the window (a cave wall, a cliff face), stays {@code #}.
     */
    static char heightOnly(Sampler s, Heights h, int x, int y, int z) {
        int free = h.topY(x, z);
        if (free == Integer.MAX_VALUE || free <= y + 2) return '#';
        int top = free - 1;
        if (top > y + UP_WINDOW) return '#';
        return s.at(x, top, z) == Kind.TREE ? 't' : '^';
    }

    /**
     * A column that reads as a wall at foot level, looked at from feet + 2 up to feet + {@value #UP_WINDOW}:
     * the first tree there makes it {@code t}; otherwise, solid ground starting at feet + 2 and ending in open
     * air at feet + 3 or higher (or reaching a top that the heightmap puts inside the window) makes it {@code ^};
     * otherwise {@code #}. A face solid past the window stays {@code #}: underground it is a cave wall.
     * A 2-high wall, or one with a gap before the next solid block, stays {@code #}. The heightmap skips columns
     * with nothing at feet + 2 and stops the scan at the highest blocking block.
     */
    static char wallOrHeight(Sampler s, Heights h, int x, int y, int z) {
        int free = h.topY(x, z); // first free y above the highest blocking block
        if (free <= y + 2) return '#'; // nothing at feet + 2 or higher
        int top = (int) Math.min((long) y + UP_WINDOW, (long) free - 1);
        boolean run = true; // solid from feet + 2 without a gap so far
        boolean high = false;
        for (int yy = y + 2; yy <= top; yy++) {
            Kind k = s.at(x, yy, z);
            if (k == Kind.TREE) return 't';
            if (k == Kind.UNKNOWN) {
                run = false;
                break;
            }
            if (run && !solid(k)) {
                run = false;
                high = yy >= y + 3;
            }
        }
        // A run solid to the end of the scan is high ground only when the heightmap puts the column's top inside the
        // window; past it (or with no heightmap) it could as well be a cave wall, so it stays #.
        boolean topInWindow = free != Integer.MAX_VALUE && free - 1 <= y + UP_WINDOW;
        return high || (run && topInWindow) ? '^' : '#';
    }

    // ---- coarse cells ----

    /** Symbols a column can read as, in the order of {@link #coarse}'s bitmask. */
    private static final String SYMBOLS = " .+-v#^tT~LCWw=";

    /**
     * A coarse cell samples one column, the centre one, read as {@link #cell} but with the heightmap in place of the
     * upward scan; every other column gets one or two cheap reads (feet, then ground) for what must not be missed:
     * a container, lava or fire, water. At radius 64 (43 x 43 cells of 3 x 3) that is 23 reads a cell on flat ground
     * and at most about 25, some 46 thousand in all, where reading every column fully took about 300 thousand.
     */
    private static char coarseCell(Sampler s, Heights h, int x0, int y, int z0, int scale) {
        int cx = x0 + scale / 2;
        int cz = z0 + scale / 2;
        int seen = bit(cell(s, h, cx, y, cz, true));
        for (int dz = 0; dz < scale; dz++) {
            for (int dx = 0; dx < scale; dx++) {
                if (x0 + dx != cx || z0 + dz != cz) seen |= marks(s, x0 + dx, y, z0 + dz);
            }
        }
        return coarse(seen);
    }

    /** The cheap look at a coarse cell's other columns: C, L or ~ from the feet and ground blocks, nothing else. */
    private static int marks(Sampler s, int x, int y, int z) {
        Kind feet = s.at(x, y, z);
        if (feet == Kind.CONTAINER) return bit('C');
        if (feet == Kind.LAVA) return bit('L');
        Kind ground = s.at(x, y - 1, z);
        if (ground == Kind.LAVA) return bit('L');
        return feet == Kind.WATER || ground == Kind.WATER ? bit('~') : 0;
    }

    private static int bit(char c) {
        return 1 << SYMBOLS.indexOf(c);
    }

    private static boolean has(int seen, char c) {
        return (seen & bit(c)) != 0;
    }

    /**
     * The one symbol for a coarse cell, from the set of symbols its columns read as. Priority: container C, lava
     * or fire L, water ~, tree (T before t), wall or high ground (^ before #), drop v, then floor (ripe crop W,
     * growing crop w, bare farmland =, floor ., step up +, step down -). Space only when nothing in it is known.
     * Hostile and player markers are laid over this by {@link #render}.
     */
    static char coarse(int seen) {
        for (char c : "CL~Tt^#vWw=.+-".toCharArray()) {
            if (has(seen, c)) return c;
        }
        return ' ';
    }

    /** Vertical window searched for crops and farmland, nearest to feet level first (so a field one step down still shows). */
    private static final int[] FARM_WINDOW = {0, -1, 1, -2};

    /** W, w or = for the nearest crop or bare farmland within the window, with open air above it; 0 if none. */
    static char farm(Sampler s, int x, int y, int z) {
        for (int dy : FARM_WINDOW) {
            Kind k = s.at(x, y + dy, z);
            if (k != Kind.CROP_RIPE && k != Kind.CROP_GROWING && k != Kind.FARMLAND) continue;
            if (s.at(x, y + dy + 1, z) != Kind.OPEN) continue;
            return k == Kind.CROP_RIPE ? 'W' : k == Kind.CROP_GROWING ? 'w' : '=';
        }
        return 0;
    }

    private static boolean solid(Kind k) {
        return k == Kind.SOLID || k == Kind.TREE || k == Kind.CONTAINER || k == Kind.FARMLAND;
    }

    private static int ceilDiv(int a, int b) {
        return Math.floorDiv(a + b - 1, b);
    }
}
