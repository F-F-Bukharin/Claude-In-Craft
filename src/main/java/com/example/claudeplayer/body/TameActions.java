package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;

/**
 * tame and pet. tame: wolf with bones, cat with raw cod or salmon, parrot with seeds; fails missing_item, no_animal,
 * not_tamed. pet: sit or follow for an animal of its own; fails not_mine, no_target.
 * Contract: the tool descriptions in core/brain/ToolSchemas.java and the records Action.Tame and Action.Pet in
 * core/act/Action.java. This is a stub that the owning team replaces; it does nothing yet.
 */
final class TameActions {
    private TameActions() {
    }

    static ActionRunner.Handle tame(AgentRuntime rt, AgentPlayer p, Action.Tame a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }

    static ActionRunner.Handle pet(AgentRuntime rt, AgentPlayer p, Action.Pet a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }
}
