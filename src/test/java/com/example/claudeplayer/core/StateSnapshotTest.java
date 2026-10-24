package com.example.claudeplayer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    /** UTF-8 bytes of the full-scene JSON below, measured when the test was written (3108 before the creatures were added, 719 for the 8 of them). */
    private static final int FULL_SCENE_MEASURED_BYTES = 3827;

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

    private static StateSnapshot withCreatures(List<StateSnapshot.Creature> creatures) {
        StateSnapshot.Vec body = new StateSnapshot.Vec(0, 87, 4);
        return new StateSnapshot(true, false, body, "minecraft:overworld", "north", 20, 20, 20, "empty",
                List.of(), List.of(), List.of(), List.of(), creatures, Map.of(), List.of(), null, 1000, "clear", 3, body, null, 0);
    }

    @Test
    void creaturesCarryIdTypeDistanceBabyTamedAndAnIntegerPosition() {
        StateSnapshot s = withCreatures(List.of(
                new StateSnapshot.Creature(12, "cow", null, 5.23, false, false, null, new StateSnapshot.Vec(10.9, 64, -3.2)),
                new StateSnapshot.Creature(13, "wolf", null, 7.0, true, true, null, new StateSnapshot.Vec(1.5, 70.99, 2.5))));
        JsonObject json = JsonParser.parseString(s.toJson()).getAsJsonObject();
        assertTrue(json.has("creatures"));
        JsonObject cow = json.getAsJsonArray("creatures").get(0).getAsJsonObject();
        assertEquals(List.of("id", "type", "dist", "baby", "tamed", "pos"), List.copyOf(cow.keySet()));
        assertEquals(12, cow.get("id").getAsInt());
        assertEquals("cow", cow.get("type").getAsString());
        assertEquals(5.2, cow.get("dist").getAsDouble());
        assertEquals(false, cow.get("baby").getAsBoolean());
        assertEquals(false, cow.get("tamed").getAsBoolean());
        assertEquals("[10,64,-4]", cow.get("pos").toString(), "block position: floor of each coordinate");
        JsonObject wolf = json.getAsJsonArray("creatures").get(1).getAsJsonObject();
        assertEquals(true, wolf.get("baby").getAsBoolean());
        assertEquals(true, wolf.get("tamed").getAsBoolean());
        assertEquals("[1,70,2]", wolf.get("pos").toString());
        assertEquals("{\"id\":12,\"type\":\"cow\",\"dist\":5.2,\"baby\":false,\"tamed\":false,\"pos\":[10,64,-4]}",
                json.getAsJsonArray("creatures").get(0).toString());
    }

    @Test
    void onlyVillagersCarryAJobAndOnlyNamedCreaturesCarryAName() {
        StateSnapshot s = withCreatures(List.of(
                new StateSnapshot.Creature(1, "villager", null, 3.0, false, false, "farmer", new StateSnapshot.Vec(0, 64, 0)),
                new StateSnapshot.Creature(2, "villager", "  Bob<script>\n§4Evil  ", 4.0, false, false, "nitwit", new StateSnapshot.Vec(0, 64, 0)),
                new StateSnapshot.Creature(3, "sheep", "x".repeat(100), 5.0, false, false, null, new StateSnapshot.Vec(0, 64, 0))));
        var list = JsonParser.parseString(s.toJson()).getAsJsonObject().getAsJsonArray("creatures");
        assertEquals("farmer", list.get(0).getAsJsonObject().get("job").getAsString());
        assertFalse(list.get(0).getAsJsonObject().has("name"));
        assertEquals("Bob‹script› Evil", list.get(1).getAsJsonObject().get("name").getAsString(), "world text is cleaned");
        assertFalse(list.get(2).getAsJsonObject().has("job"), "no job for a sheep");
        assertTrue(list.get(2).getAsJsonObject().get("name").getAsString().length() <= com.example.claudeplayer.core.safety.WorldText.NAME_MAX);
    }

    @Test
    void theOldConstructorMeansNoCreaturesAndAbsentStillWorks() {
        StateSnapshot.Vec body = new StateSnapshot.Vec(0, 87, 4);
        StateSnapshot old = new StateSnapshot(true, false, body, "minecraft:overworld", "north", 20, 20, 20, "empty",
                List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), null, 1000, "clear", 3, body, null, 0);
        assertEquals(List.of(), old.creatures());
        assertTrue(old.toJson().contains("\"creatures\":[]"), old.toJson());
        assertEquals(List.of(), StateSnapshot.absent(true, 3, null).creatures());
        assertFalse(StateSnapshot.absent(false, 3, null).toJson().contains("creatures"));
    }

    /**
     * Pins the size of a busy scene: 36 inventory slots, 10 hostiles, 10 players, 8 creatures, 12 points, blocks_near and a full
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
        String[] animals = {"cow", "sheep", "pig", "chicken", "wolf", "villager", "cow", "horse"};
        List<StateSnapshot.Creature> creatures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            boolean villager = animals[i].equals("villager");
            creatures.add(new StateSnapshot.Creature(2000 + i, animals[i], villager ? "Old Pete" : null, 2.5 + i * 2.7,
                    i == 3 || i == 6, animals[i].equals("wolf"), villager ? "farmer" : null,
                    new StateSnapshot.Vec(125.5 - i, 64 + i % 3, -36.5 + i)));
        }
        WorldMap.View map = new WorldMap.View(112, -41, List.copyOf(rows));
        StateSnapshot s = new StateSnapshot(true, false, body, "minecraft:overworld", "north", 17.5f, 20f, 14,
                "iron_sword", List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"),
                inventory, hostiles, players, creatures, blocks, points, map, 6000, "clear", 3,
                new StateSnapshot.Vec(100, 64, -40), "mine_block", 4);
        int bytes = s.toJson().getBytes(StandardCharsets.UTF_8).length;
        int limit = (int) Math.ceil(FULL_SCENE_MEASURED_BYTES * 1.1 / 100) * 100;
        assertTrue(bytes <= limit, "full scene JSON is " + bytes + " bytes, over the limit of " + limit);
    }
}
