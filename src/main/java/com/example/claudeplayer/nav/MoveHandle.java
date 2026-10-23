package com.example.claudeplayer.nav;

import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.safety.Reflexes;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * move_to: ok on arrival, failed:no_path if the planner can't reach it, failed:stuck after repeated re-plans,
 * failed:hazard_lava, hazard_fire or hazard_drop when the next step would be deadly (the body halts at the edge).
 */
final class MoveHandle implements ActionRunner.Handle {
    private static final double ARRIVE = 1.0;
    private static final double NODE_REACHED = 0.6;
    private static final int STUCK_CHECK_TICKS = 20;
    private static final int MAX_REPLANS = 2;
    private static final double PROBE_AHEAD = 0.8;
    private static final int MAX_DROP = 24;

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
        Vec3 point = node < path.getNodeCount() ? center(path.getNode(node)) : target;
        String hazard = hazardAhead(body, point);
        if (hazard != null) {
            Navigator.halt(body);
            return ActionResult.failed("hazard_" + hazard);
        }
        Navigator.steer(body, point);
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

    /** Looks at the block the body is about to step into: "lava", "fire", "drop", or null when it is safe. */
    static @Nullable String hazardAhead(ServerPlayer body, Vec3 point) {
        double dx = point.x - body.getX();
        double dz = point.z - body.getZ();
        double length = Reflexes.horizontal(dx, dz);
        if (length < 0.01) return null;
        ServerLevel level = body.serverLevel();
        BlockPos feet = BlockPos.containing(body.position().add(dx / length * PROBE_AHEAD, 0, dz / length * PROBE_AHEAD));
        BlockPos below = feet.below();
        int depth = 0;
        // A slab, stair or carpet at the feet is the ground the body stands on: no drop under it, and what lies
        // below it (lava under a slab bridge) is not underfoot.
        boolean standsOnFeet = !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
        if (!standsOnFeet) {
            while (depth < MAX_DROP && level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                    && level.getFluidState(below).isEmpty()) {
                depth++;
                below = below.below();
            }
        }
        boolean water = level.getFluidState(below).is(FluidTags.WATER);
        BlockPos ground = standsOnFeet ? feet : feet.below();
        return Reflexes.hazard(id(level.getBlockState(feet)), id(level.getBlockState(ground)), depth, water, body.getHealth());
    }

    /** The full registry id ("minecraft:lava"), which is what {@link Reflexes#hazard} matches. */
    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
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
