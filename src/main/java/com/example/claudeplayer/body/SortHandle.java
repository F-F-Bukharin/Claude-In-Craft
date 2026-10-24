package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;

/**
 * sort_chests: sorts among the containers within radius (no ender chests) by kind. Fails no_chests.
 * Contract: the tool description in core/brain/ToolSchemas.java and the record Action.SortChests in core/act/Action.java.
 * This is a stub that the owning team replaces; it does nothing yet.
 */
final class SortHandle {
    private SortHandle() {
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.SortChests a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }
}
