package com.example.claudeplayer.core.work;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * harvest's pure parts: which blocks are crops, how each kind is harvested, when it is ripe, and what replants it.
 * Ids are short (wheat_seeds). Soil rules for till and plant live in {@link Planting}.
 */
public final class Farming {
    /** How a crop is harvested. */
    public enum Kind {
        /** Wheat, carrots, potatoes, beetroots, nether wart: break at max age, replant the seed. */
        CROP,
        /** Cocoa on a jungle log: break at age 2, replant the beans on the same log face. */
        COCOA,
        /** Sweet berry bush: use it at age 3 (it drops berries and goes back to age 1); never broken. */
        BERRY_BUSH,
        /** Melon and pumpkin: break the fruit an attached stem faces; the stem stays. */
        FRUIT,
        /** Sugar cane, cactus, bamboo: break the second block up; the bottom stays and regrows. */
        COLUMN
    }

    /** Crop block to the item that plants it again. */
    private static final Map<String, String> SEEDS = Map.of("wheat", "wheat_seeds", "carrots", "carrot", "potatoes", "potato",
            "beetroots", "beetroot_seeds", "nether_wart", "nether_wart", "cocoa", "cocoa_beans");

    private Farming() {}

    /** The harvest kind of this block, or null for blocks harvest leaves alone (stems, saplings, placed decoration ids). */
    public static Kind kind(String blockId) {
        return switch (blockId) {
            case "wheat", "carrots", "potatoes", "beetroots", "nether_wart" -> Kind.CROP;
            case "cocoa" -> Kind.COCOA;
            case "sweet_berry_bush" -> Kind.BERRY_BUSH;
            case "melon", "pumpkin" -> Kind.FRUIT;
            case "sugar_cane", "cactus", "bamboo" -> Kind.COLUMN;
            default -> null;
        };
    }

    /** CropBlock#isMaxAge, or the age property at its max for beetroot, nether wart, cocoa and berry bushes. */
    public static boolean ripe(int age, int maxAge) {
        return age >= maxAge;
    }

    /** Ripe by age: crops, cocoa and berry bushes at their max age. Fruits and columns have no age rule (cane's age is growth time). */
    public static boolean ageRipe(String blockId, int age, int maxAge) {
        Kind k = kind(blockId);
        return (k == Kind.CROP || k == Kind.COCOA || k == Kind.BERRY_BUSH) && ripe(age, maxAge);
    }

    /** Whether this neighbour is the attached stem of this fruit (the caller checks the stem's FACING points at the fruit). */
    public static boolean fruitAttached(String fruitId, String stemId) {
        return kind(fruitId) == Kind.FRUIT && stemId.equals("attached_" + fruitId + "_stem");
    }

    /** The cut for a column crop: this block is the second one up (the one below is the same plant, the one under that isn't). */
    public static boolean cutPoint(String blockId, String belowId, String twoBelowId) {
        return kind(blockId) == Kind.COLUMN && belowId.equals(blockId) && !twoBelowId.equals(blockId);
    }

    /** Only crops and cocoa are broken whole, so only they get a seed back. */
    public static boolean replants(Kind kind) {
        return kind == Kind.CROP || kind == Kind.COCOA;
    }

    /** A berry bush is picked by using it; everything else is broken. */
    public static boolean usedNotBroken(Kind kind) {
        return kind == Kind.BERRY_BUSH;
    }

    /** Blocks that hurt the body standing in or against them, so drops inside them are picked up from beside. */
    public static boolean hurtsToStand(String blockId) {
        return blockId.equals("sweet_berry_bush") || blockId.equals("cactus");
    }

    /**
     * Whether a drop is picked up from beside rather than walked onto: it lies in a cactus or berry bush, or one of its
     * horizontal neighbours is one (walking onto it brushes the cactus or wades through the bush).
     */
    public static boolean awkwardDrop(String cellId, List<String> sideIds) {
        if (hurtsToStand(cellId)) return true;
        for (String side : sideIds) {
            if (hurtsToStand(side)) return true;
        }
        return false;
    }

    /** Whether standing here touches a cactus: one of the horizontal neighbours is one (a berry bush only hurts from inside). */
    public static boolean hurtsBeside(List<String> sideIds) {
        return sideIds.contains("cactus");
    }

    /** The item that replants this crop block, or null for blocks harvest doesn't replant. */
    public static String seedFor(String cropId) {
        return SEEDS.get(cropId);
    }

    /** "harvested 9 wheat, replanted 9, got 9 wheat, 14 wheat_seeds; stopped: done". Maps list in their own order. */
    public static String summary(Map<String, Integer> harvested, int replanted, Map<String, Integer> gained, String stop) {
        return "harvested " + list(harvested) + ", replanted " + replanted + ", got " + list(gained) + "; stopped: " + stop;
    }

    private static String list(Map<String, Integer> counts) {
        return counts.isEmpty() ? "nothing" : counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
    }
}
