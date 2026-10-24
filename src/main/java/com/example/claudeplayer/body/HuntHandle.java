package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;

/**
 * hunt: needs attacks on; never players, villagers, named, tamed, leashed or baby animals. Fails no_animals, timeout.
 * Contract: the tool description in core/brain/ToolSchemas.java and the record Action.Hunt in core/act/Action.java.
 * This is a stub that the owning team replaces; it does nothing yet.
 */
final class HuntHandle {
    private HuntHandle() {
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Hunt a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }
}
