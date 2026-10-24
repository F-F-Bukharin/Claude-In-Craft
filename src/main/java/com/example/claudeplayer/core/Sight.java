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
 * (ores, containers, ripe crops, ...) listed first and exactly, and the living things in sight. The game side only
 * collects the hits; everything it says here is pure.
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
    private static final int MAX_NOTABLE = 10;
    private static final int MAX_GROUPS = 12;
    private static final int MAX_ENTITIES = 8;

    /** Blocks worth a line of their own: ores, ancient debris, netherite and diamond blocks, spawners, containers, ripe crops. */
    public static boolean notable(String blockId) {
        return blockId.endsWith("_ore") || blockId.endsWith("shulker_box") || blockId.startsWith("ripe_") || NOTABLE.contains(blockId);
    }

    /** One line: notable blocks first, then the rest grouped by id (most first, fluids last), then the entities. */
    public static String format(String facing, List<Hit> hits, List<Seen> seen) {
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
        String text = compose(facing, notable, nCount, ordered, gCount, entities, eCount);
        while (text.length() > MAX_CHARS && (gCount > 0 || eCount > 0 || nCount > 0)) {
            if (gCount > 0) gCount--;
            else if (eCount > 0) eCount--;
            else nCount--;
            text = compose(facing, notable, nCount, ordered, gCount, entities, eCount);
        }
        return text;
    }

    private static String compose(String facing, List<Hit> notable, int nCount, List<List<Hit>> groups, int gCount,
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
