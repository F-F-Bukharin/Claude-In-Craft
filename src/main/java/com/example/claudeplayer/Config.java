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
            .comment("Seconds one Claude Code turn may take before its session is killed.")
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
            .comment("Chat prefix for orders. One ending in a letter, like C, must be typed in that case and followed by a space: \"C come here\".",
                    "The body's name with a colon (\"Claude: come here\") always works too.")
            .define("trigger", D.trigger(), o -> o instanceof String s && !s.isBlank());
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
            .comment("Weighted tokens (cache reads count a tenth) allowed per rolling hour; at the cap, model calls wait",
                    "until older tokens age out or /cp resetcap.")
            .defineInRange("session_token_cap", D.sessionTokenCap(), 1_000L, Long.MAX_VALUE);
    private static final ForgeConfigSpec.IntValue HOSTILE_RANGE = B
            .defineInRange("hostile_range", D.hostileRange(), 2, 64);
    private static final ForgeConfigSpec.IntValue ESCAPE_DISTANCE = B
            .defineInRange("escape_distance", D.escapeDistance(), 2, 64);
    private static final ForgeConfigSpec.IntValue ESCAPE_MAX_STEPS = B
            .defineInRange("escape_max_steps", D.escapeMaxSteps(), 2, 64);
    private static final ForgeConfigSpec.IntValue TOOL_PHASE = B
            .comment("Highest plan phase whose tools Claude may call: 2 = v1 tools, memory, containers, eat, follow_player,",
                    "the block tools (mine_block, place_block, equip), craft and smelt; 4 adds attack_entity.")
            .defineInRange("tool_phase", D.toolPhase(), 1, 6);
    private static final ForgeConfigSpec.BooleanValue IDLE_REACTIONS = B
            .comment("Let Claude react to threats when no order is running. Costs model calls.")
            .define("idle_reactions", D.idleReactions());
    private static final ForgeConfigSpec.BooleanValue REFLEXES = B
            .comment("Code reflexes: flee or fight by caution level, and refuse moves into lava, fire or deadly drops, before the model hears about it.")
            .define("reflexes", D.reflexes());
    private static final ForgeConfigSpec.BooleanValue AUTO_EAT = B
            .comment("Eat on its own when hungry, carrying food and nothing hostile is near. Costs no model calls.")
            .define("auto_eat", D.autoEat());
    private static final ForgeConfigSpec.IntValue MEMORY_SHORT_CAPACITY = B
            .comment("Short-term notes kept at once; the oldest goes first.")
            .defineInRange("memory_short_capacity", D.shortTermCapacity(), 1, 256);
    private static final ForgeConfigSpec.IntValue MEMORY_SHORT_MINUTES = B
            .comment("Minutes before a short-term note fades.")
            .defineInRange("memory_short_minutes", D.shortTermMinutes(), 1, 1440);
    private static final ForgeConfigSpec.IntValue MEMORY_LONG_CAPACITY = B
            .comment("Long-term memories kept per world (saved in <world>/claudeplayer/memory.json).")
            .defineInRange("memory_long_capacity", D.longTermCapacity(), 1, 1024);
    private static final ForgeConfigSpec.IntValue CLAUDE_CODE_IDLE = B
            .comment("Minutes a warm Claude Code process (an order's session, or the spare kept for the next order) may sit unused before it is stopped.")
            .defineInRange("claude_code_idle_minutes", D.claudeCodeIdleMinutes(), 1, 1440);
    private static final ForgeConfigSpec.IntValue AUTONOMY_INTERVAL = B
            .comment("While autonomous (\"C go do your thing\"), at least this many seconds between goals, each one model call.")
            .defineInRange("autonomy_interval_seconds", D.autonomyIntervalSeconds(), 15, 3600);
    private static final ForgeConfigSpec.IntValue VISION_COOLDOWN = B
            .comment("Seconds after a glance before the next one may run (one per model turn at most). A 320x180 picture is about 77 tokens.")
            .defineInRange("vision_cooldown_seconds", D.visionCooldownSeconds(), 0, 3600);
    private static final ForgeConfigSpec.IntValue VISION_WIDTH = B
            .comment("Width in pixels of a glance picture.").defineInRange("vision_width", D.visionWidth(), 64, 640);
    private static final ForgeConfigSpec.IntValue VISION_HEIGHT = B
            .comment("Height in pixels of a glance picture.").defineInRange("vision_height", D.visionHeight(), 64, 640);

    private static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");
    private static final ForgeConfigSpec.ConfigValue<String> CLAUDE_EFFORT = B
            .comment("Claude Code's --effort on normal turns: low, medium, high, xhigh or max. The API brain keeps effort.")
            .defineInList("claude_effort", D.claudeEffort(), EFFORTS);
    private static final ForgeConfigSpec.ConfigValue<String> CLAUDE_THINK_EFFORT = B
            .comment("Claude Code's --effort for an order the ordering player switched to think-harder.")
            .defineInList("claude_think_effort", D.claudeThinkEffort(), EFFORTS);
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> THINK_HARDER_PHRASES = B
            .comment("An order containing one of these (any case) offers the think-harder effort for that order.")
            .defineListAllowEmpty("think_harder_phrases", D.thinkHarderPhrases(), o -> o instanceof String);
    private static final ForgeConfigSpec.IntValue THINK_HARDER_TIMEOUT = B
            .comment("Seconds the think-harder offer waits for y or n; no answer counts as no.")
            .defineInRange("think_harder_timeout_seconds", D.thinkHarderTimeoutSeconds(), 5, 300);
    private static final ForgeConfigSpec.ConfigValue<List<? extends Double>> USAGE_WARN_AT = B
            .comment("Claude Code usage fractions (0 to 1) that post a chat warning, once per window each.")
            .defineList("usage_warn_at", D.usageWarnAt(), o -> o instanceof Double d && d > 0 && d <= 1);
    private static final ForgeConfigSpec.DoubleValue CAVEMAN_AT = B
            .comment("From this usage fraction on, the caveman skill's rules join the prompt. Above 1 turns caveman off.")
            .defineInRange("caveman_at", D.cavemanAt(), 0.0, 2.0);
    private static final ForgeConfigSpec.ConfigValue<String> CAVEMAN_SKILL_PATH = B
            .comment("The caveman SKILL.md to read. Empty looks under ~/.claude/skills/ for the newest caveman skill.")
            .define("caveman_skill_path", D.cavemanSkillPath());
    private static final ForgeConfigSpec.IntValue COMPACT_AT_TOKENS = B
            .comment("When one model call's prompt reaches this many tokens, the order's earlier turns collapse into a short summary (no extra call) before the next turn.")
            .defineInRange("compact_at_tokens", D.compactAtTokens(), 2_000, 1_000_000);

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
            b.hostileRange = HOSTILE_RANGE.get();
            b.escapeDistance = ESCAPE_DISTANCE.get();
            b.escapeMaxSteps = ESCAPE_MAX_STEPS.get();
            b.toolPhase = TOOL_PHASE.get();
            b.idleReactions = IDLE_REACTIONS.get();
            b.reflexes = REFLEXES.get();
            b.autoEat = AUTO_EAT.get();
            b.shortTermCapacity = MEMORY_SHORT_CAPACITY.get();
            b.shortTermMinutes = MEMORY_SHORT_MINUTES.get();
            b.longTermCapacity = MEMORY_LONG_CAPACITY.get();
            b.claudeCodeIdleMinutes = CLAUDE_CODE_IDLE.get();
            b.autonomyIntervalSeconds = AUTONOMY_INTERVAL.get();
            b.visionCooldownSeconds = VISION_COOLDOWN.get();
            b.visionWidth = VISION_WIDTH.get();
            b.visionHeight = VISION_HEIGHT.get();
            b.claudeEffort = CLAUDE_EFFORT.get();
            b.claudeThinkEffort = CLAUDE_THINK_EFFORT.get();
            b.thinkHarderPhrases = List.copyOf(THINK_HARDER_PHRASES.get());
            b.thinkHarderTimeoutSeconds = THINK_HARDER_TIMEOUT.get();
            b.usageWarnAt = List.copyOf(USAGE_WARN_AT.get());
            b.cavemanAt = CAVEMAN_AT.get();
            b.cavemanSkillPath = CAVEMAN_SKILL_PATH.get();
            b.compactAtTokens = COMPACT_AT_TOKENS.get();
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
