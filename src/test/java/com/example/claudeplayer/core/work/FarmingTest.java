package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** till, plant and harvest's pure parts. */
class FarmingTest {
    @Test
    void aCropIsRipeOnlyAtItsMaxAge() {
        assertTrue(Farming.ripe(7, 7), "wheat at 7/7");
        assertFalse(Farming.ripe(6, 7), "wheat still growing");
        assertTrue(Farming.ripe(3, 3), "beetroot and nether wart top out at 3");
        assertFalse(Farming.ripe(0, 3));
    }

    @Test
    void dirtGrassAndPathsTill() {
        for (String id : new String[] {"dirt", "grass_block", "coarse_dirt", "rooted_dirt", "dirt_path"}) assertTrue(Farming.tillable(id), id);
        for (String id : new String[] {"farmland", "stone", "sand", "podzol", "air"}) assertFalse(Farming.tillable(id), id);
    }

    @Test
    void cropsNeedFarmland() {
        for (String seed : new String[] {"wheat_seeds", "carrot", "potato", "beetroot_seeds"}) {
            assertTrue(Farming.fits(seed, "farmland"), seed);
            assertFalse(Farming.fits(seed, "dirt"), seed);
            assertFalse(Farming.fits(seed, "grass_block"), seed);
        }
    }

    @Test
    void saplingsNeedDirtOrGrass() {
        for (String soil : new String[] {"dirt", "grass_block", "coarse_dirt", "podzol", "rooted_dirt"}) assertTrue(Farming.fits("oak_sapling", soil), soil);
        assertTrue(Farming.fits("cherry_sapling", "grass_block"));
        assertFalse(Farming.fits("oak_sapling", "sand"));
        assertFalse(Farming.fits("oak_sapling", "stone"));
    }

    @Test
    void netherWartNeedsSoulSand() {
        assertTrue(Farming.fits("nether_wart", "soul_sand"));
        assertFalse(Farming.fits("nether_wart", "soul_soil"));
        assertFalse(Farming.fits("nether_wart", "farmland"));
    }

    @Test
    void onlySeedsSaplingsAndWartArePlantable() {
        assertTrue(Farming.plantable("wheat_seeds"));
        assertTrue(Farming.plantable("birch_sapling"));
        assertTrue(Farming.plantable("nether_wart"));
        assertFalse(Farming.plantable("dirt"));
        assertFalse(Farming.plantable("wheat"), "wheat is the harvest, not a seed");
        assertFalse(Farming.fits("dirt", "farmland"));
    }

    @Test
    void eachCropReplantsWithItsOwnSeed() {
        assertEquals("wheat_seeds", Farming.seedFor("wheat"));
        assertEquals("carrot", Farming.seedFor("carrots"));
        assertEquals("potato", Farming.seedFor("potatoes"));
        assertEquals("beetroot_seeds", Farming.seedFor("beetroots"));
        assertEquals("nether_wart", Farming.seedFor("nether_wart"));
        assertNull(Farming.seedFor("melon"));
    }

    @Test
    void harvestSummarySaysWhatWasCutReplantedAndPickedUp() {
        Map<String, Integer> cut = new LinkedHashMap<>();
        cut.put("wheat", 9);
        Map<String, Integer> got = new LinkedHashMap<>();
        got.put("wheat", 9);
        got.put("wheat_seeds", 14);
        assertEquals("harvested 9 wheat, replanted 9, got 9 wheat, 14 wheat_seeds; stopped: done", Farming.summary(cut, 9, got, "done"));
        assertEquals("harvested nothing, replanted 0, got nothing; stopped: none_ripe", Farming.summary(Map.of(), 0, Map.of(), "none_ripe"));
    }
}
