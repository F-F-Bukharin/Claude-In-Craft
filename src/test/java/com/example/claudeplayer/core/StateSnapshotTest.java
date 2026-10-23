package com.example.claudeplayer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StateSnapshotTest {
    /** UTF-8 bytes of the full-scene JSON below, measured when the test was written. */
    private static final int FULL_SCENE_MEASURED_BYTES = 3108;

    @Test
    void playersCarryTheirPositionSoComeHereWorks() {
        StateSnapshot.Vec body = new StateSnapshot.Vec(0, 87, 4);
        StateSnapshot s = new StateSnapshot(true, false, body, "minecraft:overworld", "north", 20, 20, 20, "empty",
                List.of(), List.of(), List.of(),
                List.of(new StateSnapshot.Seen("I_Must_Observe", 4.2, new StateSnapshot.Vec(3.46, 87, 6.51))),
                Map.of(), List.of(), null, 1000, "clear", 3, body, null, 0);
        JsonObject player = JsonParser.parseString(s.toJson()).getAsJsonObject().getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals("I_Must_Observe", player.get("name").getAsString());
        assertEquals("[3.5,87.0,6.5]", player.get("pos").toString());
    }

    @Test
    void pointsListNotableBlocksWithIntegerPositionsAfterBlocksNear() {
        StateSnapshot.Vec body = new StateSnapshot.Vec(0, 87, 4);
        StateSnapshot s = new StateSnapshot(true, false, body, "minecraft:overworld", "north", 20, 20, 20, "empty",
                List.of(), List.of(), List.of(), List.of(), Map.of("stone", 5),
                List.of(new StateSnapshot.Poi("minecraft:chest", 12, 91, 9)), null, 1000, "clear", 3, body, null, 0);
        JsonObject json = JsonParser.parseString(s.toJson()).getAsJsonObject();
        JsonObject poi = json.getAsJsonArray("points").get(0).getAsJsonObject();
        assertEquals("minecraft:chest", poi.get("block").getAsString());
        assertEquals("[12,91,9]", poi.get("pos").toString());
        assertEquals(List.of("blocks_near", "points"), json.keySet().stream().filter(k -> k.equals("blocks_near") || k.equals("points")).toList());
    }

    /**
     * Pins the size of a busy scene: 36 inventory slots, 10 hostiles, 10 players, 12 points, blocks_near and a full
     * 17 by 17 map. The limit is the measured size plus 10%, rounded up to the next 100 bytes, so growth shows up.
     */
    @Test
    void fullSceneJsonStaysWithinTenPercentOfItsMeasuredSize() {
        StateSnapshot.Vec body = new StateSnapshot.Vec(120.5, 64, -33.5);
        String[] items = {"cobblestone", "oak_planks", "torch", "bread", "iron_ingot", "coal", "arrow", "oak_log",
            "stick", "diamond", "raw_iron", "dirt"};
        List<StateSnapshot.Stack> inventory = new ArrayList<>();
        for (int i = 0; i < 36; i++) inventory.add(new StateSnapshot.Stack(items[i % items.length], 1 + (i * 7) % 64));
        String[] kinds = {"zombie", "skeleton", "creeper"};
        List<StateSnapshot.Mob> hostiles = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            hostiles.add(new StateSnapshot.Mob(1000 + i, kinds[i % kinds.length], null, 3.5 + i, 14.5f,
                    new StateSnapshot.Vec(118.5 + i, 64, -30.5)));
        }
        String[] names = {"Felipe", "Ana_Lima", "Gabriel", "Tiago", "Bruna", "Carlos", "Maria", "Lucas", "Helena", "Pedro"};
        List<StateSnapshot.Seen> players = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            players.add(new StateSnapshot.Seen(names[i], 5.0 + i, new StateSnapshot.Vec(110.25 + i, 64, -28.75)));
        }
        String[] spots = {"chest", "furnace", "crafting_table", "red_bed", "ender_chest"};
        List<StateSnapshot.Poi> points = new ArrayList<>();
        for (int i = 0; i < 12; i++) points.add(new StateSnapshot.Poi(spots[i % spots.length], 100 + i, 64, -40 - i));
        Map<String, Integer> blocks = new LinkedHashMap<>();
        blocks.put("stone", 40);
        blocks.put("dirt", 12);
        blocks.put("grass_block", 9);
        blocks.put("oak_log", 4);
        blocks.put("oak_leaves", 22);
        blocks.put("air", 90);
        blocks.put("water", 6);
        blocks.put("gravel", 3);
        char[] floor = "........#T.~+-C.Ww=P!".toCharArray();
        List<String> rows = new ArrayList<>();
        for (int r = 0; r < 17; r++) {
            char[] row = new char[17];
            for (int c = 0; c < 17; c++) row[c] = floor[(r * 5 + c * 3) % floor.length];
            if (r == 8) row[8] = '@';
            rows.add(new String(row));
        }
        WorldMap.View map = new WorldMap.View(112, -41, List.copyOf(rows));
        StateSnapshot s = new StateSnapshot(true, false, body, "minecraft:overworld", "north", 17.5f, 20f, 14,
                "iron_sword", List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"),
                inventory, hostiles, players, blocks, points, map, 6000, "clear", 3,
                new StateSnapshot.Vec(100, 64, -40), "mine_block", 4);
        int bytes = s.toJson().getBytes(StandardCharsets.UTF_8).length;
        int limit = (int) Math.ceil(FULL_SCENE_MEASURED_BYTES * 1.1 / 100) * 100;
        assertTrue(bytes <= limit, "full scene JSON is " + bytes + " bytes, over the limit of " + limit);
    }
}
