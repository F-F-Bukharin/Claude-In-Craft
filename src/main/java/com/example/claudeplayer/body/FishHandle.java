package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.Fishing;
import com.example.claudeplayer.core.work.GatherRule;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * fish: holds a fishing rod, faces water within 8 blocks and casts; Fishing (core) decides when to wait, reel in,
 * collect the loot and recast, this class does the casting and reeling through the rod's normal use, sees the bite on
 * the hook, and counts what lands in the inventory. The body stands still. Ends on count, time, danger, a broken rod
 * or a hook that won't land in water. Fails missing_rod and no_water at the start; the hook is never cast near a living
 * thing and one that catches an entity is dropped, not reeled (that would pull it).
 */
final class FishHandle implements ActionRunner.Handle {
    private static final double WATER_RANGE = 8.0;
    /** The cast must clear everyone this close to its path. */
    private static final double PATH_CLEARANCE = 1.5;
    /** Water this close to the eyes is too close to cast at sensibly when a farther spot is free. */
    private static final double MIN_AIM = 2.0;
    /** Hooks reeled in after landing out of water, in a row, before deciding the water can't be reached. */
    private static final int DRY_REEL_LIMIT = 4;
    /** The first entity-data ids belong to Entity itself (flags, air, name, silent, no gravity, pose, frozen); the hook's own start here. */
    private static final int HOOK_DATA_START = 8;

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final Fishing fishing;
    private final int seconds;
    private final Set<BlockPos> bad = new HashSet<>();
    private @Nullable BlockPos target;
    private Map<String, Integer> before = Map.of();
    private int dryReels;

    private FishHandle(AgentRuntime rt, AgentPlayer body, Action.Fish a) {
        this.rt = rt;
        this.body = body;
        this.fishing = new Fishing(a.count(), a.seconds());
        this.seconds = a.seconds();
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Fish a) {
        int rod = Hands.find(p.getInventory(), "fishing_rod");
        if (rod < 0) return ActionRunner.Handle.done(ActionResult.failed("missing_rod"));
        FishHandle handle = new FishHandle(rt, p, a);
        if (handle.pickTarget() == null) return ActionRunner.Handle.done(ActionResult.failed("no_water"));
        Hands.hold(p.getInventory(), rod);
        return handle;
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) {
            cancel();
            return ActionResult.failed("died");
        }
        if (fishing.stop() == null && danger()) fishing.abort("danger");
        if (fishing.stop() != null) return end();
        FishingHook hook = hook();
        if (hook != null && hook.getHookedIn() != null) { // caught a creature: let go without pulling it
            hook.discard();
            hook = null;
        }
        boolean out = hook != null;
        switch (fishing.tick(out, out && hook.isInWater(), out && biting(hook))) {
            case CAST -> cast();
            case REEL -> reel(hook);
            case HARVEST -> fishing.landed(gained());
            case NONE -> { }
        }
        return fishing.stop() == null ? null : end();
    }

    private boolean danger() {
        List<?> near = SnapshotBuilder.hostiles(body, GatherRule.DANGER_RANGE);
        return !near.isEmpty() || body.getHealth() / body.getMaxHealth() < GatherRule.LOW_HEALTH;
    }

    /** The body's own hook, if one is out. */
    private @Nullable FishingHook hook() {
        FishingHook hook = body.fishing;
        return hook != null && !hook.isRemoved() ? hook : null;
    }

    /**
     * Whether a fish is on the hook. The flag that says so (FishingHook.biting) is private and the vanilla dip in the
     * hook's motion is only applied client side, so this reads the synced entity data through its public API: the hook's
     * own data (ids from HOOK_DATA_START) holds one boolean, DATA_BITING, and it is non-default only while a fish bites.
     */
    private static boolean biting(FishingHook hook) {
        List<SynchedEntityData.DataValue<?>> values = hook.getEntityData().getNonDefaultValues();
        if (values == null) return false;
        for (SynchedEntityData.DataValue<?> v : values) {
            if (v.id() >= HOOK_DATA_START && Boolean.TRUE.equals(v.value())) return true;
        }
        return false;
    }

    /** Holds the rod, faces the water and casts. Answers the machine with {@code declined} when nothing can be cast right now. */
    private void cast() {
        int rod = Hands.find(body.getInventory(), "fishing_rod");
        if (rod < 0) {
            fishing.abort("no_rod");
            return;
        }
        Hands.hold(body.getInventory(), rod);
        BlockPos water = target != null && usable(body.serverLevel(), target) ? target : pickTarget();
        if (water == null) {
            fishing.abort("no_water");
            return;
        }
        Vec3 eye = body.getEyePosition();
        Vec3 aim = new Vec3(water.getX() + 0.5, water.getY() + 0.9, water.getZ() + 0.5);
        if (occupied(eye, aim)) {
            fishing.declined();
            return;
        }
        body.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
        float pitch = Fishing.castPitch(Math.hypot(aim.x - eye.x, aim.z - eye.z), eye.y - aim.y);
        if (!Float.isNaN(pitch)) body.setXRot(pitch);
        use();
    }

    private void reel(@Nullable FishingHook hook) {
        if (hook == null) return;
        boolean wet = hook.isInWater();
        before = Hands.counts(body.getInventory());
        int rod = Hands.find(body.getInventory(), "fishing_rod");
        if (rod < 0) {
            hook.discard();
            fishing.abort("no_rod");
            return;
        }
        Hands.hold(body.getInventory(), rod);
        use();
        if (fishing.collecting()) {
            dryReels = 0;
        } else if (!wet) { // given up on a hook that never reached water: aim somewhere else
            if (target != null) bad.add(target);
            target = null;
            if (++dryReels >= DRY_REEL_LIMIT) fishing.abort("no_water");
        } else {
            dryReels = 0;
        }
    }

    /** The rod's normal use: casts when no hook is out, reels in when one is. */
    private void use() {
        ItemStack rod = body.getMainHandItem();
        body.gameMode.useItem(body, body.level(), rod, InteractionHand.MAIN_HAND);
        body.swing(InteractionHand.MAIN_HAND, true);
    }

    /** Items that arrived in the inventory since the reel. */
    private Map<String, Integer> gained() {
        Map<String, Integer> now = Hands.counts(body.getInventory());
        Map<String, Integer> gained = new LinkedHashMap<>();
        now.forEach((id, n) -> {
            int more = n - before.getOrDefault(id, 0);
            if (more > 0) gained.put(id, more);
        });
        return gained;
    }

    /**
     * A living thing near the path of the cast, whose hook it could catch. The pond's own animals (fish, squid, dolphins:
     * WaterAnimal; axolotls extend Animal, not WaterAnimal) don't count, or no cast would ever be made; a snagged one is
     * dropped by poll. Players and land creatures do.
     */
    private boolean occupied(Vec3 from, Vec3 to) {
        AABB box = new AABB(from, to).inflate(PATH_CLEARANCE);
        return !body.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != body && e.isAlive() && !(e instanceof WaterAnimal) && !(e instanceof Axolotl)).isEmpty();
    }

    /** The nearest water the hook can land in, clear to see from the eyes, and not one that already failed; also stored as the target. */
    private @Nullable BlockPos pickTarget() {
        ServerLevel level = body.serverLevel();
        Vec3 eye = body.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        int r = (int) Math.ceil(WATER_RANGE);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            double dist = Vec3.atCenterOf(pos).distanceTo(eye);
            if (dist > WATER_RANGE || bad.contains(pos) || !level.isLoaded(pos) || !usable(level, pos)) continue;
            double score = dist < MIN_AIM ? dist + 100 : dist; // a spot a couple of blocks out beats one underfoot
            if (score >= bestScore) continue;
            Vec3 aim = new Vec3(pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5);
            if (!seen(level, eye, aim)) continue;
            best = pos.immutable();
            bestScore = score;
        }
        target = best;
        return best;
    }

    /** A water source with nothing solid or flowing over it, so the hook can float there. */
    private static boolean usable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().is(FluidTags.WATER) || !state.getFluidState().isSource()) return false;
        BlockPos above = pos.above();
        BlockState over = level.getBlockState(above);
        return over.getCollisionShape(level, above).isEmpty() && over.getFluidState().isEmpty();
    }

    /** Nothing solid between the eyes and the water surface point (the water itself doesn't count). */
    private boolean seen(ServerLevel level, Vec3 eye, Vec3 aim) {
        HitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(aim) < 1.0;
    }

    private ActionResult end() {
        cancel();
        String stop = fishing.stop();
        String summary = fishing.summary();
        if ("danger".equals(stop)) return ActionResult.failed("danger", summary);
        if (fishing.catches() == 0) {
            if ("no_water".equals(stop)) return ActionResult.failed("no_water", summary);
            if ("no_rod".equals(stop)) return ActionResult.failed("missing_rod", summary);
        }
        return ActionResult.ok(summary);
    }

    /** Lets go of the hook (no pull, no loot) and leaves the body still. */
    @Override
    public void cancel() {
        FishingHook hook = hook();
        if (hook != null) hook.discard();
        rt.navigator().stop(body);
    }

    /** Its own time limit ends the run first; this adds room for a last bite to be played out and collected. */
    @Override
    public int timeoutTicks() {
        return seconds * 20 + Fishing.COLLECT_TICKS + Fishing.RECAST_DELAY_TICKS + 100;
    }
}
