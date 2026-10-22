package com.example.claudeplayer.core.safety;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.StateSnapshot;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** Hard limits on model calls and world edits: kill switch, call rate, token cap, work area. Server thread only. */
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

    /** Break and place only within break_radius of home. No home, no edits. */
    public boolean allowBlockChange(int x, int y, int z, StateSnapshot.Vec home) {
        if (home == null) return false;
        double dx = x - home.x(), dy = y - home.y(), dz = z - home.z();
        double r = settings.breakRadius();
        return dx * dx + dy * dy + dz * dz <= r * r;
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
