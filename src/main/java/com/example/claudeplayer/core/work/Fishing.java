package com.example.claudeplayer.core.work;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * fish's timing and bookkeeping, one tick at a time. The game side tells it what it sees (is a hook out, is it in
 * water, is a fish biting) and does what it answers: cast, reel in, or count what arrived. Cycle: cast, let the hook
 * settle, watch for a bite, reel in a moment after it, wait for the loot to fly in, count it, recast. A cast that
 * never bites is given up and recast.
 */
public final class Fishing {
    /** What the body should do this tick. */
    public enum Step { NONE, CAST, REEL, HARVEST }

    private enum Phase { READY, CASTING, BITE, COLLECT }

    /** The hook flies and splashes down first; a bite seen before this many ticks is not acted on (and none can come that early). */
    public static final int SETTLE_TICKS = 20;
    /** From noticing the bite to reeling in: 0.2 s. A vanilla bite lasts 20 to 40 ticks, so this is well inside it. */
    public static final int REEL_DELAY_TICKS = 4;
    /** After a reel that followed a bite, the time for the loot to fly to the body and be picked up (1.5 s). */
    public static final int COLLECT_TICKS = 30;
    /** Before the next cast, after a reel, a miss or a lost hook (0.5 s). */
    public static final int RECAST_DELAY_TICKS = 10;
    /**
     * A cast with no bite is reeled in and recast after this long (35 s). Vanilla waits 100 to 600 ticks for a fish to
     * be lured and 20 to 80 more for it to bite, so 680 is the longest a cast can take without a Lure enchantment;
     * giving up sooner would throw away casts that were about to bite, and a recast restarts the wait.
     */
    public static final int CAST_LIMIT_TICKS = 700;
    /** A hook still out of water this long after settling is on land or in a block: reel it in (1.5 s). */
    public static final int DRY_LIMIT_TICKS = 30;
    /** This many casts in a row whose hook vanished at once (rod broken, nowhere to land): stop with cannot_cast. */
    public static final int LOST_LIMIT = 5;

    private final int count;
    private final int limit;
    private final Map<String, Integer> caught = new LinkedHashMap<>();
    private Phase phase = Phase.READY;
    private int phaseTicks;
    private int wait; // READY: ticks before the next cast
    private int dry;
    private int lost;
    private int elapsed;
    private int casts;
    private String stop;

    /** {@code count} catches wanted (1 to 16) within {@code seconds} (10 to 120). */
    public Fishing(int count, int seconds) {
        this.count = count;
        this.limit = seconds * 20;
    }

    public int limitTicks() {
        return limit;
    }

    /**
     * One tick. {@code hookOut}: a hook of ours is in the world; {@code inWater}: it floats in water; {@code biting}: a
     * fish is on it. The time limit ends a run while waiting, but never in the middle of a bite or a collect.
     */
    public Step tick(boolean hookOut, boolean inWater, boolean biting) {
        if (stop != null) return Step.NONE;
        elapsed++;
        switch (phase) {
            case READY -> {
                if (elapsed >= limit) {
                    stop = "timeout";
                    return Step.NONE;
                }
                if (wait > 0) {
                    wait--;
                    return Step.NONE;
                }
                phase = Phase.CASTING;
                phaseTicks = 0;
                dry = 0;
                casts++;
                return Step.CAST;
            }
            case CASTING -> {
                phaseTicks++;
                if (!hookOut) return hookGone();
                lost = 0;
                if (phaseTicks > SETTLE_TICKS && biting) {
                    phase = Phase.BITE;
                    phaseTicks = 0;
                    return Step.NONE;
                }
                if (elapsed >= limit) {
                    stop = "timeout";
                    return Step.NONE;
                }
                if (phaseTicks <= SETTLE_TICKS) return Step.NONE;
                dry = inWater ? 0 : dry + 1;
                if (dry >= DRY_LIMIT_TICKS || phaseTicks >= CAST_LIMIT_TICKS) {
                    ready();
                    return Step.REEL; // a miss: nothing to collect
                }
                return Step.NONE;
            }
            case BITE -> {
                if (!hookOut) return hookGone();
                if (++phaseTicks < REEL_DELAY_TICKS) return Step.NONE;
                phase = Phase.COLLECT;
                phaseTicks = 0;
                return Step.REEL;
            }
            case COLLECT -> {
                if (++phaseTicks < COLLECT_TICKS) return Step.NONE;
                ready();
                return Step.HARVEST;
            }
        }
        return Step.NONE;
    }

    private Step hookGone() {
        if (++lost >= LOST_LIMIT) stop = "cannot_cast";
        else ready();
        return Step.NONE;
    }

    private void ready() {
        phase = Phase.READY;
        wait = RECAST_DELAY_TICKS;
    }

    /** Answers {@link Step#CAST} when the body could not cast after all (somebody in the way): not a cast, try again soon. */
    public void declined() {
        if (phase != Phase.CASTING) return;
        casts--;
        ready();
    }

    /** True after a reel that followed a bite: the loot is on its way and {@link Step#HARVEST} comes when the wait is over. */
    public boolean collecting() {
        return phase == Phase.COLLECT;
    }

    /**
     * The pitch in degrees (Minecraft's xRot: positive looks down) at which a cast lands near water {@code horizontal}
     * blocks away and {@code below} blocks under the eyes, by the vanilla hook's own physics: launched at
     * 0.6 * cos(pitch) + 0.5 per tick along the look direction, gravity 0.03, drag 0.92. NaN when no pitch lands within
     * 1.5 blocks of it (too far, or the water is above the eyes). Block collisions on the way are not modeled.
     */
    public static float castPitch(double horizontal, double below) {
        if (below <= 0) return Float.NaN;
        float best = Float.NaN;
        double bestError = 1.5;
        for (int pitch = -40; pitch <= 85; pitch++) {
            double error = Math.abs(castLanding(horizontal, below, pitch) - horizontal);
            if (error < bestError || error == bestError && !Float.isNaN(best) && Math.abs(pitch) < Math.abs(best)) {
                bestError = error;
                best = pitch;
            }
        }
        return best;
    }

    /** How far from the body a hook cast at this pitch first reaches a surface {@code below} blocks under the eyes; infinity if never. */
    public static double castLanding(double horizontal, double below, float pitch) {
        double rad = Math.toRadians(pitch);
        double speed = 0.6 * Math.cos(rad) + 0.5;
        double vx = speed;
        double vy = -Math.tan(rad) * speed;
        double x = 0;
        double y = 0;
        for (int t = 0; t < 200; t++) {
            vy -= 0.03;
            x += vx;
            y += vy;
            vx *= 0.92;
            vy *= 0.92;
            if (y <= -below) return x;
        }
        return Double.POSITIVE_INFINITY;
    }

    /** What arrived since the reel (item id to count), answering {@link Step#HARVEST}. Ends the run when the count is reached. */
    public void landed(Map<String, Integer> items) {
        items.forEach((id, n) -> {
            if (n > 0) caught.merge(id, n, Integer::sum);
        });
        if (stop == null && catches() >= count) stop = "done";
    }

    /** Ends the run for this reason (danger, no_rod...); the first reason given wins. */
    public void abort(String reason) {
        if (stop == null) stop = reason;
    }

    /** done, timeout, cannot_cast or whatever was passed to {@link #abort}; null while running. */
    public String stop() {
        return stop;
    }

    /** Items caught so far. */
    public int catches() {
        return caught.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int casts() {
        return casts;
    }

    /** "caught 3: cod x2, salmon x1 (5 casts)", or "caught nothing (4 casts)"; a stop other than done adds "; stopped: timeout". */
    public String summary() {
        String items = caught.isEmpty() ? "nothing" : catches() + ": " + caught.entrySet().stream()
                .map(e -> e.getKey() + " x" + e.getValue()).collect(Collectors.joining(", "));
        String tail = stop == null || stop.equals("done") ? "" : "; stopped: " + stop;
        return "caught " + items + " (" + casts + (casts == 1 ? " cast" : " casts") + ")" + tail;
    }
}
