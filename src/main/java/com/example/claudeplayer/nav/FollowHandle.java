package com.example.claudeplayer.nav;

import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.nav.FollowGap;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * follow_player: stays 2 to 3.5 blocks behind the target (walks past 3.5, stands under 2, and keeps doing what it
 * was doing in between), re-planning when the target has moved more than 3 blocks from where the path was aimed
 * and every 2 s as a fallback, never more than one search per tick; ok when the time is up.
 * failed:hazard_lava, hazard_fire or hazard_drop when the next step would be deadly (the body halts at the edge).
 */
final class FollowHandle implements ActionRunner.Handle {
    private final Navigator nav;
    private final ServerPlayer body;
    private final ServerPlayer target;
    private final int duration;
    private Path path;
    private Vec3 aimed = Vec3.ZERO; // where the target stood when the path was planned
    private int sincePlan = FollowGap.NEVER;
    private boolean walking;
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
        walking = FollowGap.walking(walking, body.distanceTo(target));
        if (!walking) { // close enough: stand, facing the player, and plan afresh when it walks off
            Navigator.halt(body);
            path = null;
            sincePlan = FollowGap.NEVER;
            if (Math.abs(target.getX() - body.getX()) + Math.abs(target.getZ() - body.getZ()) > 0.5) {
                body.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
            }
            return null;
        }
        if (sincePlan < FollowGap.NEVER) sincePlan++;
        Vec3 now = target.position();
        if (FollowGap.replan(path != null, sincePlan, aimed.x, aimed.y, aimed.z, now.x, now.y, now.z)) {
            path = nav.findPath(body, target.blockPosition(), 2); // at most one search per poll
            aimed = now;
            node = 0;
            sincePlan = 0;
        }
        if (path == null) { // no route right now; stand and try again at the retry limit
            Navigator.halt(body);
            return null;
        }
        while (node < path.getNodeCount() && Navigator.reached(body, path.getNode(node))) node++;
        Vec3 point = node < path.getNodeCount() ? Navigator.center(path.getNode(node)) : target.position();
        String hazard = Navigator.hazardAhead(body, point); // the same probe as move_to: the target may walk past lava
        if (hazard != null) {
            Navigator.halt(body);
            return ActionResult.failed("hazard_" + hazard);
        }
        Navigator.steer(body, point);
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
