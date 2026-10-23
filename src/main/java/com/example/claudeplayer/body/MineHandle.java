package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * mine_block after its checks passed: digs over time like a player (crack stages, arm swings), breaks the block
 * through the game mode so drops, tool wear and Forge's break event come with it, then walks onto the drops for
 * up to 5 s so the normal touch pickup takes them. ok reports what was mined and picked up.
 */
final class MineHandle implements ActionRunner.Handle {
    private static final int SWING_EVERY = 5;
    private static final int COLLECT_TICKS = 100;
    private static final double DROP_RANGE = 5.0;

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final BlockPos pos;
    private final Block block;
    private final String blockId;
    private final int digTicks;
    private final Map<String, Integer> before;
    private float progress;
    private int stage = -1;
    private int ticks;
    private List<ItemEntity> drops; // null while digging
    private int collectTicks;
    private ItemEntity walkingTo;
    private ActionRunner.Handle walk;

    MineHandle(AgentRuntime rt, AgentPlayer body, BlockPos pos, BlockState state, int digTicks) {
        this.rt = rt;
        this.body = body;
        this.pos = pos.immutable();
        this.block = state.getBlock();
        this.blockId = SnapshotBuilder.blockId(state);
        this.digTicks = digTicks;
        this.before = Hands.counts(body.getInventory());
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) {
            cancel();
            return ActionResult.failed("died");
        }
        return drops == null ? dig() : collect();
    }

    private ActionResult dig() {
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(pos);
        if (!state.is(block)) {
            clearCracks();
            return ActionResult.failed("block_changed");
        }
        if (!ActionExecutor.inBlockReach(body, pos)) {
            clearCracks();
            return ActionResult.failed("out_of_reach");
        }
        body.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        if (ticks++ % SWING_EVERY == 0) body.swing(InteractionHand.MAIN_HAND, true);
        progress += state.getDestroyProgress(body, level, pos);
        if (progress < 1f) {
            int now = Math.min(9, (int) (progress * 10));
            if (now != stage) {
                stage = now;
                level.destroyBlockProgress(body.getId(), pos, now);
            }
            return null;
        }
        clearCracks();
        if (!body.gameMode.destroyBlock(pos)) return ActionResult.failed("break_refused");
        rt.data().removePlaced(level.dimension(), pos);
        drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(DROP_RANGE), e -> e.getAge() < 2);
        return drops.isEmpty() ? finish() : null;
    }

    private ActionResult collect() {
        drops.removeIf(e -> !e.isAlive());
        if (drops.isEmpty() || ++collectTicks > COLLECT_TICKS) return finish();
        if (walk == null || !walkingTo.isAlive()) {
            walkingTo = drops.stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElseThrow();
            walk = rt.navigator().moveTo(body, walkingTo.position());
        }
        ActionResult step = walk.poll();
        if (step != null) {
            if (step.isError()) drops.remove(walkingTo); // can't get there: leave it
            walk = null; // arrived: stand on it until the pickup delay ends, or re-aim if it rolled away
        }
        return null;
    }

    private ActionResult finish() {
        rt.navigator().stop(body);
        Map<String, Integer> after = Hands.counts(body.getInventory());
        String picked = after.entrySet().stream()
                .filter(e -> e.getValue() > before.getOrDefault(e.getKey(), 0))
                .map(e -> (e.getValue() - before.getOrDefault(e.getKey(), 0)) + " " + e.getKey())
                .collect(Collectors.joining(", "));
        long left = drops.stream().filter(Entity::isAlive).count();
        return ActionResult.ok("mined " + blockId + "; picked up " + (picked.isEmpty() ? "nothing" : picked)
                + (left > 0 ? "; " + left + " drop(s) left on the ground" : ""));
    }

    private void clearCracks() {
        if (stage >= 0) body.serverLevel().destroyBlockProgress(body.getId(), pos, -1);
        stage = -1;
    }

    @Override
    public void cancel() {
        clearCracks();
        rt.navigator().stop(body);
    }

    /** The expected dig plus 2 s, then the time allowed for collecting drops. */
    @Override
    public int timeoutTicks() {
        return digTicks + 40 + COLLECT_TICKS;
    }
}
