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
        assertEquals(new Action.Craft("oak_planks", 4), v1.parse("craft", json("{\"item\":\"oak_planks\",\"count\":4}")).action());
        assertEquals(new Action.Smelt("minecraft:raw_iron", 8), v1.parse("smelt", json("{\"item\":\"minecraft:raw_iron\",\"count\":\"8\"}")).action());
    }

    @Test
    void survivalAndStorageToolsParse() {
        assertEquals(new Action.Eat(false), v1.parse("eat", json("{}")).action());
        assertEquals(new Action.Store(1, 2, 3, "oak_log", 15),
                v1.parse("store", json("{\"x\":1,\"y\":2,\"z\":3,\"item\":\"minecraft:Oak_Log\",\"count\":15}")).action());
        assertEquals(new Action.Store(1, 2, 3, Action.ALL, 0),
                v1.parse("store", json("{\"x\":1,\"y\":2,\"z\":3,\"item\":\"all\",\"count\":9999}")).action(), "count is ignored for all");
        assertEquals(new Action.Take(1, 2, 3, "iron_ingot", 2304),
                v1.parse("take", json("{\"x\":1,\"y\":2,\"z\":3,\"item\":\"iron_ingot\",\"count\":2304}")).action());
        assertEquals(new Action.FollowPlayer("Steve", 30), v1.parse("follow_player", json("{\"name\":\"Steve\",\"seconds\":30}")).action());
        assertEquals("failed:bad_input:count", error(v1, "take", "{\"x\":1,\"y\":2,\"z\":3,\"item\":\"iron_ingot\",\"count\":2305}"));
        assertEquals("failed:bad_input:count", error(v1, "store", "{\"x\":1,\"y\":2,\"z\":3,\"item\":\"oak_log\",\"count\":0}"));
        assertEquals("failed:bad_input:item", error(v1, "store", "{\"x\":1,\"y\":2,\"z\":3,\"count\":1}"));
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
        assertEquals("failed:bad_input:count", error(v1, "craft", "{\"item\":\"stick\",\"count\":65}"));
        assertEquals("failed:bad_input:count", error(v1, "craft", "{\"item\":\"stick\",\"count\":0}"));
        assertEquals("failed:bad_input:item", error(v1, "craft", "{\"count\":1}"));
        assertEquals("failed:bad_input:count", error(v1, "smelt", "{\"item\":\"raw_iron\",\"count\":2.5}"));
        assertEquals("failed:bad_input:item", error(v1, "smelt", "{\"item\":\" \",\"count\":1}"));
    }

    @Test
    void gatherAndDigParse() {
        assertEquals(new Action.Gather("log", 16), v1.parse("gather", json("{\"target\":\"log\",\"count\":16}")).action());
        assertEquals(new Action.Gather("iron_ore", 256), v1.parse("gather", json("{\"target\":\"minecraft:Iron_Ore\",\"count\":\"256\"}")).action());
        assertEquals(new Action.Dig("down", 15), v1.parse("dig", json("{\"direction\":\"Down\",\"length\":15}")).action());
        assertEquals(new Action.Dig("north", 1), v1.parse("dig", json("{\"direction\":\"north\",\"length\":1}")).action());
        assertEquals("failed:bad_input:count", error(v1, "gather", "{\"target\":\"log\",\"count\":257}"));
        assertEquals("failed:bad_input:count", error(v1, "gather", "{\"target\":\"log\",\"count\":0}"));
        assertEquals("failed:bad_input:target", error(v1, "gather", "{\"count\":4}"));
        assertEquals("failed:bad_input:direction", error(v1, "dig", "{\"direction\":\"up\",\"length\":4}"));
        assertEquals("failed:bad_input:length", error(v1, "dig", "{\"direction\":\"east\",\"length\":33}"));
        assertEquals("failed:bad_input:length", error(v1, "dig", "{\"direction\":\"east\"}"));
    }

    @Test
    void farmingAndBucketToolsParse() {
        assertEquals(new Action.Till(4, 63, -2), v1.parse("till", json("{\"x\":4,\"y\":63,\"z\":-2}")).action());
        assertEquals(new Action.Plant(4, 63, -2, "wheat_seeds"), v1.parse("plant", json("{\"x\":4,\"y\":63,\"z\":-2,\"item\":\"minecraft:Wheat_Seeds\"}")).action());
        assertEquals(new Action.Harvest(10, 64, 10, 8), v1.parse("harvest", json("{\"x\":10,\"y\":64,\"z\":10,\"radius\":8}")).action());
        assertEquals(new Action.Harvest(10, 64, 10, 1), v1.parse("harvest", json("{\"x\":10,\"y\":64,\"z\":10,\"radius\":\"1\"}")).action());
        assertEquals(new Action.UseBucket(0, 70, 0), v1.parse("use_bucket", json("{\"x\":0,\"y\":70,\"z\":0}")).action());
        assertEquals("failed:bad_input:radius", error(v1, "harvest", "{\"x\":10,\"y\":64,\"z\":10,\"radius\":0}"));
        assertEquals("failed:bad_input:radius", error(v1, "harvest", "{\"x\":10,\"y\":64,\"z\":10,\"radius\":9}"));
        assertEquals("failed:bad_input:radius", error(v1, "harvest", "{\"x\":10,\"y\":64,\"z\":10}"));
        assertEquals("failed:bad_input:item", error(v1, "plant", "{\"x\":4,\"y\":63,\"z\":-2}"));
        assertEquals("failed:bad_input:item", error(v1, "plant", "{\"x\":4,\"y\":63,\"z\":-2,\"item\":\" \"}"));
        assertEquals("failed:bad_input:y", error(v1, "till", "{\"x\":4,\"y\":400,\"z\":-2}"));
        assertEquals("failed:bad_input:z", error(v1, "use_bucket", "{\"x\":0,\"y\":70}"));
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
        assertEquals("denied:unknown_tool", error(new ToolSchemas(3), "fight", "{\"entity_id\":42}"));
        assertEquals(new Action.Fight(42), new ToolSchemas(4).parse("fight", json("{\"entity_id\":42}")).action());
        assertEquals("failed:bad_input:entity_id", error(new ToolSchemas(4), "fight", "{}"));
    }

    @Test
    void phaseFourAddsAttackAndFight() {
        List<String> names = new ArrayList<>();
        for (JsonElement el : new ToolSchemas(4).definitions()) names.add(el.getAsJsonObject().get("name").getAsString());
        assertTrue(names.contains("attack_entity"));
        assertTrue(names.contains("fight"));
        assertEquals(new ToolSchemas(2).definitions().size() + 2, names.size());
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
                "store", "take", "eat", "follow_player", "mine_block", "place_block", "equip", "craft", "smelt",
                "gather", "dig", "till", "plant", "harvest", "use_bucket"), names);
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
        ToolSchemas.Parsed fight = new ToolSchemas(4).parse("fight", json("{\"entity_id\":7,\"prio\":3}"));
        assertEquals(3, fight.prio());
        assertFalse(fight.prioLowered());
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
    void nullPrioMeansTheDefault() {
        ToolSchemas.Parsed p = v1.parse("stop", json("{\"prio\":null}"));
        assertEquals(new Action.Stop(), p.action());
        assertEquals(1, p.prio());
        assertEquals("failed:bad_input:z", error(v1, "move_to", "{\"x\":1,\"y\":64,\"z\":null}"), "a null required field still fails");
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
