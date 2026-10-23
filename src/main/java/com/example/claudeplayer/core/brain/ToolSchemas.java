package com.example.claudeplayer.core.brain;

import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.work.DigPlan;
import com.example.claudeplayer.core.work.GatherTargets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The tools the model can call: each entry keeps its JSON schema and its parser side by side. Tools from later
 * plan phases stay hidden until tool_phase reaches them. There is deliberately no tool that changes the caution
 * level, the allowlist or the config, or that runs commands.
 */
public final class ToolSchemas {
    /** Tools allowed to ask for priority 3; anywhere else a 3 is lowered to 2. */
    public static final Set<String> SAFETY_TOOLS = Set.of("flee", "stop", "attack_entity");

    private static final String PRIO = "Priority 1 to 3, default 1. 1: routine, dropped if danger interrupts. "
            + "2: important, paused and resumed after danger. 3: only flee, stop and attack_entity; runs before everything else.";

    /** Either an action to queue (with its priority), or the result to send back without running anything. */
    public record Parsed(Action action, ActionResult error, int prio, boolean prioLowered) {}

    @FunctionalInterface
    private interface Parser {
        Action parse(Input in);
    }

    private record Tool(String name, int phase, String description, JsonObject schema, Parser parser) {}

    private record Prop(String name, JsonObject schema) {}

    private final int phase;
    private final List<Tool> tools = new ArrayList<>();
    private final JsonArray definitions = new JsonArray();

    public ToolSchemas(int phase) {
        this.phase = phase;
        // v1
        add("get_state", 1, "Get a fresh world snapshot. Every message already ends with one; use this mid-batch to re-check.",
                in -> new Action.GetState());
        add("look", 1, "See farther: the same top-down map as world_state.map, with a radius of 4 to 24 blocks instead of 8. A radius of 24 is about 900 tokens, so ask for what you need.",
                in -> new Action.Look(in.integer("radius", 4, 24)),
                integer("radius", "Blocks from you to the map's edge, 4 to 24."));
        add("move_to", 1, "Walk to a block position. Holds the queue until you arrive, there is no path (failed:no_path) or the action times out (failed:timeout).",
                in -> new Action.MoveTo(in.number("x", -3e7, 3e7), in.number("y", -64, 320), in.number("z", -3e7, 3e7)),
                number("x", "Target x."), number("y", "Target y, at your feet."), number("z", "Target z."));
        add("say", 1, "Say something in chat as yourself: 15 words at most, only when a player needs to know.",
                in -> new Action.Say(in.text("text", 200)),
                text("text", "What to say. Chat shows two lines of 100 characters at most; the rest is cut."));
        add("wait", 1, "Do nothing for 0.1 to 10 seconds. Holds the queue.",
                in -> new Action.Wait(in.number("seconds", 0.1, 10)),
                number("seconds", "How long, 0.1 to 10."));
        add("stop", 1, "Stop moving and cancel every action queued after this one.",
                in -> new Action.Stop());
        add("flee", 1, "Run from the hostiles around you to a spot well away from all of them. Holds the queue until you get there; failed:no_escape if there is no way out, ok with no_threats if nothing is near.",
                in -> new Action.Flee());
        // memory
        add("note", 1, "Short-term memory: jot down an exact, recent detail (counts, positions). Replaces the note with the same subject; notes fade after a few minutes.",
                in -> new Action.Note(in.text("subject", 48), in.text("text", 240)),
                text("subject", "What it's about, reused to update it, e.g. chest@10,64,5."), text("text", "The detail, at most 240 characters."));
        add("remember", 1, "Long-term memory: keep a durable fact across sessions (what is stored where, landmarks, players' preferences). Replaces the memory with the same subject.",
                in -> new Action.Remember(in.text("subject", 48), in.text("text", 240)),
                text("subject", "What it's about, reused to update it, e.g. chest@10,64,5."), text("text", "The fact, at most 240 characters."));
        add("forget", 1, "Delete the short- and long-term memory with this subject, for things that are no longer true.",
                in -> new Action.Forget(in.text("subject", 48)),
                text("subject", "The subject to forget."));
        add("inspect_container", 2, "Look inside a chest, barrel or other container within 6 blocks. Returns its contents and notes them in short-term memory.",
                in -> new Action.InspectContainer(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000)),
                integer("x", "Block x."), integer("y", "Block y."), integer("z", "Block z."));
        add("store", 2, "Put items from your inventory into a chest, barrel, furnace or other container within 6 blocks. item all stores everything except your held item, armor and food. Moves what fits and says so; the container's new contents go to short-term memory.",
                in -> { String item = item(in); return new Action.Store(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000), item, count(in, item)); },
                integer("x", "Block x."), integer("y", "Block y."), integer("z", "Block z."), text("item", "Item id, for example oak_log, or all."), integer("count", "How many, 1 to 2304. Ignored for all."));
        add("take", 2, "Take items from a chest, furnace (its output too) or other container within 6 blocks into your inventory. item all takes everything that fits. The container's new contents go to short-term memory.",
                in -> { String item = item(in); return new Action.Take(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000), item, count(in, item)); },
                integer("x", "Block x."), integer("y", "Block y."), integer("z", "Block z."), text("item", "Item id, for example iron_ingot, or all."), integer("count", "How many, 1 to 2304. Ignored for all."));
        add("eat", 2, "Eat the best food you carry; takes about 1.6 s. failed:not_hungry when food is full, failed:no_food when you have none. You also eat on your own when hungry and nothing is near.",
                in -> new Action.Eat(false));
        add("follow_player", 2, "Follow a player, staying within about 3 blocks, for 1 to 60 seconds. Holds the queue.",
                in -> new Action.FollowPlayer(in.text("name", 16), in.number("seconds", 1, 60)),
                text("name", "The player's name, as in world_state.players."), number("seconds", "How long, 1 to 60."));
        // v2
        add("attack_entity", 4, "Attack one entity by its id from world_state.hostiles. Your caution level decides whether that's allowed; a denial says why.",
                in -> new Action.AttackEntity(in.integer("entity_id", 0, Integer.MAX_VALUE)),
                integer("entity_id", "The entity's id."));
        add("mine_block", 2, "Dig out the block at a position within 4.5 blocks of your eyes, with the best tool you carry, then pick up what drops. Holds the queue while digging. Natural blocks (logs, leaves, stone, dirt, sand, gravel, ores, plants) can be mined anywhere; anything else only if you placed it. Fails with out_of_reach (walk closer first), no_block, too_slow (needs a better tool) or block_changed; denied:protected_block or denied:unbreakable.",
                in -> new Action.MineBlock(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000)),
                integer("x", "Block x."), integer("y", "Block y."), integer("z", "Block z."));
        add("place_block", 2, "Place a block from your inventory at a position within 4.5 blocks of your eyes, against a solid neighbour. Fails with missing_item, not_a_block, out_of_reach, occupied, no_support or blocked (something stands there).",
                in -> new Action.PlaceBlock(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000), in.text("item", 64)),
                integer("x", "Block x."), integer("y", "Block y."), integer("z", "Block z."), text("item", "Item id as in your inventory, for example cobblestone."));
        add("equip", 2, "Hold an item from your inventory in your main hand; armor goes on instead. Nothing is dropped: whatever was there swaps into its slot. Fails with missing_item.",
                in -> new Action.Equip(in.text("item", 64)),
                text("item", "Item id as in your inventory, for example stone_sword or iron_helmet."));
        add("craft", 2, "Craft an item from your inventory, 0.25 s per craft. 2x2 recipes work anywhere; bigger ones need a crafting table within 4.5 blocks (failed:needs_crafting_table). failed:missing lists what you lack.",
                in -> new Action.Craft(in.text("item", 64), in.integer("count", 1, 64)),
                text("item", "Item id to make, for example oak_planks or minecraft:stick."),
                integer("count", "How many items you want, 1 to 64. Crafts as often as needed, so 4 oak_planks is one craft."));
        add("smelt", 2, "Load a furnace, smoker or blast furnace within 6 blocks with an item from your inventory and enough fuel (coal or charcoal first, then planks, then logs). Returns at once with when it will be ready and notes it in short-term memory; collect the output later with take.",
                in -> new Action.Smelt(in.text("item", 64), in.integer("count", 1, 64)),
                text("item", "Item id to smelt or cook, for example raw_iron, oak_log or beef."), integer("count", "How many, 1 to 64."));
        add("gather", 2, "Collect blocks on your own in one call: walks to the nearest reachable matching block within 24 blocks, mines it with your best tool, picks up the drops and repeats until you have count more of what they drop (stone gives cobblestone, coal_ore gives coal). Prefer it to many move_to and mine_block calls. Takes only natural blocks with an open face, nothing beside water or lava, nothing deeper than 4 below you, and nothing out of reach from the ground (no pillaring). Gives up after 120 s. ok says what was gathered and why it stopped: done, none_reachable, needs_tool (nothing you carry harvests it), inventory_full, timeout, hazard_lava, hazard_fire or hazard_drop. failed:danger (a hostile within 6 blocks or health under 40%) also says what was gathered; failed:unknown_target.",
                in -> new Action.Gather(GatherTargets.normalize(in.text("target", 64)), in.integer("count", 1, 256)),
                text("target", "A block id (oak_log, iron_ore) or a group: log (any tree), stone (stone, deepslate, andesite, diorite, granite, tuff), coal, iron, copper, gold, diamond, redstone, lapis, emerald (each its ore and deepslate ore), sand, dirt, gravel, clay."),
                integer("count", "How many dropped items you want, 1 to 256."));
        add("dig", 2, "Dig a tunnel 1 wide and 2 high from where you stand, walking into each step. north, south, east or west keep your level and never dig the floor; down digs stairs one block lower per step toward the way you face, so you can walk back up. Use it to get underground to ores instead of many mine_block calls. Ores on the tunnel walls are listed (at most 5) so you can gather or mine_block them. Stops at y=-60. ok: dug N/length steps (stopped: y_floor or timeout after 5 minutes). Fails, saying how far it got, with protected_block, unbreakable, hazard_lava or hazard_water (a fluid next to the block about to open), hazard_drop (a fall of 3 or more ahead), too_slow (needs a better tool), stuck, or blocked (falling sand or gravel keeps refilling it).",
                in -> new Action.Dig(direction(in), in.integer("length", 1, 32)),
                text("direction", "north, south, east, west or down."), integer("length", "Steps to dig, 1 to 32."));
        // farming
        add("till", 2, "Turn dirt, grass, coarse dirt, rooted dirt or a path block within 4.5 blocks of your eyes into farmland, with a hoe from your inventory (it holds the hoe for you). Fails with no_hoe, not_tillable, too_far or blocked (the block above isn't air).",
                in -> new Action.Till(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000)),
                integer("x", "Soil block x."), integer("y", "Soil block y."), integer("z", "Soil block z."));
        add("plant", 2, "Plant one item from your inventory on the soil block at x, y, z (within 4.5 blocks); it grows in the block above. wheat_seeds, carrot, potato and beetroot_seeds need farmland (till first), saplings dirt or grass, nether_wart soul sand. Fails with no_item, wrong_soil, occupied or too_far.",
                in -> new Action.Plant(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000), item(in)),
                integer("x", "Soil block x."), integer("y", "Soil block y."), integer("z", "Soil block z."), text("item", "Item id to plant, for example wheat_seeds or oak_sapling."));
        add("harvest", 2, "Harvest a field in one call: walks to every ripe wheat, carrot, potato, beetroot or nether wart within radius of the point, breaks it, picks up the drops and replants the spot with a matching seed you carry. Growing crops are left alone. Gives up after 120 s. ok: what was harvested, replanted and picked up, and why it stopped: done, none_ripe, inventory_full or timeout. failed:danger (a hostile within 6 blocks or health under 40%) also says what was done.",
                in -> new Action.Harvest(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000), in.integer("radius", 1, 8)),
                integer("x", "Field centre x."), integer("y", "Field y, at the crops."), integer("z", "Field centre z."), integer("radius", "Blocks from the point, 1 to 8."));
        add("use_bucket", 2, "Use a bucket on a block within 4.5 blocks of your eyes. With a water or lava bucket: pour it there and keep the empty bucket. With an empty bucket: fill it from the water or lava source there. Lava pours only within 32 blocks of home (denied:outside_work_area) and never within 4 blocks of another player (failed:lava_near_player). Fails with no_bucket, no_source, too_far, own_block (where you stand) or not_poured.",
                in -> new Action.UseBucket(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000)),
                integer("x", "Block x."), integer("y", "Block y."), integer("z", "Block z."));
        // vision
        add("glance", 2, "Take a low-res picture of what you see right now. For important moments: a rare or valuable target, or a player asking you to look. It has a cooldown.",
                in -> new Action.Glance());
    }

    /** The enabled tools in a fixed order; a stable list keeps the prompt cache valid. */
    public JsonArray definitions() {
        return definitions.deepCopy();
    }

    public Parsed parse(String name, JsonObject input) {
        for (Tool tool : tools) {
            if (!tool.name().equals(name)) continue;
            if (tool.phase() > phase) break;
            try {
                Input in = new Input(input == null ? new JsonObject() : input);
                Action action = tool.parser().parse(in);
                int prio = in.has("prio") ? in.integer("prio", 1, 3) : 1;
                boolean lowered = prio == 3 && !SAFETY_TOOLS.contains(name);
                return new Parsed(action, null, lowered ? 2 : prio, lowered);
            } catch (BadInput e) {
                return new Parsed(null, ActionResult.failed("bad_input:" + e.getMessage()), 1, false);
            }
        }
        return new Parsed(null, ActionResult.denied("unknown_tool"), 1, false);
    }

    private void add(String name, int toolPhase, String description, Parser parser, Prop... props) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (Prop p : props) {
            properties.add(p.name(), p.schema());
            required.add(p.name());
        }
        properties.add("prio", integer("prio", PRIO).schema());
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        tools.add(new Tool(name, toolPhase, description, schema, parser));
        if (toolPhase > phase) return;
        JsonObject def = new JsonObject();
        def.addProperty("name", name);
        def.addProperty("description", description);
        def.add("input_schema", schema);
        definitions.add(def);
    }

    /** An item id for store, take and plant, lower case and without the minecraft: prefix (all only means something to store and take). */
    private static String item(Input in) {
        String id = in.text("item", 64).toLowerCase(Locale.ROOT);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** One of DigPlan.DIRECTIONS. */
    private static String direction(Input in) {
        String d = in.text("direction", 8).toLowerCase(Locale.ROOT);
        if (!DigPlan.DIRECTIONS.contains(d)) throw new BadInput("direction");
        return d;
    }

    /** 1 to 2304 (a full inventory of 64-stacks); not read for all. */
    private static int count(Input in, String item) {
        return Action.ALL.equals(item) ? 0 : in.integer("count", 1, 2304);
    }

    private static Prop number(String name, String description) {
        return prop(name, "number", description);
    }

    private static Prop integer(String name, String description) {
        return prop(name, "integer", description);
    }

    private static Prop text(String name, String description) {
        return prop(name, "string", description);
    }

    private static Prop prop(String name, String type, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        schema.addProperty("description", description);
        return new Prop(name, schema);
    }

    /** Validates one tool_use input. Numbers may arrive as JSON numbers or numeric strings. */
    private record Input(JsonObject json) {
        /** A key set to null counts as absent, so {@code "prio": null} means the default. */
        boolean has(String key) {
            return json.has(key) && !json.get(key).isJsonNull();
        }

        double number(String key, double min, double max) {
            double v = numeric(key);
            if (!Double.isFinite(v) || v < min || v > max) throw new BadInput(key);
            return v;
        }

        int integer(String key, int min, int max) {
            double v = numeric(key);
            if (v != Math.rint(v) || v < min || v > max) throw new BadInput(key);
            return (int) v;
        }

        String text(String key, int maxLen) {
            JsonElement e = json.get(key);
            if (e == null || !e.isJsonPrimitive()) throw new BadInput(key);
            String s = e.getAsString().strip();
            if (s.isEmpty()) throw new BadInput(key);
            return s.length() > maxLen ? s.substring(0, maxLen) : s;
        }

        private double numeric(String key) {
            JsonElement e = json.get(key);
            if (e == null || !e.isJsonPrimitive()) throw new BadInput(key);
            try {
                return e.getAsDouble();
            } catch (NumberFormatException ex) {
                throw new BadInput(key);
            }
        }
    }

    private static final class BadInput extends RuntimeException {
        BadInput(String field) {
            super(field, null, false, false);
        }
    }
}
