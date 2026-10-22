package com.example.claudeplayer.nav;

import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Path;

/** follow_player: keeps within about 3 blocks of the target, re-planning each second; ok when the time is up. */
final class FollowHandle implements ActionRunner.Handle {
    private static final double CLOSE_ENOUGH = 3.0;
    private static final int REPLAN_TICKS = 20;

    private final Navigator nav;
    private final ServerPlayer body;
    private final ServerPlayer target;
    private final int duration;
    private Path path;
    private int node;
    private int ticks;

    FollowHandle(Navigator nav, ServerPlayer body, ServerPlayer target, int duration) {
        this.nav = nav;
        this.body = body;
        this.target = target;
        this.duration = duration;
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) return ActionResult.failed("died");
        if (!target.isAlive() || target.isRemoved() || target.level() != body.level()) {
            Navigator.halt(body);
            return ActionResult.failed("target_gone");
        }
        if (++ticks >= duration) {
            Navigator.halt(body);
            return ActionResult.ok();
        }
        if (body.distanceTo(target) <= CLOSE_ENOUGH) {
            Navigator.halt(body);
            path = null;
            return null;
        }
        if (path == null || ticks % REPLAN_TICKS == 0) {
            path = nav.findPath(body, target.blockPosition(), 2);
            node = 0;
        }
        if (path == null) { // no route right now; try again next second
            Navigator.halt(body);
            return null;
        }
        while (node < path.getNodeCount() && MoveHandle.reached(body, path.getNode(node))) node++;
        Navigator.steer(body, node < path.getNodeCount() ? MoveHandle.center(path.getNode(node)) : target.position());
        return null;
    }

    @Override
    public void cancel() {
        Navigator.halt(body);
    }

    @Override
    public int timeoutTicks() {
        return duration + 20;
    }
}
