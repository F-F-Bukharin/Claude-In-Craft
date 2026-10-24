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
 * see: a fan of rays from the eyes (11 across 70 degrees, 5 down 40 degrees, 32 blocks) plus the living things in
 * that cone with a clear line of sight. This only collects what was hit; Sight words it. Server thread only.
 */
final class SeeActions {
    private static final int ACROSS = 11;
    private static final int DOWN = 5;
    private static final double HALF_WIDTH = 35;
    private static final double HALF_HEIGHT = 20;
    private static final double RANGE = 32;

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
            for (int j = 0; j < DOWN; j++) {
                double down = Math.tan(Math.toRadians(-HALF_HEIGHT + 2 * HALF_HEIGHT * j / (DOWN - 1)));
                Vec3 ray = forward.add(right.scale(across)).add(up.scale(down)).normalize();
                Sight.Hit hit = cast(level, p, eye, ray);
                if (hit != null) hits.add(hit);
            }
        }

        List<Sight.Seen> seen = new ArrayList<>();
        AABB box = new AABB(eye, eye).inflate(RANGE);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, x -> x != p && x.isAlive())) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double ahead = to.dot(forward);
            if (ahead <= 0 || to.length() > RANGE) continue;
            if (Math.abs(Math.toDegrees(Math.atan2(to.dot(right), ahead))) > HALF_WIDTH) continue;
            if (Math.abs(Math.toDegrees(Math.atan2(to.dot(up), ahead))) > HALF_HEIGHT) continue;
            if (!p.hasLineOfSight(e)) continue;
            BlockPos at = e.blockPosition();
            seen.add(new Sight.Seen(SnapshotBuilder.typeId(e), e.getId(), e instanceof Player pl ? pl.getGameProfile().getName() : null,
                    to.length(), at.getX(), at.getY(), at.getZ()));
        }
        String facing = direction.equals("up") || direction.equals("down") ? direction : Direction.fromYRot(p.getYRot()).getName();
        return ActionResult.ok(Sight.format(facing, hits, seen));
    }

    /** One ray. Outline shapes, so crops, signs and flowers show too; stops short of a chunk that isn't loaded (never loads one). */
    private static Sight.Hit cast(ServerLevel level, ServerPlayer p, Vec3 eye, Vec3 ray) {
        double reach = RANGE;
        for (double d = 0; d <= RANGE; d += 4) {
            if (!level.hasChunkAt(BlockPos.containing(eye.add(ray.scale(d))))) {
                reach = Math.max(0, d - 4);
                break;
            }
        }
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
