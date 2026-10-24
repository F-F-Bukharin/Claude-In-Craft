package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;

/**
 * breed: feeds two adults the right food. Fails missing_food, no_pair, not_ready.
 * Contract: the tool description in core/brain/ToolSchemas.java and the record Action.Breed in core/act/Action.java.
 * This is a stub that the owning team replaces; it does nothing yet.
 */
final class BreedHandle {
    private BreedHandle() {
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Breed a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }
}
