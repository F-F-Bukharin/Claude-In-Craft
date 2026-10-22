package com.example.claudeplayer.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Request and response shapes for the Messages API, without any network. */
class ApiBrainTest {
    private static final String NO_SUCH_ENV = "CLAUDEPLAYER_TEST_NO_SUCH_VAR_7731";

    private static Brain.Request request() {
        JsonArray messages = JsonParser.parseString(
                "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"<order>hi</order>\"}]}]").getAsJsonArray();
        return new Brain.Request("You are Claude.", new ToolSchemas(2).definitions(), messages);
    }

    @Test
    void requestBodyCarriesModelToolsEffortAndCacheBreakpoints() {
        Brain.Request req = request();
        JsonObject body = ApiBrain.requestBody(req, Settings.DEFAULTS);
        assertEquals("claude-haiku-5-5", body.get("model").getAsString());
        assertEquals(4096, body.get("max_tokens").getAsInt());
        assertEquals("ephemeral", body.getAsJsonArray("system").get(0).getAsJsonObject()
                .getAsJsonObject("cache_control").get("type").getAsString());
        assertEquals("auto", body.getAsJsonObject("tool_choice").get("type").getAsString());
        assertEquals("low", body.getAsJsonObject("output_config").get("effort").getAsString());
        assertEquals(10, body.getAsJsonArray("tools").size());
        JsonObject lastBlock = body.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
        assertTrue(lastBlock.has("cache_control"), "the newest turn is a cache breakpoint");
        assertFalse(req.messages().toString().contains("cache_control"), "the caller's transcript is untouched");
        assertFalse(body.has("temperature"), "sampling parameters are rejected on API version 2026-10-21");
    }

    @Test
    void cachingAndEffortCanBeTurnedOff() {
        JsonObject body = ApiBrain.requestBody(request(), Settings.of(b -> {
            b.promptCaching = false;
            b.effort = "";
        }));
        assertFalse(body.toString().contains("cache_control"));
        assertFalse(body.has("output_config"));
    }

    @Test
    void replyParsingKeepsRawContentAndReadsToolCalls() {
        JsonObject json = JsonParser.parseString("""
                {"id":"msg_1","type":"message","role":"assistant","stop_reason":"tool_use",
                 "content":[
                   {"type":"thinking","thinking":"","signature":"sig"},
                   {"type":"text","text":"On my way."},
                   {"type":"tool_use","id":"toolu_1","name":"move_to","input":{"x":1,"y":64,"z":2}},
                   {"type":"tool_use","id":"toolu_2","name":"say","input":{"text":"hi"}}],
                 "usage":{"input_tokens":120,"output_tokens":40,"cache_creation_input_tokens":null,"cache_read_input_tokens":900}}
                """).getAsJsonObject();
        Brain.Reply reply = ApiBrain.parseReply(json);
        assertEquals("msg_1", reply.id());
        assertEquals("tool_use", reply.stopReason());
        assertEquals("On my way.", reply.text());
        assertEquals(4, reply.content().size(), "thinking blocks stay in the transcript");
        assertEquals(2, reply.toolCalls().size());
        assertEquals("toolu_1", reply.toolCalls().get(0).id());
        assertEquals("move_to", reply.toolCalls().get(0).name());
        assertEquals(64, reply.toolCalls().get(0).input().get("y").getAsInt());
        assertEquals(new Brain.Usage(120, 40, 0, 900), reply.usage());
    }

    @Test
    void errorsAreClassified() {
        BrainException auth = BrainException.fromResponse(401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}", "req_1");
        assertEquals(BrainException.Kind.AUTH, auth.kind());
        assertFalse(auth.kind().retryable);
        assertEquals("invalid x-api-key", auth.getMessage());
        assertEquals("req_1", auth.requestId());
        assertTrue(BrainException.fromResponse(429, "", null).kind().retryable);
        assertEquals(BrainException.Kind.OVERLOADED, BrainException.fromResponse(529, "not json", null).kind());
        assertEquals(BrainException.Kind.SERVER, BrainException.fromResponse(503, "", null).kind());
        assertEquals(BrainException.Kind.BAD_REQUEST, BrainException.fromResponse(400, "", null).kind());
        assertFalse(auth.chatMessage("ANTHROPIC_API_KEY").contains("sk-"), "chat messages never carry a key");
    }

    @Test
    void keyComesFromTheEnvOrTheKeyFile(@TempDir Path dir) throws Exception {
        assertNull(ApiBrain.resolveKey(NO_SUCH_ENV, dir.resolve("missing.key")));
        Path keyFile = dir.resolve("claudeplayer.key");
        Files.writeString(keyFile, "\n  sk-test-123  \n");
        assertEquals("sk-test-123", ApiBrain.resolveKey(NO_SUCH_ENV, keyFile));
    }
}
