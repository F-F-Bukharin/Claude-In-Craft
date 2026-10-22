package com.example.claudeplayer;

import com.example.claudeplayer.core.Settings;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * config/claudeplayer-common.toml. Defaults come from {@link Settings.Builder}. Read once per server start,
 * except the allowlist, which is read live so /cp allow and edits apply at once.
 */
public final class Config {
    private static final ForgeConfigSpec.Builder B = new ForgeConfigSpec.Builder();
    private static final Settings D = Settings.DEFAULTS;

    private static final ForgeConfigSpec.ConfigValue<String> BRAIN = B
            .comment("claude_code: run your own Claude Code CLI, so calls use your Claude subscription (no API key).",
                    "api: call the Messages API with a key (billed per token).")
            .define("brain", D.brain(), o -> Settings.BRAIN_CLAUDE_CODE.equals(o) || Settings.BRAIN_API.equals(o));
    private static final ForgeConfigSpec.ConfigValue<String> CLAUDE_CODE_PATH = B
            .comment("Path to claude.exe (or claude). Empty finds it on PATH or in ~/.local/bin.")
            .define("claude_code_path", D.claudeCodePath());
    private static final ForgeConfigSpec.IntValue CLAUDE_CODE_TIMEOUT = B
            .comment("Seconds one Claude Code call may take, startup included.")
            .defineInRange("claude_code_timeout_seconds", D.claudeCodeTimeoutSeconds(), 10, 600);
    private static final ForgeConfigSpec.ConfigValue<String> MODEL = B
            .comment("Model id, for either brain.").define("model", D.model());
    private static final ForgeConfigSpec.ConfigValue<String> API_KEY_ENV = B
            .comment("Environment variable that holds the API key. Fallback: the file config/claudeplayer.key (never commit it).")
            .define("api_key_env", D.apiKeyEnv());
    private static final ForgeConfigSpec.ConfigValue<String> EFFORT = B
            .comment("output_config.effort: low, medium, high, xhigh or max. Empty uses the model's default.")
            .define("effort", D.effort());
    private static final ForgeConfigSpec.IntValue MAX_TOKENS = B
            .comment("max_tokens per reply. Thinking counts against it.").defineInRange("max_tokens", D.maxTokens(), 256, 64_000);
    private static final ForgeConfigSpec.IntValue REQUEST_TIMEOUT = B
            .defineInRange("request_timeout_seconds", D.requestTimeoutSeconds(), 5, 120);
    private static final ForgeConfigSpec.BooleanValue PROMPT_CACHING = B
            .comment("Cache the system prompt, tools and each order's transcript (cheaper repeat calls).")
            .define("prompt_caching", D.promptCaching());
    private static final ForgeConfigSpec.BooleanValue LOG_PROMPTS = B
            .comment("Log full request bodies at DEBUG. Off by default; INFO logs only ids, stop reasons and tokens.")
            .define("log_prompts", D.logPrompts());
    private static final ForgeConfigSpec.ConfigValue<String> BODY_NAME = B
            .comment("The body's player name, 3 to 16 letters, digits or underscores.")
            .define("body_name", D.bodyName(), o -> o instanceof String s && s.matches("\\w{3,16}"));
    private static final ForgeConfigSpec.ConfigValue<String> TRIGGER = B
            .comment("Chat prefix for orders.").define("trigger", D.trigger(), o -> o instanceof String s && !s.isBlank());
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ORDERS_ALLOWLIST = B
            .comment("Players allowed to give orders. Empty means nobody. Ops can edit it with /cp allow and /cp deny.")
            .defineListAllowEmpty("orders_allowlist", List.of(), o -> o instanceof String s && !s.isBlank());
    private static final ForgeConfigSpec.IntValue DEFAULT_LEVEL = B
            .comment("Caution level for a new world, 0 (guns blazing) to 5 (innocent).").defineInRange("default_level", D.defaultLevel(), 0, 5);
    private static final ForgeConfigSpec.DoubleValue ACTIONS_PER_SECOND = B
            .defineInRange("actions_per_second", D.actionsPerSecond(), 0.5, 20.0);
    private static final ForgeConfigSpec.IntValue ACTION_TIMEOUT = B
            .comment("Seconds before a timed action (move, wait) fails with failed:timeout.")
            .defineInRange("action_timeout_seconds", D.actionTimeoutSeconds(), 1, 60);
    private static final ForgeConfigSpec.IntValue BATCH_SIZE = B
            .comment("Most tool calls run from one model reply.").defineInRange("batch_size", D.batchSize(), 1, 32);
    private static final ForgeConfigSpec.IntValue PREFETCH_AT = B
            .comment("Send a batch's results early once this many of its calls are still waiting to start.")
            .defineInRange("prefetch_at", D.prefetchAt(), 0, 32);
    private static final ForgeConfigSpec.IntValue MAX_STEPS = B
            .comment("Model rounds per order.").defineInRange("max_steps", D.maxSteps(), 1, 100);
    private static final ForgeConfigSpec.IntValue MAX_CALLS_PER_MINUTE = B
            .defineInRange("max_calls_per_minute", D.maxCallsPerMinute(), 1, 120);
    private static final ForgeConfigSpec.LongValue SESSION_TOKEN_CAP = B
            .comment("Model calls stop once this many tokens were used since the server started.")
            .defineInRange("session_token_cap", D.sessionTokenCap(), 1_000L, Long.MAX_VALUE);
    private static final ForgeConfigSpec.IntValue BREAK_RADIUS = B
            .comment("Blocks may be broken or placed only within this distance of home.")
            .defineInRange("break_radius", D.breakRadius(), 0, 1024);
    private static final ForgeConfigSpec.IntValue HOSTILE_RANGE = B
            .defineInRange("hostile_range", D.hostileRange(), 2, 64);
    private static final ForgeConfigSpec.IntValue ESCAPE_DISTANCE = B
            .defineInRange("escape_distance", D.escapeDistance(), 2, 64);
    private static final ForgeConfigSpec.IntValue ESCAPE_MAX_STEPS = B
            .defineInRange("escape_max_steps", D.escapeMaxSteps(), 2, 64);
    private static final ForgeConfigSpec.IntValue TOOL_PHASE = B
            .comment("Highest plan phase whose tools Claude may call: 2 = v1 tools, memory and inspect_container;",
                    "3 adds follow_player; 4 adds attack_entity; 6 adds the v2 world tools (stubs for now).")
            .defineInRange("tool_phase", D.toolPhase(), 1, 6);
    private static final ForgeConfigSpec.BooleanValue IDLE_REACTIONS = B
            .comment("Let Claude react to threats when no order is running. Costs model calls.")
            .define("idle_reactions", D.idleReactions());
    private static final ForgeConfigSpec.BooleanValue REFLEXES = B
            .comment("Code reflexes: flee or fight by caution level, and refuse moves into lava, fire or deadly drops, before the model hears about it.")
            .define("reflexes", D.reflexes());
    private static final ForgeConfigSpec.IntValue MEMORY_SHORT_CAPACITY = B
            .comment("Short-term notes kept at once; the oldest goes first.")
            .defineInRange("memory_short_capacity", D.shortTermCapacity(), 1, 256);
    private static final ForgeConfigSpec.IntValue MEMORY_SHORT_MINUTES = B
            .comment("Minutes before a short-term note fades.")
            .defineInRange("memory_short_minutes", D.shortTermMinutes(), 1, 1440);
    private static final ForgeConfigSpec.IntValue MEMORY_LONG_CAPACITY = B
            .comment("Long-term memories kept per world (saved in <world>/claudeplayer/memory.json).")
            .defineInRange("memory_long_capacity", D.longTermCapacity(), 1, 1024);

    static final ForgeConfigSpec SPEC = B.build();

    private Config() {}

    public static Settings settings() {
        return Settings.of(b -> {
            b.brain = BRAIN.get();
            b.claudeCodePath = CLAUDE_CODE_PATH.get();
            b.claudeCodeTimeoutSeconds = CLAUDE_CODE_TIMEOUT.get();
            b.model = MODEL.get();
            b.apiKeyEnv = API_KEY_ENV.get();
            b.effort = EFFORT.get();
            b.maxTokens = MAX_TOKENS.get();
            b.requestTimeoutSeconds = REQUEST_TIMEOUT.get();
            b.promptCaching = PROMPT_CACHING.get();
            b.logPrompts = LOG_PROMPTS.get();
            b.bodyName = BODY_NAME.get();
            b.trigger = TRIGGER.get();
            b.ordersAllowlist = allowlist();
            b.defaultLevel = DEFAULT_LEVEL.get();
            b.actionsPerSecond = ACTIONS_PER_SECOND.get();
            b.actionTimeoutSeconds = ACTION_TIMEOUT.get();
            b.batchSize = BATCH_SIZE.get();
            b.prefetchAt = PREFETCH_AT.get();
            b.maxSteps = MAX_STEPS.get();
            b.maxCallsPerMinute = MAX_CALLS_PER_MINUTE.get();
            b.sessionTokenCap = SESSION_TOKEN_CAP.get();
            b.breakRadius = BREAK_RADIUS.get();
            b.hostileRange = HOSTILE_RANGE.get();
            b.escapeDistance = ESCAPE_DISTANCE.get();
            b.escapeMaxSteps = ESCAPE_MAX_STEPS.get();
            b.toolPhase = TOOL_PHASE.get();
            b.idleReactions = IDLE_REACTIONS.get();
            b.reflexes = REFLEXES.get();
            b.shortTermCapacity = MEMORY_SHORT_CAPACITY.get();
            b.shortTermMinutes = MEMORY_SHORT_MINUTES.get();
            b.longTermCapacity = MEMORY_LONG_CAPACITY.get();
        });
    }

    public static List<String> allowlist() {
        return List.copyOf(ORDERS_ALLOWLIST.get());
    }

    public static void setAllowlist(List<String> names) {
        ORDERS_ALLOWLIST.set(new ArrayList<>(names));
        ORDERS_ALLOWLIST.save();
    }
}
