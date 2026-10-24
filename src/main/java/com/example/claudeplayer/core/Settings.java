package com.example.claudeplayer.core;

import java.util.List;
import java.util.function.Consumer;

/**
 * Every tunable, as one immutable value. The game side builds it from the Forge config ({@code Config});
 * tests build it with {@link #of}. Defaults live in {@link Builder} and nowhere else.
 */
public record Settings(
        String brain, String claudeCodePath, int claudeCodeTimeoutSeconds,
        String model, String apiKeyEnv, String effort, int maxTokens, int requestTimeoutSeconds,
        boolean promptCaching, boolean logPrompts,
        String bodyName, String trigger, List<String> ordersAllowlist, int defaultLevel,
        double actionsPerSecond, int actionTimeoutSeconds, int batchSize, int prefetchAt,
        int maxSteps, int maxCallsPerMinute, long sessionTokenCap,
        int hostileRange, int escapeDistance, int escapeMaxSteps,
        int toolPhase, boolean idleReactions, boolean reflexes, boolean autoEat,
        int shortTermCapacity, int shortTermMinutes, int longTermCapacity,
        int claudeCodeIdleMinutes, int autonomyIntervalSeconds,
        int visionCooldownSeconds, int visionWidth, int visionHeight,
        int tradeMaxEmeralds) {

    /** {@code brain}: the player's Claude Code CLI (their subscription), or the Messages API with a key. */
    public static final String BRAIN_CLAUDE_CODE = "claude_code";
    public static final String BRAIN_API = "api";
    public static final Settings DEFAULTS = of(b -> {});

    /** The configured trigger, then the body's name with a colon ("Claude:"), which always works too. */
    public List<String> triggers() {
        String byName = bodyName + ":";
        return trigger.equalsIgnoreCase(byName) ? List.of(trigger) : List.of(trigger, byName);
    }

    public boolean usesApi() {
        return BRAIN_API.equals(brain);
    }

    /** The tool_phase that unlocks attack_entity and fight; below it a fight reflex becomes a flee. */
    public static final int ATTACK_PHASE = 4;

    public boolean attacksEnabled() {
        return toolPhase >= ATTACK_PHASE;
    }

    /** For players: why the body flees instead of fighting, or "" when attacks are on. */
    public String attacksOffNote() {
        if (attacksEnabled()) return "";
        return "Attacks are off (tool_phase " + toolPhase + " in claudeplayer-common.toml; fighting needs " + ATTACK_PHASE
                + "), so " + bodyName + " flees instead of fighting. A server op can raise it, then reload the world.";
    }

    public Settings {
        ordersAllowlist = List.copyOf(ordersAllowlist);
    }

    public static Settings of(Consumer<Builder> edits) {
        Builder b = new Builder();
        edits.accept(b);
        return b.build();
    }

    /** Mutable defaults: edit the fields, then {@link #build}. */
    public static final class Builder {
        public String brain = BRAIN_CLAUDE_CODE;
        public String claudeCodePath = "";
        public int claudeCodeTimeoutSeconds = 90;
        public String model = "claude-haiku-5-5";
        public String apiKeyEnv = "ANTHROPIC_API_KEY";
        public String effort = "low";
        public int maxTokens = 4096;
        public int requestTimeoutSeconds = 20;
        public boolean promptCaching = true;
        public boolean logPrompts = false;
        public String bodyName = "Claude";
        public String trigger = "C";
        public List<String> ordersAllowlist = List.of();
        public int defaultLevel = 3;
        public double actionsPerSecond = 3.0;
        public int actionTimeoutSeconds = 10;
        public int batchSize = 12;
        public int prefetchAt = 4;
        public int maxSteps = 8;
        public int maxCallsPerMinute = 12;
        public long sessionTokenCap = 200_000;
        public int hostileRange = 16;
        public int escapeDistance = 8;
        public int escapeMaxSteps = 12;
        public int toolPhase = 2;
        public boolean idleReactions = true;
        public boolean reflexes = true;
        public boolean autoEat = true;
        public int shortTermCapacity = 24;
        public int shortTermMinutes = 10;
        public int longTermCapacity = 64;
        public int claudeCodeIdleMinutes = 10;
        public int autonomyIntervalSeconds = 60;
        public int visionCooldownSeconds = 15;
        public int visionWidth = 320;
        public int visionHeight = 180;
        /** Most emeralds one trade call may spend; 0 turns trading off. */
        public int tradeMaxEmeralds = 32;

        public Settings build() {
            return new Settings(brain, claudeCodePath, claudeCodeTimeoutSeconds, model, apiKeyEnv, effort, maxTokens, requestTimeoutSeconds, promptCaching, logPrompts,
                    bodyName, trigger, ordersAllowlist, defaultLevel, actionsPerSecond, actionTimeoutSeconds,
                    batchSize, prefetchAt, maxSteps, maxCallsPerMinute, sessionTokenCap, hostileRange,
                    escapeDistance, escapeMaxSteps, toolPhase, idleReactions, reflexes, autoEat,
                    shortTermCapacity, shortTermMinutes, longTermCapacity,
                    claudeCodeIdleMinutes, autonomyIntervalSeconds,
                    visionCooldownSeconds, visionWidth, visionHeight,
                    tradeMaxEmeralds);
        }
    }
}
