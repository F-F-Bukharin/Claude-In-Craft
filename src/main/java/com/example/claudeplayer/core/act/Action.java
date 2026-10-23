package com.example.claudeplayer.core.act;

/**
 * One parsed, validated tool call. Records only: ToolSchemas builds them, the game-side ActionRunner executes
 * them. Adding a tool means a record here, a schema entry in ToolSchemas, and a branch in the executor.
 */
public sealed interface Action {
    // v1
    record GetState() implements Action {}

    /** A wider top-down map than world_state.map (executed in the game). */
    record Look(int radius) implements Action {}

    /** What a fan of rays from the eyes hits, as text (Sight); {@code direction} is forward, north, south, east, west, up or down. */
    record See(String direction) implements Action {}

    record MoveTo(double x, double y, double z) implements Action {}

    record Say(String text) implements Action {}

    record Wait(double seconds) implements Action {}

    /** Stops movement and cancels everything queued after it (the queue handles the cancelling). */
    record Stop() implements Action {}

    /** Runs from the hostiles nearby to a spot well away from all of them (executed in the game). */
    record Flee() implements Action {}

    // memory: run in the core by MemoryActions, never reach the world
    record Note(String subject, String text) implements Action {}

    record Remember(String subject, String text) implements Action {}

    record Forget(String subject) implements Action {}

    /** Read-only look into a chest or other container; the game side also notes it in short-term memory. */
    record InspectContainer(int x, int y, int z) implements Action {}

    /** The item for store and take that means every item; their count is then ignored (0). */
    String ALL = "all";

    /** Moves items from the inventory into a container; {@code item} is a short id (oak_log) or {@link #ALL}. */
    record Store(int x, int y, int z, String item, int count) implements Action {}

    /** Moves items from a container into the inventory; {@code item} is a short id (oak_log) or {@link #ALL}. */
    record Take(int x, int y, int z, String item, int count) implements Action {}

    /** Eats the best food carried. {@code auto}: queued by the auto-eat reflex, never by the model; it only adds a log line. */
    record Eat(boolean auto) implements Action {}

    record FollowPlayer(String name, double seconds) implements Action {}

    // v2: schemas exist, hidden until tool_phase reaches them
    record AttackEntity(int entityId) implements Action {}

    record MineBlock(int x, int y, int z) implements Action {}

    record PlaceBlock(int x, int y, int z, String item) implements Action {}

    record Equip(String item) implements Action {}

    /** Crafts {@code count} items from the inventory (executed in the game). */
    record Craft(String item, int count) implements Action {}

    /** Loads up to {@code count} of an item and enough fuel into a furnace nearby; doesn't wait for it to finish. */
    record Smelt(String item, int count) implements Action {}

    /** Mines blocks matching {@code target} (a block id or a GatherTargets group) nearby until {@code count} of their drops are gained. */
    record Gather(String target, int count) implements Action {}

    /** Digs a 1-wide, 2-high tunnel {@code length} steps north, south, east or west, or a staircase down (DigPlan). */
    record Dig(String direction, int length) implements Action {}

    /** Turns dirt, grass or a path block into farmland with a hoe from the inventory. */
    record Till(int x, int y, int z) implements Action {}

    /** Plants {@code item} (a short id) in the block above the soil block at x, y, z (Farming.fits). */
    record Plant(int x, int y, int z, String item) implements Action {}

    /** Harvests every ripe crop within {@code radius} of a point, picks up the drops and replants from the inventory. */
    record Harvest(int x, int y, int z, int radius) implements Action {}

    /** Pours a water or lava bucket at a position, or fills an empty bucket from the source there (Guardrails.checkPour). */
    record UseBucket(int x, int y, int z) implements Action {}
}
