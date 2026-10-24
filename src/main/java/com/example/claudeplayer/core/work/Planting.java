package com.example.claudeplayer.core.work;

import java.util.Set;

/**
 * till, plant and use_bucket's pure parts, as Forge 1.20.1 decides them: IForgeBlock#getToolModifiedState (HOE_TILL),
 * BushBlock#mayPlaceOn and its overrides, SugarCaneBlock#canSurvive, BucketItem#use. Ids are short (grass_block).
 * The game still runs the real placement; these rules only pick a clear failure code before trying.
 */
public final class Planting {
    /** BlockTags.DIRT in 1.20.1. */
    private static final Set<String> DIRT = Set.of("dirt", "grass_block", "podzol", "coarse_dirt", "mycelium", "rooted_dirt",
            "moss_block", "mud", "muddy_mangrove_roots");
    /** BlockTags.SAND in 1.20.1. */
    private static final Set<String> SAND = Set.of("sand", "red_sand", "suspicious_sand");
    /** Items whose block is a CropBlock or stem (mayPlaceOn: farmland). */
    private static final Set<String> FARMLAND_CROPS = Set.of("wheat_seeds", "carrot", "potato", "beetroot_seeds", "melon_seeds",
            "pumpkin_seeds", "torchflower_seeds", "pitcher_pod");

    /** Where a poured bucket's fluid goes, the way BucketItem#use picks it. */
    public enum PourAt { CLICKED, BESIDE_FACE }

    private enum Plant { CROP, SAPLING, PROPAGULE, WART, CANE, BERRY }

    private Planting() {}

    /** What a hoe turns this block into ("farmland" or "dirt"), or null when it doesn't till. */
    public static String tilledInto(String blockId) {
        return switch (blockId) {
            case "grass_block", "dirt", "dirt_path" -> "farmland";
            case "coarse_dirt", "rooted_dirt" -> "dirt";
            default -> null;
        };
    }

    /** null when the hoe works; not_tillable, or blocked (grass, dirt, path and coarse dirt need air above; rooted dirt doesn't). */
    public static String tillFailure(String blockId, boolean airAbove) {
        if (tilledInto(blockId) == null) return "not_tillable";
        if (!airAbove && !blockId.equals("rooted_dirt")) return "blocked";
        return null;
    }

    public static boolean plantable(String item) {
        return kind(item) != null;
    }

    /**
     * null when the item may go on this soil; else, in this order: not_plantable, wrong_soil, no_water (sugar cane
     * with no water or frosted ice beside the soil), occupied (the block above can't take a plant).
     */
    public static String plantFailure(String item, String soilId, boolean spaceFree, boolean waterBeside) {
        Plant kind = kind(item);
        if (kind == null) return "not_plantable";
        boolean soil = switch (kind) {
            case CROP -> soilId.equals("farmland");
            case SAPLING, BERRY -> DIRT.contains(soilId) || soilId.equals("farmland");
            case PROPAGULE -> DIRT.contains(soilId) || soilId.equals("farmland") || soilId.equals("clay");
            case WART -> soilId.equals("soul_sand");
            case CANE -> soilId.equals("sugar_cane") || DIRT.contains(soilId) || SAND.contains(soilId);
        };
        if (!soil) return "wrong_soil";
        if (kind == Plant.CANE && !soilId.equals("sugar_cane") && !waterBeside) return "no_water";
        if (!spaceFree) return "occupied";
        return null;
    }

    /** The clicked block if it can take the fluid (air, replaceable, or a block it waterlogs), else the space beside the face used. */
    public static PourAt pourAt(boolean clickedTakesFluid) {
        return clickedTakesFluid ? PourAt.CLICKED : PourAt.BESIDE_FACE;
    }

    private static Plant kind(String item) {
        if (FARMLAND_CROPS.contains(item)) return Plant.CROP;
        if (item.endsWith("_sapling")) return Plant.SAPLING;
        return switch (item) {
            case "mangrove_propagule" -> Plant.PROPAGULE;
            case "nether_wart" -> Plant.WART;
            case "sugar_cane" -> Plant.CANE;
            case "sweet_berries" -> Plant.BERRY;
            default -> null;
        };
    }
}
