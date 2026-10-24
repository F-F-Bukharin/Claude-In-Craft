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
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * harvest: walks within reach of the nearest ripe crop around the point, looks at it, and takes it the way its kind
 * is taken (Farming.Kind): breaks crops, cocoa, the fruit a stem faces and the second block of a cane, cactus or
 * bamboo column through the game mode (drops and Forge's break event); uses a full berry bush. Then it walks to the
 * fresh drops (waiting for the blocks above a cut column to fall, and stepping beside drops floating in water or
 * lying in a bush), and replants only what it broke whole (crops and cocoa) when it carries the seed. Repeats until
 * nothing ripe is left. Growing crops are never touched, so the break rule for mine_block doesn't apply here. Stops
 * on danger, a full inventory or the timeout like gather.
 */
final class HarvestHandle implements ActionRunner.Handle {
    private static final int WALK_TICKS = 200;
    private static final int COLLECT_TICKS = 60;
    private static final double DROP_RANGE = 3.0;
    private static final int MAX_COLUMN = 16; // bamboo's MAX_HEIGHT; cane and cactus stop at 3 without bone meal

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final BlockPos center;
    private final int radius;
    private final Map<String, Integer> before;
    private final Map<String, Integer> harvested = new LinkedHashMap<>();
    private final Set<String> yield = new LinkedHashSet<>(); // item ids the harvested crops drop
    private final Set<BlockPos> skipped = new HashSet<>();
    private int ticks;
    private int replanted;
    private BlockPos aim;
    private Farming.Kind kind;
    private ActionRunner.Handle walk;
    private int walkTicks;
    // collecting, after a harvest
    private boolean collecting;
    private int harvestTick;
    private int settleTicks; // ticks to wait for drops still to come (the rest of a cut column)
    private Set<String> dropIds = Set.of();
    private AABB dropArea;
    private final Set<ItemEntity> unreachable = new HashSet<>();
    private ItemEntity walkingTo;
    // replanting, after collecting
    private String seed;
    private BlockPos plantAgainst;
    private Direction plantFace;
    private BlockPos replantAt; // non-null while walking back to sow it
    private ActionRunner.Handle back;
    private int backTicks;

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
        if (collecting) return collect();
        if (replantAt != null) return walkBack();
        if (walk != null) {
            if (ActionExecutor.inBlockReach(body, aim)) { // close enough: don't walk into the crop itself
                rt.navigator().stop(body);
                walk = null;
                take();
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
        kind = Farming.kind(SnapshotBuilder.blockId(body.serverLevel().getBlockState(aim)));
        if (ActionExecutor.inBlockReach(body, aim)) take();
        else {
            BlockPos ground = kind == Farming.Kind.COLUMN ? aim.below() : aim; // a column's cut is in the air: walk to its bottom
            walk = rt.navigator().moveTo(body, Vec3.atBottomCenterOf(ground));
            walkTicks = 0;
        }
        return null;
    }

    /**
     * Whether harvest takes this block now: ripe by age (FarmActions#ripe), a melon or pumpkin an attached stem
     * faces, or the second block of a cane, cactus or bamboo column. Growing crops and loose fruit are left alone.
     */
    static boolean ripe(Level level, BlockPos pos, BlockState state) {
        String id = SnapshotBuilder.blockId(state);
        Farming.Kind k = Farming.kind(id);
        if (k == null) return false;
        return switch (k) {
            case CROP, COCOA, BERRY_BUSH -> FarmActions.ripe(state);
            case FRUIT -> stemFacing(level, pos, id);
            case COLUMN -> Farming.cutPoint(id, SnapshotBuilder.blockId(level.getBlockState(pos.below())),
                    SnapshotBuilder.blockId(level.getBlockState(pos.below(2))));
        };
    }

    /** An attached stem of this fruit beside it, whose FACING points at it. */
    private static boolean stemFacing(Level level, BlockPos fruit, String fruitId) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos at = fruit.relative(d);
            BlockState stem = level.getBlockState(at);
            if (stem.getBlock() instanceof AttachedStemBlock && stem.getValue(AttachedStemBlock.FACING) == d.getOpposite()
                    && Farming.fruitAttached(fruitId, SnapshotBuilder.blockId(stem))) return true;
        }
        return false;
    }

    /** Looks at the ripe crop at aim and takes it: uses a berry bush, breaks the rest; then collects. A changed block is skipped. */
    private void take() {
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(aim);
        if (!ripe(level, aim, state)) {
            skip();
            return;
        }
        String crop = SnapshotBuilder.blockId(state);
        Set<String> drops = new LinkedHashSet<>();
        int settle = 2;
        seed = null;
        if (Farming.usedNotBroken(kind)) {
            if (!FarmActions.useOn(body, aim, faceToward(aim), false).consumesAction()) {
                skip();
                return;
            }
            body.swing(InteractionHand.MAIN_HAND, true);
            drops.add("sweet_berries");
        } else {
            for (ItemStack drop : Block.getDrops(state, level, aim, level.getBlockEntity(aim), body, body.getMainHandItem())) {
                drops.add(SnapshotBuilder.itemId(drop));
            }
            if (kind == Farming.Kind.COLUMN) settle += columnAbove(level, aim, crop); // each block above falls a tick after the one below
            if (kind == Farming.Kind.COCOA) {
                Direction toLog = state.getValue(CocoaBlock.FACING);
                plantAgainst = aim.relative(toLog);
                plantFace = toLog.getOpposite();
            } else {
                plantAgainst = aim.below();
                plantFace = Direction.UP;
            }
            body.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(aim));
            if (!body.gameMode.destroyBlock(aim)) {
                skip();
                return;
            }
            if (Farming.replants(kind)) seed = Farming.seedFor(crop);
        }
        harvested.merge(crop, 1, Integer::sum);
        yield.addAll(drops);
        dropIds = drops;
        settleTicks = settle;
        dropArea = new AABB(aim).inflate(DROP_RANGE).expandTowards(0, settle, 0);
        harvestTick = ticks;
        unreachable.clear();
        walkingTo = null;
        walk = null;
        collecting = true;
    }

    /** How many blocks of the same plant stand above the cut, up to the tallest column. */
    private static int columnAbove(Level level, BlockPos cut, String id) {
        int n = 0;
        while (n < MAX_COLUMN && SnapshotBuilder.blockId(level.getBlockState(cut.above(n + 1))).equals(id)) n++;
        return n;
    }

    /**
     * Walks to the fresh drops of the last harvest (item entities of its drop types, spawned since it), waiting until
     * the rest of a cut column has fallen; then replants. Gives up on a drop it can't reach, and after COLLECT_TICKS.
     */
    private ActionResult collect() {
        int since = ticks - harvestTick;
        List<ItemEntity> fresh = body.serverLevel().getEntitiesOfClass(ItemEntity.class, dropArea,
                e -> e.isAlive() && e.getAge() <= since + 1 && dropIds.contains(SnapshotBuilder.itemId(e.getItem())) && !unreachable.contains(e));
        if (fresh.isEmpty() && since >= settleTicks || since > settleTicks + COLLECT_TICKS) {
            rt.navigator().stop(body);
            walk = null;
            collecting = false;
            replant();
            return null;
        }
        if (fresh.isEmpty()) return null; // the column is still falling
        if (walk == null || walkingTo == null || !fresh.contains(walkingTo)) {
            walkingTo = fresh.stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElseThrow();
            walk = rt.navigator().moveTo(body, approach(walkingTo));
        }
        ActionResult step = walk.poll();
        if (step != null) {
            if (step.isError()) unreachable.add(walkingTo); // can't get there: leave it
            walk = null;
        }
        return null;
    }

    /**
     * Where to stand to pick this drop up: on it, unless it floats in water or lies in a berry bush or against a
     * cactus; then a dry standable block beside it (a player's pickup reach is a block around the body).
     */
    private Vec3 approach(ItemEntity drop) {
        ServerLevel level = body.serverLevel();
        BlockPos cell = drop.blockPosition();
        boolean awkward = drop.isInWater() || !level.getFluidState(cell).isEmpty() || Farming.hurtsToStand(SnapshotBuilder.blockId(level.getBlockState(cell)));
        if (!awkward) return drop.position();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos stand = cell.relative(d).above(dy);
                if (!standable(level, stand)) continue;
                double dist = stand.distToCenterSqr(body.position());
                if (dist < bestDist) {
                    best = stand;
                    bestDist = dist;
                }
            }
        }
        return best == null ? drop.position() : Vec3.atBottomCenterOf(best);
    }

    /** Feet and head free of collision, fluid and harm, on a block with a solid top. */
    private static boolean standable(Level level, BlockPos feet) {
        for (BlockPos at : new BlockPos[] {feet, feet.above()}) {
            BlockState s = level.getBlockState(at);
            if (!s.getCollisionShape(level, at).isEmpty() || !s.getFluidState().isEmpty() || Farming.hurtsToStand(SnapshotBuilder.blockId(s))) return false;
        }
        return level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
    }

    /**
     * What was broken whole gets its seed back, on the same face, when one is carried and the spot is still clear.
     * If collecting took the body out of reach, a crop's spot is walked back to first (cocoa hangs in the air: only in reach).
     */
    private void replant() {
        BlockPos spot = aim;
        aim = null;
        if (seed == null || Hands.find(body.getInventory(), seed) < 0 || !body.serverLevel().getBlockState(spot).isAir()) {
            seed = null;
            return;
        }
        if (ActionExecutor.inBlockReach(body, plantAgainst)) {
            sowSeed(spot);
            return;
        }
        if (kind != Farming.Kind.CROP) {
            seed = null;
            return;
        }
        replantAt = spot;
        back = rt.navigator().moveTo(body, Vec3.atBottomCenterOf(spot));
        backTicks = 0;
    }

    /** Walks back toward the spot waiting for its seed and sows it once in reach; gives up like the walk to a crop. */
    private ActionResult walkBack() {
        if (ActionExecutor.inBlockReach(body, plantAgainst)) {
            rt.navigator().stop(body);
            back = null;
            BlockPos spot = replantAt;
            replantAt = null;
            sowSeed(spot);
            return null;
        }
        ActionResult r = back.poll();
        if (r == null && ++backTicks < WALK_TICKS) return null;
        rt.navigator().stop(body);
        back = null;
        replantAt = null;
        seed = null;
        if (r != null && r.isError() && r.reason().startsWith("hazard_")) return end(r.reason());
        return null;
    }

    private void sowSeed(BlockPos spot) {
        int slot = Hands.find(body.getInventory(), seed);
        seed = null;
        if (slot >= 0 && body.serverLevel().getBlockState(spot).isAir() && FarmActions.sowOn(body, plantAgainst, plantFace, slot)) replanted++;
    }

    private void skip() {
        skipped.add(aim);
        aim = null;
    }

    /** The face of pos that points most toward the eyes. */
    private Direction faceToward(BlockPos pos) {
        Vec3 eye = body.getEyePosition();
        Vec3 c = Vec3.atCenterOf(pos);
        return Direction.getNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
    }

    /** The ripe crop nearest the body within radius of the point, not skipped. */
    private @Nullable BlockPos nearestRipe() {
        ServerLevel level = body.serverLevel();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos) || skipped.contains(pos) || !ripe(level, pos, level.getBlockState(pos))) continue;
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

    /** Like gather: no empty slot and no crop drop stack with room; never full before the first crop is taken. */
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
        back = null;
        replantAt = null;
        collecting = false;
        rt.navigator().stop(body);
    }

    /** Its own 120 s limit ends the run with a report first; this is the backstop. */
    @Override
    public int timeoutTicks() {
        return GatherRule.LIMIT_TICKS + 200;
    }
}
