package com.example.claudeplayer.core.nav;

/**
 * follow_player's pure rules: how far behind the target to stay, and when the path being walked has gone stale.
 * No game types, so the thresholds are tested without a world.
 */
public final class FollowGap {
    /** Walk when the gap grows past this many blocks. */
    public static final double START = 3.5;
    /** Stand still when the gap shrinks under this many blocks. */
    public static final double STOP = 2.0;
    /** A path aimed at a spot further than this from where the target is now is stale. */
    public static final double MOVED = 3.0;
    /** Fewest ticks between two searches while a path exists, so a sprinting target cannot cost one per tick. */
    public static final int MIN_TICKS = 10;
    /** Ticks between searches while there is no path (no route right now). */
    public static final int RETRY_TICKS = 20;
    /** A path this old is replaced even if the target stood still, so a stale path cannot trap the body. */
    public static final int FALLBACK_TICKS = 40;
    /** "Never planned": a search is due at once. */
    public static final int NEVER = Integer.MAX_VALUE / 2;

    private FollowGap() {}

    /**
     * Hysteresis: a standing body starts only past {@link #START}, a walking one stops only under {@link #STOP}, and
     * in between it keeps doing what it was doing. Returns the new state.
     */
    public static boolean walking(boolean wasWalking, double gap) {
        if (wasWalking) return gap >= STOP;
        return gap > START;
    }

    /**
     * Whether to search for a new path now. Without a path: once {@link #RETRY_TICKS} have passed since the last
     * search. With one: never inside {@link #MIN_TICKS}, then when the target is more than {@link #MOVED} blocks from
     * where the path was aimed, or when the path is {@link #FALLBACK_TICKS} old.
     */
    public static boolean replan(boolean hasPath, int ticksSincePlan, double aimX, double aimY, double aimZ,
                                 double targetX, double targetY, double targetZ) {
        if (!hasPath) return ticksSincePlan >= RETRY_TICKS;
        if (ticksSincePlan >= FALLBACK_TICKS) return true;
        if (ticksSincePlan < MIN_TICKS) return false;
        double dx = targetX - aimX;
        double dy = targetY - aimY;
        double dz = targetZ - aimZ;
        return dx * dx + dy * dy + dz * dz > MOVED * MOVED;
    }
}
