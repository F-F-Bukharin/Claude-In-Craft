package com.example.claudeplayer.core.nav;

/**
 * Stuck detection for a walk, in plain numbers. Each tick the walker reports the distance to the node it is heading
 * for and that node's index; this class says what to do about a stall: nothing, jump (about 1 s without progress),
 * re-plan (about 2 s), or give up (a stall that outlasts three re-plans). Progress is the node index going up, or the
 * distance dropping by more than {@link #MIN_GAIN} below the best seen for that node. Progress clears every counter.
 */
public final class Progress {
    public enum Decision { NONE, JUMP, REPLAN, GIVE_UP }

    /** Ticks without progress before a jump. */
    public static final int JUMP_AFTER = 20;
    /** Ticks without progress before a re-plan. */
    public static final int REPLAN_AFTER = 40;
    /** Re-plans without progress in between before the next stall gives up. */
    public static final int MAX_REPLANS = 3;
    /** Blocks the distance must shrink by to count as progress (jitter and bumping don't). */
    public static final double MIN_GAIN = 0.05;

    private int lastProgressTick;
    private int index = Integer.MIN_VALUE;
    private double best = Double.POSITIVE_INFINITY;
    private boolean jumped;
    private boolean rebase = true;
    private int replans;

    public Progress(int startTick) {
        this.lastProgressTick = startTick;
    }

    /** One tick: where the walker is relative to the node it is heading for. */
    public Decision update(int tick, double distance, int nodeIndex) {
        if (rebase || nodeIndex < index) { // the first look, or a fresh path: a new baseline, not progress
            rebase = false;
            index = nodeIndex;
            best = distance;
        } else if (nodeIndex > index) {
            return progressed(tick, nodeIndex, distance);
        } else if (distance < best - MIN_GAIN) {
            return progressed(tick, nodeIndex, distance);
        }
        int stalled = tick - lastProgressTick;
        if (stalled >= REPLAN_AFTER) {
            if (replans >= MAX_REPLANS) return Decision.GIVE_UP;
            replans++;
            lastProgressTick = tick;
            jumped = false;
            rebase = true; // the new path restarts its node numbering
            return Decision.REPLAN;
        }
        if (stalled >= JUMP_AFTER && !jumped) {
            jumped = true;
            return Decision.JUMP;
        }
        return Decision.NONE;
    }

    private Decision progressed(int tick, int nodeIndex, double distance) {
        lastProgressTick = tick;
        index = nodeIndex;
        best = distance;
        jumped = false;
        replans = 0;
        return Decision.NONE;
    }

    /** Forget everything: the walker is deliberately standing still (rising for air, waiting on a door). */
    public void reset(int tick) {
        lastProgressTick = tick;
        index = Integer.MIN_VALUE;
        best = Double.POSITIVE_INFINITY;
        jumped = false;
        rebase = true;
        replans = 0;
    }

    /** Re-plans done since the last progress. */
    public int replans() {
        return replans;
    }
}
