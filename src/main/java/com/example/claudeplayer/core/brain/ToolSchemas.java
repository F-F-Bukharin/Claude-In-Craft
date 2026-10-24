package com.example.claudeplayer.core.brain;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.Sight;
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
    public static final Set<String> SAFETY_TOOLS = Set.of("flee", "stop", "attack_entity", "fight");

    private static final String PRIO = "Priority 1 to 3, default 1. 1: routine, dropped if danger interrupts. "
            + "2: important, paused and resumed after danger. 3: only flee, stop, attack_entity and fight; runs before everything else.";

    /** Either an action to queue (with its priority), or the result to send back without running anything. */
    public record Parsed(Action action, ActionResult error, int prio, boolean prioLowered) {}

    @FunctionalInterface
    private interface Parser {
        Action parse(Input in);
    }

    private record Tool(String name, int phase, String description, JsonObject schema, Parser parser) {}

    private record Prop(String name, JsonObject schema, boolean required) {
        Prop(String name, JsonObject schema) {
            this(name, schema, true);
        }
    }

    private final int phase;
    private final List<Tool> tools = new ArrayList<>();
    private final JsonArray definitions = new JsonArray();

    public ToolSchemas(int phase) {
        this.phase = phase;
        // v1
        add("get_state", 1, "Get a fresh world snapshot. Every message already ends with one; use this mid-batch to re-check.",
                in -> new Action.GetState());
        add("look", 1, "See farther: the same top-down map as world_state.map, with a radius of 4 to 64 blocks instead of 8. Up to radius 24 each cell is one block and a radius of 24 is about 900 tokens; past that the view stays at most 49 cells across and each cell covers several blocks (the map's first line says the scale). Ask for what you need.",
                in -> new Action.Look(in.integer("radius", 4, 64)),
                integer("radius", "Blocks from you to the map's edge, 4 to 64."));
        add("see", 2, "See what is in your line of sight, as text, for when the map doesn't show it (the map can't see through walls): the blocks in front of you grouped by id with a count and the nearest one's position and distance, ores, containers and ripe crops listed first with exact positions, water and lava, and the living things in view with ids. A fan of rays 70 degrees wide and 40 tall, up to 32 blocks, under 1 KB. With a direction other than forward you turn to look that way first (up and down tilt your head).",
                in -> new Action.See(seeDirection(in)),
                optional(text("direction", "forward (default, the way you face), north, south, east, west, up or down.")));
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
        add("attack_entity", Settings.ATTACK_PHASE, "Attack one entity by its id from world_state.hostiles. Your caution level decides whether that's allowed; a denial says why.",
                in -> new Action.AttackEntity(in.integer("entity_id", 0, Integer.MAX_VALUE)),
                integer("entity_id", "The entity's id."));
        // combat
        add("fight", Settings.ATTACK_PHASE, "Fight one hostile by its id until it's dead, you must stop, or it's out of your caution level's range. Gears up first. Reports once at the end.",
                in -> new Action.Fight(in.integer("entity_id", 0, Integer.MAX_VALUE)),
                integer("entity_id", "The hostile's id from world_state.hostiles."));
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
        add("use_bucket", 2, "Use a bucket on a block within 4.5 blocks of your eyes. With a water or lava bucket: pour it there and keep the empty bucket. With an empty bucket: fill it from the water or lava source there. With fluid (water or lava), pour or fill only that kind; without it, water first. Lava pours only within 32 blocks of home (denied:outside_work_area) and never within 4 blocks of another player (denied:lava_near_player). Fails with no_bucket, no_source, too_far, own_block (where you stand) or not_poured.",
                in -> new Action.UseBucket(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000), fluid(in)),
                integer("x", "Block x."), integer("y", "Block y."), integer("z", "Block z."),
                optional(text("fluid", "water or lava: which bucket to pour. Leave it out for any full bucket.")));
        // vision
        add("glance", 2, "Take a low-res picture of what you see right now. For important moments: a rare or valuable target, or a player asking you to look. It has a cooldown.",
                in -> new Action.Glance());
        // skills (24 Oct)
        add("build", 2, "Place up to 64 blocks in the order given: walks within reach of each block, places it, and skips blocks that are already there. List a block's support before the blocks that lean on it. ok reports how many were placed and skipped. Fails with missing_item:<id> (you don't carry it), no_support, blocked, no_path, or stopped_at:<index>:<reason>, which also says how many were placed.",
                in -> new Action.Build(blocks(in)),
                blockList("blocks", "1 to 64 blocks, placed in this order. Each is an object with x, y, z (the block's position) and item (the id to place, for example cobblestone)."));
        add("build_house", 2, "Build a small house in one call: 5x5 outside, 3x3 inside, walls 3 high, a flat roof, and a door in the middle of the facing wall. If you carry a bed it goes inside and becomes your home. It needs 71 blocks of the material and a wooden door in your inventory (it doesn't craft). x, y, z is the house's north-west outer corner at floor level, so the walls stand on the blocks at y-1. Fails with missing_blocks (says how many are needed and carried), missing_door, site_blocked (the first blocking position), uneven_ground or no_path.",
                in -> new Action.BuildHouse(in.integer("x", -30_000_000, 30_000_000), in.integer("y", -64, 320), in.integer("z", -30_000_000, 30_000_000), facing(in), material(in)),
                integer("x", "North-west outer corner x."), integer("y", "Floor level y; the walls stand on the blocks at y-1."), integer("z", "North-west outer corner z."),
                optional(text("facing", "The door's wall: north, south, east or west. Default south.")),
                optional(text("material", "Block id for the walls and roof, for example oak_planks. Leave it out for the building block you carry most of.")));
        add("roam", 2, "Explore on your own: wanders in directions you haven't seen, notes trees, ores, water, villages and animals as short-term notes with positions, comes back and reports once. Danger scraps it (prio 1). ok lists what it found; failed:no_path if there is nowhere to go.",
                in -> new Action.Roam(in.has("radius") ? in.integer("radius", 8, 64) : 32, in.has("minutes") ? in.number("minutes", 0.5, 5) : 2.0),
                optional(integer("radius", "Blocks from where you start, 8 to 64. Default 32.")),
                optional(number("minutes", "How long to wander, 0.5 to 5. Default 2.")));
        add("hunt", Settings.ATTACK_PHASE, "Hunt passive animals for food: fights the nearest animal of that kind and picks up the drops. Needs attacks on. Never touches players, villagers, named, tamed, leashed or baby animals. ok reports the kills and the drops; failed:no_animals or failed:timeout.",
                in -> new Action.Hunt(animal(in, FARM_ANIMALS), in.has("count") ? in.integer("count", 1, 8) : 1),
                text("animal", "cow, pig, sheep, chicken or rabbit."),
                optional(integer("count", "How many to kill, 1 to 8. Default 1.")));
        add("breed", 2, "Breed farm animals: feeds two adults of one kind the right food (cow and sheep: wheat; pig: carrot, potato or beetroot; chicken: any seeds; rabbit: carrot or dandelion). Fails with missing_food, no_pair (fewer than two adults near) or not_ready (cooldown, or babies).",
                in -> new Action.Breed(animal(in, FARM_ANIMALS), in.has("pairs") ? in.integer("pairs", 1, 4) : 1),
                text("animal", "cow, pig, sheep, chicken or rabbit."),
                optional(integer("pairs", "How many pairs to breed, 1 to 4. Default 1.")));
        add("tame", 2, "Tame a wolf (with bones), a cat (with raw cod or raw salmon) or a parrot (with seeds): keeps feeding the animal until it is tamed. Fails with missing_item, no_animal or not_tamed (gave up).",
                in -> new Action.Tame(animal(in, PETS), in.has("entity_id") ? in.integer("entity_id", 0, Integer.MAX_VALUE) : null),
                text("animal", "wolf, cat or parrot."),
                optional(integer("entity_id", "The animal's id from world_state. Leave it out for the nearest one.")));
        add("pet", 2, "Tell a pet you tamed to sit or follow you. Fails with not_mine (you didn't tame it) or no_target.",
                in -> new Action.Pet(in.integer("entity_id", 0, Integer.MAX_VALUE), petCommand(in)),
                integer("entity_id", "The pet's id."), text("command", "sit or follow."));
        add("sleep", 2, "Sleep through the night: at night or in a thunderstorm, walks to the nearest bed within 16 blocks, or your home bed, and lies down. ok once you are asleep. Fails with not_night, no_bed, monsters_nearby, occupied or no_path.",
                in -> new Action.Sleep());
        add("go_home", 2, "Walk home. With store true, you then put your loot into the containers within 6 blocks of home, keeping tools, weapons, armor, food, torches and one stack of building blocks. Fails with no_home, no_path or no_container.",
                in -> new Action.GoHome(in.has("store") && in.bool("store")),
                optional(bool("store", "true to also store your loot in the containers near home. Default false.")));
        add("fish", 2, "Fish: needs a fishing rod in your inventory and water within 8 blocks; casts and reels in until count catches or the time is up. ok reports the catch; failed:missing_rod or failed:no_water.",
                in -> new Action.Fish(in.has("count") ? in.integer("count", 1, 16) : 4, in.has("seconds") ? in.integer("seconds", 10, 120) : 60),
                optional(integer("count", "Catches to stop at, 1 to 16. Default 4.")),
                optional(integer("seconds", "Longest time to fish, 10 to 120. Default 60.")));
        add("trade_offers", 2, "List a villager's trade offers (the villager must be within 6 blocks): each one's index, what it costs, what it gives and the uses left. Fails with not_a_villager, too_far or no_offers.",
                in -> new Action.TradeOffers(in.integer("entity_id", 0, Integer.MAX_VALUE)),
                integer("entity_id", "The villager's id from world_state."));
        add("trade", 2, "Buy an offer from a villager (within 6 blocks), up to times times, never spending more emeralds than the server's trade_max_emeralds in one call. Look at trade_offers first for the offer number. Fails with not_a_villager, too_far, out_of_stock, missing_payment or over_limit.",
                in -> new Action.Trade(in.integer("entity_id", 0, Integer.MAX_VALUE), in.integer("offer", 0, 31), in.has("times") ? in.integer("times", 1, 16) : 1),
                integer("entity_id", "The villager's id."), integer("offer", "The offer's index from trade_offers, 0 to 31."),
                optional(integer("times", "How many times to buy it, 1 to 16. Default 1.")));
        add("sort_chests", 2, "Tidy up storage: sorts the items among the containers within radius of you (ender chests excluded) by kind: blocks, ores and ingots, food, tools and weapons, and the rest. Notes what each container now holds. Fails with no_chests (needs 2 or more).",
                in -> new Action.SortChests(in.has("radius") ? in.integer("radius", 2, 8) : 6),
                optional(integer("radius", "Blocks around you to look for containers, 2 to 8. Default 6.")));
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
            if (p.required()) required.add(p.name());
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
        return item(in, "item");
    }

    private static String item(Input in, String key) {
        String id = in.text(key, 64).toLowerCase(Locale.ROOT);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static final Set<String> FARM_ANIMALS = Set.of("cow", "pig", "sheep", "chicken", "rabbit");
    private static final Set<String> PETS = Set.of("wolf", "cat", "parrot");
    private static final Set<String> FACINGS = Set.of("north", "south", "east", "west");
    private static final int MAX_BUILD_BLOCKS = 64;

    /** One of {@code allowed}, lower case, from the "animal" field. */
    private static String animal(Input in, Set<String> allowed) {
        String a = in.text("animal", 16).toLowerCase(Locale.ROOT);
        if (!allowed.contains(a)) throw new BadInput("animal");
        return a;
    }

    /** sit or follow. */
    private static String petCommand(Input in) {
        String c = in.text("command", 8).toLowerCase(Locale.ROOT);
        if (!Set.of("sit", "follow").contains(c)) throw new BadInput("command");
        return c;
    }

    /** The door's wall for build_house: north, south, east or west, south when absent. */
    private static String facing(Input in) {
        if (!in.has("facing")) return "south";
        String f = in.text("facing", 8).toLowerCase(Locale.ROOT);
        if (!FACINGS.contains(f)) throw new BadInput("facing");
        return f;
    }

    /** build_house's block id, or null when absent. */
    private static String material(Input in) {
        return in.has("material") ? item(in, "material") : null;
    }

    /** build's list: 1 to 64 objects with x, y, z and item; anything else is bad_input:blocks. */
    private static List<Action.BuildBlock> blocks(Input in) {
        JsonElement e = in.json().get("blocks");
        if (e == null || !e.isJsonArray()) throw new BadInput("blocks");
        JsonArray array = e.getAsJsonArray();
        if (array.isEmpty() || array.size() > MAX_BUILD_BLOCKS) throw new BadInput("blocks");
        List<Action.BuildBlock> out = new ArrayList<>(array.size());
        for (JsonElement entry : array) {
            if (!entry.isJsonObject()) throw new BadInput("blocks");
            try {
                Input b = new Input(entry.getAsJsonObject());
                out.add(new Action.BuildBlock(b.integer("x", -30_000_000, 30_000_000), b.integer("y", -64, 320), b.integer("z", -30_000_000, 30_000_000), item(b, "item")));
            } catch (BadInput bad) {
                throw new BadInput("blocks");
            }
        }
        return out;
    }

    /** "water" or "lava" for use_bucket, or null when absent. */
    private static String fluid(Input in) {
        if (!in.has("fluid")) return null;
        String f = in.text("fluid", 8).toLowerCase(Locale.ROOT);
        if (!Set.of("water", "lava").contains(f)) throw new BadInput("fluid");
        return f;
    }

    /** One of DigPlan.DIRECTIONS. */
    private static String direction(Input in) {
        String d = in.text("direction", 8).toLowerCase(Locale.ROOT);
        if (!DigPlan.DIRECTIONS.contains(d)) throw new BadInput("direction");
        return d;
    }

    /** One of Sight.DIRECTIONS, forward when absent. */
    private static String seeDirection(Input in) {
        if (!in.has("direction")) return "forward";
        String d = in.text("direction", 8).toLowerCase(Locale.ROOT);
        if (!Sight.DIRECTIONS.contains(d)) throw new BadInput("direction");
        return d;
    }

    /** 1 to 2304 (a full inventory of 64-stacks); not read for all. */
    private static int count(Input in, String item) {
        return Action.ALL.equals(item) ? 0 : in.integer("count", 1, 2304);
    }

    private static Prop optional(Prop p) {
        return new Prop(p.name(), p.schema(), false);
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

    private static Prop bool(String name, String description) {
        return prop(name, "boolean", description);
    }

    /** An array of 1 to 64 objects, each with x, y, z (integers) and item (a string), all required. */
    private static Prop blockList(String name, String description) {
        JsonObject entry = new JsonObject();
        JsonObject entryProps = new JsonObject();
        entryProps.add("x", integer("x", "Block x.").schema());
        entryProps.add("y", integer("y", "Block y.").schema());
        entryProps.add("z", integer("z", "Block z.").schema());
        entryProps.add("item", text("item", "Item id to place, for example cobblestone.").schema());
        JsonArray entryRequired = new JsonArray();
        for (String k : List.of("x", "y", "z", "item")) entryRequired.add(k);
        entry.addProperty("type", "object");
        entry.add("properties", entryProps);
        entry.add("required", entryRequired);
        entry.addProperty("additionalProperties", false);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.addProperty("description", description);
        schema.addProperty("minItems", 1);
        schema.addProperty("maxItems", MAX_BUILD_BLOCKS);
        schema.add("items", entry);
        return new Prop(name, schema);
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

        /** A JSON boolean, or the string "true" or "false". */
        boolean bool(String key) {
            JsonElement e = json.get(key);
            if (e == null || !e.isJsonPrimitive()) throw new BadInput(key);
            if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
            String s = e.getAsString().strip().toLowerCase(Locale.ROOT);
            if (s.equals("true")) return true;
            if (s.equals("false")) return false;
            throw new BadInput(key);
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
