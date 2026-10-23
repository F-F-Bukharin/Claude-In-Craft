package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.Farming;
import com.example.claudeplayer.core.work.GatherRule;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * harvest: walks within reach of the nearest ripe crop around the point, breaks it through the game mode (drops
 * and Forge's break event), walks onto the drops, then replants the spot with its seed if one is carried. Repeats
 * until no ripe crop is left. Growing crops are never touched: only {@link FarmActions#ripe} ones are broken, so
 * the break rule for mine_block doesn't apply here. Stops on danger, a full inventory or the timeout like gather.
 */
final class HarvestHandle implements ActionRunner.Handle {
    private static final int WALK_TICKS = 200;
    private static final int COLLECT_TICKS = 40;
    private static final double DROP_RANGE = 2.5;

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final BlockPos center;
    private final int radius;
    private final Map<String, Integer> before;
    private final Map<String, Integer> harvested = new LinkedHashMap<>();
    private final Set<String> yield = new LinkedHashSet<>(); // item ids the broken crops drop
    private final Set<BlockPos> skipped = new HashSet<>();
    private int ticks;
    private int replanted;
    private BlockPos aim;
    private String seed; // what replants aim once it is broken
    private ActionRunner.Handle walk;
    private int walkTicks;
    private List<ItemEntity> drops; // non-null while collecting
    private int collectTicks;
    private ItemEntity walkingTo;

    HarvestHandle(AgentRuntime rt, AgentPlayer body, Action.Harvest h) {
        this.rt = rt;
        this.body = body;
        this.center = new BlockPos(h.x(), h.y(), h.z());
        this.radius = h.radius();
        this.before = Hands.counts(body.getInventory());
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) {
            cancel();
            return ActionResult.failed("died");
        }
        List<Mob> near = SnapshotBuilder.hostiles(body, GatherRule.DANGER_RANGE);
        String stop = GatherRule.stop(0, Integer.MAX_VALUE, ++ticks, near.isEmpty() ? Double.POSITIVE_INFINITY : near.get(0).distanceTo(body),
                body.getHealth() / body.getMaxHealth(), inventoryFull());
        if (stop != null) return end(stop);
        if (drops != null) return collect();
        if (walk != null) {
            if (ActionExecutor.inBlockReach(body, aim)) { // close enough: don't walk onto the crop itself
                rt.navigator().stop(body);
                walk = null;
                breakAim();
                return null;
            }
            ActionResult r = walk.poll();
            if (r == null && ++walkTicks < WALK_TICKS) return null;
            walk = null;
            rt.navigator().stop(body);
            if (r != null && r.isError() && r.reason().startsWith("hazard_")) return end(r.reason());
            skipped.add(aim);
            aim = null;
            return null;
        }
        aim = nearestRipe();
        if (aim == null) return end(harvested.isEmpty() ? "none_ripe" : "done");
        if (ActionExecutor.inBlockReach(body, aim)) breakAim();
        else {
            walk = rt.navigator().moveTo(body, Vec3.atBottomCenterOf(aim));
            walkTicks = 0;
        }
        return null;
    }

    /** Breaks the ripe crop at aim and starts collecting what it dropped; an unripe or changed block is skipped. */
    private void breakAim() {
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(aim);
        if (!FarmActions.ripe(state)) {
            skipped.add(aim);
            aim = null;
            return;
        }
        String crop = SnapshotBuilder.blockId(state);
        for (ItemStack drop : Block.getDrops(state, level, aim, level.getBlockEntity(aim), body, body.getMainHandItem())) {
            yield.add(SnapshotBuilder.itemId(drop));
        }
        if (!body.gameMode.destroyBlock(aim)) {
            skipped.add(aim);
            aim = null;
            return;
        }
        harvested.merge(crop, 1, Integer::sum);
        seed = Farming.seedFor(crop);
        drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(aim).inflate(DROP_RANGE), e -> e.getAge() < 2);
        collectTicks = 0;
        walk = null;
    }

    /** Walks onto the fresh drops (as MineHandle does), then replants. */
    private ActionResult collect() {
        drops.removeIf(e -> !e.isAlive());
        if (drops.isEmpty() || ++collectTicks > COLLECT_TICKS) {
            rt.navigator().stop(body);
            walk = null;
            drops = null;
            replant();
            return null;
        }
        if (walk == null || !walkingTo.isAlive()) {
            walkingTo = drops.stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElseThrow();
            walk = rt.navigator().moveTo(body, walkingTo.position());
        }
        ActionResult step = walk.poll();
        if (step != null) {
            if (step.isError()) drops.remove(walkingTo);
            walk = null;
        }
        return null;
    }

    /** The harvested spot gets its seed back when one is carried, the soil is still there and it is in reach. */
    private void replant() {
        BlockPos soil = aim.below();
        aim = null;
        int slot = seed == null ? -1 : Hands.find(body.getInventory(), seed);
        ServerLevel level = body.serverLevel();
        if (slot < 0 || !level.getBlockState(soil.above()).isAir() || !ActionExecutor.inBlockReach(body, soil)
                || !Farming.fits(seed, SnapshotBuilder.blockId(level.getBlockState(soil)))) return;
        if (FarmActions.sow(body, soil, slot)) replanted++;
    }

    /** The ripe crop nearest the body within radius of the point, not skipped. */
    private @Nullable BlockPos nearestRipe() {
        ServerLevel level = body.serverLevel();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos) || skipped.contains(pos) || !FarmActions.ripe(level.getBlockState(pos))) continue;
            double dist = pos.distToCenterSqr(body.position());
            if (dist < bestDist) {
                best = pos.immutable();
                bestDist = dist;
            }
        }
        return best;
    }

    /** Items gained since the start, among what the crops drop (net of the seeds replanted). */
    private Map<String, Integer> gained() {
        Map<String, Integer> now = Hands.counts(body.getInventory());
        Map<String, Integer> gained = new LinkedHashMap<>();
        for (String id : yield) {
            int n = now.getOrDefault(id, 0) - before.getOrDefault(id, 0);
            if (n > 0) gained.put(id, n);
        }
        return gained;
    }

    /** Like gather: no empty slot and no crop drop stack with room; never full before the first crop is broken. */
    private boolean inventoryFull() {
        if (yield.isEmpty()) return false;
        for (ItemStack s : body.getInventory().items) {
            if (s.isEmpty() || yield.contains(SnapshotBuilder.itemId(s)) && s.getCount() < s.getMaxStackSize()) return false;
        }
        return true;
    }

    private ActionResult end(String stop) {
        cancel();
        String summary = Farming.summary(harvested, replanted, gained(), stop);
        return stop.equals("danger") ? ActionResult.failed("danger", summary) : ActionResult.ok(summary);
    }

    @Override
    public void cancel() {
        walk = null;
        drops = null;
        rt.navigator().stop(body);
    }

    /** Its own 120 s limit ends the run with a report first; this is the backstop. */
    @Override
    public int timeoutTicks() {
        return GatherRule.LIMIT_TICKS + 200;
    }
}
