package com.example.claudeplayer.core.work;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * go_home with store: what stays in the inventory and what goes into the containers. The game side sorts each stack
 * into a {@link Kind}; this decides the rest.
 *
 * <p>Kept whole: tools, weapons, armor, food and torches. Kept in part: one bed, and one stack (the item's own max
 * stack size) of the building block carried most. Everything else is stored, including the surplus of those.
 * Ties go to whichever came first in the inventory, so the choice is stable from one call to the next.
 */
public final class StorePlan {
    public enum Kind { TOOL, WEAPON, ARMOR, FOOD, TORCH, BED, BUILDING_BLOCK, OTHER }

    /** One inventory stack: the short item id, how many, what kind of thing it is, and its max stack size. */
    public record Stack(String id, int count, Kind kind, int maxStack) {}

    /** What went into one container: where it is ("chest at 10, 64, 5") and the item id to count. */
    public record Placed(String where, Map<String, Integer> items) {}

    private StorePlan() {}

    /**
     * Item id to the count to store, in the order the ids first appear in the inventory. Stacks of one id are added
     * up. Empty ids and counts of zero or less are ignored. An id holds one kind (its first stack's).
     */
    public static Map<String, Integer> toStore(List<Stack> inventory) {
        Map<String, Integer> total = new LinkedHashMap<>();
        Map<String, Stack> first = new LinkedHashMap<>();
        for (Stack s : inventory) {
            if (s.id().isEmpty() || s.count() <= 0) continue;
            total.merge(s.id(), s.count(), Integer::sum);
            first.putIfAbsent(s.id(), s);
        }
        String bed = mostCarried(total, first, Kind.BED);
        String block = mostCarried(total, first, Kind.BUILDING_BLOCK);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : total.entrySet()) {
            String id = e.getKey();
            Stack s = first.get(id);
            int keep = switch (s.kind()) {
                case TOOL, WEAPON, ARMOR, FOOD, TORCH -> e.getValue();
                case BED -> id.equals(bed) ? 1 : 0;
                case BUILDING_BLOCK -> id.equals(block) ? Math.min(e.getValue(), Math.max(1, s.maxStack())) : 0;
                case OTHER -> 0;
            };
            if (e.getValue() > keep) out.put(id, e.getValue() - keep);
        }
        return out;
    }

    /** The id of this kind with the largest total; the earliest one on a tie; null when there is none. */
    private static String mostCarried(Map<String, Integer> total, Map<String, Stack> first, Kind kind) {
        String best = null;
        for (Map.Entry<String, Integer> e : total.entrySet()) {
            if (first.get(e.getKey()).kind() != kind) continue;
            if (best == null || e.getValue() > total.get(best)) best = e.getKey();
        }
        return best;
    }

    /**
     * "stored 40 cobblestone, 12 dirt in chest at 10, 64, 5; 7 gravel in barrel at 12, 64, 5", then
     * "didn't fit: ..." for what no container took and "could not reach N containers" for those skipped.
     * Containers that took nothing are left out.
     */
    public static String report(List<Placed> placed, Map<String, Integer> leftover, int unreached) {
        List<String> parts = new ArrayList<>();
        for (Placed p : placed) {
            if (p.items().isEmpty()) continue;
            parts.add((parts.isEmpty() ? "stored " : "") + listed(p.items()) + " in " + p.where());
        }
        if (parts.isEmpty()) parts.add("stored nothing");
        if (!leftover.isEmpty()) parts.add("didn't fit: " + listed(leftover));
        if (unreached > 0) parts.add("could not reach " + unreached + (unreached == 1 ? " container" : " containers"));
        return String.join("; ", parts);
    }

    private static String listed(Map<String, Integer> counts) {
        return counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
    }
}
