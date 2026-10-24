package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** till, plant and use_bucket's pure parts, checked against Forge 1.20.1 (HoeItem, IForgeBlock, BushBlock, SugarCaneBlock, BucketItem). */
class PlantingTest {
    @Test
    void grassDirtAndPathTillToFarmlandWithAirAbove() {
        for (String id : new String[] {"grass_block", "dirt", "dirt_path"}) {
            assertEquals("farmland", Planting.tilledInto(id), id);
            assertNull(Planting.tillFailure(id, true), id);
            assertEquals("blocked", Planting.tillFailure(id, false), id + " with something above");
        }
    }

    @Test
    void coarseDirtTillsToDirtAndNeedsAirAbove() {
        assertEquals("dirt", Planting.tilledInto("coarse_dirt"));
        assertNull(Planting.tillFailure("coarse_dirt", true));
        assertEquals("blocked", Planting.tillFailure("coarse_dirt", false));
    }

    @Test
    void rootedDirtTillsToDirtEvenUnderABlock() {
        assertEquals("dirt", Planting.tilledInto("rooted_dirt"));
        assertNull(Planting.tillFailure("rooted_dirt", false), "Forge's HOE_TILL has no air check for rooted dirt");
    }

    @Test
    void otherBlocksDontTill() {
        for (String id : new String[] {"farmland", "stone", "sand", "podzol", "mycelium", "air", "moss_block"}) {
            assertNull(Planting.tilledInto(id), id);
            assertEquals("not_tillable", Planting.tillFailure(id, true), id);
        }
    }

    @Test
    void seedsAndCropsGoOnFarmlandOnly() {
        for (String seed : new String[] {"wheat_seeds", "carrot", "potato", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "torchflower_seeds", "pitcher_pod"}) {
            assertTrue(Planting.plantable(seed), seed);
            assertNull(Planting.plantFailure(seed, "farmland", true, false), seed);
            assertEquals("wrong_soil", Planting.plantFailure(seed, "dirt", true, false), seed);
            assertEquals("wrong_soil", Planting.plantFailure(seed, "grass_block", true, false), seed);
        }
    }

    @Test
    void saplingsGoOnTheDirtTagAndFarmland() {
        for (String soil : new String[] {"dirt", "grass_block", "coarse_dirt", "podzol", "rooted_dirt", "mycelium", "moss_block", "mud", "muddy_mangrove_roots", "farmland"}) {
            assertNull(Planting.plantFailure("oak_sapling", soil, true, false), soil);
        }
        assertNull(Planting.plantFailure("cherry_sapling", "grass_block", true, false));
        assertEquals("wrong_soil", Planting.plantFailure("oak_sapling", "sand", true, false));
        assertEquals("wrong_soil", Planting.plantFailure("oak_sapling", "stone", true, false));
    }

    @Test
    void aMangrovePropaguleAlsoTakesClay() {
        assertTrue(Planting.plantable("mangrove_propagule"));
        assertNull(Planting.plantFailure("mangrove_propagule", "clay", true, false));
        assertNull(Planting.plantFailure("mangrove_propagule", "mud", true, false));
        assertEquals("wrong_soil", Planting.plantFailure("oak_sapling", "clay", true, false));
    }

    @Test
    void netherWartGoesOnSoulSandOnly() {
        assertNull(Planting.plantFailure("nether_wart", "soul_sand", true, false));
        assertEquals("wrong_soil", Planting.plantFailure("nether_wart", "soul_soil", true, false));
        assertEquals("wrong_soil", Planting.plantFailure("nether_wart", "farmland", true, false));
    }

    @Test
    void sugarCaneNeedsDirtOrSandWithWaterBeside() {
        for (String soil : new String[] {"grass_block", "dirt", "sand", "red_sand", "podzol", "mud"}) {
            assertNull(Planting.plantFailure("sugar_cane", soil, true, true), soil);
            assertEquals("no_water", Planting.plantFailure("sugar_cane", soil, true, false), soil);
        }
        assertEquals("wrong_soil", Planting.plantFailure("sugar_cane", "stone", true, true));
        assertEquals("wrong_soil", Planting.plantFailure("sugar_cane", "farmland", true, true), "farmland isn't in the dirt tag");
    }

    @Test
    void sugarCaneStacksOnSugarCaneWithoutWater() {
        assertNull(Planting.plantFailure("sugar_cane", "sugar_cane", true, false));
    }

    @Test
    void sweetBerriesGoOnGrassOrDirt() {
        assertTrue(Planting.plantable("sweet_berries"));
        for (String soil : new String[] {"grass_block", "dirt", "podzol", "coarse_dirt", "farmland"}) {
            assertNull(Planting.plantFailure("sweet_berries", soil, true, false), soil);
        }
        assertEquals("wrong_soil", Planting.plantFailure("sweet_berries", "sand", true, false));
    }

    @Test
    void unknownItemsAreNotPlantable() {
        for (String item : new String[] {"dirt", "wheat", "melon_slice", "cocoa_beans", "stick"}) {
            assertFalse(Planting.plantable(item), item);
            assertEquals("not_plantable", Planting.plantFailure(item, "farmland", true, true), item);
        }
    }

    @Test
    void soilIsCheckedBeforeTheSpaceAbove() {
        assertEquals("wrong_soil", Planting.plantFailure("wheat_seeds", "dirt", false, false));
        assertEquals("no_water", Planting.plantFailure("sugar_cane", "sand", false, false));
        assertEquals("occupied", Planting.plantFailure("wheat_seeds", "farmland", false, false));
    }

    @Test
    void aBucketPoursIntoTheClickedBlockWhenItTakesTheFluid() {
        assertEquals(Planting.PourAt.CLICKED, Planting.pourAt(true));
        assertEquals(Planting.PourAt.BESIDE_FACE, Planting.pourAt(false), "a solid block: the space next to the face used");
    }

    @Test
    void aPourOnlyReplacesNaturalOrSelfPlacedBlocks() {
        assertEquals(Planting.PourInto.TAKES, Planting.pourInto(true, false, false, false, false), "air or fluid");
        assertEquals(Planting.PourInto.TAKES, Planting.pourInto(false, true, false, false, false), "a block it waterlogs");
        assertEquals(Planting.PourInto.TAKES, Planting.pourInto(false, false, true, true, false), "tall grass: natural");
        assertEquals(Planting.PourInto.TAKES, Planting.pourInto(false, false, true, false, true), "a torch Claude placed");
        assertEquals(Planting.PourInto.PROTECTED, Planting.pourInto(false, false, true, false, false), "a player's torch, rail or dust");
        assertEquals(Planting.PourInto.NO, Planting.pourInto(false, false, false, true, true), "stone takes no fluid");
        assertTrue(Planting.pourInto(false, false, true, false, false).clicksInto(), "a protected clicked block is still the target, then denied");
        assertFalse(Planting.PourInto.NO.clicksInto());
    }

}
