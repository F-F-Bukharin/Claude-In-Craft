package com.example.claudeplayer.nav;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.nav.EscapeScore;
import com.example.claudeplayer.core.nav.EscapeScore.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Answers "is there a way out?" for the caution policy, and "where to?" for flee: a path of at most
 * escape_max_steps to a spot at least escape_distance from EVERY threat. Candidates sit on a ring around the body,
 * aimed away from the whole crowd (not only the nearest threat) and ordered by {@link EscapeScore}; the best few are
 * path-checked with the same planner movement uses.
 *
 * <p>Cost: at most {@link #MAX_SEARCHES} path searches per answer, and the answer is remembered for
 * {@link #CACHE_TICKS} server ticks while the body stays in its block and no threat changes block, so the several
 * callers of one scan (one per mob) share a single computation.
 */
public final class EscapePlanner {
    static final int MAX_SEARCHES = 4;
    static final int CACHE_TICKS = 4;
    private static final int RING_POINTS = 12;

    private final Navigator nav;
    private final Settings settings;

    private long[] cachedKey;
    private long cachedAt;
    private @Nullable Vec3 cachedAnswer;

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
        long now = body.level().getGameTime();
        long[] key = key(body, threats);
        if (cachedKey != null && Arrays.equals(cachedKey, key) && now >= cachedAt && now - cachedAt <= CACHE_TICKS) {
            return cachedAnswer;
        }
        Vec3 answer = search(body, threats);
        cachedKey = key;
        cachedAt = now;
        cachedAnswer = answer;
        return answer;
    }

    private @Nullable Vec3 search(ServerPlayer body, List<? extends Entity> threats) {
        double escape = settings.escapeDistance();
        List<Point> crowd = new ArrayList<>(threats.size());
        for (Entity t : threats) crowd.add(new Point(t.getX(), t.getZ()));
        double away = EscapeScore.awayAngle(body.getX(), body.getZ(), crowd);
        List<Point> ring = EscapeScore.ring(body.getX(), body.getZ(), away, escape + 2, RING_POINTS);
        List<Point> ordered = EscapeScore.order(body.getX(), body.getZ(), crowd, ring, escape);
        int searches = 0;
        for (Point spot : ordered) {
            if (searches++ >= MAX_SEARCHES) break;
            Path path = nav.findPath(body, BlockPos.containing(spot.x(), body.getY(), spot.z()), 2);
            if (path == null || !path.canReach() || path.getNodeCount() > settings.escapeMaxSteps()) continue;
            Node end = path.getEndNode();
            if (end == null) continue;
            Vec3 where = Navigator.center(end); // the end can differ from the aimed spot: check where it really is
            if (EscapeScore.clear(where.x, where.z, crowd, escape)) return where;
        }
        return null;
    }

    /** Body block, dimension, and each threat's id and block column: a change in any of them is a new question. */
    private static long[] key(ServerPlayer body, List<? extends Entity> threats) {
        long[] key = new long[2 + threats.size() * 2];
        key[0] = body.blockPosition().asLong();
        key[1] = System.identityHashCode(body.level());
        int i = 2;
        for (Entity t : threats) {
            key[i++] = t.getId();
            key[i++] = BlockPos.asLong(t.getBlockX(), 0, t.getBlockZ());
        }
        return key;
    }
}
