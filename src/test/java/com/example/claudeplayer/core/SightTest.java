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
    private static Hit hit(String block, int x, int y, int z, double dist) {
        return new Hit(block, x, y, z, dist, false);
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
        String text = Sight.format("north", List.of(hit("stone", 3, 64, -10, 4.1), hit("stone", 5, 64, -12, 6.0), hit("stone", 4, 64, -11, 5.0),
                hit("oak_log", 5, 65, -12, 6.0)), List.of());
        assertEquals("facing north: stone x3 (nearest 3,64,-10 at 4.1), oak_log x1 (5,65,-12 at 6.0).", text);
    }

    @Test
    void nearestHitIsChosenWhateverTheOrderRaysCameIn() {
        String text = Sight.format("east", List.of(hit("dirt", 9, 64, 0, 9.5), hit("dirt", 2, 64, 0, 2.5), hit("dirt", 5, 64, 0, 5.5)), List.of());
        assertTrue(text.contains("dirt x3 (nearest 2,64,0 at 2.5)"), text);
    }

    @Test
    void sameBlockHitByManyRaysCountsOnce() {
        String text = Sight.format("south", List.of(hit("stone", 1, 64, 1, 3.0), hit("stone", 1, 64, 1, 3.2), hit("stone", 1, 64, 1, 3.4)), List.of());
        assertTrue(text.contains("stone x1"), text);
    }

    @Test
    void notableBlocksComeFirstWithExactPositionsEvenFromOneRay() {
        String text = Sight.format("north", List.of(hit("stone", 3, 64, -10, 4.1), hit("iron_ore", 2, 63, -9, 3.5), new Hit("chest", 7, 64, -3, 8.2, true),
                hit("ripe_wheat", 1, 64, -2, 2.0)), List.of());
        assertEquals("notable: ripe_wheat at 1,64,-2 (2.0), iron_ore at 2,63,-9 (3.5), chest at 7,64,-3 (8.2). facing north: stone x1 (3,64,-10 at 4.1).", text);
    }

    @Test
    void aContainerTheGameFlaggedIsNotableWhateverItsId() {
        String text = Sight.format("north", List.of(new Hit("furnace", 1, 64, 1, 2.0, true)), List.of());
        assertTrue(text.startsWith("notable: furnace at 1,64,1 (2.0)."), text);
    }

    @Test
    void fluidsListAfterTheSolids() {
        String text = Sight.format("west", List.of(hit("water", 0, 62, 5, 1.5), hit("lava", 0, 40, 5, 3.0), hit("stone", 9, 64, 9, 12.0)), List.of());
        assertEquals("facing west: stone x1 (9,64,9 at 12.0), water x1 (0,62,5 at 1.5), lava x1 (0,40,5 at 3.0).", text);
    }

    @Test
    void entitiesListNearestFirstAndPlayerNamesAreCleaned() {
        String text = Sight.format("north", List.of(), List.of(new Seen("zombie", 213, null, 9.2, 4, 64, -19),
                new Seen("player", 7, "Al§cex", 3.0, 1, 64, -2)));
        assertEquals("facing north: nothing in view. entities: player Alex #7 at 3.0 (1,64,-2), zombie #213 at 9.2 (4,64,-19).", text);
    }

    @Test
    void anEmptyViewSaysSo() {
        assertEquals("facing down: nothing in view.", Sight.format("down", List.of(), List.of()));
    }

    @Test
    void theAnswerStaysUnderTheCapAndKeepsNotableAndNearestEntries() {
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < 60; i++) hits.add(hit("block_kind_" + i, i, 64, 0, 3.0 + i));
        hits.add(hit("diamond_ore", 9, 5, 9, 20.0));
        List<Seen> seen = new ArrayList<>();
        for (int i = 0; i < 30; i++) seen.add(new Seen("zombie", 100 + i, null, 5.0 + i, i, 64, 0));
        String text = Sight.format("north", hits, seen);
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
        String text = Sight.format("north", hits, List.of());
        assertTrue(text.length() <= Sight.MAX_CHARS, "length " + text.length());
        assertTrue(text.startsWith("notable: coal_ore at 0,10,0 (1.0), coal_ore at 1,10,0 (2.0)"), text);
        assertFalse(text.contains("coal_ore at 39,10,0"), text);
        assertTrue(text.contains("more"), text);
    }
}
