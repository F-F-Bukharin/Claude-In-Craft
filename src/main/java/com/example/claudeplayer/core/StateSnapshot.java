package com.example.claudeplayer.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/**
 * What the model sees of the world. Built on the server thread, immutable afterwards, so the brain thread can
 * read it. Text that comes from the world (player names, custom mob names) must already be cleaned with
 * WorldText by the builder.
 */
public record StateSnapshot(
        boolean present, boolean dead,
        Vec pos, String dimension, String facing,
        float health, float maxHealth, int food,
        String mainHand, List<String> armor, List<Stack> inventory,
        List<Mob> hostiles, List<Seen> players, Map<String, Integer> blocks,
        long timeOfDay, String weather,
        int level, Vec home, String running, int waiting) {

    public record Vec(double x, double y, double z) {}

    public record Stack(String item, int count) {}

    public record Mob(int id, String type, String name, double distance, float health, Vec pos) {}

    public record Seen(String name, double distance, Vec pos) {}

    /** No body in the world: never spawned, despawned, or dead. */
    public static StateSnapshot absent(boolean dead, int level, Vec home) {
        return new StateSnapshot(false, dead, null, null, null, 0, 0, 0, null, List.of(), List.of(), List.of(),
                List.of(), Map.of(), 0, null, level, home, null, 0);
    }

    /** Compact JSON for the prompt; a busy scene stays under 2 KB. */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("level", level);
        if (home != null) o.add("home", vec(home));
        if (!present) {
            o.addProperty("body", dead ? "dead" : "absent");
            return o.toString();
        }
        o.add("pos", vec(pos));
        o.addProperty("dim", dimension);
        o.addProperty("facing", facing);
        o.addProperty("hp", round(health));
        o.addProperty("max_hp", round(maxHealth));
        o.addProperty("food", food);
        o.addProperty("hand", mainHand);
        if (!armor.isEmpty()) {
            JsonArray a = new JsonArray();
            armor.forEach(a::add);
            o.add("armor", a);
        }
        JsonArray inv = new JsonArray();
        for (Stack s : inventory) inv.add(s.item() + " x" + s.count());
        o.add("inventory", inv);
        JsonArray mobs = new JsonArray();
        for (Mob m : hostiles) {
            JsonObject j = new JsonObject();
            j.addProperty("id", m.id());
            j.addProperty("type", m.type());
            if (m.name() != null) j.addProperty("name", m.name());
            j.addProperty("dist", round(m.distance()));
            j.addProperty("hp", round(m.health()));
            j.add("pos", vec(m.pos()));
            mobs.add(j);
        }
        o.add("hostiles", mobs);
        JsonArray seen = new JsonArray();
        for (Seen p : players) {
            JsonObject j = new JsonObject();
            j.addProperty("name", p.name());
            j.addProperty("dist", round(p.distance()));
            j.add("pos", vec(p.pos()));
            seen.add(j);
        }
        o.add("players", seen);
        JsonObject near = new JsonObject();
        for (Map.Entry<String, Integer> e : blocks.entrySet()) near.addProperty(e.getKey(), e.getValue());
        o.add("blocks_near", near);
        o.addProperty("time", timeOfDay);
        o.addProperty("weather", weather);
        JsonObject queue = new JsonObject();
        queue.addProperty("running", running == null ? "none" : running);
        queue.addProperty("waiting", waiting);
        o.add("queue", queue);
        return o.toString();
    }

    private static JsonArray vec(Vec v) {
        JsonArray a = new JsonArray();
        a.add(round(v.x()));
        a.add(round(v.y()));
        a.add(round(v.z()));
        return a;
    }

    private static double round(double d) {
        return Math.round(d * 10) / 10.0;
    }
}
