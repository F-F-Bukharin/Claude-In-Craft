package com.example.claudeplayer.core.work;

import com.example.claudeplayer.core.act.Action;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * build's rules, shared with build_house: what counts as already there, where to stand to place a block, when to
 * stop, which failure code to give and what the report says. The game side (BuildHandle) walks and places.
 */
public final class BuildPlan {
    /** Eye height of a standing player. */
    public static final double EYE = 1.62;
    /** Spots are picked closer than the 4.5 reach, since the walk may end a block short (as gather does). */
    public static final double SPOT_REACH = 4.0;
    /** Ticks a walk to one spot may take before trying the next spot. */
    public static final int WALK_TICKS = 300;
    /** Spots tried per block before it fails with no_path. */
    public static final int SPOT_TRIES = 3;
    private static final int BASE_TICKS = 400;
    private static final int PER_BLOCK_TICKS = 100;

    private BuildPlan() {}

    /** A block position: where to stand (feet) or a block of the plan. */
    public record Spot(int x, int y, int z) {}

    /** Whether the body can stand with its feet at x, y, z (feet and head free, solid ground below). */
    @FunctionalInterface
    public interface Standable {
        boolean test(int x, int y, int z);
    }

    /** The block there already is the one wanted (ids compared without the minecraft: prefix, case-blind). */
    public static boolean filled(String wanted, String current) {
        return wanted != null && current != null && norm(wanted).equals(norm(current));
    }

    private static String norm(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    /** The first item, in list order, that isn't carried at all, ignoring blocks already there; null when all are carried. */
    public static String firstMissing(List<Action.BuildBlock> blocks, Map<String, Integer> carried, IntPredicate alreadyThere) {
        for (int i = 0; i < blocks.size(); i++) {
            String item = blocks.get(i).item();
            if (!alreadyThere.test(i) && carried.getOrDefault(item, 0) <= 0) return item;
        }
        return null;
    }

    /** Whether the eyes of a body standing on {@code s} are within SPOT_REACH of the block's centre. */
    public static boolean inReach(Spot s, Action.BuildBlock b) {
        double dx = s.x() + 0.5 - (b.x() + 0.5);
        double dy = s.y() + EYE - (b.y() + 0.5);
        double dz = s.z() + 0.5 - (b.z() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= SPOT_REACH;
    }

    /**
     * Up to {@code max} spots to stand on to place {@code b}, nearest the body (at fromX, fromY, fromZ) first. Never in
     * the block's own column (on it or under it), and never with feet or head in a block still to be placed.
     */
    public static List<Spot> spots(Action.BuildBlock b, Set<Spot> pending, Standable standable, double fromX, double fromY, double fromZ, int max) {
        List<Spot> candidates = new ArrayList<>();
        for (int dy = -5; dy <= 3; dy++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dx = -4; dx <= 4; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    Spot s = new Spot(b.x() + dx, b.y() + dy, b.z() + dz);
                    if (!inReach(s, b) || pending.contains(s) || pending.contains(new Spot(s.x(), s.y() + 1, s.z()))) continue;
                    candidates.add(s);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(s -> dist2(s, fromX, fromY, fromZ)));
        List<Spot> out = new ArrayList<>();
        for (Spot s : candidates) {
            if (out.size() >= max) break;
            if (standable.test(s.x(), s.y(), s.z())) out.add(s);
        }
        return out;
    }

    private static double dist2(Spot s, double x, double y, double z) {
        double dx = s.x() + 0.5 - x;
        double dy = s.y() - y;
        double dz = s.z() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** "danger" when a hostile is within gather's danger range or health is below its low mark; null to keep going. */
    public static String danger(double nearestHostile, double health) {
        return nearestHostile <= GatherRule.DANGER_RANGE || health < GatherRule.LOW_HEALTH ? "danger" : null;
    }

    /**
     * The failure code for a stop at list index {@code index}: the bare reason while nothing has been placed yet,
     * {@code stopped_at:<index>:<reason>} once something was, and always for danger, death, a hazard or the time limit.
     */
    public static String failure(int index, int placed, String reason) {
        boolean always = reason.equals("danger") || reason.equals("died") || reason.equals("timeout") || reason.startsWith("hazard_");
        return placed == 0 && !always ? reason : "stopped_at:" + index + ":" + reason;
    }

    /** A Placing.place failure as build reports it. */
    public static String placeFailure(String placing, String item) {
        return switch (placing) {
            case "occupied", "not_placed", "blocked" -> "blocked";
            case "out_of_reach" -> "no_path";
            case "missing_item" -> "missing_item:" + item;
            case "not_a_block" -> "not_a_block:" + item;
            default -> placing;
        };
    }

    /** "placed 3, skipped 1 of 4". */
    public static String progress(int placed, int skipped, int total) {
        return "placed " + placed + ", skipped " + skipped + " of " + total;
    }

    /** "build: placed 3, skipped 1 of 4"; no label, just the progress. */
    public static String summary(String label, int placed, int skipped, int total) {
        String p = progress(placed, skipped, total);
        return label == null || label.isEmpty() ? p : label + ": " + p;
    }

    /** Its own limit: it stops with stopped_at:<index>:timeout and the progress. */
    public static int limitTicks(int blocks) {
        return BASE_TICKS + PER_BLOCK_TICKS * blocks;
    }

    /** The queue's backstop, after its own limit. */
    public static int timeoutTicks(int blocks) {
        return limitTicks(blocks) + 200;
    }
}
