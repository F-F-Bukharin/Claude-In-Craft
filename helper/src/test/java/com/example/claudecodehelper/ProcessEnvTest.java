package com.example.claudecodehelper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The child's environment never carries a way to bill anything but the subscription login. */
class ProcessEnvTest {
    @Test
    void scrubRemovesEveryAnthropicVariable() {
        Map<String, String> env = new HashMap<>(Map.of("ANTHROPIC_API_KEY", "k", "ANTHROPIC_AUTH_TOKEN", "t",
                "ANTHROPIC_BASE_URL", "u", "ANTHROPIC_MODEL", "m", "Anthropic_Custom", "c", "PATH", "p", "HOME", "h"));
        ProcessEnv.scrub(env);
        assertEquals(Map.of("PATH", "p", "HOME", "h"), env);
    }

    @Test
    void scrubRemovesTheCloudSwitchesAndTheHelperToken() {
        Map<String, String> env = new HashMap<>(Map.of("CLAUDE_CODE_USE_BEDROCK", "1", "CLAUDE_CODE_USE_VERTEX", "1",
                "claude_code_use_foundry", "1", "AWS_BEARER_TOKEN_BEDROCK", "b", "CLAUDE_HELPER_TOKEN", "secret",
                "CLAUDE_CODE_PATH", "keep", "PATH", "p"));
        ProcessEnv.scrub(env);
        assertEquals(Map.of("CLAUDE_CODE_PATH", "keep", "PATH", "p"), env);
    }

    @Test
    void theRealBuilderScrubs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        ProcessBuilder pb = new ProcessEnv(dir).builder(java.util.List.of("x"));
        pb.environment().keySet().forEach(k -> {
            if (k.toUpperCase(java.util.Locale.ROOT).startsWith("ANTHROPIC_")) throw new AssertionError(k);
        });
        assertEquals(dir.toFile(), pb.directory());
    }
}
