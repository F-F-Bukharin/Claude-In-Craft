package com.example.claudeplayer.core.act;

/**
 * One parsed, validated tool call. Records only: ToolSchemas builds them, the game-side ActionRunner executes
 * them. Adding a tool means a record here, a schema entry in ToolSchemas, and a branch in the executor.
 */
public sealed interface Action {
    // v1
    record GetState() implements Action {}

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

    // v2: schemas exist, hidden until tool_phase reaches them
    record FollowPlayer(String name, double seconds) implements Action {}

    record AttackEntity(int entityId) implements Action {}

    record MineBlock(int x, int y, int z) implements Action {}

    record PlaceBlock(int x, int y, int z, String item) implements Action {}

    record Equip(String item) implements Action {}

    record Craft(String item, int count) implements Action {}
}
