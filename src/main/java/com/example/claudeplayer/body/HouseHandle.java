package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;

/**
 * build_house: 5x5 outside, 3x3 inside, walls 3 high, flat roof, door in the middle of the facing wall; a bed inside
 * becomes home. Needs 71 blocks and a wooden door; fails missing_blocks, missing_door, site_blocked, uneven_ground,
 * no_path.
 * Contract: the tool description in core/brain/ToolSchemas.java and the record Action.BuildHouse in core/act/Action.java.
 * This is a stub that the owning team replaces; it does nothing yet.
 */
final class HouseHandle {
    private HouseHandle() {
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.BuildHouse a) {
        return ActionRunner.Handle.done(ActionResult.failed("not_implemented"));
    }
}
