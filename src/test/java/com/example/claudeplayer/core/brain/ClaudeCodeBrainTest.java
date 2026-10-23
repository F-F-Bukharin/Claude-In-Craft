package com.example.claudeplayer.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Claude Code backend's command line, prompt rendering and result parsing, without running the CLI. */
class ClaudeCodeBrainTest {
    private static final String SUCCESS = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
            + "\"session_id\":\"s-1\",\"result\":\"...\",\"usage\":{\"input_tokens\":2,\"output_tokens\":340,"
            + "\"cache_creation_input_tokens\":1631,\"cache_read_input_tokens\":7},"
            + "\"structured_output\":{\"say\":\"On my way.\",\"calls\":[{\"name\":\"move_to\",\"input\":{\"x\":1,\"y\":64,\"z\":-2}},"
            + "{\"name\":\"say\",\"input\":{\"text\":\"hi\"}}]}}";

    @Test
    void commandTurnsOffClaudeCodesOwnToolsSettingsAndSessions() {
        List<String> cmd = ClaudeCodeBrain.command("claude.exe", Settings.DEFAULTS, Path.of("system.md"), false);
        assertEquals("claude.exe", cmd.get(0));
        assertEquals("", cmd.get(cmd.indexOf("--tools") + 1), "no Bash, Read, Edit or any other built-in tool");
        assertEquals("", cmd.get(cmd.indexOf("--setting-sources") + 1), "no user hooks, plugins or permissions");
        assertTrue(cmd.contains("--strict-mcp-config"), "no MCP servers");
        assertTrue(cmd.contains("--no-session-persistence"));
        assertEquals("claude-haiku-5-5", cmd.get(cmd.indexOf("--model") + 1));
        assertEquals("low", cmd.get(cmd.indexOf("--effort") + 1));
        assertEquals(ClaudeCodeBrain.SCHEMA, cmd.get(cmd.indexOf("--json-schema") + 1));
        assertFalse(cmd.contains("--bare"), "bare mode ignores the subscription login");
    }

    @Test
    void windowsEscapesTheSchemaQuotes() {
        List<String> cmd = ClaudeCodeBrain.command("claude.exe", Settings.DEFAULTS, Path.of("system.md"), true);
        String schema = cmd.get(cmd.indexOf("--json-schema") + 1);
        assertEquals(ClaudeCodeBrain.SCHEMA, schema.replace("\\\"", "\""));
        assertFalse(schema.matches(".*[^\\\\]\".*"), "every quote is escaped");
    }

    @Test
    void successBecomesToolCallsWithFreshIds() throws BrainException {
        Brain.Reply r = ClaudeCodeBrain.parseResult(SUCCESS, "cc4_");
        assertEquals("tool_use", r.stopReason());
        assertEquals(2, r.toolCalls().size());
        assertEquals("cc4_0", r.toolCalls().get(0).id());
        assertEquals("move_to", r.toolCalls().get(0).name());
        assertEquals(-2, r.toolCalls().get(0).input().get("z").getAsInt());
        assertEquals("cc4_1", r.toolCalls().get(1).id());
        assertEquals("On my way.", r.text());
        assertEquals(3, r.content().size(), "text, then one tool_use block per call, for the transcript");
        assertEquals("tool_use", r.content().get(1).getAsJsonObject().get("type").getAsString());
        assertEquals(1631, r.usage().cacheWrite());
        assertEquals(1980, r.usage().total());
    }

    @Test
    void noCallsEndsTheTurn() throws BrainException {
        String done = SUCCESS.replaceAll("\"calls\":\\[.*]}}", "\"calls\":[]}}");
        Brain.Reply r = ClaudeCodeBrain.parseResult(done, "cc1_");
        assertEquals("end_turn", r.stopReason());
        assertTrue(r.toolCalls().isEmpty());
        assertEquals("On my way.", r.text());
    }

    @Test
    void failuresAreClassified() {
        BrainException limit = assertThrows(BrainException.class, () -> ClaudeCodeBrain.parseResult(
                "{\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":429,\"result\":\"usage limit reached\"}", "x"));
        assertEquals(BrainException.Kind.RATE_LIMITED, limit.kind());
        assertTrue(limit.kind().retryable);

        BrainException login = assertThrows(BrainException.class, () -> ClaudeCodeBrain.parseResult(
                "{\"subtype\":\"success\",\"is_error\":true,\"result\":\"Not logged in. Please run /login\"}", "x"));
        assertEquals(BrainException.Kind.CLI, login.kind());
        assertTrue(login.chatMessage("ANTHROPIC_API_KEY").contains("Not logged in"));

        BrainException garbage = assertThrows(BrainException.class, () -> ClaudeCodeBrain.parseResult("Error: boom", "x"));
        assertEquals(BrainException.Kind.CLI, garbage.kind());

        BrainException noSchema = assertThrows(BrainException.class, () -> ClaudeCodeBrain.parseResult(
                "{\"subtype\":\"error_max_turns\",\"is_error\":false}", "x"));
        assertEquals(BrainException.Kind.CLI, noSchema.kind());
    }

    @Test
    void transcriptNamesToolResultsAndSkipsThinking() {
        JsonArray messages = JsonParser.parseString("["
                + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"<order>come here</order>\"}]},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"secret\"},"
                + "{\"type\":\"tool_use\",\"id\":\"cc1_0\",\"name\":\"move_to\",\"input\":{\"x\":1}}]},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"cc1_0\",\"content\":\"failed\\nno_path\",\"is_error\":true},"
                + "{\"type\":\"text\",\"text\":\"update\"}]}]").getAsJsonArray();
        String text = ClaudeCodeBrain.renderTranscript(messages);
        assertTrue(text.contains("[game]\n<order>come here</order>"));
        assertTrue(text.contains("[you]\ncall move_to {\"x\":1}"));
        assertTrue(text.contains("result of move_to (error): failed\nno_path"));
        assertFalse(text.contains("secret"));
        assertTrue(text.endsWith("Answer with your next step as JSON."));
    }

    @Test
    void systemPromptListsEveryActionAsOneCompactLine() {
        JsonArray tools = new ToolSchemas(2).definitions();
        String system = ClaudeCodeBrain.renderSystem("You are Claude.", tools);
        assertTrue(system.startsWith("Your only callable tool is StructuredOutput"), "rules out native tool calls first");
        assertTrue(system.contains("You are Claude."));
        for (var t : tools) {
            String name = t.getAsJsonObject().get("name").getAsString();
            assertTrue(system.contains("\n- " + name + " {"), name);
        }
        assertTrue(system.contains("- move_to {\"x\": number, \"y\": number, \"z\": number}: Walk"));
        assertTrue(system.contains("- get_state {}: "));
        assertFalse(system.contains("input_schema") || system.contains("\"properties\""), "no JSON schemas: they read like tools");
        assertFalse(system.contains("\"prio\": integer"), "prio is optional and explained once");
    }
}
