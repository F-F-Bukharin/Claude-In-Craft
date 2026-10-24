package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;

/**
 * go_home: walks home; with store it puts loot in containers within 6 of home, keeping tools, weapons, armor, food,
 * torches and one stack of building blocks. Fails no_home, no_path, no_container.
 * Contract: the tool description in core/brain/ToolSchemas.java and the record Action.GoHome in core/act/Action.java.
 * This is a stub that the owning team replaces; it does nothing yet.
 */
final class HomeHandle {
    private HomeHandle() {
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.GoHome a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }
}
