package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.GatherRule;
import com.example.claudeplayer.core.work.GatherTargets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * gather: finds the nearest matching block in loaded chunks, walks to a spot within reach of it, mines it with
 * MineHandle (drops collected), and repeats until GatherRule says stop or nothing reachable is left. A block that
 * can't be walked to or mined is skipped for the rest of the run. The search goes outward in cube shells, so the
 * first shell holding a usable block wins: near enough to nearest, and cheap underground.
 */
final class GatherHandle implements ActionRunner.Handle {
    private static final int RADIUS = 24;
    /** Blocks deeper than this below where the run started are left alone, so gathering dirt doesn't dig a pit. */
    private static final int MAX_DEPTH = 4;
    /** Spots are picked closer than the 4.5 reach, since the walk may end a block short. */
    private static final double SPOT_REACH = 4.0;
    private static final int WALK_TICKS = 300;

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final GatherTargets.Target target;
    private final int count;
    private final BlockPos start;
    private final Map<String, Integer> before;
    private final Set<String> yield = new LinkedHashSet<>(); // item ids the mined blocks drop
    private final Set<BlockPos> skipped = new HashSet<>();
    private final Map<Block, Boolean> matches = new HashMap<>();
    private int ticks;
    private int blocks;
    private boolean sawUnharvestable;
    private BlockPos aim;
    private BlockPos spot;
    private ActionRunner.Handle walk;
    private int walkTicks;
    private ActionRunner.Handle mine;

    private GatherHandle(AgentRuntime rt, AgentPlayer body, GatherTargets.Target target, int count) {
        this.rt = rt;
        this.body = body;
        this.target = target;
        this.count = count;
        this.start = body.blockPosition();
        this.before = Hands.counts(body.getInventory());
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Gather g) {
        GatherTargets.Target target = GatherTargets.resolve(g.target());
        if (target == null || !target.group() && !isBlock(target.name())) return ActionRunner.Handle.done(ActionResult.failed("unknown_target"));
        return new GatherHandle(rt, p, target, g.count());
    }

    private static boolean isBlock(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key != null && BuiltInRegistries.BLOCK.containsKey(key);
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) {
            cancel();
            return ActionResult.failed("died");
        }
        List<Mob> near = SnapshotBuilder.hostiles(body, GatherRule.DANGER_RANGE);
        String stop = GatherRule.stop(gained().values().stream().mapToInt(Integer::intValue).sum(), count, ++ticks,
                near.isEmpty() ? Double.POSITIVE_INFINITY : near.get(0).distanceTo(body), body.getHealth() / body.getMaxHealth(), inventoryFull());
        if (stop != null) return end(stop);
        if (mine != null) {
            ActionResult r = mine.poll();
            if (r == null) return null;
            mine = null;
            if (r.isError()) skipped.add(aim);
            else blocks++;
            aim = null;
            return null;
        }
        if (walk != null) {
            ActionResult r = walk.poll();
            if (r == null && ++walkTicks < WALK_TICKS) return null;
            walk = null;
            if (r != null && r.isError() && r.reason().startsWith("hazard_")) return end(r.reason());
            if (r == null || r.isError() || !ActionExecutor.inBlockReach(body, aim)) {
                rt.navigator().stop(body);
                skipped.add(aim);
                aim = null;
                return null;
            }
            startMining();
            return null;
        }
        aim = nearest();
        if (aim == null) return end(sawUnharvestable ? "needs_tool" : "none_reachable");
        if (spot == null) startMining();
        else {
            walk = rt.navigator().moveTo(body, Vec3.atBottomCenterOf(spot));
            walkTicks = 0;
        }
        return null;
    }

    private void startMining() {
        mine = MineHandle.start(rt, body, aim, true); // holds the best tool, so the drops below are for that tool
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(aim);
        for (ItemStack drop : Block.getDrops(state, level, aim, level.getBlockEntity(aim), body, body.getMainHandItem())) {
            yield.add(SnapshotBuilder.itemId(drop));
        }
    }

    /** The nearest usable block, setting {@link #spot} to where to stand (null when it is already in reach). */
    private @Nullable BlockPos nearest() {
        ServerLevel level = body.serverLevel();
        BlockPos feet = body.blockPosition();
        Map<Long, Boolean> loaded = new HashMap<>();
        for (int r = 0; r <= RADIUS; r++) {
            List<BlockPos> shell = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = feet.getX() + dx;
                    int z = feet.getZ() + dz;
                    if (!loaded.computeIfAbsent(ChunkPos.asLong(x >> 4, z >> 4), k -> level.hasChunk(x >> 4, z >> 4))) continue;
                    boolean side = Math.abs(dx) == r || Math.abs(dz) == r;
                    for (int dy = -r; dy <= r; dy += side ? 1 : Math.max(1, 2 * r)) {
                        BlockPos pos = new BlockPos(x, feet.getY() + dy, z);
                        if (pos.getY() < start.getY() - MAX_DEPTH || level.isOutsideBuildHeight(pos) || skipped.contains(pos)) continue;
                        if (matches(level.getBlockState(pos)) && exposed(level, pos)) shell.add(pos);
                    }
                }
            }
            shell.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(body.position())));
            for (BlockPos pos : shell) {
                if (usable(level, pos, feet)) return pos;
                skipped.add(pos);
            }
        }
        return null;
    }

    private boolean matches(BlockState state) {
        return !state.isAir() && matches.computeIfAbsent(state.getBlock(), b -> target.matches().test(SnapshotBuilder.blockId(state)));
    }

    /** Minable by the rule, harvestable with something carried, no fluid beside it, and in reach from a standable spot. */
    private boolean usable(ServerLevel level, BlockPos pos, BlockPos feet) {
        BlockState state = level.getBlockState(pos);
        if (pos.equals(feet.below()) || MineHandle.breakRule(rt, level, pos, state).isError() || fluidBeside(level, pos)) return false;
        if (!harvestable(state)) {
            sawUnharvestable = true;
            return false;
        }
        if (ActionExecutor.inBlockReach(body, pos)) {
            spot = null;
            return true;
        }
        spot = standSpot(level, pos);
        return spot != null;
    }

    /** Whether the best tool carried makes this block drop anything. */
    private boolean harvestable(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        Inventory inv = body.getInventory();
        int slot = Hands.bestTool(inv, state);
        return (slot >= 0 ? inv.items.get(slot) : inv.getSelected()).isCorrectToolForDrops(state);
    }

    /** The standable spot nearest the body whose eyes are within SPOT_REACH of the block; never standing on it. */
    private @Nullable BlockPos standSpot(ServerLevel level, BlockPos pos) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3 center = Vec3.atCenterOf(pos);
        for (BlockPos s : BlockPos.betweenClosed(pos.offset(-4, -5, -4), pos.offset(4, 3, 4))) {
            if (s.below().equals(pos) || center.distanceTo(Vec3.atBottomCenterOf(s).add(0, body.getEyeHeight(), 0)) > SPOT_REACH) continue;
            double dist = s.distToCenterSqr(body.position());
            if (dist < bestDist && standable(level, s)) {
                best = s.immutable();
                bestDist = dist;
            }
        }
        return best;
    }

    private static boolean standable(ServerLevel level, BlockPos s) {
        return open(level, s) && open(level, s.above()) && !level.getBlockState(s.below()).getCollisionShape(level, s.below()).isEmpty()
                && level.getFluidState(s.below()).isEmpty();
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        BlockState state = level.getBlockState(p);
        return state.getCollisionShape(level, p).isEmpty() && state.getFluidState().isEmpty() && !state.is(BlockTags.FIRE);
    }

    /** At least one face that isn't against a full block, so it can be seen and dug from outside. */
    private static boolean exposed(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!level.getBlockState(n).isCollisionShapeFullBlock(level, n)) return true;
        }
        return false;
    }

    static boolean fluidBeside(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (!level.getFluidState(pos.relative(d)).isEmpty()) return true;
        }
        return false;
    }

    /** Items gained since the start, among what the mined blocks drop. */
    private Map<String, Integer> gained() {
        Map<String, Integer> now = Hands.counts(body.getInventory());
        Map<String, Integer> gained = new LinkedHashMap<>();
        for (String id : yield) {
            int n = now.getOrDefault(id, 0) - before.getOrDefault(id, 0);
            if (n > 0) gained.put(id, n);
        }
        return gained;
    }

    /** No empty slot, and no stack of what is being gathered with room left. */
    private boolean inventoryFull() {
        for (ItemStack s : body.getInventory().items) {
            if (s.isEmpty() || yield.contains(SnapshotBuilder.itemId(s)) && s.getCount() < s.getMaxStackSize()) return false;
        }
        return true;
    }

    private ActionResult end(String stop) {
        cancel();
        String summary = GatherRule.summary(gained(), blocks, stop);
        return stop.equals("danger") ? ActionResult.failed("danger", summary) : ActionResult.ok(summary);
    }

    @Override
    public void cancel() {
        if (mine != null) mine.cancel();
        mine = null;
        walk = null;
        rt.navigator().stop(body);
    }

    /** Its own 120 s limit ends the run with a report first; this is the backstop. */
    @Override
    public int timeoutTicks() {
        return GatherRule.LIMIT_TICKS + 200;
    }
}
