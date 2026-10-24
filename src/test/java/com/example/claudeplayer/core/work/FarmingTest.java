package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** harvest's pure parts, per crop. */
class FarmingTest {
    @Test
    void aCropIsRipeOnlyAtItsMaxAge() {
        assertTrue(Farming.ripe(7, 7), "wheat at 7/7");
        assertFalse(Farming.ripe(6, 7), "wheat still growing");
        assertTrue(Farming.ripe(3, 3), "beetroot and nether wart top out at 3");
        assertFalse(Farming.ripe(0, 3));
    }

    @Test
    void eachCropReplantsWithItsOwnSeed() {
        assertEquals("wheat_seeds", Farming.seedFor("wheat"));
        assertEquals("carrot", Farming.seedFor("carrots"));
        assertEquals("potato", Farming.seedFor("potatoes"));
        assertEquals("beetroot_seeds", Farming.seedFor("beetroots"));
        assertEquals("nether_wart", Farming.seedFor("nether_wart"));
        assertEquals("cocoa_beans", Farming.seedFor("cocoa"));
        assertNull(Farming.seedFor("melon"));
        assertNull(Farming.seedFor("sugar_cane"));
        assertNull(Farming.seedFor("sweet_berry_bush"));
    }

    @Test
    void everyCommonCropHasAHarvestKind() {
        for (String id : new String[] {"wheat", "carrots", "potatoes", "beetroots", "nether_wart"}) assertEquals(Farming.Kind.CROP, Farming.kind(id), id);
        assertEquals(Farming.Kind.COCOA, Farming.kind("cocoa"));
        assertEquals(Farming.Kind.BERRY_BUSH, Farming.kind("sweet_berry_bush"));
        assertEquals(Farming.Kind.FRUIT, Farming.kind("melon"));
        assertEquals(Farming.Kind.FRUIT, Farming.kind("pumpkin"));
        for (String id : new String[] {"sugar_cane", "cactus", "bamboo"}) assertEquals(Farming.Kind.COLUMN, Farming.kind(id), id);
        for (String id : new String[] {"melon_stem", "attached_melon_stem", "bamboo_sapling", "carved_pumpkin", "farmland", "grass_block"}) assertNull(Farming.kind(id), id);
    }

    @Test
    void agedCropsAreRipeAtTheirMaxAgeOnly() {
        assertTrue(Farming.ageRipe("wheat", 7, 7));
        assertFalse(Farming.ageRipe("wheat", 6, 7), "growing wheat is left alone");
        assertTrue(Farming.ageRipe("carrots", 7, 7));
        assertTrue(Farming.ageRipe("potatoes", 7, 7));
        assertTrue(Farming.ageRipe("beetroots", 3, 3));
        assertFalse(Farming.ageRipe("beetroots", 2, 3));
        assertTrue(Farming.ageRipe("nether_wart", 3, 3));
        assertFalse(Farming.ageRipe("nether_wart", 2, 3));
        assertTrue(Farming.ageRipe("cocoa", 2, 2));
        assertFalse(Farming.ageRipe("cocoa", 1, 2));
    }

    @Test
    void aBerryBushIsRipeWhenFull() {
        assertTrue(Farming.ageRipe("sweet_berry_bush", 3, 3));
        assertFalse(Farming.ageRipe("sweet_berry_bush", 2, 3), "age 2 gives fewer berries: wait for 3");
        assertFalse(Farming.ageRipe("sweet_berry_bush", 1, 3));
    }

    @Test
    void fruitsAndColumnsHaveNoAgeRule() {
        assertFalse(Farming.ageRipe("melon", 0, 0));
        assertFalse(Farming.ageRipe("sugar_cane", 15, 15), "cane's age is growth time, not ripeness");
        assertFalse(Farming.ageRipe("stone", 0, 0));
    }

    @Test
    void aFruitIsHarvestedOnlyWhenItsOwnStemFacesIt() {
        assertTrue(Farming.fruitAttached("melon", "attached_melon_stem"));
        assertTrue(Farming.fruitAttached("pumpkin", "attached_pumpkin_stem"));
        assertFalse(Farming.fruitAttached("melon", "attached_pumpkin_stem"));
        assertFalse(Farming.fruitAttached("melon", "melon_stem"), "a growing stem isn't attached");
        assertFalse(Farming.fruitAttached("pumpkin", "air"), "a placed pumpkin is decoration");
    }

    @Test
    void aColumnIsCutAtItsSecondBlock() {
        for (String id : new String[] {"sugar_cane", "cactus", "bamboo"}) {
            assertTrue(Farming.cutPoint(id, id, "sand"), id + ": the second block up");
            assertFalse(Farming.cutPoint(id, "sand", "stone"), id + ": the bottom stays");
            assertFalse(Farming.cutPoint(id, id, id), id + ": the third block falls with the second");
        }
        assertFalse(Farming.cutPoint("bamboo", "bamboo_sapling", "dirt"), "a sapling isn't a stalk");
        assertFalse(Farming.cutPoint("wheat", "wheat", "farmland"), "not a column crop");
    }

    @Test
    void onlyWhatIsBrokenAndPlantableIsReplanted() {
        assertTrue(Farming.replants(Farming.Kind.CROP));
        assertTrue(Farming.replants(Farming.Kind.COCOA));
        assertFalse(Farming.replants(Farming.Kind.BERRY_BUSH), "used, not broken");
        assertFalse(Farming.replants(Farming.Kind.FRUIT), "the stem stays");
        assertFalse(Farming.replants(Farming.Kind.COLUMN), "the bottom stays");
    }

    @Test
    void aBerryBushIsUsedNotBroken() {
        assertTrue(Farming.usedNotBroken(Farming.Kind.BERRY_BUSH));
        for (Farming.Kind k : new Farming.Kind[] {Farming.Kind.CROP, Farming.Kind.COCOA, Farming.Kind.FRUIT, Farming.Kind.COLUMN}) assertFalse(Farming.usedNotBroken(k), k.name());
    }

    @Test
    void bushesAndCactiHurtToStandIn() {
        assertTrue(Farming.hurtsToStand("sweet_berry_bush"));
        assertTrue(Farming.hurtsToStand("cactus"));
        assertFalse(Farming.hurtsToStand("wheat"));
        assertFalse(Farming.hurtsToStand("air"));
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

    @Test
    void dropsBesideACactusOrBushArePickedUpFromAClearSpot() {
        assertTrue(Farming.awkwardDrop("air", List.of("air", "cactus", "air", "air")), "a cactus beside the drop");
        assertTrue(Farming.awkwardDrop("air", List.of("sweet_berry_bush", "air", "air", "air")));
        assertTrue(Farming.awkwardDrop("cactus", List.of("air", "air", "air", "air")));
        assertFalse(Farming.awkwardDrop("air", List.of("sand", "air", "wheat", "air")));
        assertTrue(Farming.hurtsBeside(List.of("air", "air", "cactus", "air")), "touching a cactus hurts");
        assertFalse(Farming.hurtsBeside(List.of("sweet_berry_bush", "air", "stone", "air")), "a bush only hurts from inside");
    }

}
