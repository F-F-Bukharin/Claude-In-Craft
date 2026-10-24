package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;

/**
 * fish: needs a fishing rod and water within 8. Fails missing_rod, no_water.
 * Contract: the tool description in core/brain/ToolSchemas.java and the record Action.Fish in core/act/Action.java.
 * This is a stub that the owning team replaces; it does nothing yet.
 */
final class FishHandle {
    private FishHandle() {
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Fish a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }
}
