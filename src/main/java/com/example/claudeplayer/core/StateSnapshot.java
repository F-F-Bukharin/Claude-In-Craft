package com.example.claudeplayer.core;

import com.example.claudeplayer.core.safety.WorldText;
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
        List<Mob> hostiles, List<Seen> players, List<Creature> creatures, Map<String, Integer> blocks,
        List<Poi> points, WorldMap.View map,
        long timeOfDay, String weather,
        int level, Vec home, String running, int waiting) {

    /** Without creatures: the parameter list from before they existed. */
    public StateSnapshot(boolean present, boolean dead, Vec pos, String dimension, String facing,
            float health, float maxHealth, int food, String mainHand, List<String> armor, List<Stack> inventory,
            List<Mob> hostiles, List<Seen> players, Map<String, Integer> blocks,
            List<Poi> points, WorldMap.View map, long timeOfDay, String weather,
            int level, Vec home, String running, int waiting) {
        this(present, dead, pos, dimension, facing, health, maxHealth, food, mainHand, armor, inventory,
                hostiles, players, List.of(), blocks, points, map, timeOfDay, weather, level, home, running, waiting);
    }

    public record Vec(double x, double y, double z) {}

    /**
     * A passive or neutral mob or a villager nearby (not a monster; see {@link Mob} for those). {@code name} is a
     * custom name already cleaned by the builder, null if it has none; {@code job} is a villager's profession,
     * null for anything else; {@code tamed} means tamed by the agent itself.
     */
    public record Creature(int id, String type, String name, double distance, boolean baby, boolean tamed, String job, Vec pos) {}

    public record Stack(String item, int count) {}

    public record Mob(int id, String type, String name, double distance, float health, Vec pos) {}

    /** A notable block (container, ender chest, crafting table, bed) with its block position. */
    public record Poi(String block, int x, int y, int z) {}

    public record Seen(String name, double distance, Vec pos) {}

    /** No body in the world: never spawned, despawned, or dead. */
    public static StateSnapshot absent(boolean dead, int level, Vec home) {
        return new StateSnapshot(false, dead, null, null, null, 0, 0, 0, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of(), List.of(), null, 0, null, level, home, null, 0);
    }

    /** Compact JSON for the prompt; StateSnapshotTest pins the size of a busy scene (map and creatures included). */
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
        JsonArray animals = new JsonArray();
        for (Creature c : creatures) {
            JsonObject j = new JsonObject();
            j.addProperty("id", c.id());
            j.addProperty("type", c.type());
            String name = c.name() == null ? "" : WorldText.clean(c.name(), WorldText.NAME_MAX);
            if (!name.isEmpty()) j.addProperty("name", name);
            j.addProperty("dist", round(c.distance()));
            j.addProperty("baby", c.baby());
            j.addProperty("tamed", c.tamed());
            if (c.job() != null) j.addProperty("job", WorldText.clean(c.job(), WorldText.NAME_MAX));
            JsonArray at = new JsonArray();
            at.add((int) Math.floor(c.pos().x()));
            at.add((int) Math.floor(c.pos().y()));
            at.add((int) Math.floor(c.pos().z()));
            j.add("pos", at);
            animals.add(j);
        }
        o.add("creatures", animals);
        JsonObject near = new JsonObject();
        for (Map.Entry<String, Integer> e : blocks.entrySet()) near.addProperty(e.getKey(), e.getValue());
        o.add("blocks_near", near);
        JsonArray pts = new JsonArray();
        for (Poi p : points) {
            JsonObject j = new JsonObject();
            j.addProperty("block", p.block());
            JsonArray at = new JsonArray();
            at.add(p.x());
            at.add(p.y());
            at.add(p.z());
            j.add("pos", at);
            pts.add(j);
        }
        o.add("points", pts);
        if (map != null) {
            JsonArray origin = new JsonArray();
            origin.add(map.originX());
            origin.add(map.originZ());
            o.add("map_origin", origin);
            JsonArray rows = new JsonArray();
            map.rows().forEach(rows::add);
            o.add("map", rows);
        }
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
