package com.example.claudeplayer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StateSnapshotTest {
    @Test
    void playersCarryTheirPositionSoComeHereWorks() {
        StateSnapshot.Vec body = new StateSnapshot.Vec(0, 87, 4);
        StateSnapshot s = new StateSnapshot(true, false, body, "minecraft:overworld", "north", 20, 20, 20, "empty",
                List.of(), List.of(), List.of(),
                List.of(new StateSnapshot.Seen("I_Must_Observe", 4.2, new StateSnapshot.Vec(3.46, 87, 6.51))),
                Map.of(), List.of(), 1000, "clear", 3, body, null, 0);
        JsonObject player = JsonParser.parseString(s.toJson()).getAsJsonObject().getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals("I_Must_Observe", player.get("name").getAsString());
        assertEquals("[3.5,87.0,6.5]", player.get("pos").toString());
    }

    @Test
    void pointsListNotableBlocksWithIntegerPositionsAfterBlocksNear() {
        StateSnapshot.Vec body = new StateSnapshot.Vec(0, 87, 4);
        StateSnapshot s = new StateSnapshot(true, false, body, "minecraft:overworld", "north", 20, 20, 20, "empty",
                List.of(), List.of(), List.of(), List.of(), Map.of("stone", 5),
                List.of(new StateSnapshot.Poi("minecraft:chest", 12, 91, 9)), 1000, "clear", 3, body, null, 0);
        JsonObject json = JsonParser.parseString(s.toJson()).getAsJsonObject();
        JsonObject poi = json.getAsJsonArray("points").get(0).getAsJsonObject();
        assertEquals("minecraft:chest", poi.get("block").getAsString());
        assertEquals("[12,91,9]", poi.get("pos").toString());
        assertEquals(List.of("blocks_near", "points"), json.keySet().stream().filter(k -> k.equals("blocks_near") || k.equals("points")).toList());
    }
}
