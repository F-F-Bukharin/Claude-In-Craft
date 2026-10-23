package com.example.claudeplayer.core.work;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The cells dig clears, one step at a time. A horizontal step opens the feet and head cells one block ahead, never
 * the floor. A down step opens three cells in the facing direction, top first: head height of the current level,
 * then the two cells one lower, so the stairs keep two blocks of headroom and room to jump back up.
 */
public final class DigPlan {
    public static final Set<String> DIRECTIONS = Set.of("north", "south", "east", "west", "down");
    /** The lowest y the feet may reach; bedrock starts below. */
    public static final int FLOOR_Y = -60;
    /** At most this many ores are listed. */
    public static final int MAX_SEEN = 5;

    private DigPlan() {}

    public record Pos(int x, int y, int z) {
        public Pos offset(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }
    }

    /** The cells to clear, in order, and where the feet stand once the step is walked. */
    public record Step(List<Pos> clear, Pos feet) {}

    /**
     * The next step from {@code feet}. {@code direction} is one of {@link #DIRECTIONS}; {@code facing} (north,
     * south, east or west) gives the way down for a staircase. Null when the step would take the feet below
     * {@link #FLOOR_Y}.
     */
    public static Step step(String direction, String facing, Pos feet) {
        boolean down = direction.equals("down");
        int[] d = unit(down ? facing : direction);
        Pos ahead = feet.offset(d[0], 0, d[1]);
        if (!down) return new Step(List.of(ahead, ahead.offset(0, 1, 0)), ahead);
        Pos next = ahead.offset(0, -1, 0);
        if (next.y() < FLOOR_Y) return null;
        return new Step(List.of(ahead.offset(0, 1, 0), ahead, next), next);
    }

    /** The walls, floor and ceiling of a step's cells: their neighbours outside the tunnel (the step and where the body stood). */
    public static List<Pos> walls(Step step, Pos from) {
        Set<Pos> tunnel = new LinkedHashSet<>(step.clear());
        tunnel.add(from);
        tunnel.add(from.offset(0, 1, 0));
        Set<Pos> walls = new LinkedHashSet<>();
        for (Pos c : step.clear()) {
            for (int[] n : NEIGHBOURS) {
                Pos p = c.offset(n[0], n[1], n[2]);
                if (!tunnel.contains(p)) walls.add(p);
            }
        }
        return new ArrayList<>(walls);
    }

    public static boolean isOre(String id) {
        return id.endsWith("_ore") || id.equals("ancient_debris");
    }

    /**
     * "dug 12/15 steps down to y=40 (36 blocks broken); saw iron_ore at 10,41,5"; a horizontal tunnel says where the
     * feet ended instead. {@code stop} is null when every step was dug; {@code seen} entries read "iron_ore at x,y,z".
     */
    public static String summary(int steps, int length, String direction, Pos feet, int broken, List<String> seen, String stop) {
        StringBuilder s = new StringBuilder("dug ").append(steps).append('/').append(length).append(" steps ").append(direction)
                .append(direction.equals("down") ? " to y=" + feet.y() : " to " + feet.x() + "," + feet.y() + "," + feet.z())
                .append(" (").append(broken).append(broken == 1 ? " block" : " blocks").append(" broken)");
        if (!seen.isEmpty()) s.append("; saw ").append(seen.stream().limit(MAX_SEEN).collect(Collectors.joining("; ")));
        if (stop != null) s.append("; stopped: ").append(stop);
        return s.toString();
    }

    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** {dx, dz} for a compass direction (north is -z). */
    private static int[] unit(String compass) {
        return switch (compass) {
            case "north" -> new int[] {0, -1};
            case "south" -> new int[] {0, 1};
            case "east" -> new int[] {1, 0};
            case "west" -> new int[] {-1, 0};
            default -> throw new IllegalArgumentException(compass);
        };
    }
}
