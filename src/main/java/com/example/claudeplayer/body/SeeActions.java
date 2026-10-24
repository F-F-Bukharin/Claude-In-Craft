package com.example.claudeplayer.body;

import com.example.claudeplayer.core.Sight;
import com.example.claudeplayer.core.act.ActionResult;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * see: a fan of rays from the eyes (21 across 70 degrees, 9 down 40 degrees, 96 blocks: 189 rays) plus the living things
 * in that cone with a clear line of sight. This only collects what was hit; Sight words it. Server thread only.
 *
 * <p>Cost: each ray is one {@code level.clip}, which walks about one block per step, so a ray that hits nothing is up to
 * ~100-200 block lookups and the fan is at most a few tens of thousands, once per see call (not measured in game). The
 * rows are packed toward the horizon: flat ground at 96 blocks sits only about 1 degree below the eye line, so evenly
 * spaced rows would reach it with just one ray. Ray spacing at 96 blocks is about 6 blocks across, and 4 to 10 blocks
 * between rows from -5 degrees upward (wider below, where the ground is hit much nearer).
 */
final class SeeActions {
    private static final int ACROSS = 21;
    /** Pitch of each row in degrees from the look direction, up positive; all inside the 40 degree cone. Dense near and just below the horizon. */
    private static final double[] ROW_PITCH = {-20, -10, -5, -2.5, 0, 3, 8, 14, 20};
    private static final double HALF_WIDTH = 35;
    private static final double HALF_HEIGHT = 20;
    private static final double RANGE = 96;

    private SeeActions() {}

    static ActionResult see(ServerPlayer p, String direction) {
        turn(p, direction);
        ServerLevel level = p.serverLevel();
        Vec3 eye = p.getEyePosition();
        Vec3 forward = p.getLookAngle();
        double yaw = Math.toRadians(p.getYRot());
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)); // to the viewer's right, whatever the pitch
        Vec3 up = right.cross(forward);

        List<Sight.Hit> hits = new ArrayList<>();
        for (int i = 0; i < ACROSS; i++) {
            double across = Math.tan(Math.toRadians(-HALF_WIDTH + 2 * HALF_WIDTH * i / (ACROSS - 1)));
            for (double pitch : ROW_PITCH) {
                double upward = Math.tan(Math.toRadians(pitch));
                Vec3 ray = forward.add(right.scale(across)).add(up.scale(upward)).normalize();
                Sight.Hit hit = cast(level, p, eye, ray);
                if (hit != null) hits.add(hit);
            }
        }

        List<Sight.Seen> seen = new ArrayList<>();
        AABB box = new AABB(eye, eye).inflate(RANGE);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, x -> x != p && x.isAlive())) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double length = to.length();
            double ahead = to.dot(forward);
            if (ahead <= 0 || length > RANGE) continue;
            if (Math.abs(Math.toDegrees(Math.atan2(to.dot(right), ahead))) > HALF_WIDTH) continue;
            if (Math.abs(Math.toDegrees(Math.atan2(to.dot(up), ahead))) > HALF_HEIGHT) continue;
            // hasLineOfSight clips through the blocks in between: never let that load a chunk on the way
            if (loadedReach(level, eye, to.scale(1 / length), length) < length) continue;
            if (!p.hasLineOfSight(e)) continue;
            BlockPos at = e.blockPosition();
            seen.add(new Sight.Seen(SnapshotBuilder.typeId(e), e.getId(), e instanceof Player pl ? pl.getGameProfile().getName() : null,
                    length, at.getX(), at.getY(), at.getZ()));
        }
        String facing = direction.equals("up") || direction.equals("down") ? direction : Direction.fromYRot(p.getYRot()).getName();
        return ActionResult.ok(Sight.format(facing, eye.x, eye.y, eye.z, hits, seen));
    }

    /**
     * How far along the ray (up to {@code max}) every chunk it passes is loaded, sampled each block. Sight stops one block
     * short of the first unloaded chunk it finds, so the clip never asks for one (a chunk corner cut in under a block can slip through).
     */
    private static double loadedReach(ServerLevel level, Vec3 eye, Vec3 ray, double max) {
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (double d = 0; d <= max; d += 1) {
            BlockPos at = BlockPos.containing(eye.add(ray.scale(d)));
            int cx = at.getX() >> 4;
            int cz = at.getZ() >> 4;
            if (cx == lastX && cz == lastZ) continue; // same chunk as the last sample
            if (!level.hasChunkAt(at)) return Math.max(0, d - 1);
            lastX = cx;
            lastZ = cz;
        }
        return max;
    }

    /** One ray. Outline shapes, so crops, signs and flowers show too; stops short of a chunk that isn't loaded (never loads one). */
    private static Sight.Hit cast(ServerLevel level, ServerPlayer p, Vec3 eye, Vec3 ray) {
        double reach = loadedReach(level, eye, ray, RANGE);
        BlockHitResult r = level.clip(new ClipContext(eye, eye.add(ray.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
        if (r.getType() == HitResult.Type.MISS) return null;
        BlockPos pos = r.getBlockPos();
        BlockState state = level.getBlockState(pos);
        String id = SnapshotBuilder.blockId(state);
        boolean container = state.hasBlockEntity() && level.getBlockEntity(pos) instanceof Container;
        return new Sight.Hit(FarmActions.ripe(state) ? "ripe_" + id : id, pos.getX(), pos.getY(), pos.getZ(), r.getLocation().distanceTo(eye), container);
    }

    /** Faces a compass direction (head and body, pitch level), or up or down (head tilt only); forward turns nothing. */
    private static void turn(ServerPlayer p, String direction) {
        switch (direction) {
            case "up" -> p.setXRot(-90);
            case "down" -> p.setXRot(90);
            case "north", "south", "east", "west" -> {
                float yaw = switch (direction) {
                    case "north" -> 180f;
                    case "east" -> -90f;
                    case "west" -> 90f;
                    default -> 0f;
                };
                p.setYRot(yaw);
                p.setYHeadRot(yaw);
                p.setYBodyRot(yaw);
                p.setXRot(0);
            }
            default -> { }
        }
    }
}
