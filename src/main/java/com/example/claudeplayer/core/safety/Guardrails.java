package com.example.claudeplayer.core.safety;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.act.ActionResult;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** Hard limits on model calls and world edits: kill switch, call rate, token cap, which blocks may be broken. Server thread only. */
public final class Guardrails {
    /** HALTED and TOKEN_CAP end the order; RATE_LIMITED means wait and try again. */
    public enum Check { OK, HALTED, RATE_LIMITED, TOKEN_CAP }

    private static final long WINDOW_MILLIS = 60 * 60_000L;

    /** Tokens one reply cost, and when. */
    private record Spend(long at, long count) {}

    private final Settings settings;
    private final LongSupplier clockMillis;
    private final ArrayDeque<Long> recentCalls = new ArrayDeque<>();
    private final ArrayDeque<Spend> spend = new ArrayDeque<>();
    private int calls;
    private boolean halted;

    public Guardrails(Settings settings, LongSupplier clockMillis) {
        this.settings = settings;
        this.clockMillis = clockMillis;
    }

    public Check allowCall() {
        if (halted) return Check.HALTED;
        if (tokensUsed() >= settings.sessionTokenCap()) return Check.TOKEN_CAP;
        long now = clockMillis.getAsLong();
        while (!recentCalls.isEmpty() && now - recentCalls.peekFirst() >= 60_000) recentCalls.pollFirst();
        return recentCalls.size() >= settings.maxCallsPerMinute() ? Check.RATE_LIMITED : Check.OK;
    }

    public void recordCall() {
        recentCalls.addLast(clockMillis.getAsLong());
        calls++;
    }

    /**
     * Adds one reply's weighted tokens, {@code Brain.Usage.weighted()}: input, output and cache writes in full,
     * cache reads at a tenth (they cost about that). The token cap compares the last hour's sum with
     * session_token_cap; {@code Usage.total()} still counts everything, for the logs.
     */
    public void recordTokens(long count) {
        spend.addLast(new Spend(clockMillis.getAsLong(), count));
    }

    /** Clears the hour's tokens, so calls go on at once. Only /cp resetcap calls this. */
    public void resetTokens() {
        spend.clear();
    }

    /** Milliseconds until enough old tokens age out to get under the cap; 0 when under it. */
    public long capFreesInMillis() {
        long now = clockMillis.getAsLong();
        long sum = windowTotal(now);
        long cap = settings.sessionTokenCap();
        if (sum < cap) return 0;
        long needed = sum - cap + 1;
        long freed = 0;
        for (Spend s : spend) {
            freed += s.count();
            if (freed >= needed) return s.at() + WINDOW_MILLIS - now;
        }
        return WINDOW_MILLIS;
    }

    /**
     * The break rule: natural blocks anywhere, man-made ones only where Claude placed them itself, never air or an
     * unbreakable block. The game side supplies the facts; ok means go ahead.
     */
    public static ActionResult checkBreak(boolean empty, boolean unbreakable, boolean natural, boolean selfPlaced) {
        if (empty) return ActionResult.failed("no_block");
        if (unbreakable) return ActionResult.denied("unbreakable");
        if (!natural && !selfPlaced) return ActionResult.denied("protected_block");
        return ActionResult.ok();
    }

    /** Lava pours only this close to home (D4's work area, kept for lava alone). */
    public static final double LAVA_AREA = 32;
    /** Lava never goes this close to another player: "players are never attacked" covers lava too. */
    public static final double LAVA_PLAYER_GAP = 4;

    /**
     * The pour rule for use_bucket: never into the body's own feet or head block; water anywhere else in reach; lava
     * only within {@link #LAVA_AREA} of home and more than {@link #LAVA_PLAYER_GAP} from every other player.
     * Distances in blocks, infinity for no home or no other player.
     */
    public static ActionResult checkPour(boolean lava, boolean ownBlock, double fromHome, double nearestPlayer) {
        if (ownBlock) return ActionResult.failed("own_block");
        if (!lava) return ActionResult.ok();
        if (!(fromHome <= LAVA_AREA)) return ActionResult.denied("outside_work_area");
        if (nearestPlayer <= LAVA_PLAYER_GAP) return ActionResult.denied("lava_near_player");
        return ActionResult.ok();
    }

    /** Kill switch: blocks model calls until {@link #resume} (the next allowlisted order). */
    public void halt() {
        halted = true;
    }

    public void resume() {
        halted = false;
    }

    public boolean halted() {
        return halted;
    }

    /** Weighted tokens in the last hour, the count the cap is checked against. */
    public long tokensUsed() {
        return windowTotal(clockMillis.getAsLong());
    }

    /** Sums the last hour's spend, dropping entries that have aged out. */
    private long windowTotal(long now) {
        while (!spend.isEmpty() && now - spend.peekFirst().at() >= WINDOW_MILLIS) spend.pollFirst();
        long sum = 0;
        for (Spend s : spend) sum += s.count();
        return sum;
    }

    public int callsMade() {
        return calls;
    }
}
