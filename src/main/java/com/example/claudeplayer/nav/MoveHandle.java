package com.example.claudeplayer.nav;

import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.nav.Gait;
import com.example.claudeplayer.core.nav.PathSmoother;
import com.example.claudeplayer.core.nav.PathSmoother.Cell;
import com.example.claudeplayer.core.nav.Progress;
import com.example.claudeplayer.core.safety.Reflexes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * move_to: ok on arrival, failed:no_path if the planner can't reach it, failed:stuck when the body makes no progress
 * through three re-plans, failed:hazard_lava, hazard_fire or hazard_drop when the next step would be deadly (the body
 * halts at the edge).
 *
 * <p>Beyond the plain walk: it heads straight for a later node when the line is safe ({@link PathSmoother}), sprints on
 * long straight runs ({@link Gait}), swims for the nearest dry node on the path and rises when air runs low, opens
 * wooden doors (and a fence gate that blocks the way) and closes what it opened once clear, and jumps up one-block
 * steps. {@link Progress} decides when a stall means a jump, a re-plan or giving up. The 0.8-block hazard probe runs
 * every tick before any of it takes effect.
 */
final class MoveHandle implements ActionRunner.Handle {
    private static final double ARRIVE = 1.0;
    private static final double NODE_REACHED = 0.6;
    private static final double PROBE_AHEAD = 0.8;
    private static final int MAX_DROP = 24;
    /** A node this much above the feet needs a jump (a slab or stair step is 0.5 and doesn't). */
    private static final double STEP_UP = 0.5;
    /** ... once the body is within this many blocks of it. */
    private static final double STEP_NEAR = 1.6;
    private static final double DOOR_REACH = 2.4;
    /** An opened door is closed once the body is this far (flat) from it. */
    private static final double DOOR_CLEAR = 2.0;
    /** When the move ends, a door is closed only if the body is at least this far from it. */
    private static final double DOOR_CLEAR_AT_END = 1.2;
    /** Sprint safety is checked this many nodes ahead, and again when fewer than SPRINT_RESCAN remain checked. */
    private static final int SPRINT_WINDOW = 24;
    private static final int SPRINT_RESCAN = 8;
    /** A drop of this many blocks beside or on a sprint run stops the sprint, whatever the hazard rules say. */
    private static final int SPRINT_DROP = 3;
    private static final int SWIM_EXIT_SCAN = 24;
    private static final int AIR_SCAN = 16;

    private final Navigator nav;
    private final ServerPlayer body;
    private final Vec3 target;
    private final Progress progress = new Progress(0);
    private final Deque<BlockPos> opened = new ArrayDeque<>();
    private final Set<BlockPos> refused = new HashSet<>();
    private Path path;
    private List<Cell> cells = List.of();
    private int node;
    private int ticks;
    // caches, each keyed by the node index so block reads happen when it changes, not every tick
    private int aimFor = -1;
    private int aim;
    private int runFor = -1;
    private int runEnd;
    private int exitFor = -1;
    private int exitIdx;
    private int sprintCheckedTo = -1;
    private int sprintCheckedRun = -1;
    private boolean sprintSafe;

    MoveHandle(Navigator nav, ServerPlayer body, Vec3 target) {
        this.nav = nav;
        this.body = body;
        this.target = target;
    }

    @Override
    public ActionResult poll() {
        ticks++;
        if (!body.isAlive()) return ActionResult.failed("died");
        if (near(body, target, ARRIVE)) return finish(ActionResult.ok());
        if (path == null && !plan()) return finish(ActionResult.failed("no_path"));
        closeBehind(false);

        Gait.Swim swim = swimming();
        boolean swimming = swim != Gait.Swim.NONE;
        advance(swimming);
        Vec3 point = swimming ? swimPoint() : aimPoint();
        String hazard = hazardAhead(body, point); // the probe: before anything below can move the body
        if (hazard != null) return finish(ActionResult.failed("hazard_" + hazard));
        openDoorsOnRoute();

        Navigator.steer(body, point);
        Progress.Decision stall = Progress.Decision.NONE;
        if (swim == Gait.Swim.RISE) {
            progress.reset(ticks); // standing still on purpose, to breathe
        } else {
            stall = progress.update(ticks, flat(body, point), Math.min(node, path.getNodeCount()));
        }
        if (stall == Progress.Decision.GIVE_UP) return finish(ActionResult.failed("stuck"));
        if (stall == Progress.Decision.JUMP || stall == Progress.Decision.REPLAN) openObstacleAhead(point);

        applyGait(swim, point, stall == Progress.Decision.JUMP);
        if (stall == Progress.Decision.REPLAN) { // one search per poll: the next poll plans
            path = null;
            refused.clear();
        }
        return null;
    }

    @Override
    public void cancel() {
        Navigator.halt(body);
        closeBehind(true);
    }

    private ActionResult finish(ActionResult result) {
        Navigator.halt(body);
        closeBehind(true);
        return result;
    }

    // ---- planning and following ------------------------------------------------------------------------------

    private boolean plan() {
        Path p = nav.findPath(body, BlockPos.containing(target), 1, true);
        if (p == null || !p.canReach()) return false;
        path = p;
        node = 0;
        aimFor = runFor = exitFor = -1;
        sprintCheckedTo = sprintCheckedRun = -1;
        List<Cell> list = new ArrayList<>(p.getNodeCount());
        for (int i = 0; i < p.getNodeCount(); i++) {
            Node n = p.getNode(i);
            list.add(new Cell(n.x, n.y, n.z));
        }
        cells = list;
        return true;
    }

    /** Moves {@link #node} past the farthest of the next few nodes the body stands on (or, swimming, the exit). */
    private void advance(boolean swimming) {
        int count = path.getNodeCount();
        int far = Math.min(count - 1, node + PathSmoother.MAX_LOOKAHEAD);
        if (swimming && exitFor == node && exitIdx > far) far = Math.min(count - 1, exitIdx);
        boolean flat = body.isInWater(); // swimming: the path's nodes may be at any depth, so height doesn't count
        for (int k = far; k >= node; k--) {
            Vec3 c = center(path.getNode(k));
            if (flat ? flat(body, c) < NODE_REACHED : near(body, c, NODE_REACHED)) {
                node = k + 1;
                return;
            }
        }
    }

    /** The point to walk to: the farthest of the next few nodes that a straight, safe line reaches. */
    private Vec3 aimPoint() {
        int count = path.getNodeCount();
        if (node >= count) return target;
        if (aimFor != node) {
            aimFor = node;
            int fromY = node > 0 ? cells.get(node - 1).y() : body.getBlockY();
            Cell from = new Cell(body.getBlockX(), fromY, body.getBlockZ());
            aim = PathSmoother.farthest(new Ground(body.serverLevel()), from, cells, node, PathSmoother.MAX_LOOKAHEAD);
        }
        return center(path.getNode(aim));
    }

    // ---- water -----------------------------------------------------------------------------------------------

    private Gait.Swim swimming() {
        if (!body.isInWater()) return Gait.Swim.NONE;
        boolean eyesUnder = body.isUnderWater();
        int air = body.getAirSupply();
        int max = body.getMaxAirSupply();
        boolean wayUp = air * 3 >= max || !eyesUnder || openAbove();
        return Gait.swim(true, eyesUnder, body.onGround(), air, max, wayUp);
    }

    /** Open water (then air) straight above the head, with no ceiling in between. */
    private boolean openAbove() {
        ServerLevel level = body.serverLevel();
        BlockPos.MutableBlockPos p = BlockPos.containing(body.getEyePosition()).mutable();
        for (int i = 0; i < AIR_SCAN; i++) {
            p.move(Direction.UP);
            BlockState s = level.getBlockState(p);
            if (!s.getCollisionShape(level, p).isEmpty()) return false;
            if (s.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /** While swimming: the next path node on dry ground, or the target when the path has none. */
    private Vec3 swimPoint() {
        int count = path.getNodeCount();
        if (node >= count) return target;
        if (exitFor != node) {
            exitFor = node;
            exitIdx = -1;
            ServerLevel level = body.serverLevel();
            for (int i = node; i < count && i <= node + SWIM_EXIT_SCAN; i++) {
                if (dry(level, cells.get(i))) {
                    exitIdx = i;
                    break;
                }
            }
        }
        return exitIdx >= 0 ? center(path.getNode(exitIdx)) : target;
    }

    private static boolean dry(ServerLevel level, Cell c) {
        BlockPos feet = new BlockPos(c.x(), c.y(), c.z());
        BlockState state = level.getBlockState(feet);
        if (!state.getFluidState().isEmpty()) return false;
        return !state.getCollisionShape(level, feet).isEmpty() || level.getFluidState(feet.below()).isEmpty();
    }

    // ---- gait ------------------------------------------------------------------------------------------------

    /** Jump, sprint and stroke for this tick, after {@link Navigator#steer} has faced the body and set it walking. */
    private void applyGait(Gait.Swim swim, Vec3 point, boolean stalledJump) {
        boolean jump;
        if (swim.holdsJump()) {
            jump = true; // hold the jump key: float, and hop onto the bank when the water is shallow
            if (!swim.walks()) body.zza = 0f; // straight up for air
        } else {
            jump = body.onGround() && (stalledJump || stepUpAhead(point)
                    || (body.horizontalCollision && point.y > body.getY() - STEP_UP));
        }
        body.setJumping(jump);
        boolean sprint = !swim.holdsJump() && sprintWanted();
        if (body.isSprinting() != sprint) body.setSprinting(sprint);
    }

    /**
     * A node on the way to the point steered to (or the point itself) stands a block up and close: jump now, while
     * the body is still a step short of the wall, not a tick after it has bumped it.
     */
    private boolean stepUpAhead(Vec3 point) {
        if (higherAndNear(point)) return true;
        int count = path.getNodeCount();
        int last = Math.min(count - 1, aimFor == node ? Math.max(node, aim) : node);
        for (int i = node; i <= last; i++) {
            if (higherAndNear(center(path.getNode(i)))) return true;
        }
        return false;
    }

    private boolean higherAndNear(Vec3 p) {
        return p.y - body.getY() > STEP_UP && flat(body, p) <= STEP_NEAR;
    }

    private boolean sprintWanted() {
        int count = path.getNodeCount();
        if (node >= count) return false;
        if (runFor != node) {
            runFor = node;
            runEnd = Gait.straightRunEnd(cells, node);
        }
        double remaining = flat(body, center(path.getNode(runEnd)));
        boolean now = body.isSprinting();
        // cheap exits first: no block is read unless the run is long enough to matter
        if (remaining < (now ? Gait.SPRINT_STOP_RUN : Gait.SPRINT_START_RUN) || body.horizontalCollision) return false;
        if (body.getFoodData().getFoodLevel() <= Gait.SPRINT_FOOD_ABOVE) return false;
        boolean hazardNear = !runSafe();
        return Gait.sprint(now, body.getFoodData().getFoodLevel(), body.isInWater(), remaining, hazardNear);
    }

    /** Whether the next stretch of the straight run (and the blocks beside it) is free of lava, fire, drops and doors. */
    private boolean runSafe() {
        int window = Math.min(runEnd, node + SPRINT_WINDOW);
        boolean newRun = sprintCheckedRun != runEnd;
        boolean windowRunningOut = node + SPRINT_RESCAN > sprintCheckedTo && sprintCheckedTo < runEnd;
        if (newRun || windowRunningOut) {
            sprintCheckedRun = runEnd;
            sprintCheckedTo = window;
            sprintSafe = scanRun(node, window);
        }
        return sprintSafe;
    }

    private boolean scanRun(int from, int to) {
        ServerLevel level = body.serverLevel();
        double hp = body.getHealth();
        for (int i = from; i <= to; i++) {
            Cell c = cells.get(i);
            if (sprintUnsafe(level, new BlockPos(c.x(), c.y(), c.z()), hp)) return false;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (sprintUnsafe(level, new BlockPos(c.x() + side.getStepX(), c.y(), c.z() + side.getStepZ()), hp)) return false;
            }
        }
        return true;
    }

    private static boolean sprintUnsafe(ServerLevel level, BlockPos feet, double hp) {
        BlockState state = level.getBlockState(feet);
        Block block = state.getBlock();
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) return true;
        if (!state.getFluidState().isEmpty()) return true;
        if (hazardAt(level, feet, hp) != null) return true;
        return state.getCollisionShape(level, feet).isEmpty() && dropDepth(level, feet) >= SPRINT_DROP;
    }

    // ---- doors -----------------------------------------------------------------------------------------------

    /** Opens a closed wooden door standing on the next two path nodes once the body is close. */
    private void openDoorsOnRoute() {
        int count = path.getNodeCount();
        ServerLevel level = body.serverLevel();
        for (int i = node; i < count && i <= node + 1; i++) {
            Cell c = cells.get(i);
            BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
            BlockState state = level.getBlockState(pos);
            if (!closedOpenable(state)) continue;
            if (flat(body, Vec3.atCenterOf(pos)) <= DOOR_REACH && Math.abs(pos.getY() - body.getY()) < 2) openDoor(level, pos, state);
        }
    }

    /**
     * Found when stuck: a closed wooden door or fence gate right in front of the body. The planner can't route
     * through a closed gate (its path type is a fence), so a gate is only ever met this way.
     */
    private void openObstacleAhead(Vec3 point) {
        double dx = point.x - body.getX();
        double dz = point.z - body.getZ();
        double length = Reflexes.horizontal(dx, dz);
        if (length < 0.01) return;
        ServerLevel level = body.serverLevel();
        for (double ahead : new double[] {0.6, 1.1}) {
            BlockPos pos = BlockPos.containing(body.getX() + dx / length * ahead, body.getBlockY(), body.getZ() + dz / length * ahead);
            BlockState state = level.getBlockState(pos);
            if (closedOpenable(state)) {
                openDoor(level, pos, state);
                return;
            }
        }
    }

    /** A door or gate the hands can open, and that is shut. Iron doors fail the first test and are never touched. */
    private static boolean closedOpenable(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) return !state.getValue(DoorBlock.OPEN) && door.type().canOpenByHand();
        if (block instanceof FenceGateBlock) return !state.getValue(FenceGateBlock.OPEN);
        return false;
    }

    private static boolean openOpenable(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) return state.getValue(DoorBlock.OPEN) && door.type().canOpenByHand();
        if (block instanceof FenceGateBlock) return state.getValue(FenceGateBlock.OPEN);
        return false;
    }

    private void openDoor(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos lower = lowerHalf(pos, state);
        if (refused.contains(lower)) return;
        if (use(level, lower)) {
            opened.add(lower);
        } else {
            refused.add(lower); // a protection mod said no: don't ask every tick
        }
    }

    /**
     * A right-click on the block with an empty hand, the way a player works a door. The stack passed is empty, so no
     * held item is ever used or placed; vanilla runs the block's own use first and a door's use consumes the click.
     */
    private boolean use(ServerLevel level, BlockPos pos) {
        Vec3 at = Vec3.atCenterOf(pos);
        Direction face = Direction.getNearest(body.getX() - at.x, 0, body.getZ() - at.z);
        InteractionResult result = body.gameMode.useItemOn(body, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(at, face, pos, false));
        return result.consumesAction();
    }

    private static BlockPos lowerHalf(BlockPos pos, BlockState state) {
        boolean upper = state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
        return upper ? pos.below() : pos;
    }

    /** Closes what this move opened, once the body is clear of it (or, at the end, not standing right at it). */
    private void closeBehind(boolean last) {
        if (opened.isEmpty()) return;
        ServerLevel level = body.serverLevel();
        Iterator<BlockPos> it = opened.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            BlockState state = level.getBlockState(pos);
            if (!openOpenable(state)) { // someone closed it, or it's gone
                it.remove();
                continue;
            }
            if (flat(body, Vec3.atCenterOf(pos)) >= (last ? DOOR_CLEAR_AT_END : DOOR_CLEAR)) {
                use(level, pos);
                it.remove();
            } else if (last) {
                it.remove(); // still at the door when the move ends: leave it open
            }
        }
    }

    // ---- the probe, shared with follow_player ----------------------------------------------------------------

    /**
     * Looks at the block the body is about to step into: "lava", "fire", "drop", or null when it is safe. Shared by
     * move_to and follow_player. Standing on a slab, stair or carpet puts the feet inside that block, so a feet block
     * with a collision shape is the floor itself: no drop, and the ground under it doesn't count.
     */
    static @Nullable String hazardAhead(ServerPlayer body, Vec3 point) {
        double dx = point.x - body.getX();
        double dz = point.z - body.getZ();
        double length = Reflexes.horizontal(dx, dz);
        if (length < 0.01) return null;
        BlockPos feet = BlockPos.containing(body.position().add(dx / length * PROBE_AHEAD, 0, dz / length * PROBE_AHEAD));
        return hazardAt(body.serverLevel(), feet, body.getHealth());
    }

    /** The hazard rules for a body whose feet would be in {@code feet}. */
    static @Nullable String hazardAt(ServerLevel level, BlockPos feet, double hp) {
        BlockState feetState = level.getBlockState(feet);
        if (!feetState.getCollisionShape(level, feet).isEmpty()) { // a partial block walked on, or a step up
            return Reflexes.hazard(id(feetState), id(feetState), 0, false, hp);
        }
        BlockPos below = feet.below();
        int depth = 0;
        while (depth < MAX_DROP && level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                && level.getFluidState(below).isEmpty()) {
            depth++;
            below = below.below();
        }
        boolean water = level.getFluidState(below).is(FluidTags.WATER);
        return Reflexes.hazard(id(feetState), id(level.getBlockState(feet.below())), depth, water, hp);
    }

    /** Blocks of empty, fluid-free air under {@code feet}, up to {@link #MAX_DROP}. */
    private static int dropDepth(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        int depth = 0;
        while (depth < MAX_DROP && level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                && level.getFluidState(below).isEmpty()) {
            depth++;
            below = below.below();
        }
        return depth;
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

    private static double flat(ServerPlayer body, Vec3 p) {
        return Reflexes.horizontal(p.x - body.getX(), p.z - body.getZ());
    }

    /** What the smoother asks: can the body stand with its feet in this cell, on a full block, in the clear. */
    private static final class Ground implements PathSmoother.Terrain {
        private final ServerLevel level;
        private final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        Ground(ServerLevel level) {
            this.level = level;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight() - 2) return false;
            p.set(x, y, z);
            if (!level.hasChunkAt(p)) return false;
            BlockState feet = level.getBlockState(p);
            if (!feet.getFluidState().isEmpty() || !feet.getCollisionShape(level, p).isEmpty()) return false;
            p.set(x, y + 1, z);
            BlockState head = level.getBlockState(p);
            if (!head.getFluidState().isEmpty() || !head.getCollisionShape(level, p).isEmpty()) return false;
            p.set(x, y - 1, z);
            if (!level.getBlockState(p).isFaceSturdy(level, p, Direction.UP)) return false;
            p.set(x, y, z);
            // vanilla's own verdict also rules out fire, cactus, berries, magma and honey underfoot or alongside
            return WalkNodeEvaluator.getBlockPathTypeStatic(level, p) == BlockPathTypes.WALKABLE;
        }
    }
}
