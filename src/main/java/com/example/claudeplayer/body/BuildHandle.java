package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.BuildPlan;
import com.example.claudeplayer.core.work.GatherRule;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * build: places the blocks in list order. A position that already holds its block is skipped; one out of reach is
 * walked to first, to a spot near it (never on it, never in a block still to come), and placed as soon as the walk
 * brings it into reach. A block more than 64 blocks away or in an unloaded chunk fails no_path up front, before any
 * read. Danger or low health stops the
 * run the way gather does. Reports once: ok with how many were placed and skipped, or the failure code BuildPlan
 * gives with the progress. build_house runs its walls and roof through {@link #run}.
 */
final class BuildHandle implements ActionRunner.Handle {
    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final List<Action.BuildBlock> blocks;
    private final String label;
    private final Set<BuildPlan.Spot> pending = new HashSet<>();
    private final Map<String, String> blockIds = new HashMap<>(); // item id -> the block id it places
    private boolean started;
    private int ticks;
    private int index;
    private int placed;
    private int skipped;
    private List<BuildPlan.Spot> spots;
    private int spotTry;
    private ActionRunner.Handle walk;
    private int walkTicks;

    private BuildHandle(AgentRuntime rt, AgentPlayer body, List<Action.BuildBlock> blocks, String label) {
        this.rt = rt;
        this.body = body;
        this.blocks = List.copyOf(blocks);
        this.label = label;
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Build a) {
        return run(rt, p, a.blocks(), "build");
    }

    /** The shared entry: any number of blocks (no 64 cap), reported under {@code label}. */
    static ActionRunner.Handle run(AgentRuntime rt, AgentPlayer p, List<Action.BuildBlock> blocks, String label) {
        return new BuildHandle(rt, p, blocks, label);
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) return end("died");
        List<Mob> near = SnapshotBuilder.hostiles(body, GatherRule.DANGER_RANGE);
        String danger = BuildPlan.danger(near.isEmpty() ? Double.POSITIVE_INFINITY : near.get(0).distanceTo(body), body.getHealth() / body.getMaxHealth());
        if (danger != null) return end(danger);
        if (++ticks >= BuildPlan.limitTicks(blocks.size())) return end("timeout");
        if (!started) {
            started = true;
            String bad = check();
            if (bad != null) return end(bad);
        }
        if (walk != null) {
            ActionResult r = walk.poll();
            if (r != null && r.isError() && r.reason().startsWith("hazard_")) return end(r.reason());
            if (canPlaceFromHere(pos(blocks.get(index)))) { // in reach on the way, or on arrival: stop and place
                walk = null;
                rt.navigator().stop(body);
                return place();
            }
            if (r == null && ++walkTicks < BuildPlan.WALK_TICKS) return null;
            walk = null;
            rt.navigator().stop(body);
            return nextSpot();
        }
        while (index < blocks.size() && filled(blocks.get(index))) {
            pending.remove(spot(blocks.get(index)));
            skipped++;
            index++;
        }
        if (index >= blocks.size()) {
            cancel();
            return ActionResult.ok(BuildPlan.summary(label, placed, skipped, blocks.size()));
        }
        Action.BuildBlock b = blocks.get(index);
        if (canPlaceFromHere(pos(b))) return place();
        ServerLevel level = body.serverLevel();
        spots = BuildPlan.spots(b, pending, (x, y, z) -> standable(level, new BlockPos(x, y, z)), body.getX(), body.getY(), body.getZ(), BuildPlan.SPOT_TRIES);
        spotTry = 0;
        return nextSpot();
    }

    /**
     * Before the first block: every position near and loaded (no_path otherwise, before the world is read there, so a
     * far coordinate never loads or generates a chunk), every item carried (unless its blocks are all there already)
     * and every item a block.
     */
    private String check() {
        String far = unreachable(body, blocks);
        if (far != null) return far;
        Map<String, Integer> carried = new HashMap<>();
        Map<String, Item> items = new HashMap<>();
        for (ItemStack s : body.getInventory().items) {
            if (s.isEmpty()) continue;
            carried.merge(SnapshotBuilder.itemId(s), s.getCount(), Integer::sum);
            items.putIfAbsent(SnapshotBuilder.itemId(s), s.getItem());
        }
        String missing = BuildPlan.firstMissing(blocks, carried, i -> filled(blocks.get(i)));
        if (missing != null) return "missing_item:" + missing;
        for (int i = 0; i < blocks.size(); i++) {
            Action.BuildBlock b = blocks.get(i);
            if (filled(b)) continue;
            if (!(items.get(b.item()) instanceof BlockItem)) return "not_a_block:" + b.item();
            pending.add(spot(b));
        }
        return null;
    }

    /** "no_path" when a block is {@link BuildPlan#tooFar} from the body or in a chunk that isn't loaded; null when all are fine. */
    private static String unreachable(AgentPlayer body, List<Action.BuildBlock> blocks) {
        if (BuildPlan.firstTooFar(blocks, body.getX(), body.getZ()) >= 0) return "no_path";
        ServerLevel level = body.serverLevel();
        for (Action.BuildBlock b : blocks) {
            if (!level.hasChunk(b.x() >> 4, b.z() >> 4)) return "no_path";
        }
        return null;
    }

    private ActionResult nextSpot() {
        if (spots == null || spotTry >= spots.size()) return end("no_path");
        BuildPlan.Spot s = spots.get(spotTry++);
        walk = rt.navigator().moveTo(body, Vec3.atBottomCenterOf(new BlockPos(s.x(), s.y(), s.z())));
        walkTicks = 0;
        return null;
    }

    private ActionResult place() {
        Action.BuildBlock b = blocks.get(index);
        pending.remove(spot(b));
        spots = null;
        if (filled(b)) { // put there while it walked
            skipped++;
            index++;
            return null;
        }
        ActionResult r = Placing.place(rt, body, pos(b), b.item());
        if (r.isError()) return end(BuildPlan.placeFailure(r.reason(), b.item()));
        placed++;
        index++;
        return null;
    }

    /** In reach, and the body isn't standing in the block's space. */
    private boolean canPlaceFromHere(BlockPos pos) {
        return ActionExecutor.inBlockReach(body, pos) && !body.getBoundingBox().intersects(AABB.unitCubeFromLowerCorner(Vec3.atLowerCornerOf(pos)));
    }

    private boolean filled(Action.BuildBlock b) {
        BlockState state = body.serverLevel().getBlockState(pos(b));
        return !state.isAir() && BuildPlan.filled(blockIds.computeIfAbsent(b.item(), BuildHandle::placedBlockId), SnapshotBuilder.blockId(state));
    }

    /** The id of the block an item places (redstone places redstone_wire), else the item id itself. */
    private static String placedBlockId(String item) {
        ResourceLocation key = ResourceLocation.tryParse(item);
        if (key != null && BuiltInRegistries.ITEM.containsKey(key) && BuiltInRegistries.ITEM.get(key) instanceof BlockItem bi) {
            return SnapshotBuilder.blockId(bi.getBlock().defaultBlockState());
        }
        return item;
    }

    private static BlockPos pos(Action.BuildBlock b) {
        return new BlockPos(b.x(), b.y(), b.z());
    }

    private static BuildPlan.Spot spot(Action.BuildBlock b) {
        return new BuildPlan.Spot(b.x(), b.y(), b.z());
    }

    /** Feet and head free of collision, fluid and fire, and solid ground under the feet that isn't fluid. */
    private static boolean standable(ServerLevel level, BlockPos s) {
        if (level.isOutsideBuildHeight(s) || !level.hasChunk(s.getX() >> 4, s.getZ() >> 4)) return false;
        return open(level, s) && open(level, s.above()) && !level.getBlockState(s.below()).getCollisionShape(level, s.below()).isEmpty()
                && level.getFluidState(s.below()).isEmpty();
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        BlockState state = level.getBlockState(p);
        return state.getCollisionShape(level, p).isEmpty() && state.getFluidState().isEmpty() && !state.is(BlockTags.FIRE);
    }

    private ActionResult end(String reason) {
        cancel();
        return ActionResult.failed(BuildPlan.failure(index, placed, reason), BuildPlan.summary(label, placed, skipped, blocks.size()));
    }

    @Override
    public void cancel() {
        walk = null;
        rt.navigator().stop(body);
    }

    /** Its own limit ends the run with a report first; this is the backstop. */
    @Override
    public int timeoutTicks() {
        return BuildPlan.timeoutTicks(blocks.size());
    }
}
