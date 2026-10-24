package com.example.claudeplayer.core.work;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * tame and pet's pure parts: which foods tame which animal, which carried food to offer, when to give up, when the
 * run stops, the pet command and the result texts. Ids are short (bone, cod); a "minecraft:" prefix is accepted.
 */
public final class Taming {
    /** Feeds before tame ends with not_tamed. A wolf tames 1 time in 3, a cat 1 in 3, a parrot 1 in 10. */
    public static final int MAX_TRIES = 15;
    /** Offers the animal refused (a cat that is scared, an angry wolf) before giving up; they cost no food. */
    public static final int MAX_REFUSALS = 40;
    /** Blocks around the body searched for the nearest animal when no entity_id is given. */
    public static final double SEARCH_RANGE = 16.0;
    /** Blocks from the animal to feed it. A cat takes food only from a player closer than 3. */
    public static final double FEED_REACH = 2.5;
    /** Ticks between two feeds. */
    public static final int FEED_GAP_TICKS = 10;
    /** Ticks to stand still after arriving, before the first feed: a cat is scared off by a moving player. */
    public static final int SETTLE_TICKS = 10;
    /** 60 s, walking included. */
    public static final int LIMIT_TICKS = 1200;

    /** Animal to the foods that tame it, in the order they are offered. */
    private static final Map<String, List<String>> FOODS = Map.of(
            "wolf", List.of("bone"),
            "cat", List.of("cod", "salmon"),
            "parrot", List.of("wheat_seeds", "melon_seeds", "pumpkin_seeds", "beetroot_seeds"));

    private Taming() {}

    /** Lower case, without the "minecraft:" prefix; empty for null. */
    public static String normalize(String id) {
        if (id == null) return "";
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    public static boolean isAnimal(String animal) {
        return FOODS.containsKey(normalize(animal));
    }

    /** The foods that tame this animal, empty for an animal that can't be tamed. */
    public static List<String> foods(String animal) {
        return FOODS.getOrDefault(normalize(animal), List.of());
    }

    public static boolean isFood(String animal, String item) {
        return foods(animal).contains(normalize(item));
    }

    /** The first food of the table that {@code counts} (item id to count) holds, or null when none is carried. */
    public static String chooseFood(String animal, Map<String, Integer> counts) {
        for (String food : foods(animal)) {
            if (counts.getOrDefault(food, 0) > 0) return food;
        }
        return null;
    }

    /** "wolf needs bone", "cat needs cod or salmon". */
    public static String missing(String animal) {
        List<String> foods = foods(animal);
        String list = foods.size() < 2 ? String.join("", foods)
                : String.join(", ", foods.subList(0, foods.size() - 1)) + " or " + foods.get(foods.size() - 1);
        return normalize(animal) + " needs " + list;
    }

    /** Whether to stop feeding: {@code feeds} eaten without success, {@code refusals} offers turned down. */
    public static boolean giveUp(int feeds, int refusals) {
        return feeds >= MAX_TRIES || refusals >= MAX_REFUSALS;
    }

    /**
     * danger (a hostile within {@link GatherRule#DANGER_RANGE}, or health under {@link GatherRule#LOW_HEALTH}),
     * timeout, or null to keep going. {@code nearestHostile} is in blocks (infinity when none), {@code health} a fraction.
     */
    public static String stop(int ticks, double nearestHostile, double health) {
        if (nearestHostile <= GatherRule.DANGER_RANGE || health < GatherRule.LOW_HEALTH) return "danger";
        if (ticks >= LIMIT_TICKS) return "timeout";
        return null;
    }

    /** sit gives TRUE, follow FALSE (the pet's sit order), anything else null. */
    public static Boolean sitting(String command) {
        String c = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        return switch (c) {
            case "sit" -> Boolean.TRUE;
            case "follow" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** "tamed wolf #123 after 3 bones". {@code used}: food id to the number eaten, in the order to list them. */
    public static String tamed(String animal, int id, Map<String, Integer> used) {
        return "tamed " + subject(animal, id) + after(used);
    }

    public static String gaveUp(String animal, int id, Map<String, Integer> used) {
        return "gave up on " + subject(animal, id) + after(used);
    }

    public static String ranOut(String animal, int id, Map<String, Integer> used) {
        return "ran out of food for " + subject(animal, id) + after(used);
    }

    /** "stopped (danger) with wolf #7 after 1 bone". */
    public static String interrupted(String why, String animal, int id, Map<String, Integer> used) {
        return "stopped (" + why + ") with " + subject(animal, id) + after(used);
    }

    public static String already(String animal, int id) {
        return subject(animal, id) + " was already tamed";
    }

    /** "wolf #123 is sitting" or "cat #45 is following". */
    public static String petText(String animal, int id, boolean sit) {
        return subject(animal, id) + " is " + (sit ? "sitting" : "following");
    }

    private static String subject(String animal, int id) {
        return normalize(animal) + " #" + id; // no custom name: world text stays out
    }

    private static String after(Map<String, Integer> used) {
        if (used.isEmpty()) return " before any food";
        return " after " + used.entrySet().stream().map(e -> e.getValue() + " " + noun(e.getKey(), e.getValue()))
                .collect(Collectors.joining(", "));
    }

    /** bone gives bones; cod, salmon and the seeds are already plural or a mass noun. */
    private static String noun(String item, int n) {
        return n != 1 && item.equals("bone") ? "bones" : item;
    }
}
