package com.example.claudeplayer.nav;

import com.example.claudeplayer.core.act.ActionRunner;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Walks the body. Players have no pathfinder, so a zombie that is never added to the world plans the route with
 * vanilla ground navigation, and the body follows it by steering: face the next node, walk forward, jump when the
 * node is higher or something blocks the way. The movement itself is ordinary player physics.
 */
public final class Navigator {
    private Zombie planner;
    private ActionRunner.Handle active;

    public ActionRunner.Handle moveTo(ServerPlayer body, Vec3 target) {
        stop(body);
        active = new MoveHandle(this, body, target);
        return active;
    }

    public ActionRunner.Handle follow(ServerPlayer body, ServerPlayer target, int ticks) {
        stop(body);
        active = new FollowHandle(this, body, target, ticks);
        return active;
    }

    /** Stops any movement in progress and leaves the body standing still. */
    public void stop(@Nullable ServerPlayer body) {
        ActionRunner.Handle running = active;
        active = null;
        if (running != null) running.cancel();
        if (body != null) halt(body);
    }

    /** A ground path from the body toward {@code target}, or null. */
    public @Nullable Path findPath(ServerPlayer body, BlockPos target, int accuracy) {
        Zombie z = planner(body.serverLevel());
        z.moveTo(body.getX(), body.getY(), body.getZ(), body.getYRot(), 0f);
        z.setOnGround(true);
        return z.getNavigation().createPath(target, accuracy);
    }

    private Zombie planner(ServerLevel level) {
        if (planner == null || planner.level() != level) {
            planner = EntityType.ZOMBIE.create(level);
            planner.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64); // how far a single path may reach
        }
        return planner;
    }

    /** One tick of steering toward {@code point}. */
    public static void steer(ServerPlayer body, Vec3 point) {
        double dx = point.x - body.getX();
        double dz = point.z - body.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        body.setYRot(yaw);
        body.setYHeadRot(yaw);
        body.yBodyRot = yaw;
        body.setXRot(0f); // eyes level while walking, whatever the last lookAt left behind
        body.zza = 1f;
        boolean climb = point.y > body.getY() + 0.5 || body.horizontalCollision;
        body.setJumping(climb && (body.onGround() || body.isInWater()));
    }

    public static void halt(ServerPlayer body) {
        body.zza = 0f;
        body.xxa = 0f;
        body.setJumping(false);
        body.setSprinting(false);
    }
}
