package com.example.claudeplayer.nav;

import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** move_to: ok on arrival, failed:no_path if the planner can't reach it, failed:stuck after repeated re-plans. */
final class MoveHandle implements ActionRunner.Handle {
    private static final double ARRIVE = 1.0;
    private static final double NODE_REACHED = 0.6;
    private static final int STUCK_CHECK_TICKS = 20;
    private static final int MAX_REPLANS = 2;

    private final Navigator nav;
    private final ServerPlayer body;
    private final Vec3 target;
    private Path path;
    private int node;
    private int ticks;
    private int replans;
    private Vec3 checkpoint;

    MoveHandle(Navigator nav, ServerPlayer body, Vec3 target) {
        this.nav = nav;
        this.body = body;
        this.target = target;
        this.checkpoint = body.position();
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) return ActionResult.failed("died");
        if (near(body, target, ARRIVE)) {
            Navigator.halt(body);
            return ActionResult.ok();
        }
        if (path == null) {
            path = nav.findPath(body, BlockPos.containing(target), 1);
            if (path == null || !path.canReach()) {
                Navigator.halt(body);
                return ActionResult.failed("no_path");
            }
            node = 0;
        }
        while (node < path.getNodeCount() && reached(body, path.getNode(node))) node++;
        Navigator.steer(body, node < path.getNodeCount() ? center(path.getNode(node)) : target);
        if (++ticks % STUCK_CHECK_TICKS == 0) {
            if (body.position().distanceToSqr(checkpoint) < 0.25) { // under half a block in a second
                if (++replans > MAX_REPLANS) {
                    Navigator.halt(body);
                    return ActionResult.failed("stuck");
                }
                path = null;
            }
            checkpoint = body.position();
        }
        return null;
    }

    @Override
    public void cancel() {
        Navigator.halt(body);
    }

    static boolean near(ServerPlayer body, Vec3 point, double range) {
        double dx = point.x - body.getX();
        double dz = point.z - body.getZ();
        return dx * dx + dz * dz < range * range && Math.abs(point.y - body.getY()) < 1.5;
    }

    static boolean reached(ServerPlayer body, Node n) {
        return near(body, center(n), NODE_REACHED);
    }

    static Vec3 center(Node n) {
        return new Vec3(n.x + 0.5, n.y, n.z + 0.5);
    }
}
