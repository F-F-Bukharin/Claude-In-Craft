package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.GatherRule;
import com.mojang.datafixers.util.Either;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * sleep: at night or in a thunderstorm, walks to the nearest bed within 16 blocks (else the bed at or right next to
 * home) and lies down the vanilla way with {@code startSleepInBed}. Ok once asleep; the body then stays in bed until
 * the morning, a hit, or its next order ({@link AgentPlayer#wakeWhenBusy}).
 *
 * <p>Failures use the contract's codes only. not_night is checked up front (and again if dawn comes on the way).
 * Per bed, a problem skips to the next bed and the first one met is reported when none is left:
 * monsters_nearby (vanilla NOT_SAFE, or a monster-category mob near the bed), no_path (no route, stuck, a hazard on
 * the way, or vanilla TOO_FAR_AWAY), no_bed (bed gone or blocked above, OBSTRUCTED, NOT_POSSIBLE_HERE, OTHER_PROBLEM).
 * Beds in use are skipped; when every candidate is in use the result is occupied.
 */
final class SleepHandle implements ActionRunner.Handle {
    /** "Within 16 blocks" of the body. */
    private static final int SEARCH = 16;
    /** The home bed is at or right next to home. */
    private static final int HOME_RADIUS = 3;
    private static final int HOME_HEIGHT = 2;
    /** Vanilla's bedInRange allows 3 blocks horizontally and 2 vertically; stop a little inside that. */
    private static final double REACH_H = 2.5;
    private static final double REACH_V = 1.5;
    /** Vanilla's NOT_SAFE box around the bed. */
    private static final double SAFE_H = 8.0;
    private static final double SAFE_V = 5.0;
    /** Own limit for the whole run (45 s); the queue's timeout is only the backstop. */
    private static final int LIMIT_TICKS = 900;

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final Deque<BlockPos> pending;
    private int ticks;
    private @Nullable BlockPos bed;
    private @Nullable ActionRunner.Handle walk;
    private @Nullable String failure;
    private @Nullable String failureDetail;
    private boolean sawOccupied;

    private SleepHandle(AgentRuntime rt, AgentPlayer body, List<BlockPos> candidates) {
        this.rt = rt;
        this.body = body;
        this.pending = new ArrayDeque<>(candidates);
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Sleep a) {
        if (!p.isAlive()) return ActionRunner.Handle.done(ActionResult.failed("died"));
        if (p.isSleeping()) {
            p.wakeWhenBusy(rt.queue()::runningTool);
            return ActionRunner.Handle.done(ActionResult.ok("already asleep"));
        }
        ServerLevel level = p.serverLevel();
        if (level.isDay()) return ActionRunner.Handle.done(ActionResult.failed("not_night")); // night or a thunderstorm is not day
        if (!level.dimensionType().natural()) {
            return ActionRunner.Handle.done(ActionResult.failed("no_bed", "beds can't be slept in in this dimension"));
        }
        List<BlockPos> beds = candidates(rt, p, level);
        if (beds.isEmpty()) return ActionRunner.Handle.done(ActionResult.failed("no_bed"));
        return new SleepHandle(rt, p, beds);
    }

    /** Bed heads within 16 of the body, nearest first, then the ones at home (in the home dimension). */
    private static List<BlockPos> candidates(AgentRuntime rt, AgentPlayer p, ServerLevel level) {
        Set<BlockPos> found = new LinkedHashSet<>();
        Map<Long, Boolean> loaded = new HashMap<>();
        BlockPos feet = p.blockPosition();
        found.addAll(bedsIn(level, loaded, feet, SEARCH, SEARCH, p.position(), SEARCH));
        BlockPos home = rt.data().home();
        if (home != null && level.dimension().equals(rt.data().homeDimension())) {
            found.addAll(bedsIn(level, loaded, home, HOME_RADIUS, HOME_HEIGHT, p.position(), Double.POSITIVE_INFINITY));
        }
        return new ArrayList<>(found);
    }

    /** Bed heads in the box, within {@code maxDist} of {@code from}, nearest to it first. Unloaded chunks are left alone. */
    private static List<BlockPos> bedsIn(ServerLevel level, Map<Long, Boolean> loaded, BlockPos center, int radius, int height,
                                         Vec3 from, double maxDist) {
        List<BlockPos> beds = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -height, -radius), center.offset(radius, height, radius))) {
            if (level.isOutsideBuildHeight(pos) || pos.distToCenterSqr(from) > maxDist * maxDist) continue;
            if (!loaded.computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), k -> level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4))) continue;
            if (isHead(level.getBlockState(pos))) beds.add(pos.immutable());
        }
        beds.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(from)));
        return beds;
    }

    private static boolean isHead(BlockState state) {
        return state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) {
            cancel();
            return ActionResult.failed("died");
        }
        if (body.isSleeping()) return asleep(bed != null ? bed : body.blockPosition());
        if (body.serverLevel().isDay()) return fail(ActionResult.failed("not_night")); // dawn came on the way
        if (++ticks > LIMIT_TICKS) return fail(ActionResult.failed("no_path", "timeout"));
        List<Mob> near = SnapshotBuilder.hostiles(body, GatherRule.DANGER_RANGE);
        if (!near.isEmpty()) return fail(ActionResult.failed("monsters_nearby"));

        if (bed == null) {
            bed = nextBed();
            walk = null;
            if (bed == null) return fail(finalFailure());
        }
        if (near(bed)) return lieDown();
        if (walk == null) walk = rt.navigator().moveTo(body, Vec3.atBottomCenterOf(bed));
        ActionResult r = walk.poll();
        if (r == null) return null;
        walk = null;
        if (r.isError()) { // no_path, stuck or hazard_*: the contract has one code for a bed that can't be reached
            skip("no_path", r.reason());
            return null;
        }
        return lieDown(); // the walk arrived by its own rule; vanilla has the last word on the range
    }

    /** Next candidate that is still a bed head, free, and has no monster near it. Records why the others were passed over. */
    private @Nullable BlockPos nextBed() {
        ServerLevel level = body.serverLevel();
        while (!pending.isEmpty()) {
            BlockPos pos = pending.poll();
            if (!level.hasChunkAt(pos)) {
                note("no_path", "bed not loaded");
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!isHead(state)) {
                note("no_bed", "bed is gone");
                continue;
            }
            if (state.getValue(BedBlock.OCCUPIED)) {
                sawOccupied = true;
                continue;
            }
            if (monstersNear(level, pos)) {
                note("monsters_nearby", null);
                continue;
            }
            return pos;
        }
        return null;
    }

    /** What vanilla's NOT_SAFE looks for: monsters that prevent rest, in a box around the bed. */
    private boolean monstersNear(ServerLevel level, BlockPos pos) {
        Vec3 c = Vec3.atBottomCenterOf(pos);
        AABB box = new AABB(c.x - SAFE_H, c.y - SAFE_V, c.z - SAFE_H, c.x + SAFE_H, c.y + SAFE_V, c.z + SAFE_H);
        return !level.getEntitiesOfClass(Monster.class, box, m -> m.isPreventingPlayerRest(body)).isEmpty();
    }

    private boolean near(BlockPos pos) {
        Vec3 c = Vec3.atBottomCenterOf(pos);
        return Math.abs(body.getX() - c.x) <= REACH_H && Math.abs(body.getY() - c.y) <= REACH_V && Math.abs(body.getZ() - c.z) <= REACH_H;
    }

    /** Standing close enough: stop, and ask vanilla to put the body to bed. */
    private @Nullable ActionResult lieDown() {
        rt.navigator().stop(body);
        walk = null;
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(bed);
        if (!isHead(state)) {
            skip("no_bed", "bed is gone");
            return null;
        }
        if (state.getValue(BedBlock.OCCUPIED)) { // startSleepInBed doesn't look at this
            sawOccupied = true;
            bed = null;
            return null;
        }
        Either<Player.BedSleepingProblem, Unit> result = body.startSleepInBed(bed);
        Player.BedSleepingProblem problem = result.left().orElse(null);
        if (problem == null) return asleep(bed);
        switch (problem) {
            case NOT_POSSIBLE_NOW -> {
                return fail(ActionResult.failed("not_night")); // the time check says day; nothing to try elsewhere
            }
            case NOT_SAFE -> skip("monsters_nearby", null);
            case TOO_FAR_AWAY -> skip("no_path", "could not get next to the bed");
            case OBSTRUCTED -> skip("no_bed", "bed is blocked above");
            case NOT_POSSIBLE_HERE -> skip("no_bed", "can't sleep here");
            default -> skip("no_bed", problem.name().toLowerCase(java.util.Locale.ROOT));
        }
        return null;
    }

    private ActionResult asleep(BlockPos pos) {
        rt.navigator().stop(body);
        body.wakeWhenBusy(rt.queue()::runningTool);
        return ActionResult.ok("asleep in bed at " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    /** The current bed can't be used: remember why and move on to the next one. */
    private void skip(String code, @Nullable String detail) {
        note(code, detail);
        bed = null;
        walk = null;
    }

    /** Remembers the first problem met, which is the one reported if no bed works out. */
    private void note(String code, @Nullable String detail) {
        if (failure == null) {
            failure = code;
            failureDetail = detail;
        }
    }

    private ActionResult finalFailure() {
        if (failure != null) return failureDetail == null ? ActionResult.failed(failure) : ActionResult.failed(failure, failureDetail);
        return ActionResult.failed(sawOccupied ? "occupied" : "no_bed");
    }

    private ActionResult fail(ActionResult result) {
        cancel();
        return result;
    }

    @Override
    public void cancel() {
        if (walk != null) walk.cancel();
        walk = null;
        rt.navigator().stop(body);
    }

    @Override
    public int timeoutTicks() {
        return LIMIT_TICKS + 100;
    }
}
