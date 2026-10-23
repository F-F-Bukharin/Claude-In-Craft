package com.example.claudeplayer.core.craft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The bookkeeping behind craft: item ids, how many crafts a wanted count takes, and which inventory items fill
 * which ingredient slots. The game side reads recipes and the inventory and hands them over as plain ids.
 */
public final class CraftRules {
    private CraftRules() {}

    /** "oak_planks" or "minecraft:oak_planks" both become "minecraft:oak_planks". */
    public static String fullId(String item) {
        String id = item.strip().toLowerCase(Locale.ROOT);
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    /** The id as the model sees it elsewhere: vanilla items without their namespace. */
    public static String shortId(String fullId) {
        return fullId.startsWith("minecraft:") ? fullId.substring("minecraft:".length()) : fullId;
    }

    /** Crafts needed for at least {@code wanted} items when one craft makes {@code perCraft}. */
    public static int craftsFor(int wanted, int perCraft) {
        return (wanted + perCraft - 1) / perCraft;
    }

    /** One ingredient slot: the inventory item ids it accepts, and the id to name when none is there. */
    public record Slot(List<String> accepts, String label) {}

    /** What one craft takes from the inventory (id → count), and what it lacks (label → count). */
    public record Allocation(Map<String, Integer> used, Map<String, Integer> missing) {
        public boolean complete() {
            return missing.isEmpty();
        }

        public int missingCount() {
            return missing.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * Fills each slot with one item. Slots with the fewest choices go first, and each takes the accepted item the
     * inventory has most of, so "any planks" doesn't use up the oak an "oak only" slot needs.
     */
    public static Allocation allocate(List<Slot> slots, Map<String, Integer> have) {
        Map<String, Integer> left = new HashMap<>(have);
        Map<String, Integer> used = new LinkedHashMap<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        List<Slot> order = new ArrayList<>(slots);
        order.sort(Comparator.comparingInt(s -> s.accepts().size()));
        for (Slot slot : order) {
            String best = null;
            for (String id : slot.accepts()) {
                int n = left.getOrDefault(id, 0);
                if (n > 0 && (best == null || n > left.get(best))) best = id;
            }
            if (best == null) {
                missing.merge(slot.label(), 1, Integer::sum);
                continue;
            }
            left.merge(best, -1, Integer::sum);
            used.merge(best, 1, Integer::sum);
        }
        return new Allocation(used, missing);
    }

    /** "missing:oak_logx1,stickx2", the failure reason for a craft that lacks ingredients. */
    public static String missing(Map<String, Integer> missing) {
        return "missing:" + missing.entrySet().stream().map(e -> e.getKey() + "x" + e.getValue()).collect(Collectors.joining(","));
    }

    /**
     * "crafted 4 oak_planks (used 1 oak_log)". When it ran out of ingredients first, the wanted count shows ("crafted
     * 4 of 8 ..."); results that didn't fit the inventory and went to the ground are mentioned.
     */
    public static String summary(String item, int made, int wanted, boolean ranOut, Map<String, Integer> used, int dropped) {
        String usedText = used.entrySet().stream().map(e -> e.getValue() + " " + shortId(e.getKey())).collect(Collectors.joining(", "));
        return "crafted " + made + (ranOut ? " of " + wanted : "") + " " + item + " (used " + usedText + ")"
                + (ranOut ? ", ran out of ingredients" : "")
                + (dropped > 0 ? ", " + dropped + " dropped at my feet (inventory full)" : "");
    }
}
