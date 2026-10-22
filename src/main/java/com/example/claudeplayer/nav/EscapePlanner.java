package com.example.claudeplayer.nav;

import com.example.claudeplayer.core.Settings;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Answers "is there a way out?" for the caution policy: a path of at most escape_max_steps to a spot at least
 * escape_distance from every threat. Probes eight directions with the same planner movement uses.
 */
public final class EscapePlanner {
    private static final int DIRECTIONS = 8;

    private final Navigator nav;
    private final Settings settings;

    public EscapePlanner(Navigator nav, Settings settings) {
        this.nav = nav;
        this.settings = settings;
    }

    public boolean escapeExists(ServerPlayer body, List<? extends Entity> threats) {
        if (threats.isEmpty()) return true;
        double reach = settings.escapeDistance() + 2;
        for (int i = 0; i < DIRECTIONS; i++) {
            double angle = 2 * Math.PI * i / DIRECTIONS;
            Vec3 spot = body.position().add(Math.cos(angle) * reach, 0, Math.sin(angle) * reach);
            if (threats.stream().anyMatch(t -> t.position().distanceTo(spot) < settings.escapeDistance())) continue;
            Path path = nav.findPath(body, BlockPos.containing(spot), 2);
            if (path != null && path.canReach() && path.getNodeCount() <= settings.escapeMaxSteps()) return true;
        }
        return false;
    }
}
