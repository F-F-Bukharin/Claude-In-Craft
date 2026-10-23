package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.DigPlan;
import com.example.claudeplayer.nav.Navigator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * dig: plans each step with DigPlan from where the feet are, checks it (fluids beside each cell, a deep drop under
 * the new feet), clears its cells with MineHandle, then steers into it. Ores on the new walls are noted for the
 * result. Stops at the first refusal with how far it got.
 */
final class DigHandle implements ActionRunner.Handle {
    /** 5 minutes: a 32-step staircase dug with a wooden pickaxe takes about two. */
    private static final int LIMIT_TICKS = 6000;
    private static final int STEP_WALK_TICKS = 60;
    /** Breaks per step before giving up: sand or gravel falling into the cells refills them. */
    private static final int MAX_BREAKS = 12;
    private static final int MAX_DROP = 3;

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final String direction;
    private final String facing;
    private final int length;
    private final Map<BlockPos, String> seen = new LinkedHashMap<>();
    private int ticks;
    private int steps;
    private int broken;
    private DigPlan.Step step;
    private DigPlan.Pos from;
    private int breaks;
    private ActionRunner.Handle mine;
    private int walkTicks = -1; // -1 while not walking

    DigHandle(AgentRuntime rt, AgentPlayer body, Action.Dig dig) {
        this.rt = rt;
        this.body = body;
        this.direction = dig.direction();
        this.facing = body.getDirection().getName(); // the horizontal way the body faces: north, south, east or west
        this.length = dig.length();
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) {
            cancel();
            return ActionResult.failed("died");
        }
        if (++ticks > LIMIT_TICKS) return end("timeout", false);
        if (mine != null) {
            ActionResult r = mine.poll();
            if (r == null) return null;
            mine = null;
            if (r.isError() && !r.reason().equals("block_changed")) return end(r.reason(), true); // block_changed: look again
            if (!r.isError()) broken++;
            return null;
        }
        if (walkTicks >= 0) return walk();
        if (steps == length) return end(null, false);
        if (step == null) {
            from = pos(body.blockPosition());
            step = DigPlan.step(direction, facing, from);
            if (step == null) return end("y_floor", false);
            breaks = 0;
            String hazard = groundHazard(block(step.feet()));
            if (hazard != null) return end(hazard, true);
        }
        ServerLevel level = body.serverLevel();
        for (DigPlan.Pos cell : step.clear()) {
            BlockPos pos = block(cell);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) return end(fluidHazard(state.getFluidState()), true);
            if (state.is(BlockTags.FIRE)) return end("hazard_fire", true);
            if (state.getCollisionShape(level, pos).isEmpty()) continue;
            for (Direction d : Direction.values()) {
                FluidState fluid = level.getFluidState(pos.relative(d));
                if (!fluid.isEmpty()) return end(fluidHazard(fluid), true);
            }
            ActionResult rule = MineHandle.breakRule(rt, level, pos, state);
            if (rule.isError()) return end(rule.reason(), true);
            if (++breaks > MAX_BREAKS) return end("blocked", true);
            mine = MineHandle.start(rt, body, pos, false);
            return null;
        }
        walkTicks = 0;
        return walk();
    }

    /** Steers into the step's feet cell; arrived once the body stands in it, near its middle. */
    private ActionResult walk() {
        BlockPos feet = block(step.feet());
        Vec3 center = Vec3.atBottomCenterOf(feet);
        double dx = center.x - body.getX();
        double dz = center.z - body.getZ();
        if (body.blockPosition().equals(feet) && dx * dx + dz * dz < 0.16) {
            Navigator.halt(body);
            walkTicks = -1;
            steps++;
            noteOres();
            step = null;
            return null;
        }
        if (++walkTicks > STEP_WALK_TICKS) return end("stuck", true);
        Navigator.steer(body, center);
        return null;
    }

    /** "lava" under the new feet, or a fall of MAX_DROP or more; null when the floor is fine. */
    private String groundHazard(BlockPos feet) {
        ServerLevel level = body.serverLevel();
        BlockPos below = feet.below();
        int depth = 0;
        while (depth < MAX_DROP && level.getBlockState(below).getCollisionShape(level, below).isEmpty() && level.getFluidState(below).isEmpty()) {
            depth++;
            below = below.below();
        }
        if (level.getFluidState(below).is(FluidTags.LAVA)) return "hazard_lava";
        return depth >= MAX_DROP ? "hazard_drop" : null;
    }

    private void noteOres() {
        ServerLevel level = body.serverLevel();
        for (DigPlan.Pos wall : DigPlan.walls(step, from)) {
            if (seen.size() >= DigPlan.MAX_SEEN) return;
            BlockPos pos = block(wall);
            String id = SnapshotBuilder.blockId(level.getBlockState(pos));
            if (DigPlan.isOre(id)) seen.putIfAbsent(pos, id + " at " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
    }

    private static String fluidHazard(FluidState fluid) {
        return fluid.is(FluidTags.LAVA) ? "hazard_lava" : "hazard_water";
    }

    /** ok with the progress, or failed with the reason and the progress. */
    private ActionResult end(String stop, boolean failed) {
        cancel();
        String summary = DigPlan.summary(steps, length, direction, pos(body.blockPosition()), broken, List.copyOf(seen.values()), failed ? null : stop);
        return failed ? ActionResult.failed(stop, summary) : ActionResult.ok(summary);
    }

    @Override
    public void cancel() {
        if (mine != null) mine.cancel();
        mine = null;
        walkTicks = -1;
        Navigator.halt(body);
    }

    /** Its own 5-minute limit ends the run with a report first; this is the backstop. */
    @Override
    public int timeoutTicks() {
        return LIMIT_TICKS + 200;
    }

    private static DigPlan.Pos pos(BlockPos p) {
        return new DigPlan.Pos(p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos block(DigPlan.Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }
}
