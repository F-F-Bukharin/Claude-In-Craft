package com.example.claudeplayer.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.claudeplayer.core.act.Action;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    }

    @Test
    void malformedInputFailsWithTheField() {
        assertEquals("failed:bad_input:z", error(v1, "move_to", "{\"x\":1,\"y\":64}"));
        assertEquals("failed:bad_input:x", error(v1, "move_to", "{\"x\":\"far\",\"y\":64,\"z\":0}"));
        assertEquals("failed:bad_input:seconds", error(v1, "wait", "{\"seconds\":99}"));
        assertEquals("failed:bad_input:text", error(v1, "say", "{\"text\":\"   \"}"));
        assertEquals("failed:bad_input:x", error(v1, "inspect_container", "{\"x\":1.5,\"y\":2,\"z\":3}"));
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
        assertEquals(List.of("get_state", "move_to", "say", "wait", "stop", "note", "remember", "forget", "inspect_container"), names);
    }

    @Test
    void thereIsNoToolForLevelsAllowlistConfigOrCommands() {
        List<String> banned = List.of("level", "caution", "allow", "allowlist", "config", "command", "op", "permission");
        for (JsonElement el : new ToolSchemas(6).definitions()) {
            String name = el.getAsJsonObject().get("name").getAsString();
            for (String part : name.split("_")) assertFalse(banned.contains(part), name);
        }
    }
}
