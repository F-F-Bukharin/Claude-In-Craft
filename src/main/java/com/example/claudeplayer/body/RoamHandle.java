package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;

/**
 * roam: wanders in unseen directions, notes trees, ores, water, villages and animals as short-term notes, comes back
 * and reports once. Danger scraps it (prio 1); failed no_path.
 * Contract: the tool description in core/brain/ToolSchemas.java and the record Action.Roam in core/act/Action.java.
 * This is a stub that the owning team replaces; it does nothing yet.
 */
final class RoamHandle {
    private RoamHandle() {
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Roam a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }
}
