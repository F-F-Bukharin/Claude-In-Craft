package com.example.claudeplayer.core;

import com.example.claudeplayer.core.safety.WorldText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The text of the see tool: what a fan of rays from the eyes hit, grouped by block id, with the notable blocks
 * (ores, containers, ripe crops, ...) listed first and exactly, then a landmarks line (highest point, nearest tree, water,
 * lava, village signs, each with a compass point and a ground distance), and the living things in sight. The game side
 * only collects the hits; everything it says here is pure.
 */
public final class Sight {
    /** The longest answer; groups, then entities, then notable entries are dropped (farthest first) to fit. */
    public static final int MAX_CHARS = 900;

    /** One block a ray stopped at. {@code container}: the game saw a container block entity. A ripe crop arrives as {@code ripe_<id>}. */
    public record Hit(String block, int x, int y, int z, double dist, boolean container) {}

    /** A living entity in the cone with a clear line of sight. {@code name}: a player's name (already a name, quoted here), else null. */
    public record Seen(String type, int id, String name, double dist, int x, int y, int z) {}

    /** The directions see accepts; forward keeps the way the body faces. */
    public static final List<String> DIRECTIONS = List.of("forward", "north", "south", "east", "west", "up", "down");

    private Sight() {}

    private static final Set<String> NOTABLE = Set.of("ancient_debris", "netherite_block", "diamond_block", "spawner",
            "chest", "trapped_chest", "barrel", "ender_chest");
    private static final Set<String> FLUIDS = Set.of("water", "lava");
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final int MAX_NOTABLE = 10;
    private static final int MAX_GROUPS = 12;
    private static final int MAX_ENTITIES = 8;

    /** Blocks worth a line of their own: ores, ancient debris, netherite and diamond blocks, spawners, containers, ripe crops. */
    public static boolean notable(String blockId) {
        return blockId.endsWith("_ore") || blockId.endsWith("shulker_box") || blockId.startsWith("ripe_") || NOTABLE.contains(blockId);
    }

    /**
     * The 8-point compass direction (N, NE, E, SE, S, SW, W, NW) of an offset on the ground plane: north is -z, east is +x,
     * each point owning a 45 degree sector centred on it. An offset of (0, 0) reads as N; callers say "here" for that.
     */
    public static String compass(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north (-z), 90 = east (+x), clockwise
        int sector = (int) Math.floor((deg + 22.5) / 45.0);
        return COMPASS[Math.floorMod(sector, 8)];
    }

    /** Trees: logs, leaves and bark-wood of any wood, and the nether's crimson and warped stems and hyphae (not melon or pumpkin stems). */
    public static boolean tree(String id) {
        if (id.endsWith("_log") || id.endsWith("_leaves") || id.endsWith("_wood")) return true;
        return (id.endsWith("_stem") || id.endsWith("_hyphae")) && (id.contains("crimson") || id.contains("warped"));
    }

    /**
     * One answer: notable blocks first, then the landmarks, then the rest grouped by id (most first, fluids last), then
     * the entities. {@code eyeX/eyeY/eyeZ} is where the viewer's eyes are: landmark directions and distances are measured
     * from them (see {@link #landmarks}). Over {@link #MAX_CHARS}, groups go first, then entities, then notable entries;
     * the landmarks line is at most about 110 characters and is never dropped.
     */
    public static String format(String facing, double eyeX, double eyeY, double eyeZ, List<Hit> hits, List<Seen> seen) {
        String landmarks = landmarks(eyeX, eyeZ, hits, seen);
        List<Hit> notable = new ArrayList<>();
        Map<String, List<Hit>> groups = new LinkedHashMap<>();
        Set<String> positions = new HashSet<>();
        for (Hit h : hits.stream().sorted(Comparator.comparingDouble(Hit::dist)).toList()) {
            if (!positions.add(h.x() + "," + h.y() + "," + h.z())) continue; // one block, however many rays hit it
            if (h.container() || notable(h.block())) notable.add(h);
            else groups.computeIfAbsent(h.block(), k -> new ArrayList<>()).add(h);
        }
        List<List<Hit>> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.<List<Hit>, Boolean>comparing(g -> FLUIDS.contains(g.get(0).block()))
                .thenComparing((g1, g2) -> Integer.compare(g2.size(), g1.size()))
                .thenComparingDouble(g -> g.get(0).dist()));
        List<Seen> entities = seen.stream().sorted(Comparator.comparingDouble(Seen::dist)).toList();

        int nCount = Math.min(notable.size(), MAX_NOTABLE);
        int gCount = Math.min(ordered.size(), MAX_GROUPS);
        int eCount = Math.min(entities.size(), MAX_ENTITIES);
        String text = compose(facing, landmarks, notable, nCount, ordered, gCount, entities, eCount);
        while (text.length() > MAX_CHARS && (gCount > 0 || eCount > 0 || nCount > 0)) {
            if (gCount > 0) gCount--;
            else if (eCount > 0) eCount--;
            else nCount--;
            text = compose(facing, landmarks, notable, nCount, ordered, gCount, entities, eCount);
        }
        return text;
    }

    /**
     * The landmarks line (empty when nothing qualifies, otherwise ending in ". "): the highest point in view (a hit that
     * is not a fluid; its block y, ties to the nearer), the nearest tree, water and lava, and village signs (the nearest
     * bell, the nearest villager). Each is "{@code <direction> <distance>}": the direction is {@link #compass} from the
     * eyes to the middle of the block (or the entity's block) on the ground plane, and the distance is that same
     * horizontal distance in whole blocks, height ignored; under half a block reads "here". Anything not in view is left out.
     */
    static String landmarks(double eyeX, double eyeZ, List<Hit> hits, List<Seen> seen) {
        Hit highest = null;
        Hit tree = null;
        Hit water = null;
        Hit lava = null;
        Hit bell = null;
        for (Hit h : hits) {
            double d = ground(eyeX, eyeZ, h.x(), h.z());
            if (!FLUIDS.contains(h.block()) && (highest == null || h.y() > highest.y() || h.y() == highest.y() && d < ground(eyeX, eyeZ, highest.x(), highest.z()))) highest = h;
            if (tree(h.block()) && (tree == null || d < ground(eyeX, eyeZ, tree.x(), tree.z()))) tree = h;
            if (h.block().equals("water") && (water == null || d < ground(eyeX, eyeZ, water.x(), water.z()))) water = h;
            if (h.block().equals("lava") && (lava == null || d < ground(eyeX, eyeZ, lava.x(), lava.z()))) lava = h;
            if (h.block().equals("bell") && (bell == null || d < ground(eyeX, eyeZ, bell.x(), bell.z()))) bell = h;
        }
        Seen villager = null;
        for (Seen s : seen) {
            if (s.type().equals("villager") && (villager == null || ground(eyeX, eyeZ, s.x(), s.z()) < ground(eyeX, eyeZ, villager.x(), villager.z()))) villager = s;
        }
        List<String> parts = new ArrayList<>();
        if (highest != null) parts.add("highest y=" + highest.y() + " " + where(eyeX, eyeZ, highest.x(), highest.z()));
        if (tree != null) parts.add("tree " + where(eyeX, eyeZ, tree.x(), tree.z()));
        if (water != null) parts.add("water " + where(eyeX, eyeZ, water.x(), water.z()));
        if (lava != null) parts.add("lava " + where(eyeX, eyeZ, lava.x(), lava.z()));
        List<String> village = new ArrayList<>();
        if (bell != null) village.add("bell " + where(eyeX, eyeZ, bell.x(), bell.z()));
        if (villager != null) village.add("villager " + where(eyeX, eyeZ, villager.x(), villager.z()));
        if (!village.isEmpty()) parts.add("village: " + String.join(", ", village));
        return parts.isEmpty() ? "" : "landmarks: " + String.join(", ", parts) + ". ";
    }

    /** Ground-plane distance from the eyes to the middle of block column (x, z). */
    private static double ground(double eyeX, double eyeZ, int x, int z) {
        return Math.hypot(x + 0.5 - eyeX, z + 0.5 - eyeZ);
    }

    /** "NE 42" (compass point, whole blocks), or "here" when the block is within half a block of straight above or below. */
    private static String where(double eyeX, double eyeZ, int x, int z) {
        long blocks = Math.round(ground(eyeX, eyeZ, x, z));
        return blocks == 0 ? "here" : compass(x + 0.5 - eyeX, z + 0.5 - eyeZ) + " " + blocks;
    }

    private static String compose(String facing, String landmarks, List<Hit> notable, int nCount, List<List<Hit>> groups, int gCount,
            List<Seen> entities, int eCount) {
        StringBuilder out = new StringBuilder();
        if (nCount > 0) {
            out.append("notable: ");
            for (int i = 0; i < nCount; i++) {
                Hit h = notable.get(i);
                out.append(i > 0 ? ", " : "").append(h.block()).append(" at ").append(pos(h.x(), h.y(), h.z())).append(" (").append(dist(h.dist())).append(')');
            }
            if (notable.size() > nCount) out.append(", +").append(notable.size() - nCount).append(" more");
            out.append(". ");
        }
        out.append(landmarks);
        out.append("facing ").append(facing).append(':');
        if (groups.isEmpty()) {
            out.append(" nothing in view");
        } else {
            for (int i = 0; i < gCount; i++) {
                List<Hit> g = groups.get(i);
                Hit near = g.get(0);
                out.append(i > 0 ? ", " : " ").append(near.block()).append(" x").append(g.size()).append(" (")
                        .append(i == 0 && g.size() > 1 ? "nearest " : "").append(pos(near.x(), near.y(), near.z())).append(" at ").append(dist(near.dist())).append(')');
            }
            if (groups.size() > gCount) out.append(gCount > 0 ? ", " : " ").append('+').append(groups.size() - gCount).append(" more kinds");
        }
        out.append('.');
        if (eCount > 0) {
            out.append(" entities: ");
            for (int i = 0; i < eCount; i++) {
                Seen e = entities.get(i);
                out.append(i > 0 ? ", " : "").append(e.type());
                if (e.name() != null) out.append(' ').append(WorldText.clean(e.name(), WorldText.NAME_MAX));
                out.append(" #").append(e.id()).append(" at ").append(dist(e.dist())).append(" (").append(pos(e.x(), e.y(), e.z())).append(')');
            }
            if (entities.size() > eCount) out.append(", +").append(entities.size() - eCount).append(" more");
            out.append('.');
        }
        return out.toString();
    }

    private static String pos(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static String dist(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
