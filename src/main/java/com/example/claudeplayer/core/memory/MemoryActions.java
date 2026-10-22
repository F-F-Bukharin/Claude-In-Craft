package com.example.claudeplayer.core.memory;

import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;

/** Runs the memory tools (note, remember, forget) in the core and hands every other action to the world runner. */
public final class MemoryActions implements ActionRunner {
    private final Memory memory;
    private final ActionRunner world;

    public MemoryActions(Memory memory, ActionRunner world) {
        this.memory = memory;
        this.world = world;
    }

    @Override
    public Handle start(Action action) {
        if (action instanceof Action.Note n) {
            memory.note(n.subject(), n.text());
            return Handle.done(ActionResult.ok());
        }
        if (action instanceof Action.Remember r) {
            return Handle.done(memory.remember(r.subject(), r.text()) ? ActionResult.ok() : ActionResult.failed("memory_full"));
        }
        if (action instanceof Action.Forget f) {
            return Handle.done(memory.forget(f.subject()) ? ActionResult.ok() : ActionResult.failed("not_found"));
        }
        return world.start(action);
    }
}
