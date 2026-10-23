package com.example.claudeplayer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(v.text().startsWith("map, north up, top-left cell at x=8 z=18:\n..#.."));
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
