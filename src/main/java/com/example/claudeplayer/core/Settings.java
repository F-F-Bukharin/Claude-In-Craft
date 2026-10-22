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
        int breakRadius, int hostileRange, int escapeDistance, int escapeMaxSteps,
        int toolPhase, boolean idleReactions,
        int shortTermCapacity, int shortTermMinutes, int longTermCapacity) {

    /** {@code brain}: the player's Claude Code CLI (their subscription), or the Messages API with a key. */
    public static final String BRAIN_CLAUDE_CODE = "claude_code";
    public static final String BRAIN_API = "api";
    public static final Settings DEFAULTS = of(b -> {});

    public boolean usesApi() {
        return BRAIN_API.equals(brain);
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
        public String trigger = "claude:";
        public List<String> ordersAllowlist = List.of();
        public int defaultLevel = 3;
        public double actionsPerSecond = 3.0;
        public int actionTimeoutSeconds = 10;
        public int batchSize = 12;
        public int prefetchAt = 4;
        public int maxSteps = 8;
        public int maxCallsPerMinute = 12;
        public long sessionTokenCap = 200_000;
        public int breakRadius = 32;
        public int hostileRange = 16;
        public int escapeDistance = 8;
        public int escapeMaxSteps = 12;
        public int toolPhase = 2;
        public boolean idleReactions = true;
        public int shortTermCapacity = 24;
        public int shortTermMinutes = 10;
        public int longTermCapacity = 64;

        public Settings build() {
            return new Settings(brain, claudeCodePath, claudeCodeTimeoutSeconds, model, apiKeyEnv, effort, maxTokens, requestTimeoutSeconds, promptCaching, logPrompts,
                    bodyName, trigger, ordersAllowlist, defaultLevel, actionsPerSecond, actionTimeoutSeconds,
                    batchSize, prefetchAt, maxSteps, maxCallsPerMinute, sessionTokenCap, breakRadius, hostileRange,
                    escapeDistance, escapeMaxSteps, toolPhase, idleReactions,
                    shortTermCapacity, shortTermMinutes, longTermCapacity);
        }
    }
}
