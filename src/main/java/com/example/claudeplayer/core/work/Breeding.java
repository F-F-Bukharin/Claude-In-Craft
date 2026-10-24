package com.example.claudeplayer.core.work;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * breed's pure parts: which food each farm animal takes, which carried food to use, whether a pair can be bred, and
 * what the result says. Ids are short (wheat_seeds); a "minecraft:" prefix is accepted on animals and items.
 */
public final class Breeding {
    /** Adults farther than this (blocks) are not looked for. */
    public static final double SEARCH_RANGE = 16.0;
    /** 60 s for the whole run. */
    public static final int LIMIT_TICKS = 1200;

    /** Animal to the foods that put it in love, in the order they are preferred on a tie. */
    private static final Map<String, List<String>> FOODS = Map.of(
            "cow", List.of("wheat"),
            "sheep", List.of("wheat"),
            "pig", List.of("carrot", "potato", "beetroot"),
            "chicken", List.of("wheat_seeds", "melon_seeds", "pumpkin_seeds", "beetroot_seeds", "torchflower_seeds", "pitcher_pod"),
            "rabbit", List.of("carrot", "golden_carrot", "dandelion"));

    private Breeding() {}

    /** Whether breed handles this animal. */
    public static boolean known(String animal) {
        return FOODS.containsKey(shortId(animal));
    }

    /** The foods this animal takes, preferred first; empty for an animal breed doesn't know. */
    public static List<String> foods(String animal) {
        return FOODS.getOrDefault(shortId(animal), List.of());
    }

    public static boolean isFood(String animal, String item) {
        return foods(animal).contains(shortId(item));
    }

    /** Total of the foods this animal takes among the carried item counts. */
    public static int carried(String animal, Map<String, Integer> counts) {
        int total = 0;
        for (String food : foods(animal)) total += count(counts, food);
        return total;
    }

    /** The food to use: the one carried most (a tie goes to the first in the table); null when none is carried. */
    public static String chooseFood(String animal, Map<String, Integer> counts) {
        String best = null;
        int bestCount = 0;
        for (String food : foods(animal)) {
            int n = count(counts, food);
            if (n > bestCount) {
                best = food;
                bestCount = n;
            }
        }
        return best;
    }

    /** Food items one breeding of {@code pairs} pairs takes: one per animal. */
    public static int foodFor(int pairs) {
        return 2 * pairs;
    }

    /**
     * null when a pair can be bred; "no_pair" with fewer than two adults near; "not_ready" with two or more adults
     * but fewer than two that can fall in love now (in love already, on cooldown).
     */
    public static String check(int adults, int ready) {
        if (adults < 2) return "no_pair";
        if (ready < 2) return "not_ready";
        return null;
    }

    /** How many pairs {@code ready} animals make. */
    public static int pairsAvailable(int ready) {
        return Math.max(0, ready / 2);
    }

    /** "bred 2 cow pairs (used 4 wheat)". {@code used}: food id to count, in the order to list them. */
    public static String summary(String animal, int pairs, Map<String, Integer> used) {
        return "bred " + pairs + " " + shortId(animal) + (pairs == 1 ? " pair" : " pairs") + " (used " + list(used) + ")";
    }

    /** "bred 1 of 3 cow pairs (used 2 wheat)": the run stopped before all the pairs were bred. */
    public static String shortfall(String animal, int bred, int wanted, Map<String, Integer> used) {
        return "bred " + bred + " of " + wanted + " " + shortId(animal) + (wanted == 1 ? " pair" : " pairs") + " (used " + list(used) + ")";
    }

    private static String list(Map<String, Integer> used) {
        return used.isEmpty() ? "nothing" : used.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
    }

    /** Counts of an item whether the inventory keys it short or prefixed. */
    private static int count(Map<String, Integer> counts, String food) {
        int total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (shortId(e.getKey()).equals(food)) total += Math.max(0, e.getValue());
        }
        return total;
    }

    /** The id lower-cased without its "minecraft:" prefix. */
    public static String shortId(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }
}
