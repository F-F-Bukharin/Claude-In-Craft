package com.example.claudeplayer.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The model's eyes: a top-down text map around the body, one character per block, read at the body's feet
 * level. North (-z) is the top row and west (-x) the left column; {@link View#originX}/{@link View#originZ}
 * give the world position of the top-left cell. The game side supplies a {@link Sampler}; everything else is
 * pure, so the reading of steps, drops and walls is unit-tested.
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

    /** A player, mob or other thing drawn over the terrain. */
    public record Marker(int x, int z, char symbol) {}

    public record View(int originX, int originZ, List<String> rows) {
        /** For a tool result: a header with the origin, then the rows. */
        public String text() {
            return "map, north up, top-left cell at x=" + originX + " z=" + originZ + ":\n" + String.join("\n", rows);
        }
    }

    public static final String KEY = ". floor at your level, + one block up (jump), - one block down, v drop of 2 or more, "
            + "# wall, T tree, ~ water, L lava, C container, W ripe crop, w growing crop, = bare farmland, @ you, P player, ! hostile, space unknown";

    private WorldMap() {}

    /** A crop is ripe once its age reaches the block's maximum; the game side feeds the age and max age. */
    public static Kind crop(int age, int maxAge) {
        return age >= maxAge ? Kind.CROP_RIPE : Kind.CROP_GROWING;
    }

    /** Markers outside the square are skipped; {@code @} marks the centre and wins over everything. */
    public static View render(Sampler s, int cx, int feetY, int cz, int radius, List<Marker> markers) {
        int size = radius * 2 + 1;
        char[][] grid = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                grid[row][col] = cell(s, cx - radius + col, feetY, cz - radius + row);
            }
        }
        for (Marker m : markers) {
            int col = m.x() - cx + radius, row = m.z() - cz + radius;
            if (col >= 0 && col < size && row >= 0 && row < size) grid[row][col] = m.symbol();
        }
        grid[radius][radius] = '@';
        List<String> rows = new ArrayList<>(size);
        for (char[] r : grid) rows.add(new String(r));
        return new View(cx - radius, cz - radius, List.copyOf(rows));
    }

    /** What standing at feet level in this column would be like. */
    static char cell(Sampler s, int x, int y, int z) {
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
        return tree ? 'T' : '#';
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
}
