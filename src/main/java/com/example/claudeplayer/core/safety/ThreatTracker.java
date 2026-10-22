package com.example.claudeplayer.core.safety;

import java.util.HashMap;
import java.util.Map;

/**
 * Debounces threat interrupts: one per threat, re-armed only after that threat has gone quiet, and at most one
 * interrupt every {@code minGapTicks} overall. Keys are the caller's ("near:42", "hit:42").
 */
public final class ThreatTracker {
    private static final class State {
        long lastSeen = Long.MIN_VALUE / 2;
        boolean reported;
    }

    private final long minGapTicks;
    private final long rearmTicks;
    private final Map<String, State> keys = new HashMap<>();
    private long lastInterrupt = Long.MIN_VALUE / 2;

    public ThreatTracker(long minGapTicks, long rearmTicks) {
        this.minGapTicks = minGapTicks;
        this.rearmTicks = rearmTicks;
    }

    /** Call each time the threat is observed. True means: interrupt now. */
    public boolean report(String key, long tick) {
        State s = keys.computeIfAbsent(key, k -> new State());
        if (tick - s.lastSeen > rearmTicks) s.reported = false;
        s.lastSeen = tick;
        if (keys.size() > 64) keys.values().removeIf(other -> tick - other.lastSeen > rearmTicks);
        if (s.reported || tick - lastInterrupt < minGapTicks) return false;
        s.reported = true;
        lastInterrupt = tick;
        return true;
    }
}
