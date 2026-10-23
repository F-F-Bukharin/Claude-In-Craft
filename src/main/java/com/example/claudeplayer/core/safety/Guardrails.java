package com.example.claudeplayer.core.safety;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.act.ActionResult;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** Hard limits on model calls and world edits: kill switch, call rate, token cap, which blocks may be broken. Server thread only. */
public final class Guardrails {
    /** HALTED and TOKEN_CAP end the order; RATE_LIMITED means wait and try again. */
    public enum Check { OK, HALTED, RATE_LIMITED, TOKEN_CAP }

    private final Settings settings;
    private final LongSupplier clockMillis;
    private final ArrayDeque<Long> recentCalls = new ArrayDeque<>();
    private long tokens;
    private int calls;
    private boolean halted;

    public Guardrails(Settings settings, LongSupplier clockMillis) {
        this.settings = settings;
        this.clockMillis = clockMillis;
    }

    public Check allowCall() {
        if (halted) return Check.HALTED;
        if (tokens >= settings.sessionTokenCap()) return Check.TOKEN_CAP;
        long now = clockMillis.getAsLong();
        while (!recentCalls.isEmpty() && now - recentCalls.peekFirst() >= 60_000) recentCalls.pollFirst();
        return recentCalls.size() >= settings.maxCallsPerMinute() ? Check.RATE_LIMITED : Check.OK;
    }

    public void recordCall() {
        recentCalls.addLast(clockMillis.getAsLong());
        calls++;
    }

    /** Counts every token the API processed, cache reads included, so the cap errs on the safe side. */
    public void recordTokens(long count) {
        tokens += count;
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

    public long tokensUsed() {
        return tokens;
    }

    public int callsMade() {
        return calls;
    }
}
