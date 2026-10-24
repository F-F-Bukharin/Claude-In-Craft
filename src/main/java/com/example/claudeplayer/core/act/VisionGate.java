package com.example.claudeplayer.core.act;

import java.util.function.LongSupplier;

/**
 * When a glance may run: at most one per model turn, and none within {@code vision_cooldown_seconds} of the last
 * one let through. A picture costs about w×h/750 tokens, so this is policy in code, not a prompt suggestion.
 */
public final class VisionGate {
    private final long cooldownMillis;
    private final LongSupplier clockMillis;
    private boolean usedThisTurn;
    private long lastAt = Long.MIN_VALUE;

    public VisionGate(int cooldownSeconds, LongSupplier clockMillis) {
        this.cooldownMillis = cooldownSeconds * 1000L;
        this.clockMillis = clockMillis;
    }

    /** A new model reply: its first glance may run again, cooldown permitting. */
    public void newTurn() {
        usedThisTurn = false;
    }

    /** Null when the glance may run (and it now counts), else the denial reason: one_per_turn or cooldown. */
    public String admit() {
        if (usedThisTurn) return "one_per_turn";
        long now = clockMillis.getAsLong();
        if (lastAt != Long.MIN_VALUE && now - lastAt < cooldownMillis) return "cooldown";
        usedThisTurn = true;
        lastAt = now;
        return null;
    }
}
