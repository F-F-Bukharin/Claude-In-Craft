package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;

/**
 * build: walks within reach of each block, places them in order and skips blocks already there. ok reports placed
 * and skipped; failed missing_item:&lt;id&gt;, no_support, blocked, no_path, stopped_at:&lt;index&gt;:&lt;reason&gt;.
 * Contract: the tool description in core/brain/ToolSchemas.java and the record Action.Build in core/act/Action.java.
 * This is a stub that the owning team replaces; it does nothing yet.
 */
final class BuildHandle {
    private BuildHandle() {
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Build a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }
}
