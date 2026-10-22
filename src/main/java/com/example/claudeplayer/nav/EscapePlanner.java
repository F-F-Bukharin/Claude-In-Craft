package com.example.claudeplayer.nav;

import com.example.claudeplayer.core.Settings;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Answers "is there a way out?" for the caution policy, and "where to?" for flee: a path of at most
 * escape_max_steps to a spot at least escape_distance from every threat. Probes eight directions with the same
 * planner movement uses, starting straight away from the nearest threat and turning toward it last.
 */
public final class EscapePlanner {
    private static final int[] TURNS = {0, 1, -1, 2, -2, 3, -3, 4}; // in eighths of a circle

    private final Navigator nav;
    private final Settings settings;

    public EscapePlanner(Navigator nav, Settings settings) {
        this.nav = nav;
        this.settings = settings;
    }

    public boolean escapeExists(ServerPlayer body, List<? extends Entity> threats) {
        return threats.isEmpty() || destination(body, threats) != null;
    }

    /** The first reachable escape spot (the end of its path), or null when every direction is blocked or watched. */
    public @Nullable Vec3 destination(ServerPlayer body, List<? extends Entity> threats) {
        if (threats.isEmpty()) return null;
        Entity nearest = threats.stream().min(Comparator.comparingDouble(t -> horizontalSqr(body, t))).orElseThrow();
        double away = Math.atan2(body.getZ() - nearest.getZ(), body.getX() - nearest.getX());
        double reach = settings.escapeDistance() + 2;
        for (int turn : TURNS) {
            double angle = away + Math.PI / 4 * turn;
            Vec3 spot = body.position().add(Math.cos(angle) * reach, 0, Math.sin(angle) * reach);
            if (threats.stream().anyMatch(t -> t.position().distanceTo(spot) < settings.escapeDistance())) continue;
            Path path = nav.findPath(body, BlockPos.containing(spot), 2);
            if (path != null && path.canReach() && path.getNodeCount() <= settings.escapeMaxSteps()) {
                return MoveHandle.center(path.getEndNode());
            }
        }
        return null;
    }

    private static double horizontalSqr(Entity a, Entity b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        return dx * dx + dz * dz;
    }
}
