package com.example.claudeplayer.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.act.Action;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolParseTest {
    private final ToolSchemas v1 = new ToolSchemas(2);

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static String error(ToolSchemas tools, String name, String input) {
        ToolSchemas.Parsed p = tools.parse(name, json(input));
        assertNull(p.action());
        return p.error().code();
    }

    @Test
    void validCallsBecomeActions() {
        assertEquals(new Action.MoveTo(1.5, 64, -3), v1.parse("move_to", json("{\"x\":1.5,\"y\":64,\"z\":-3}")).action());
        assertEquals(new Action.MoveTo(12, 64, 0), v1.parse("move_to", json("{\"x\":\"12\",\"y\":64,\"z\":0}")).action());
        assertEquals(new Action.Wait(2.5), v1.parse("wait", json("{\"seconds\":2.5}")).action());
        assertEquals(new Action.Stop(), v1.parse("stop", json("{}")).action());
        assertEquals(new Action.Note("chest@1,2,3", "15 oak_log"), v1.parse("note", json("{\"subject\":\"chest@1,2,3\",\"text\":\"15 oak_log\"}")).action());
        assertEquals(new Action.InspectContainer(1, 2, 3), v1.parse("inspect_container", json("{\"x\":1,\"y\":2,\"z\":3}")).action());
        assertEquals(new Action.Look(16), v1.parse("look", json("{\"radius\":16}")).action());
        assertEquals(new Action.MineBlock(4, -10, 7), v1.parse("mine_block", json("{\"x\":4,\"y\":-10,\"z\":7}")).action());
        assertEquals(new Action.PlaceBlock(4, 65, 7, "dirt"), v1.parse("place_block", json("{\"x\":4,\"y\":65,\"z\":7,\"item\":\"dirt\"}")).action());
        assertEquals(new Action.Equip("minecraft:iron_pickaxe"), v1.parse("equip", json("{\"item\":\"minecraft:iron_pickaxe\"}")).action());
    }

    @Test
    void malformedInputFailsWithTheField() {
        assertEquals("failed:bad_input:z", error(v1, "move_to", "{\"x\":1,\"y\":64}"));
        assertEquals("failed:bad_input:x", error(v1, "move_to", "{\"x\":\"far\",\"y\":64,\"z\":0}"));
        assertEquals("failed:bad_input:seconds", error(v1, "wait", "{\"seconds\":99}"));
        assertEquals("failed:bad_input:text", error(v1, "say", "{\"text\":\"   \"}"));
        assertEquals("failed:bad_input:x", error(v1, "inspect_container", "{\"x\":1.5,\"y\":2,\"z\":3}"));
        assertEquals("failed:bad_input:radius", error(v1, "look", "{\"radius\":99}"));
        assertEquals("failed:bad_input:y", error(v1, "mine_block", "{\"x\":4,\"y\":999,\"z\":7}"));
        assertEquals("failed:bad_input:item", error(v1, "place_block", "{\"x\":4,\"y\":65,\"z\":7}"));
        assertEquals("failed:bad_input:item", error(v1, "equip", "{\"item\":\"  \"}"));
    }

    @Test
    void longChatIsCutNotRejected() {
        Action.Say say = (Action.Say) v1.parse("say", json("{\"text\":\"" + "a".repeat(500) + "\"}")).action();
        assertEquals(200, say.text().length());
    }

    @Test
    void unknownAndLaterPhaseToolsAreDenied() {
        assertEquals("denied:unknown_tool", error(v1, "fly", "{}"));
        assertEquals("denied:unknown_tool", error(v1, "attack_entity", "{\"entity_id\":42}"));
        assertEquals(new Action.AttackEntity(42), new ToolSchemas(4).parse("attack_entity", json("{\"entity_id\":42}")).action());
    }

    @Test
    void definitionsListOnlyEnabledToolsInAFixedOrder() {
        List<String> names = new ArrayList<>();
        for (JsonElement el : v1.definitions()) {
            JsonObject def = el.getAsJsonObject();
            assertEquals("object", def.getAsJsonObject("input_schema").get("type").getAsString());
            names.add(def.get("name").getAsString());
        }
        assertEquals(List.of("get_state", "look", "move_to", "say", "wait", "stop", "flee", "note", "remember", "forget", "inspect_container",
                "mine_block", "place_block", "equip"), names);
    }

    @Test
    void thereIsNoToolForLevelsAllowlistConfigOrCommands() {
        List<String> banned = List.of("level", "caution", "allow", "allowlist", "config", "command", "op", "permission");
        for (JsonElement el : new ToolSchemas(6).definitions()) {
            String name = el.getAsJsonObject().get("name").getAsString();
            for (String part : name.split("_")) assertFalse(banned.contains(part), name);
        }
    }

    @Test
    void prioDefaultsToOneAndTwoIsKept() {
        ToolSchemas.Parsed p = v1.parse("move_to", json("{\"x\":1,\"y\":64,\"z\":1}"));
        assertEquals(1, p.prio());
        assertFalse(p.prioLowered());
        assertEquals(2, v1.parse("move_to", json("{\"x\":1,\"y\":64,\"z\":1,\"prio\":2}")).prio());
    }

    @Test
    void prioThreeIsOnlyForSafetyTools() {
        assertEquals(3, v1.parse("flee", json("{\"prio\":3}")).prio());
        assertEquals(3, v1.parse("stop", json("{\"prio\":3}")).prio());
        assertEquals(3, new ToolSchemas(4).parse("attack_entity", json("{\"entity_id\":7,\"prio\":3}")).prio());
        ToolSchemas.Parsed lowered = v1.parse("say", json("{\"text\":\"hi\",\"prio\":3}"));
        assertEquals(2, lowered.prio());
        assertTrue(lowered.prioLowered());
    }

    @Test
    void badPrioFailsWithTheField() {
        for (String bad : List.of("0", "4", "1.5", "\"high\"")) {
            assertEquals("failed:bad_input:prio", error(v1, "stop", "{\"prio\":" + bad + "}"), bad);
        }
    }

    @Test
    void fleeIsAPhaseOneTool() {
        assertEquals(new Action.Flee(), new ToolSchemas(1).parse("flee", json("{}")).action());
    }

    @Test
    void everyToolOffersAnOptionalPrio() {
        for (JsonElement d : new ToolSchemas(6).definitions()) {
            JsonObject schema = d.getAsJsonObject().getAsJsonObject("input_schema");
            assertTrue(schema.getAsJsonObject("properties").has("prio"), d.toString());
            assertFalse(schema.getAsJsonArray("required").contains(new JsonPrimitive("prio")), d.toString());
        }
    }
}
