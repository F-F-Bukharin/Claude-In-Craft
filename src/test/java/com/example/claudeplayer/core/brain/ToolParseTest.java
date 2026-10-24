package com.example.claudeplayer.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.Settings;
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
        assertEquals("failed:bad_input:radius", error(v1, "look", "{\"radius\":65}"));
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
        assertEquals(new Action.UseBucket(0, 70, 0, null), v1.parse("use_bucket", json("{\"x\":0,\"y\":70,\"z\":0}")).action());
        assertEquals(new Action.UseBucket(0, 70, 0, "lava"), v1.parse("use_bucket", json("{\"x\":0,\"y\":70,\"z\":0,\"fluid\":\"lava\"}")).action());
        assertEquals(new Action.UseBucket(0, 70, 0, "water"), v1.parse("use_bucket", json("{\"x\":0,\"y\":70,\"z\":0,\"fluid\":\"Water\"}")).action());
        assertEquals(new Action.UseBucket(0, 70, 0, null), v1.parse("use_bucket", json("{\"x\":0,\"y\":70,\"z\":0,\"fluid\":null}")).action());
        assertEquals("failed:bad_input:radius", error(v1, "harvest", "{\"x\":10,\"y\":64,\"z\":10,\"radius\":0}"));
        assertEquals("failed:bad_input:radius", error(v1, "harvest", "{\"x\":10,\"y\":64,\"z\":10,\"radius\":9}"));
        assertEquals("failed:bad_input:radius", error(v1, "harvest", "{\"x\":10,\"y\":64,\"z\":10}"));
        assertEquals("failed:bad_input:item", error(v1, "plant", "{\"x\":4,\"y\":63,\"z\":-2}"));
        assertEquals("failed:bad_input:item", error(v1, "plant", "{\"x\":4,\"y\":63,\"z\":-2,\"item\":\" \"}"));
        assertEquals("failed:bad_input:y", error(v1, "till", "{\"x\":4,\"y\":400,\"z\":-2}"));
        assertEquals("failed:bad_input:z", error(v1, "use_bucket", "{\"x\":0,\"y\":70}"));
        assertEquals("failed:bad_input:fluid", error(v1, "use_bucket", "{\"x\":0,\"y\":70,\"z\":0,\"fluid\":\"milk\"}"));
        assertEquals("failed:bad_input:fluid", error(v1, "use_bucket", "{\"x\":0,\"y\":70,\"z\":0,\"fluid\":\"\"}"));
    }

    @Test
    void longChatIsCutNotRejected() {
        Action.Say say = (Action.Say) v1.parse("say", json("{\"text\":\"" + "a".repeat(500) + "\"}")).action();
        assertEquals(200, say.text().length());
    }

    @Test
    void glanceTakesNoArguments() {
        assertEquals(new Action.Glance(), v1.parse("glance", json("{}")).action());
        assertEquals("denied:unknown_tool", error(new ToolSchemas(1), "glance", "{}"), "a phase 2 tool");
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
        assertTrue(names.contains("hunt"));
        assertEquals(new ToolSchemas(2).definitions().size() + 3, names.size());
    }

    @Test
    void theAttackToolsAppearExactlyWhenTheSettingsSayAttacksAreOn() {
        for (int phase = 1; phase <= 6; phase++) {
            int p = phase;
            List<String> names = new ArrayList<>();
            for (JsonElement el : new ToolSchemas(phase).definitions()) names.add(el.getAsJsonObject().get("name").getAsString());
            boolean on = com.example.claudeplayer.core.Settings.of(b -> b.toolPhase = p).attacksEnabled();
            assertEquals(on, names.contains("attack_entity"), "phase " + phase);
            assertEquals(on, names.contains("fight"), "phase " + phase);
            assertEquals(on, names.contains("hunt"), "phase " + phase);
        }
    }

    @Test
    void seeTakesAnOptionalDirectionAndRejectsOthers() {
        assertEquals(new Action.See("forward"), v1.parse("see", json("{}")).action());
        assertEquals(new Action.See("forward"), v1.parse("see", json("{\"direction\":null}")).action());
        for (String d : List.of("forward", "north", "south", "east", "west", "up", "down")) {
            assertEquals(new Action.See(d), v1.parse("see", json("{\"direction\":\"" + d + "\"}")).action());
        }
        assertEquals(new Action.See("north"), v1.parse("see", json("{\"direction\":\" North \"}")).action());
        assertEquals("failed:bad_input:direction", error(v1, "see", "{\"direction\":\"left\"}"));
        assertEquals("failed:bad_input:direction", error(v1, "see", "{\"direction\":5}"));
        assertEquals("denied:unknown_tool", error(new ToolSchemas(1), "see", "{}"));
        for (JsonElement el : v1.definitions()) {
            JsonObject def = el.getAsJsonObject();
            if (def.get("name").getAsString().equals("see")) {
                assertFalse(def.getAsJsonObject("input_schema").getAsJsonArray("required").contains(new JsonPrimitive("direction")));
            }
        }
    }

    @Test
    void definitionsListOnlyEnabledToolsInAFixedOrder() {
        List<String> names = new ArrayList<>();
        for (JsonElement el : v1.definitions()) {
            JsonObject def = el.getAsJsonObject();
            assertEquals("object", def.getAsJsonObject("input_schema").get("type").getAsString());
            names.add(def.get("name").getAsString());
        }
        assertEquals(List.of("get_state", "look", "see", "move_to", "say", "wait", "stop", "flee", "note", "remember", "forget", "inspect_container",
                "store", "take", "eat", "follow_player", "mine_block", "place_block", "equip", "craft", "smelt",
                "gather", "dig", "till", "plant", "harvest", "use_bucket", "glance",
                "build", "build_house", "roam", "breed", "tame", "pet", "sleep", "go_home", "fish", "trade_offers", "trade", "sort_chests"), names);
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

    // skills (24 Oct)

    private static String blocks(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : ",").append("{\"x\":").append(i).append(",\"y\":64,\"z\":0,\"item\":\"cobblestone\"}");
        return sb.append("]").toString();
    }

    @Test
    void lookReachesRadiusSixtyFour() {
        assertEquals(new Action.Look(64), v1.parse("look", json("{\"radius\":64}")).action());
        assertEquals(new Action.Look(4), v1.parse("look", json("{\"radius\":4}")).action());
        assertEquals("failed:bad_input:radius", error(v1, "look", "{\"radius\":65}"));
        assertEquals("failed:bad_input:radius", error(v1, "look", "{\"radius\":3}"));
    }

    @Test
    void buildParsesABlockList() {
        Action.Build b = (Action.Build) v1.parse("build", json("{\"blocks\":[{\"x\":1,\"y\":64,\"z\":-2,\"item\":\"minecraft:Oak_Planks\"},{\"x\":2,\"y\":65,\"z\":-2,\"item\":\"dirt\"}]}")).action();
        assertEquals(List.of(new Action.BuildBlock(1, 64, -2, "oak_planks"), new Action.BuildBlock(2, 65, -2, "dirt")), b.blocks());
        assertEquals(1, ((Action.Build) v1.parse("build", json("{\"blocks\":" + blocks(1) + "}")).action()).blocks().size());
        assertEquals(64, ((Action.Build) v1.parse("build", json("{\"blocks\":" + blocks(64) + "}")).action()).blocks().size());
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":[]}"), "0 entries");
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":" + blocks(65) + "}"), "65 entries");
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{}"));
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":null}"));
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":\"oak\"}"));
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":{\"x\":1,\"y\":2,\"z\":3,\"item\":\"dirt\"}}"));
        // any bad entry
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":[{\"x\":1,\"y\":64,\"z\":0,\"item\":\"dirt\"},5]}"), "not an object");
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":[{\"x\":1,\"y\":64,\"z\":0}]}"), "no item");
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":[{\"x\":1,\"y\":64,\"item\":\"dirt\"}]}"), "no z");
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":[{\"x\":1.5,\"y\":64,\"z\":0,\"item\":\"dirt\"}]}"), "fractional x");
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":[{\"x\":1,\"y\":400,\"z\":0,\"item\":\"dirt\"}]}"), "y out of range");
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":[{\"x\":1,\"y\":-65,\"z\":0,\"item\":\"dirt\"}]}"), "y below range");
        assertEquals("failed:bad_input:blocks", error(v1, "build", "{\"blocks\":[{\"x\":1,\"y\":64,\"z\":0,\"item\":\"  \"}]}"), "blank item");
    }

    @Test
    void buildHouseParsesWithDefaults() {
        assertEquals(new Action.BuildHouse(10, 64, -5, "south", null), v1.parse("build_house", json("{\"x\":10,\"y\":64,\"z\":-5}")).action());
        assertEquals(new Action.BuildHouse(10, 64, -5, "south", null), v1.parse("build_house", json("{\"x\":10,\"y\":64,\"z\":-5,\"facing\":null,\"material\":null}")).action());
        for (String f : List.of("north", "south", "east", "west")) {
            assertEquals(new Action.BuildHouse(0, 70, 0, f, "oak_planks"),
                    v1.parse("build_house", json("{\"x\":0,\"y\":70,\"z\":0,\"facing\":\"" + f + "\",\"material\":\"oak_planks\"}")).action());
        }
        assertEquals(new Action.BuildHouse(0, 70, 0, "east", "cobblestone"),
                v1.parse("build_house", json("{\"x\":0,\"y\":70,\"z\":0,\"facing\":\" East \",\"material\":\"minecraft:Cobblestone\"}")).action());
        assertEquals("failed:bad_input:facing", error(v1, "build_house", "{\"x\":0,\"y\":70,\"z\":0,\"facing\":\"up\"}"));
        assertEquals("failed:bad_input:facing", error(v1, "build_house", "{\"x\":0,\"y\":70,\"z\":0,\"facing\":\"\"}"));
        assertEquals("failed:bad_input:material", error(v1, "build_house", "{\"x\":0,\"y\":70,\"z\":0,\"material\":\"  \"}"));
        assertEquals("failed:bad_input:x", error(v1, "build_house", "{\"y\":70,\"z\":0}"));
        assertEquals("failed:bad_input:y", error(v1, "build_house", "{\"x\":0,\"z\":0}"));
        assertEquals("failed:bad_input:z", error(v1, "build_house", "{\"x\":0,\"y\":70}"));
        assertEquals("failed:bad_input:y", error(v1, "build_house", "{\"x\":0,\"y\":321,\"z\":0}"));
        assertEquals("failed:bad_input:y", error(v1, "build_house", "{\"x\":0,\"y\":-65,\"z\":0}"));
    }

    @Test
    void roamParsesWithDefaultsAndRanges() {
        assertEquals(new Action.Roam(32, 2.0), v1.parse("roam", json("{}")).action());
        assertEquals(new Action.Roam(8, 0.5), v1.parse("roam", json("{\"radius\":8,\"minutes\":0.5}")).action());
        assertEquals(new Action.Roam(64, 5.0), v1.parse("roam", json("{\"radius\":64,\"minutes\":5}")).action());
        assertEquals(new Action.Roam(20, 2.0), v1.parse("roam", json("{\"radius\":20,\"minutes\":null}")).action());
        assertEquals("failed:bad_input:radius", error(v1, "roam", "{\"radius\":7}"));
        assertEquals("failed:bad_input:radius", error(v1, "roam", "{\"radius\":65}"));
        assertEquals("failed:bad_input:radius", error(v1, "roam", "{\"radius\":10.5}"));
        assertEquals("failed:bad_input:minutes", error(v1, "roam", "{\"minutes\":0.4}"));
        assertEquals("failed:bad_input:minutes", error(v1, "roam", "{\"minutes\":5.1}"));
        assertEquals("failed:bad_input:minutes", error(v1, "roam", "{\"minutes\":\"soon\"}"));
    }

    @Test
    void huntIsAnAttackPhaseTool() {
        ToolSchemas four = new ToolSchemas(Settings.ATTACK_PHASE);
        assertEquals("denied:unknown_tool", error(new ToolSchemas(3), "hunt", "{\"animal\":\"cow\"}"));
        assertEquals("denied:unknown_tool", error(v1, "hunt", "{\"animal\":\"cow\"}"));
        assertEquals(new Action.Hunt("cow", 1), four.parse("hunt", json("{\"animal\":\"cow\"}")).action());
        for (String a : List.of("cow", "pig", "sheep", "chicken", "rabbit")) {
            assertEquals(new Action.Hunt(a, 3), four.parse("hunt", json("{\"animal\":\"" + a + "\",\"count\":3}")).action());
        }
        assertEquals(new Action.Hunt("pig", 8), four.parse("hunt", json("{\"animal\":\"Pig\",\"count\":\"8\"}")).action());
        assertEquals(new Action.Hunt("pig", 1), four.parse("hunt", json("{\"animal\":\"pig\",\"count\":1}")).action());
        assertEquals("failed:bad_input:animal", error(four, "hunt", "{\"animal\":\"villager\"}"));
        assertEquals("failed:bad_input:animal", error(four, "hunt", "{\"animal\":\"wolf\"}"));
        assertEquals("failed:bad_input:animal", error(four, "hunt", "{}"));
        assertEquals("failed:bad_input:count", error(four, "hunt", "{\"animal\":\"cow\",\"count\":0}"));
        assertEquals("failed:bad_input:count", error(four, "hunt", "{\"animal\":\"cow\",\"count\":9}"));
    }

    @Test
    void breedParsesAnimalAndPairs() {
        assertEquals(new Action.Breed("cow", 1), v1.parse("breed", json("{\"animal\":\"cow\"}")).action());
        for (String a : List.of("cow", "pig", "sheep", "chicken", "rabbit")) {
            assertEquals(new Action.Breed(a, 2), v1.parse("breed", json("{\"animal\":\"" + a + "\",\"pairs\":2}")).action());
        }
        assertEquals(new Action.Breed("sheep", 4), v1.parse("breed", json("{\"animal\":\" Sheep \",\"pairs\":4}")).action());
        assertEquals("failed:bad_input:animal", error(v1, "breed", "{\"animal\":\"cat\"}"));
        assertEquals("failed:bad_input:animal", error(v1, "breed", "{\"pairs\":1}"));
        assertEquals("failed:bad_input:pairs", error(v1, "breed", "{\"animal\":\"cow\",\"pairs\":0}"));
        assertEquals("failed:bad_input:pairs", error(v1, "breed", "{\"animal\":\"cow\",\"pairs\":5}"));
    }

    @Test
    void tameParsesAnimalAndAnOptionalId() {
        for (String a : List.of("wolf", "cat", "parrot")) {
            assertEquals(new Action.Tame(a, null), v1.parse("tame", json("{\"animal\":\"" + a + "\"}")).action());
        }
        assertEquals(new Action.Tame("wolf", 77), v1.parse("tame", json("{\"animal\":\"wolf\",\"entity_id\":77}")).action());
        assertEquals(new Action.Tame("cat", null), v1.parse("tame", json("{\"animal\":\"cat\",\"entity_id\":null}")).action());
        assertEquals(new Action.Tame("cat", 0), v1.parse("tame", json("{\"animal\":\"cat\",\"entity_id\":0}")).action());
        assertEquals("failed:bad_input:animal", error(v1, "tame", "{\"animal\":\"cow\"}"));
        assertEquals("failed:bad_input:animal", error(v1, "tame", "{}"));
        assertEquals("failed:bad_input:entity_id", error(v1, "tame", "{\"animal\":\"wolf\",\"entity_id\":-1}"));
        assertEquals("failed:bad_input:entity_id", error(v1, "tame", "{\"animal\":\"wolf\",\"entity_id\":1.5}"));
    }

    @Test
    void petParsesIdAndCommand() {
        assertEquals(new Action.Pet(5, "sit"), v1.parse("pet", json("{\"entity_id\":5,\"command\":\"sit\"}")).action());
        assertEquals(new Action.Pet(5, "follow"), v1.parse("pet", json("{\"entity_id\":5,\"command\":\"Follow\"}")).action());
        assertEquals("failed:bad_input:command", error(v1, "pet", "{\"entity_id\":5,\"command\":\"stay\"}"));
        assertEquals("failed:bad_input:command", error(v1, "pet", "{\"entity_id\":5}"));
        assertEquals("failed:bad_input:entity_id", error(v1, "pet", "{\"command\":\"sit\"}"));
        assertEquals("failed:bad_input:entity_id", error(v1, "pet", "{\"entity_id\":-3,\"command\":\"sit\"}"));
    }

    @Test
    void sleepTakesNoArguments() {
        assertEquals(new Action.Sleep(), v1.parse("sleep", json("{}")).action());
        assertEquals("denied:unknown_tool", error(new ToolSchemas(1), "sleep", "{}"), "a phase 2 tool");
    }

    @Test
    void goHomeTakesAnOptionalStoreFlag() {
        assertEquals(new Action.GoHome(false), v1.parse("go_home", json("{}")).action());
        assertEquals(new Action.GoHome(false), v1.parse("go_home", json("{\"store\":false}")).action());
        assertEquals(new Action.GoHome(true), v1.parse("go_home", json("{\"store\":true}")).action());
        assertEquals(new Action.GoHome(false), v1.parse("go_home", json("{\"store\":null}")).action());
        assertEquals("failed:bad_input:store", error(v1, "go_home", "{\"store\":\"maybe\"}"));
        assertEquals("failed:bad_input:store", error(v1, "go_home", "{\"store\":3}"));
    }

    @Test
    void fishParsesWithDefaultsAndRanges() {
        assertEquals(new Action.Fish(4, 60), v1.parse("fish", json("{}")).action());
        assertEquals(new Action.Fish(1, 10), v1.parse("fish", json("{\"count\":1,\"seconds\":10}")).action());
        assertEquals(new Action.Fish(16, 120), v1.parse("fish", json("{\"count\":16,\"seconds\":120}")).action());
        assertEquals(new Action.Fish(2, 60), v1.parse("fish", json("{\"count\":2}")).action());
        assertEquals(new Action.Fish(4, 30), v1.parse("fish", json("{\"seconds\":30}")).action());
        assertEquals("failed:bad_input:count", error(v1, "fish", "{\"count\":0}"));
        assertEquals("failed:bad_input:count", error(v1, "fish", "{\"count\":17}"));
        assertEquals("failed:bad_input:seconds", error(v1, "fish", "{\"seconds\":9}"));
        assertEquals("failed:bad_input:seconds", error(v1, "fish", "{\"seconds\":121}"));
    }

    @Test
    void tradeOffersAndTradeParse() {
        assertEquals(new Action.TradeOffers(12), v1.parse("trade_offers", json("{\"entity_id\":12}")).action());
        assertEquals("failed:bad_input:entity_id", error(v1, "trade_offers", "{}"));
        assertEquals("failed:bad_input:entity_id", error(v1, "trade_offers", "{\"entity_id\":-1}"));
        assertEquals(new Action.Trade(12, 0, 1), v1.parse("trade", json("{\"entity_id\":12,\"offer\":0}")).action());
        assertEquals(new Action.Trade(12, 31, 16), v1.parse("trade", json("{\"entity_id\":12,\"offer\":31,\"times\":16}")).action());
        assertEquals(new Action.Trade(12, 3, 1), v1.parse("trade", json("{\"entity_id\":12,\"offer\":3,\"times\":1}")).action());
        assertEquals("failed:bad_input:offer", error(v1, "trade", "{\"entity_id\":12,\"offer\":-1}"));
        assertEquals("failed:bad_input:offer", error(v1, "trade", "{\"entity_id\":12,\"offer\":32}"));
        assertEquals("failed:bad_input:offer", error(v1, "trade", "{\"entity_id\":12}"));
        assertEquals("failed:bad_input:entity_id", error(v1, "trade", "{\"offer\":1}"));
        assertEquals("failed:bad_input:times", error(v1, "trade", "{\"entity_id\":12,\"offer\":1,\"times\":0}"));
        assertEquals("failed:bad_input:times", error(v1, "trade", "{\"entity_id\":12,\"offer\":1,\"times\":17}"));
    }

    @Test
    void tradeEmeraldLimitDefaultsTo32() {
        assertEquals(32, Settings.DEFAULTS.tradeMaxEmeralds());
        assertEquals(0, Settings.of(b -> b.tradeMaxEmeralds = 0).tradeMaxEmeralds());
    }

    @Test
    void sortChestsParsesWithADefaultRadius() {
        assertEquals(new Action.SortChests(6), v1.parse("sort_chests", json("{}")).action());
        assertEquals(new Action.SortChests(2), v1.parse("sort_chests", json("{\"radius\":2}")).action());
        assertEquals(new Action.SortChests(8), v1.parse("sort_chests", json("{\"radius\":8}")).action());
        assertEquals("failed:bad_input:radius", error(v1, "sort_chests", "{\"radius\":1}"));
        assertEquals("failed:bad_input:radius", error(v1, "sort_chests", "{\"radius\":9}"));
    }

    @Test
    void skillToolsCannotAskForPrioThree() {
        ToolSchemas four = new ToolSchemas(Settings.ATTACK_PHASE);
        List<String> inputs = List.of(
                "build|{\"blocks\":" + blocks(1) + ",\"prio\":3}", "build_house|{\"x\":0,\"y\":64,\"z\":0,\"prio\":3}", "roam|{\"prio\":3}",
                "hunt|{\"animal\":\"cow\",\"prio\":3}", "breed|{\"animal\":\"cow\",\"prio\":3}", "tame|{\"animal\":\"wolf\",\"prio\":3}",
                "pet|{\"entity_id\":1,\"command\":\"sit\",\"prio\":3}", "sleep|{\"prio\":3}", "go_home|{\"prio\":3}", "fish|{\"prio\":3}",
                "trade_offers|{\"entity_id\":1,\"prio\":3}", "trade|{\"entity_id\":1,\"offer\":0,\"prio\":3}", "sort_chests|{\"prio\":3}");
        for (String s : inputs) {
            String[] parts = s.split("\\|", 2);
            ToolSchemas.Parsed p = four.parse(parts[0], json(parts[1]));
            assertTrue(p.action() != null, s);
            assertEquals(2, p.prio(), s);
            assertTrue(p.prioLowered(), s);
        }
        assertFalse(ToolSchemas.SAFETY_TOOLS.contains("hunt"));
    }

    @Test
    void theBlocksPropertyIsAnArrayOfObjects() {
        for (JsonElement el : v1.definitions()) {
            JsonObject def = el.getAsJsonObject();
            if (!def.get("name").getAsString().equals("build")) continue;
            JsonObject schema = def.getAsJsonObject("input_schema");
            assertTrue(schema.getAsJsonArray("required").contains(new JsonPrimitive("blocks")));
            JsonObject blocks = schema.getAsJsonObject("properties").getAsJsonObject("blocks");
            assertEquals("array", blocks.get("type").getAsString());
            assertEquals(1, blocks.get("minItems").getAsInt());
            assertEquals(64, blocks.get("maxItems").getAsInt());
            JsonObject items = blocks.getAsJsonObject("items");
            assertEquals("object", items.get("type").getAsString());
            for (String k : List.of("x", "y", "z", "item")) {
                assertTrue(items.getAsJsonObject("properties").has(k), k);
                assertTrue(items.getAsJsonArray("required").contains(new JsonPrimitive(k)), k);
            }
            return;
        }
        throw new AssertionError("no build tool");
    }

    @Test
    void requiredFieldsOfTheSkillToolsAreDeclared() {
        java.util.Map<String, List<String>> required = java.util.Map.ofEntries(
                java.util.Map.entry("build", List.of("blocks")), java.util.Map.entry("build_house", List.of("x", "y", "z")),
                java.util.Map.entry("roam", List.<String>of()), java.util.Map.entry("hunt", List.of("animal")),
                java.util.Map.entry("breed", List.of("animal")), java.util.Map.entry("tame", List.of("animal")),
                java.util.Map.entry("pet", List.of("entity_id", "command")), java.util.Map.entry("sleep", List.<String>of()),
                java.util.Map.entry("go_home", List.<String>of()), java.util.Map.entry("fish", List.<String>of()),
                java.util.Map.entry("trade_offers", List.of("entity_id")), java.util.Map.entry("trade", List.of("entity_id", "offer")),
                java.util.Map.entry("sort_chests", List.<String>of()));
        int seen = 0;
        for (JsonElement el : new ToolSchemas(Settings.ATTACK_PHASE).definitions()) {
            JsonObject def = el.getAsJsonObject();
            List<String> want = required.get(def.get("name").getAsString());
            if (want == null) continue;
            seen++;
            List<String> got = new ArrayList<>();
            for (JsonElement r : def.getAsJsonObject("input_schema").getAsJsonArray("required")) got.add(r.getAsString());
            assertEquals(want, got, def.get("name").getAsString());
        }
        assertEquals(13, seen);
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
