package com.example.claudeplayer.core.work;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * When gather stops, and what it says. The game side checks these every tick; the walking, searching and mining
 * add their own stops (none_reachable, needs_tool, hazard_*).
 */
public final class GatherRule {
    /** 120 s. */
    public static final int LIMIT_TICKS = 2400;
    /** A hostile this close (blocks) ends the run; the reflexes deal with it. */
    public static final double DANGER_RANGE = 6.0;
    /** Health below this fraction of the maximum ends the run. */
    public static final double LOW_HEALTH = 0.4;

    private GatherRule() {}

    /**
     * done, danger, inventory_full or timeout; null to keep going. {@code nearestHostile} is in blocks
     * (infinity when none), {@code health} a fraction of the maximum.
     */
    public static String stop(int gained, int count, int ticks, double nearestHostile, double health, boolean inventoryFull) {
        if (gained >= count) return "done";
        if (nearestHostile <= DANGER_RANGE || health < LOW_HEALTH) return "danger";
        if (inventoryFull) return "inventory_full";
        if (ticks >= LIMIT_TICKS) return "timeout";
        return null;
    }

    /** "gathered 16 oak_log (16 blocks); stopped: done". {@code gained}: item id to count, in the order to list them. */
    public static String summary(Map<String, Integer> gained, int blocks, String stop) {
        String items = gained.isEmpty() ? "nothing" : gained.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
        return "gathered " + items + " (" + blocks + (blocks == 1 ? " block" : " blocks") + "); stopped: " + stop;
    }
}
