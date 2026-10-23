package com.example.claudeplayer.core.work;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** till, plant and harvest's pure parts: which soil takes which item, what tills, when a crop is ripe. Ids are short (wheat_seeds). */
public final class Farming {
    /** What a hoe turns into farmland (coarse and rooted dirt become dirt first, the vanilla way). */
    private static final Set<String> TILLABLE = Set.of("dirt", "grass_block", "coarse_dirt", "rooted_dirt", "dirt_path");
    private static final Set<String> CROP_SEEDS = Set.of("wheat_seeds", "carrot", "potato", "beetroot_seeds");
    private static final Set<String> SAPLING_SOIL = Set.of("dirt", "grass_block", "coarse_dirt", "podzol", "rooted_dirt");
    /** Crop block to the item that plants it again. */
    private static final Map<String, String> SEEDS = Map.of("wheat", "wheat_seeds", "carrots", "carrot", "potatoes", "potato",
            "beetroots", "beetroot_seeds", "nether_wart", "nether_wart");

    private Farming() {}

    /** CropBlock#isMaxAge, or the age property at its max for beetroot and nether wart. */
    public static boolean ripe(int age, int maxAge) {
        return age >= maxAge;
    }

    public static boolean tillable(String blockId) {
        return TILLABLE.contains(blockId);
    }

    /** Seeds for farmland, saplings, or nether wart. */
    public static boolean plantable(String item) {
        return CROP_SEEDS.contains(item) || item.endsWith("_sapling") || item.equals("nether_wart");
    }

    /** Whether this item may be planted on this soil block: crops on farmland, saplings on dirt or grass, wart on soul sand. */
    public static boolean fits(String item, String soilId) {
        if (CROP_SEEDS.contains(item)) return soilId.equals("farmland");
        if (item.endsWith("_sapling")) return SAPLING_SOIL.contains(soilId);
        if (item.equals("nether_wart")) return soilId.equals("soul_sand");
        return false;
    }

    /** The item that replants this crop block, or null for blocks harvest doesn't know. */
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
