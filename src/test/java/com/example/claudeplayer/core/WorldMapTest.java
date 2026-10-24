package com.example.claudeplayer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.WorldMap.Kind;
import com.example.claudeplayer.core.brain.PromptBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The map's reading of terrain around feet level y = 64, on a flat floor at y = 63 unless a test changes it. */
class WorldMapTest {
    private final Map<String, Kind> blocks = new HashMap<>();

    private void put(int x, int y, int z, Kind k) {
        blocks.put(x + "," + y + "," + z, k);
    }

    private final WorldMap.Sampler world = (x, y, z) -> blocks.getOrDefault(x + "," + y + "," + z, y < 64 ? Kind.SOLID : Kind.OPEN);

    private char at(int x, int z) {
        return WorldMap.cell(world, x, 64, z);
    }

    @Test
    void readsFloorStepsDropsAndWalls() {
        assertEquals('.', at(0, 0));
        put(1, 64, 0, Kind.SOLID);
        assertEquals('+', at(1, 0), "one block up with headroom is a jump");
        put(2, 64, 0, Kind.SOLID);
        put(2, 65, 0, Kind.SOLID);
        assertEquals('#', at(2, 0), "two high is a wall");
        put(3, 63, 0, Kind.OPEN);
        assertEquals('-', at(3, 0), "one block down");
        put(4, 63, 0, Kind.OPEN);
        put(4, 62, 0, Kind.OPEN);
        assertEquals('v', at(4, 0), "two or more down");
        put(5, 65, 0, Kind.SOLID);
        assertEquals('#', at(5, 0), "no headroom");
    }

    @Test
    void marksCropsAndBareFarmland() {
        put(0, 63, 0, Kind.FARMLAND);
        assertEquals('=', at(0, 0), "farmland with nothing on it");
        put(0, 64, 0, WorldMap.crop(7, 7));
        assertEquals('W', at(0, 0), "ripe");
        put(0, 64, 0, WorldMap.crop(3, 7));
        assertEquals('w', at(0, 0), "growing");
        put(1, 63, 0, Kind.SOLID);
        put(1, 64, 0, Kind.CROP_RIPE);
        assertEquals('W', at(1, 0), "nether wart on soul sand needs no farmland");
        assertTrue(WorldMap.KEY.contains("W ripe crop") && WorldMap.KEY.contains("w growing crop") && WorldMap.KEY.contains("= bare farmland"));
    }

    @Test
    void cropsOneStepBelowOrAboveStillShow() {
        put(0, 62, 0, Kind.FARMLAND);
        put(0, 63, 0, Kind.CROP_RIPE);
        assertEquals('W', at(0, 0), "field one block down is a crop, not a drop");
        put(1, 61, 1, Kind.FARMLAND);
        put(1, 62, 1, Kind.CROP_GROWING);
        put(1, 63, 1, Kind.OPEN);
        assertEquals('w', at(1, 1), "two down still in the window");
        put(2, 65, 0, Kind.CROP_GROWING);
        put(2, 64, 0, Kind.SOLID);
        assertEquals('w', at(2, 0), "one up");
        put(3, 64, 1, Kind.CROP_RIPE);
        put(3, 63, 1, Kind.FARMLAND);
        put(3, 62, 1, Kind.CROP_GROWING);
        assertEquals('W', at(3, 1), "feet level wins over a deeper crop");
        put(4, 60, 0, Kind.CROP_RIPE);
        assertEquals('.', at(4, 0), "three down is outside the window");
    }

    @Test
    void ripeAtMaxAge() {
        assertEquals(Kind.CROP_GROWING, WorldMap.crop(0, 7));
        assertEquals(Kind.CROP_GROWING, WorldMap.crop(2, 3), "beetroot and nether wart top out at 3");
        assertEquals(Kind.CROP_RIPE, WorldMap.crop(3, 3));
        assertEquals(Kind.CROP_RIPE, WorldMap.crop(7, 7));
    }

    @Test
    void readsFluidsTreesContainersAndUnknown() {
        put(0, 63, 1, Kind.WATER);
        assertEquals('~', at(0, 1));
        put(1, 64, 1, Kind.LAVA);
        assertEquals('L', at(1, 1));
        put(2, 64, 1, Kind.TREE);
        put(2, 65, 1, Kind.TREE);
        assertEquals('T', at(2, 1));
        put(3, 64, 1, Kind.CONTAINER);
        assertEquals('C', at(3, 1));
        put(4, 64, 1, Kind.UNKNOWN);
        assertEquals(' ', at(4, 1), "unloaded chunks are left blank");
        put(5, 63, 1, Kind.OPEN);
        put(5, 62, 1, Kind.LAVA);
        assertEquals('L', at(5, 1), "a one-block drop into lava shows the lava");
    }

    @Test
    void renderPutsNorthUpWestLeftAndDrawsMarkers() {
        put(10, 64, 18, Kind.SOLID);
        put(10, 65, 18, Kind.SOLID); // a wall pillar north of the centre (z smaller)
        WorldMap.View v = WorldMap.render(world, 10, 64, 20, 2,
                List.of(new WorldMap.Marker(12, 20, '!'), new WorldMap.Marker(8, 22, 'P'), new WorldMap.Marker(99, 99, 'P')));
        assertEquals(8, v.originX());
        assertEquals(18, v.originZ());
        assertEquals(List.of(
                "..#..",
                ".....",
                "..@.!",
                ".....",
                "P...."), v.rows());
        assertEquals(1, v.scale());
        assertTrue(v.text().startsWith("map, north up, 1 cell = 1 block, top-left cell at x=8 z=18:\n..#.."), v.text());
    }

    // ---- height-aware cells: what rises above the foot-level slice ----

    private void column(int x, int z, int fromY, int toY, Kind k) {
        for (int y = fromY; y <= toY; y++) put(x, y, z, k);
    }

    @Test
    void aTreeOnAHillReadsLowerCaseT() {
        column(6, 0, 64, 66, Kind.SOLID); // hill, top surface 3 up
        put(6, 68, 0, Kind.TREE); // leaves hanging over it with a gap below
        assertEquals('t', at(6, 0));
        column(7, 0, 64, 69, Kind.SOLID);
        put(7, 70, 0, Kind.TREE); // trunk straight on the hilltop
        assertEquals('t', at(7, 0));
        column(8, 0, 64, 65, Kind.SOLID); // a plain 2-high wall with a canopy over it
        put(8, 70, 0, Kind.TREE);
        assertEquals('t', at(8, 0), "any tree within 12 up counts");
        column(9, 0, 64, 66, Kind.SOLID);
        put(9, 76, 0, Kind.TREE); // feet + 12: the last block of the window
        assertEquals('t', at(9, 0));
        column(10, 0, 64, 66, Kind.SOLID);
        put(10, 77, 0, Kind.TREE); // feet + 13: out of the window
        assertEquals('^', at(10, 0), "a tree past the window leaves high ground");
    }

    @Test
    void groundThreeOrMoreAboveFeetReadsCaret() {
        column(1, 0, 64, 66, Kind.SOLID);
        assertEquals('^', at(1, 0), "top surface 3 above the feet");
        column(2, 0, 64, 65, Kind.SOLID);
        assertEquals('#', at(2, 0), "a 2-high wall stays a wall");
        column(3, 0, 64, 76, Kind.SOLID); // open air at feet + 13
        assertEquals('^', WorldMap.cell(world, (x, z) -> 77, 3, 64, 0), "the heightmap puts the top at feet + 12");
        assertEquals('#', at(3, 0), "without a heightmap a run through the whole window is not known to end");
        column(4, 0, 64, 100, Kind.SOLID);
        assertEquals('#', WorldMap.cell(world, (x, z) -> 101, 4, 64, 0), "solid past the window: could be a cave wall");
        assertEquals('#', WorldMap.cell(world, (x, z) -> 120, 4, 64, 0), "underground, the surface far above");
        column(5, 0, 64, 65, Kind.SOLID);
        column(5, 0, 68, 69, Kind.SOLID); // floating block above a 2-high wall: the solid run is broken
        assertEquals('#', at(5, 0));
        put(6, 65, 0, Kind.SOLID); // feet open, a ceiling block at head height, then a 3-thick roof
        column(6, 0, 66, 68, Kind.SOLID);
        assertEquals('^', at(6, 0), "solid from head height up to a top 5 above");
    }

    @Test
    void walkableCellsKeepTheirSymbolUnderTreesAndCliffs() {
        put(0, 70, 0, Kind.TREE);
        assertEquals('.', at(0, 0), "floor under a canopy");
        put(1, 64, 0, Kind.SOLID);
        put(1, 70, 0, Kind.TREE);
        assertEquals('+', at(1, 0), "a step under a canopy");
        put(2, 63, 0, Kind.OPEN);
        put(2, 62, 0, Kind.OPEN);
        column(2, 0, 66, 90, Kind.SOLID);
        assertEquals('v', at(2, 0), "a drop under an overhang");
        put(3, 63, 0, Kind.WATER);
        put(3, 70, 0, Kind.TREE);
        assertEquals('~', at(3, 0));
        put(4, 64, 1, Kind.TREE);
        put(4, 65, 1, Kind.TREE);
        put(4, 70, 1, Kind.TREE);
        assertEquals('T', at(4, 1), "a tree at feet level stays T");
    }

    @Test
    void theHeightmapSkipsAndBoundsTheUpwardScan() {
        int[] highReads = {0};
        int[] farReads = {0};
        WorldMap.Sampler counting = (x, y, z) -> {
            if (y >= 66) highReads[0]++;
            if (y >= 68) farReads[0]++;
            return world.at(x, y, z);
        };
        column(0, 0, 64, 65, Kind.SOLID);
        // the first free block above the highest blocking block is at 66: nothing at feet + 2 or higher
        assertEquals('#', WorldMap.cell(counting, (x, z) -> 66, 0, 64, 0));
        assertEquals(0, highReads[0], "an early-out costs no reads above the wall");
        column(1, 0, 64, 66, Kind.SOLID);
        put(1, 72, 0, Kind.TREE); // contradicts the heightmap on purpose: the scan must stop at its top
        assertEquals('^', WorldMap.cell(counting, (x, z) -> 68, 1, 64, 0));
        assertEquals(0, farReads[0], "the scan stops at the highest blocking block");
        assertEquals('t', WorldMap.cell(counting, (x, z) -> Integer.MAX_VALUE, 1, 64, 0), "unknown heights scan the window");
    }

    @Test
    void keyNamesTheNewSymbols() {
        assertTrue(WorldMap.KEY.contains("^ high ground or cliff"), WorldMap.KEY);
        assertTrue(WorldMap.KEY.contains("t tree on higher ground"), WorldMap.KEY);
        assertTrue(WorldMap.KEY.contains("L lava or fire"), WorldMap.KEY);
    }

    // ---- coarse cells for look past radius 24 ----

    private static final WorldMap.Sampler FLAT = (x, y, z) -> y < 64 ? Kind.SOLID : Kind.OPEN;

    @Test
    void scaleFollowsTheFormulaAndNeverExceedsFortyNineCells() {
        for (int r = 4; r <= 64; r++) {
            int scale = WorldMap.scale(r);
            assertEquals((2 * r + 1 + 48) / 49, scale, "scale at radius " + r);
            int cells = WorldMap.cells(r);
            assertTrue(cells <= 49 && cells % 2 == 1, "cells at radius " + r + " = " + cells);
            if (r <= 24) assertEquals(1, scale, "one block per cell up to radius 24");
            for (int cx : new int[] {0, 1, -7, 1000, -1001}) {
                WorldMap.View v = WorldMap.render(FLAT, cx, 64, 5 - cx, r, List.of());
                assertEquals(cells, v.rows().size(), "rows at radius " + r);
                for (String row : v.rows()) assertEquals(cells, row.length());
                assertEquals('@', v.rows().get(cells / 2).charAt(cells / 2), "centred at radius " + r);
                assertEquals(1, v.rows().stream().mapToInt(row -> (int) row.chars().filter(c -> c == '@').count()).sum());
                int centreStart = v.originX() + (cells / 2) * scale;
                assertTrue(cx >= centreStart && cx < centreStart + scale, "the body's column is inside the centre cell, r=" + r);
                int centreZ = v.originZ() + (cells / 2) * scale;
                assertTrue(5 - cx >= centreZ && 5 - cx < centreZ + scale, "and so is its row, r=" + r);
                assertTrue(v.originX() <= cx - r && v.originX() + cells * scale - 1 >= cx + r, "covers the radius east and west, r=" + r);
                assertTrue(v.originZ() <= 5 - cx - r && v.originZ() + cells * scale - 1 >= 5 - cx + r, "and north and south, r=" + r);
            }
        }
        assertEquals(49, WorldMap.cells(24));
        assertEquals(2, WorldMap.scale(25));
        assertEquals(3, WorldMap.scale(64));
        assertEquals(43, WorldMap.cells(64));
    }

    /** Radius 49 gives 3x3-block cells; the centre cell covers x and z from -1 to 1 and cell (17, 16) covers x 2..4, z -1..1. */
    private char coarse(int col, int row, WorldMap.Marker... markers) {
        WorldMap.View v = WorldMap.render(world, 0, 64, 0, 49, List.of(markers));
        assertEquals(3, v.scale());
        assertEquals(-49, v.originX());
        assertEquals(-49, v.originZ());
        return v.rows().get(row).charAt(col);
    }

    @Test
    void aCoarseCellReadsItsCentreColumn() {
        assertEquals('.', coarse(17, 16));
        put(2, 63, -1, Kind.OPEN);
        put(2, 62, -1, Kind.OPEN);
        assertEquals('.', coarse(17, 16), "a drop off the centre column isn't sampled");
        put(3, 63, 0, Kind.OPEN);
        put(3, 62, 0, Kind.OPEN);
        assertEquals('v', coarse(17, 16), "a drop in the centre column");
        put(3, 64, 0, Kind.SOLID);
        put(3, 65, 0, Kind.SOLID);
        assertEquals('#', coarse(17, 16), "a wall, and no heightmap to call it high ground");
        put(3, 64, 0, Kind.TREE);
        assertEquals('T', coarse(17, 16), "a tree at feet");
    }

    /** Cell (17, 16) at radius 49 with a heightmap that is flat (64) but for the centre column (3, 0). */
    private char coarseWithTop(int centreTop) {
        WorldMap.Heights heights = (x, z) -> x == 3 && z == 0 ? centreTop : 64;
        return WorldMap.render(world, heights, 0, 64, 0, 49, List.of()).rows().get(16).charAt(17);
    }

    @Test
    void aCoarseWallUsesTheHeightmapNotAnUpwardScan() {
        column(3, 0, 64, 70, Kind.SOLID);
        assertEquals('#', coarseWithTop(66), "top block at feet + 1: a 2-high wall");
        assertEquals('^', coarseWithTop(67), "top block at feet + 2: you stand 3 above the feet up there");
        assertEquals('^', coarseWithTop(77), "top at the edge of the window");
        assertEquals('#', coarseWithTop(78), "past the window: a cliff face or cave wall");
        put(3, 70, 0, Kind.TREE);
        assertEquals('t', coarseWithTop(71), "the one read at the top finds the tree");
        put(3, 72, 0, Kind.TREE); // above the heightmap's top: never read
        assertEquals('^', coarseWithTop(67));
    }

    @Test
    void aCoarseCellStillShowsFluidsAndContainersAnywhereInIt() {
        put(2, 63, -1, Kind.WATER);
        assertEquals('~', coarse(17, 16), "water in a corner of the cell");
        put(4, 63, 1, Kind.LAVA);
        assertEquals('L', coarse(17, 16), "lava beats water");
        put(2, 64, 1, Kind.CONTAINER);
        assertEquals('C', coarse(17, 16), "a container beats lava");
        assertEquals('P', coarse(17, 16, new WorldMap.Marker(3, 0, 'P')), "a player beats a container");
        assertEquals('!', coarse(17, 16, new WorldMap.Marker(3, 0, 'P'), new WorldMap.Marker(4, 1, '!')), "a hostile beats a player");
        assertEquals('!', coarse(17, 16, new WorldMap.Marker(4, 1, '!'), new WorldMap.Marker(3, 0, 'P')), "whatever the order");
    }

    @Test
    void coarseFloorShowsCropsAndUnknownWhenTheCentreIsUnknown() {
        put(3, 63, 0, Kind.FARMLAND);
        assertEquals('=', coarse(17, 16), "bare farmland in the centre column");
        put(3, 64, 0, Kind.CROP_GROWING);
        assertEquals('w', coarse(17, 16));
        put(3, 64, 0, Kind.CROP_RIPE);
        assertEquals('W', coarse(17, 16));
        for (int x = 2; x <= 4; x++) for (int z = -1; z <= 1; z++) put(x, 64, z, Kind.UNKNOWN);
        assertEquals(' ', coarse(17, 16), "an unknown centre column");
        put(2, 64, -1, Kind.LAVA);
        assertEquals('L', coarse(17, 16), "a cheap marker still shows");
    }

    @Test
    void radiusSixtyFourReadsAboutTwentyThreeBlocksPerCell() {
        int[] reads = {0};
        WorldMap.Sampler counting = (x, y, z) -> {
            reads[0]++;
            return FLAT.at(x, y, z);
        };
        WorldMap.render(counting, (x, z) -> 64, 0, 64, 0, 64, List.of());
        // 43 x 43 cells: the centre column's 7 reads (the crop window, feet, head, ground), 2 for each of the 8 others
        assertEquals(43 * 43 * (7 + 8 * 2), reads[0]);
        reads[0] = 0;
        WorldMap.render(counting, (x, z) -> 64, 0, 64, 0, 24, List.of());
        assertEquals(49 * 49 * 7, reads[0], "the one-block view reads as before");
    }

    @Test
    void coarseMarkersLandInTheirOwnCellAndTheBodyKeepsTheCentre() {
        assertEquals('P', coarse(17, 16, new WorldMap.Marker(4, 1, 'P')), "x 4, z 1 is the last block of cell (17, 16)");
        assertEquals('.', coarse(17, 16, new WorldMap.Marker(5, 1, 'P')), "x 5 is the next cell");
        assertEquals('P', coarse(18, 16, new WorldMap.Marker(5, 1, 'P')));
        assertEquals('!', coarse(15, 16, new WorldMap.Marker(-2, 0, '!')), "negative x floors toward the west cell");
        assertEquals('@', coarse(16, 16, new WorldMap.Marker(-1, 0, '!')), "x -1 is the centre cell, and @ wins in its cell");
        assertEquals('@', coarse(16, 16, new WorldMap.Marker(1, 1, 'P')));
        assertEquals('@', coarse(16, 16));
    }

    @Test
    void headerStatesTheScale() {
        WorldMap.View v = WorldMap.render(world, 0, 64, 0, 49, List.of());
        assertTrue(v.text().startsWith("map, north up, 1 cell = 3x3 blocks, top-left cell at x=-49 z=-49:\n"), v.text());
        assertEquals("map, north up, 1 cell = 1 block, top-left cell at x=3 z=4:\nab",
                new WorldMap.View(3, 4, List.of("ab")).text(), "the three-argument constructor means one block per cell");
        assertEquals(1, new WorldMap.View(3, 4, List.of("ab")).scale());
    }

    @Test
    void radiusEightStillGivesTheSeventeenBySeventeenMap() {
        WorldMap.View v = WorldMap.render(FLAT, 0, 64, 0, 8, List.of());
        assertEquals(17, v.rows().size());
        assertEquals(1, v.scale());
        assertEquals(-8, v.originX());
        assertEquals(".".repeat(8) + "@" + ".".repeat(8), v.rows().get(8));
    }

    @Test
    void snapshotJsonCarriesTheMapAndItsOrigin() {
        WorldMap.View v = WorldMap.render(world, 0, 64, 0, 1, List.of());
        StateSnapshot s = new StateSnapshot(true, false, new StateSnapshot.Vec(0.5, 64, 0.5), "minecraft:overworld", "north",
                20, 20, 20, "empty", List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), v, 1000, "clear", 3, null, null, 0);
        String json = s.toJson();
        assertTrue(json.contains("\"map_origin\":[-1,-1]"), json);
        assertTrue(json.contains("\"map\":[\"...\",\".@.\",\"...\"]"), json);
        assertFalse(StateSnapshot.absent(false, 3, null).toJson().contains("map"));
    }

    @Test
    void systemPromptExplainsTheMapAndShortChat() {
        String system = new PromptBuilder(Settings.DEFAULTS, PromptBuilder.loadTemplate(null)).system();
        assertTrue(system.contains(WorldMap.KEY));
        assertTrue(system.contains("15 words at most"));
        assertFalse(system.contains("{{"), "every placeholder is filled");
    }
}
