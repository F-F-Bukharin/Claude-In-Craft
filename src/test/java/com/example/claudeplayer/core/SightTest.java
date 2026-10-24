package com.example.claudeplayer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.Sight.Hit;
import com.example.claudeplayer.core.Sight.Seen;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SightTest {
    // The viewer stands at the centre of block (0, 0): a block at (x, z) is then exactly x east and z south of the eyes.
    private static final double EX = 0.5;
    private static final double EY = 65.0;
    private static final double EZ = 0.5;

    private static Hit hit(String block, int x, int y, int z, double dist) {
        return new Hit(block, x, y, z, dist, false);
    }

    private static String fmt(String facing, List<Hit> hits, List<Seen> seen) {
        return Sight.format(facing, EX, EY, EZ, hits, seen);
    }

    /** Just the landmarks line of an answer, without its closing full stop. */
    private static String landmarks(String text) {
        int from = text.indexOf("landmarks: ");
        assertTrue(from >= 0, text);
        return text.substring(from, text.indexOf(". ", from));
    }

    @Test
    void notableCoversOresDebrisValuableBlocksSpawnersContainersAndRipeCrops() {
        for (String id : List.of("iron_ore", "deepslate_diamond_ore", "nether_gold_ore", "ancient_debris", "netherite_block",
                "diamond_block", "spawner", "chest", "trapped_chest", "barrel", "ender_chest", "white_shulker_box", "ripe_wheat")) {
            assertTrue(Sight.notable(id), id);
        }
        for (String id : List.of("stone", "oak_log", "water", "wheat", "dirt", "iron_block", "coal_block")) {
            assertFalse(Sight.notable(id), id);
        }
    }

    @Test
    void groupsByBlockIdWithCountAndNearestHit() {
        String text = fmt("north", List.of(hit("stone", 3, 64, -10, 4.1), hit("stone", 5, 64, -12, 6.0), hit("stone", 4, 64, -11, 5.0),
                hit("oak_log", 0, 65, -12, 6.0)), List.of());
        assertEquals("landmarks: highest y=65 N 12, tree N 12. facing north: stone x3 (nearest 3,64,-10 at 4.1), oak_log x1 (0,65,-12 at 6.0).", text);
    }

    @Test
    void nearestHitIsChosenWhateverTheOrderRaysCameIn() {
        String text = fmt("east", List.of(hit("dirt", 9, 64, 0, 9.5), hit("dirt", 2, 64, 0, 2.5), hit("dirt", 5, 64, 0, 5.5)), List.of());
        assertTrue(text.contains("dirt x3 (nearest 2,64,0 at 2.5)"), text);
    }

    @Test
    void sameBlockHitByManyRaysCountsOnce() {
        String text = fmt("south", List.of(hit("stone", 1, 64, 1, 3.0), hit("stone", 1, 64, 1, 3.2), hit("stone", 1, 64, 1, 3.4)), List.of());
        assertTrue(text.contains("stone x1"), text);
    }

    @Test
    void notableBlocksComeFirstWithExactPositionsEvenFromOneRay() {
        String text = fmt("north", List.of(hit("stone", 3, 66, -10, 4.1), hit("iron_ore", 2, 63, -9, 3.5), new Hit("chest", 7, 64, -3, 8.2, true),
                hit("ripe_wheat", 1, 64, -2, 2.0)), List.of());
        assertEquals("notable: ripe_wheat at 1,64,-2 (2.0), iron_ore at 2,63,-9 (3.5), chest at 7,64,-3 (8.2). "
                + "landmarks: highest y=66 N 10. facing north: stone x1 (3,66,-10 at 4.1).", text);
    }

    @Test
    void aContainerTheGameFlaggedIsNotableWhateverItsId() {
        String text = fmt("north", List.of(new Hit("furnace", 1, 64, 1, 2.0, true)), List.of());
        assertTrue(text.startsWith("notable: furnace at 1,64,1 (2.0)."), text);
    }

    @Test
    void fluidsListAfterTheSolids() {
        String text = fmt("west", List.of(hit("water", 0, 62, 5, 1.5), hit("lava", 0, 40, 5, 3.0), hit("stone", 9, 64, 9, 12.0)), List.of());
        assertEquals("landmarks: highest y=64 SE 13, water S 5, lava S 5. "
                + "facing west: stone x1 (9,64,9 at 12.0), water x1 (0,62,5 at 1.5), lava x1 (0,40,5 at 3.0).", text);
    }

    @Test
    void entitiesListNearestFirstAndPlayerNamesAreCleaned() {
        String text = fmt("north", List.of(), List.of(new Seen("zombie", 213, null, 9.2, 4, 64, -19),
                new Seen("player", 7, "Al§cex", 3.0, 1, 64, -2)));
        assertEquals("facing north: nothing in view. entities: player Alex #7 at 3.0 (1,64,-2), zombie #213 at 9.2 (4,64,-19).", text);
    }

    @Test
    void anEmptyViewSaysSo() {
        assertEquals("facing down: nothing in view.", fmt("down", List.of(), List.of()));
    }

    @Test
    void theAnswerStaysUnderTheCapAndKeepsNotableAndNearestEntries() {
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < 60; i++) hits.add(hit("block_kind_" + i, i, 64, 0, 3.0 + i));
        hits.add(hit("diamond_ore", 9, 5, 9, 20.0));
        List<Seen> seen = new ArrayList<>();
        for (int i = 0; i < 30; i++) seen.add(new Seen("zombie", 100 + i, null, 5.0 + i, i, 64, 0));
        String text = fmt("north", hits, seen);
        assertTrue(text.length() <= Sight.MAX_CHARS, "length " + text.length());
        assertTrue(text.startsWith("notable: diamond_ore at 9,5,9 (20.0)."), text);
        assertTrue(text.contains("block_kind_0 x1"), text);
        assertTrue(text.contains("zombie #100"), text);
        assertFalse(text.contains("block_kind_59"), text);
    }

    @Test
    void manyNotableBlocksAreCappedNearestFirst() {
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < 40; i++) hits.add(hit("coal_ore", i, 10, 0, 1.0 + i));
        String text = fmt("north", hits, List.of());
        assertTrue(text.length() <= Sight.MAX_CHARS, "length " + text.length());
        assertTrue(text.startsWith("notable: coal_ore at 0,10,0 (1.0), coal_ore at 1,10,0 (2.0)"), text);
        assertFalse(text.contains("coal_ore at 39,10,0"), text);
        assertTrue(text.contains("more"), text);
    }

    // ---- compass ----

    @Test
    void compassGivesAllEightPointsWithNorthMinusZAndEastPlusX() {
        assertEquals("N", Sight.compass(0, -1));
        assertEquals("NE", Sight.compass(1, -1));
        assertEquals("E", Sight.compass(1, 0));
        assertEquals("SE", Sight.compass(1, 1));
        assertEquals("S", Sight.compass(0, 1));
        assertEquals("SW", Sight.compass(-1, 1));
        assertEquals("W", Sight.compass(-1, 0));
        assertEquals("NW", Sight.compass(-1, -1));
    }

    @Test
    void compassSectorsAreFortyFiveDegreesWideAroundEachPoint() {
        assertEquals("N", Sight.compass(4, -10)); // 21.8 degrees east of north
        assertEquals("NE", Sight.compass(5, -10)); // 26.6
        assertEquals("E", Sight.compass(10, -4)); // 68.2 from north
        assertEquals("SE", Sight.compass(10, 5)); // 116.6
        assertEquals("S", Sight.compass(-4, 10)); // 201.8 clockwise from north: still south
        assertEquals("S", Sight.compass(4, 10));
        assertEquals("SW", Sight.compass(-10, 10));
        assertEquals("W", Sight.compass(-10, 2));
        assertEquals("NW", Sight.compass(-10, -10));
        assertEquals("N", Sight.compass(-4, -10)); // north from the west side too
    }

    @Test
    void landmarkDirectionsFollowTheViewerNotTheWorldOrigin() {
        // Viewer at (100.5, 65, -200.5); the water is 12 blocks west of the eyes and the tree 7 blocks north.
        String text = Sight.format("west", 100.5, 65, -200.5, List.of(hit("water", 88, 62, -201, 12.0), hit("oak_log", 100, 70, -208, 7.5)), List.of());
        assertTrue(text.contains("tree N 7"), text);
        assertTrue(text.contains("water W 12"), text);
    }

    // ---- landmarks ----

    @Test
    void theHighestPointIsPickedWithItsYDirectionAndGroundDistance() {
        String text = fmt("north", List.of(hit("stone", 0, 70, -20, 20.5), hit("stone", 30, 87, -30, 45.0), hit("grass_block", -10, 64, 5, 11.0)), List.of());
        // 30 east, 30 north: NE, sqrt(1800) = 42.4 on the ground plane, not the 3D distance
        assertEquals("landmarks: highest y=87 NE 42", landmarks(text));
    }

    @Test
    void theHighestPointIgnoresFluids() {
        String text = fmt("north", List.of(hit("water", 5, 120, -5, 7.0), hit("lava", 6, 130, -6, 8.0), hit("stone", 40, 64, 0, 40.0)), List.of());
        assertEquals("landmarks: highest y=64 E 40, water NE 7, lava NE 8", landmarks(text));
    }

    @Test
    void aHighestTieGoesToTheNearerBlock() {
        String text = fmt("north", List.of(hit("stone", 40, 80, 0, 40.0), hit("stone", 0, 80, 20, 20.0)), List.of());
        assertTrue(text.startsWith("landmarks: highest y=80 S 20"), text);
    }

    @Test
    void aBlockRightUnderOrOverTheEyesIsHere() {
        String text = fmt("up", List.of(hit("stone", 0, 70, 0, 5.0)), List.of());
        assertTrue(text.startsWith("landmarks: highest y=70 here."), text);
    }

    @Test
    void theNearestTreeIsChosenOverFartherOnesAndLeavesCount() {
        String text = fmt("north", List.of(hit("oak_log", 60, 70, 0, 60.0), hit("birch_leaves", 0, 70, 20, 20.0), hit("spruce_log", -30, 70, 0, 30.0)), List.of());
        assertTrue(text.contains(", tree S 20"), text);
    }

    @Test
    void woodAndNetherStemsCountAsTreesButCropStemsDoNot() {
        assertTrue(Sight.tree("oak_log"));
        assertTrue(Sight.tree("stripped_birch_log"));
        assertTrue(Sight.tree("acacia_leaves"));
        assertTrue(Sight.tree("oak_wood"));
        assertTrue(Sight.tree("crimson_stem"));
        assertTrue(Sight.tree("warped_hyphae"));
        assertFalse(Sight.tree("melon_stem"));
        assertFalse(Sight.tree("attached_pumpkin_stem"));
        assertFalse(Sight.tree("oak_planks"));
        assertFalse(Sight.tree("oak_sapling"));
        assertFalse(Sight.tree("log_pile"));
    }

    @Test
    void theNearestWaterAndLavaAreChosenOverFartherOnes() {
        String text = fmt("north", List.of(hit("water", 0, 62, 50, 50.0), hit("water", 12, 62, 0, 12.0), hit("lava", -70, 10, 0, 70.0),
                hit("lava", 0, 10, -30, 30.0)), List.of());
        assertEquals("landmarks: water E 12, lava N 30", landmarks(text));
    }

    @Test
    void aBellIsAVillageSign() {
        String text = fmt("north", List.of(hit("bell", 60, 70, -60, 85.0), hit("bell", 90, 70, -90, 127.0)), List.of());
        assertTrue(text.contains(", village: bell NE 85."), text);
    }

    @Test
    void aVillagerSeenIsAVillageSignAndAZombieVillagerIsNot() {
        String text = fmt("north", List.of(), List.of(new Seen("villager", 31, null, 41.0, 0, 64, -40), new Seen("zombie_villager", 32, null, 10.0, 0, 64, -10)));
        assertTrue(text.startsWith("landmarks: village: villager N 40. "), text);
        String onlyZombie = fmt("north", List.of(), List.of(new Seen("zombie_villager", 32, null, 10.0, 0, 64, -10)));
        assertFalse(onlyZombie.contains("landmarks"), onlyZombie);
    }

    @Test
    void aBellAndAVillagerAreBothListedUnderVillage() {
        String text = fmt("north", List.of(hit("bell", 60, 70, -60, 85.0)), List.of(new Seen("villager", 31, null, 41.0, 0, 64, -40)));
        assertTrue(text.contains("village: bell NE 85, villager N 40."), text);
    }

    @Test
    void aLandmarkThatIsNotInViewIsOmitted() {
        String text = fmt("north", List.of(hit("stone", 0, 64, -5, 5.0)), List.of());
        String line = landmarks(text);
        assertEquals("landmarks: highest y=64 N 5", line);
        for (String absent : List.of("tree", "water", "lava", "village")) assertFalse(line.contains(absent), line);
        String onlyWater = fmt("north", List.of(hit("water", 0, 62, 8, 8.0)), List.of());
        assertTrue(onlyWater.startsWith("landmarks: water S 8. "), onlyWater);
        assertFalse(onlyWater.contains("highest"), onlyWater);
    }

    @Test
    void noLandmarksLineAtAllWhenNothingIsInView() {
        assertFalse(fmt("north", List.of(), List.of(new Seen("cow", 3, null, 5.0, 1, 64, 1))).contains("landmarks"));
        assertFalse(fmt("north", List.of(), List.of()).contains("landmarks"));
    }

    @Test
    void theTextOrderIsNotableThenLandmarksThenFacingGroupsThenEntities() {
        String text = fmt("north", List.of(hit("iron_ore", 2, 63, -9, 3.5), hit("oak_log", 0, 70, -12, 12.0)),
                List.of(new Seen("zombie", 213, null, 9.2, 4, 64, -19)));
        int notable = text.indexOf("notable: ");
        int landmarks = text.indexOf("landmarks: ");
        int facing = text.indexOf("facing north:");
        int entities = text.indexOf("entities: ");
        assertTrue(notable == 0 && notable < landmarks && landmarks < facing && facing < entities, text);
    }

    @Test
    void aNearbyThingsExactTextExample() {
        String text = fmt("north", List.of(hit("stone", 30, 87, -30, 45.0), hit("oak_log", 28, 80, -28, 40.0), hit("water", 0, 62, 12, 12.0),
                hit("lava", -30, 20, 0, 30.0), hit("bell", 60, 70, -60, 85.0)), List.of());
        assertTrue(text.startsWith("landmarks: highest y=87 NE 42, tree NE 40, water S 12, lava W 30, village: bell NE 85. facing north:"), text);
    }

    // ---- the cap, at 96-block distances ----

    @Test
    void aCrowdedSceneStaysUnderTheCapAndTheLandmarksSurviveIt() {
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < 14; i++) hits.add(hit("deepslate_diamond_ore", -95 + i, -60 + i, 90 - i, 90.0 + i / 10.0));
        for (int i = 0; i < 60; i++) hits.add(hit("some_rather_long_block_kind_name_" + i, 40 + i, 64, -90 + i / 3, 80.0 + i / 10.0));
        hits.add(hit("oak_log", 30, 87, -30, 45.0));
        hits.add(hit("birch_leaves", -60, 70, -60, 85.0));
        hits.add(hit("water", 0, 60, 90, 90.0));
        hits.add(hit("water", 0, 60, 95, 95.0));
        hits.add(hit("lava", -70, -20, 0, 70.0));
        hits.add(hit("bell", 60, 70, -60, 85.0));
        List<Seen> seen = new ArrayList<>();
        for (int i = 0; i < 30; i++) seen.add(new Seen("zombie", 100 + i, null, 60.0 + i, 50 + i, 64, -50));
        seen.add(new Seen("villager", 31, null, 41.0, 0, 64, -40));
        String text = fmt("north", hits, seen);
        assertTrue(text.length() <= Sight.MAX_CHARS, "length " + text.length() + ": " + text);
        assertEquals("landmarks: highest y=87 NE 42, tree NE 42, water S 90, lava W 70, village: bell NE 85, villager N 40", landmarks(text));
        assertTrue(text.startsWith("notable: deepslate_diamond_ore"), text);
        assertTrue(text.indexOf("notable: ") < text.indexOf("landmarks: "), text);
        assertTrue(text.indexOf("landmarks: ") < text.indexOf("facing north:"), text);
        assertFalse(text.contains("some_rather_long_block_kind_name_59"), text);
    }

    @Test
    void landmarksSurviveEvenWhenEveryOtherPartHadToGo() {
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < 40; i++) hits.add(hit("deepslate_redstone_ore_with_a_very_long_name_" + i, -95 + i, -60, 90, 90.0));
        hits.add(hit("oak_log", 30, 87, -30, 45.0));
        hits.add(hit("water", 0, 60, 90, 90.0));
        hits.add(hit("lava", -70, -20, 0, 70.0));
        hits.add(hit("bell", 60, 70, -60, 85.0));
        List<Seen> seen = new ArrayList<>();
        for (int i = 0; i < 30; i++) seen.add(new Seen("zombie", 100 + i, "Player" + i, 60.0 + i, 50 + i, 64, -50));
        String text = fmt("north", hits, seen);
        assertTrue(text.length() <= Sight.MAX_CHARS, "length " + text.length() + ": " + text);
        assertTrue(text.contains("landmarks: highest y=87 NE 42, tree NE 42, water S 90, lava W 70, village: bell NE 85."), text);
    }
}
