package com.example.claudeplayer.core.work;

import com.example.claudeplayer.core.act.Action;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * build_house's plan: a 5x5 house (3x3 inside) whose north-west outer corner at floor level is x, y, z. Walls are the
 * 16-block ring at y, y+1 and y+2, the roof is flat at y+3, and a 1x2 doorway is left in the middle of the facing
 * wall. Blocks go bottom layer first, the roof last (outer ring, inner ring, centre) so each leans on one placed
 * before it. Also where the door and bed go and where to stand to place them, the site check and the material pick.
 * North is -z, south +z, west -x, east +x.
 */
public final class HousePlan {
    /** 3 rings of 16 less the 2-block doorway, plus a 25-block roof. */
    public static final int BLOCKS = 71;

    private HousePlan() {}

    public record Cell(int x, int y, int z) {}

    /** The world as the site check sees it; the game side decides what is solid and clear. */
    public interface Site {
        /** A floor block a wall can stand on. */
        boolean solid(int x, int y, int z);

        /** Air or a replaceable plant, nothing to dig. */
        boolean clear(int x, int y, int z);

        /** The block id there (short, without minecraft:). */
        String block(int x, int y, int z);
    }

    /** uneven_ground or site_blocked, the first position at fault and the block there. */
    public record SiteProblem(String code, Cell at, String block) {}

    /** {dx, dz} of one step toward {@code facing}. */
    public static int[] step(String facing) {
        return switch (facing) {
            case "north" -> new int[] {0, -1};
            case "east" -> new int[] {1, 0};
            case "west" -> new int[] {-1, 0};
            default -> new int[] {0, 1};
        };
    }

    public static String opposite(String facing) {
        return switch (facing) {
            case "north" -> "south";
            case "east" -> "west";
            case "west" -> "east";
            default -> "north";
        };
    }

    /** The interior cell at floor level offset from the centre by {@code along} steps toward facing and {@code side} steps to its side. */
    private static Cell at(int x, int y, int z, String facing, int along, int side) {
        int[] s = step(facing);
        int[] p = {-s[1], s[0]}; // a quarter turn from facing
        return new Cell(x + 2 + s[0] * along + p[0] * side, y, z + 2 + s[1] * along + p[1] * side);
    }

    /** The doorway's lower cell: the middle of the facing wall. */
    public static Cell door(int x, int y, int z, String facing) {
        return at(x, y, z, facing, 2, 0);
    }

    /** The door faces out, the way the house faces. */
    public static String doorFacing(String facing) {
        return facing;
    }

    /** Just inside the doorway: standing here and looking at the door gives it the outward facing. */
    public static Cell doorStand(int x, int y, int z, String facing) {
        return at(x, y, z, facing, 1, 0);
    }

    /** The bed's foot: the middle row, in a side column so the way in from the door stays free. */
    public static Cell bedFoot(int x, int y, int z, String facing) {
        return at(x, y, z, facing, 0, 1);
    }

    /** The bed's head: the back row, against the back wall. */
    public static Cell bedHead(int x, int y, int z, String facing) {
        return at(x, y, z, facing, -1, 1);
    }

    /** The bed points from foot to head, away from the door. */
    public static String bedFacing(String facing) {
        return opposite(facing);
    }

    /** In line with the bed, toward the door: looking at the foot from here points the bed at the back wall. */
    public static Cell bedStand(int x, int y, int z, String facing) {
        return at(x, y, z, facing, 1, 1);
    }

    /**
     * How far (flat) the body may be from a stand cell's centre to count as on it. 0.2 keeps the 0.6-wide body inside
     * the cell: any more and it reaches into the doorway, where a closed door's slab sits right against the stand
     * cell, or into the bed's foot, and vanilla refuses the placement as obstructed.
     */
    public static final double STAND_TOLERANCE = 0.2;

    /** Whether a body at x, y, z (feet) stands on {@code c}: within {@link #STAND_TOLERANCE} of its centre, feet in its block. */
    public static boolean onStand(double x, double y, double z, Cell c) {
        double dx = x - (c.x() + 0.5);
        double dz = z - (c.z() + 0.5);
        return dx * dx + dz * dz <= STAND_TOLERANCE * STAND_TOLERANCE && Math.floor(y + 1e-6) == c.y();
    }

    /** Whether any of the 5x5 site's corner columns is {@link BuildPlan#tooFar} from the body; the farthest point is a corner. */
    public static boolean siteTooFar(int x, int z, double fromX, double fromZ) {
        return BuildPlan.tooFar(x, z, fromX, fromZ) || BuildPlan.tooFar(x + 4, z, fromX, fromZ)
                || BuildPlan.tooFar(x, z + 4, fromX, fromZ) || BuildPlan.tooFar(x + 4, z + 4, fromX, fromZ);
    }

    /** The 16 cells of the outer ring at height y, walked clockwise from the north-west corner. */
    private static List<Cell> ring(int x0, int y, int z0, int size) {
        List<Cell> cells = new ArrayList<>();
        int last = size - 1;
        for (int i = 0; i < last; i++) cells.add(new Cell(x0 + i, y, z0));
        for (int i = 0; i < last; i++) cells.add(new Cell(x0 + last, y, z0 + i));
        for (int i = last; i > 0; i--) cells.add(new Cell(x0 + i, y, z0 + last));
        for (int i = last; i > 0; i--) cells.add(new Cell(x0, y, z0 + i));
        return cells;
    }

    /** The 71 blocks in placing order. */
    public static List<Action.BuildBlock> blocks(int x, int y, int z, String facing, String material) {
        Cell door = door(x, y, z, facing);
        List<Action.BuildBlock> out = new ArrayList<>();
        for (int h = y; h <= y + 2; h++) {
            for (Cell c : ring(x, h, z, 5)) {
                if (c.x() == door.x() && c.z() == door.z() && c.y() <= y + 1) continue;
                out.add(new Action.BuildBlock(c.x(), c.y(), c.z(), material));
            }
        }
        List<Cell> roof = new ArrayList<>(ring(x, y + 3, z, 5));
        roof.addAll(ring(x + 1, y + 3, z + 1, 3));
        roof.add(new Cell(x + 2, y + 3, z + 2));
        for (Cell c : roof) out.add(new Action.BuildBlock(c.x(), c.y(), c.z(), material));
        return out;
    }

    /**
     * Null when the house can go here. uneven_ground: a cell of the 5x5 under it at y-1 isn't solid. site_blocked: a
     * cell of the 5x5x4 volume isn't clear, scanning bottom up (then north to south, west to east). What an
     * interrupted build left is fine: the material where the plan puts it, a door in the doorway, a bed where the bed goes.
     */
    public static SiteProblem checkSite(int x, int y, int z, String facing, String material, Site site) {
        for (int dz = 0; dz < 5; dz++) {
            for (int dx = 0; dx < 5; dx++) {
                if (!site.solid(x + dx, y - 1, z + dz)) {
                    return new SiteProblem("uneven_ground", new Cell(x + dx, y - 1, z + dz), site.block(x + dx, y - 1, z + dz));
                }
            }
        }
        Set<Cell> planned = new HashSet<>();
        for (Action.BuildBlock b : blocks(x, y, z, facing, material)) planned.add(new Cell(b.x(), b.y(), b.z()));
        Cell door = door(x, y, z, facing);
        Set<Cell> doorCells = Set.of(door, new Cell(door.x(), y + 1, door.z()));
        Set<Cell> bedCells = Set.of(bedFoot(x, y, z, facing), bedHead(x, y, z, facing));
        for (int dy = 0; dy < 4; dy++) {
            for (int dz = 0; dz < 5; dz++) {
                for (int dx = 0; dx < 5; dx++) {
                    Cell c = new Cell(x + dx, y + dy, z + dz);
                    if (site.clear(c.x(), c.y(), c.z())) continue;
                    String id = site.block(c.x(), c.y(), c.z());
                    if (planned.contains(c) && BuildPlan.filled(material, id)) continue;
                    if (doorCells.contains(c) && id.endsWith("_door")) continue;
                    if (bedCells.contains(c) && id.endsWith("_bed")) continue;
                    return new SiteProblem("site_blocked", c, id);
                }
            }
        }
        return null;
    }

    /** Blocks of the plan not already there. */
    public static int needed(List<Action.BuildBlock> blocks, Site site) {
        int n = 0;
        for (Action.BuildBlock b : blocks) {
            if (!BuildPlan.filled(b.item(), site.block(b.x(), b.y(), b.z()))) n++;
        }
        return n;
    }

    /** The building block carried most (ties alphabetical), from candidates the game side filtered; null when none. */
    public static String pickMaterial(Map<String, Integer> candidates) {
        String best = null;
        int most = 0;
        for (Map.Entry<String, Integer> e : candidates.entrySet()) {
            int n = e.getValue();
            if (n > most || n == most && n > 0 && best != null && e.getKey().compareTo(best) < 0) {
                best = e.getKey();
                most = n;
            }
        }
        return best;
    }

    /** "needs 71 oak_planks, carries 40". */
    public static String countText(int needed, String material, int carried) {
        if (material == null) return "needs " + needed + " blocks of one material, carries none";
        return "needs " + needed + " " + material + ", carries " + carried;
    }
}
