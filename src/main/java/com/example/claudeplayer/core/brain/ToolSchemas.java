package com.example.claudeplayer.core.brain;

import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The tools the model can call: each entry keeps its JSON schema and its parser side by side. Tools from later
 * plan phases stay hidden until tool_phase reaches them. There is deliberately no tool that changes the caution
 * level, the allowlist or the config, or that runs commands.
 */
public final class ToolSchemas {
    /** Either an action to queue, or the result to send back without running anything. */
    public record Parsed(Action action, ActionResult error) {}

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
        add("move_to", 1, "Walk to a block position. Holds the queue until you arrive, there is no path (failed:no_path) or the action times out (failed:timeout).",
                in -> new Action.MoveTo(in.number("x", -3e7, 3e7), in.number("y", -64, 320), in.number("z", -3e7, 3e7)),
                number("x", "Target x."), number("y", "Target y, at your feet."), number("z", "Target z."));
        add("say", 1, "Say something in chat as yourself. Keep it short.",
                in -> new Action.Say(in.text("text", 200)),
                text("text", "What to say. Longer than 200 characters gets cut."));
        add("wait", 1, "Do nothing for 0.1 to 10 seconds. Holds the queue.",
                in -> new Action.Wait(in.number("seconds", 0.1, 10)),
                number("seconds", "How long, 0.1 to 10."));
        add("stop", 1, "Stop moving and cancel every action queued after this one.",
                in -> new Action.Stop());
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
        // v2
        add("follow_player", 3, "Follow a player, staying within about 3 blocks, for 1 to 60 seconds. Holds the queue.",
                in -> new Action.FollowPlayer(in.text("name", 16), in.number("seconds", 1, 60)),
                text("name", "The player's name, as in world_state.players."), number("seconds", "How long, 1 to 60."));
        add("attack_entity", 4, "Attack one entity by its id from world_state.hostiles. Your caution level decides whether that's allowed; a denial says why.",
                in -> new Action.AttackEntity(in.integer("entity_id", 0, Integer.MAX_VALUE)),
                integer("entity_id", "The entity's id."));
        add("mine_block", 6, "Break the block at a position. Only inside your work area around home.",
                in -> new Action.MineBlock(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000)),
                integer("x", "Block x."), integer("y", "Block y."), integer("z", "Block z."));
        add("place_block", 6, "Place a block from your inventory at a position. Only inside your work area around home.",
                in -> new Action.PlaceBlock(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000), in.text("item", 64)),
                integer("x", "Block x."), integer("y", "Block y."), integer("z", "Block z."), text("item", "Item id, for example minecraft:cobblestone."));
        add("equip", 6, "Move an item from your inventory to your main hand.",
                in -> new Action.Equip(in.text("item", 64)),
                text("item", "Item id, for example minecraft:stone_sword."));
        add("craft", 6, "Craft an item from what's in your inventory.",
                in -> new Action.Craft(in.text("item", 64), in.integer("count", 1, 64)),
                text("item", "Item id to make."), integer("count", "How many, 1 to 64."));
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
                return new Parsed(tool.parser().parse(new Input(input == null ? new JsonObject() : input)), null);
            } catch (BadInput e) {
                return new Parsed(null, ActionResult.failed("bad_input:" + e.getMessage()));
            }
        }
        return new Parsed(null, ActionResult.denied("unknown_tool"));
    }

    private void add(String name, int toolPhase, String description, Parser parser, Prop... props) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (Prop p : props) {
            properties.add(p.name(), p.schema());
            required.add(p.name());
        }
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
