package com.example.claudecodehelper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudecodehelper.ClaudeCode;
import com.example.claudecodehelper.FakeClaude;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The HTTP server end to end over a real socket, with a scripted fake claude behind it. */
@Timeout(30)
class HelperServerTest {
    @TempDir
    Path dir;

    private final FakeClaude fake = new FakeClaude();
    private final HttpClient http = HttpClient.newHttpClient();
    private ClaudeCode cc;
    private HelperServer server;

    private void start(String token, int maxSessions, int timeoutSeconds) throws Exception {
        cc = ClaudeCode.builder().claudePath("claude-fake").workDir(dir).launcher(fake).maxSessions(maxSessions)
                .turnTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        JsonObject status = new JsonObject();
        status.addProperty("found", true);
        status.addProperty("path", "claude-fake");
        status.addProperty("signed_in", true);
        server = new HelperServer(cc, new HelperServer.Config(0, token, null), () -> status);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
        if (cc != null) cc.close();
    }

    private HttpResponse<String> post(String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/messages"))
                .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static final String HELLO = "{\"model\":\"claude-haiku-5-5\",\"max_tokens\":100,\"temperature\":0.2,"
            + "\"system\":\"Be brief.\",\"messages\":[{\"role\":\"user\",\"content\":\"What is 2+2?\"}]}";

    @Test
    void aPlainReplyHasTheApiShape() throws Exception {
        start(null, 4, 5);
        fake.script.add(List.of(FakeClaude.result("4")));
        HttpResponse<String> r = post(HELLO);
        assertEquals(200, r.statusCode(), r.body());
        JsonObject m = json(r);
        assertTrue(m.get("id").getAsString().startsWith("msg_"));
        assertEquals("message", m.get("type").getAsString());
        assertEquals("assistant", m.get("role").getAsString());
        assertEquals("claude-haiku-5-5", m.get("model").getAsString());
        assertEquals("[{\"type\":\"text\",\"text\":\"4\"}]", m.get("content").toString());
        assertEquals("end_turn", m.get("stop_reason").getAsString());
        assertTrue(m.has("stop_sequence") && m.get("stop_sequence").isJsonNull());
        assertEquals(40, m.getAsJsonObject("usage").get("output_tokens").getAsInt());
        assertTrue(r.headers().firstValue("request-id").orElse("").startsWith("req_"));
        List<String> cmd = fake.commands.get(0);
        assertEquals("claude-haiku-5-5", cmd.get(cmd.indexOf("--model") + 1), "model passes through");
        assertEquals("Be brief.", Files.readString(Path.of(cmd.get(cmd.indexOf("--system-prompt-file") + 1))));
        assertEquals("What is 2+2?", FakeClaude.text(fake.proc(0).written.get(0)));
    }

    @Test
    void toolsComeBackAsToolUseBlocks() throws Exception {
        start(null, 4, 5);
        fake.script.add(List.of(FakeClaude.result("", 0, "{\"text\":\"Checking.\",\"tool_calls\":[{\"name\":\"get_weather\","
                + "\"input\":{\"city\":\"Paris\"}}]}")));
        HttpResponse<String> r = post("{\"model\":\"claude-haiku-5-5\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\","
                + "\"content\":\"Weather in Paris?\"}],\"tools\":[{\"name\":\"get_weather\",\"description\":\"Weather now.\","
                + "\"input_schema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}]}");
        assertEquals(200, r.statusCode(), r.body());
        JsonObject m = json(r);
        assertEquals("tool_use", m.get("stop_reason").getAsString());
        JsonArray content = m.getAsJsonArray("content");
        assertEquals("Checking.", content.get(0).getAsJsonObject().get("text").getAsString());
        JsonObject call = content.get(1).getAsJsonObject();
        assertEquals("get_weather", call.get("name").getAsString());
        assertTrue(call.get("id").getAsString().startsWith("toolu_"));
        assertEquals("{\"city\":\"Paris\"}", call.get("input").toString());
        List<String> cmd = fake.commands.get(0);
        assertTrue(cmd.contains("--json-schema"));
        String system = Files.readString(Path.of(cmd.get(cmd.indexOf("--system-prompt-file") + 1)));
        assertTrue(system.contains("## get_weather"), system);
    }

    @Test
    void aFollowUpReusesTheWarmSession() throws Exception {
        start(null, 4, 5);
        fake.script.add(List.of(FakeClaude.result("4")));
        JsonObject first = json(post(HELLO));
        JsonObject req = JsonParser.parseString(HELLO).getAsJsonObject();
        JsonObject assistant = new JsonObject();
        assistant.addProperty("role", "assistant");
        assistant.add("content", first.get("content"));
        req.getAsJsonArray("messages").add(assistant);
        req.getAsJsonArray("messages").add(JsonParser.parseString("{\"role\":\"user\",\"content\":\"Times 3?\"}"));
        fake.script.add(List.of(FakeClaude.result("12", 1800, null)));

        JsonObject second = json(post(req.toString()));

        assertEquals("12", second.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(1800, second.getAsJsonObject("usage").get("cache_read_input_tokens").getAsInt());
        assertEquals(2, fake.proc(0).written.size(), "the same process took the follow-up");
        assertEquals("Times 3?", FakeClaude.text(fake.proc(0).written.get(1)), "only the new message is sent");
    }

    @Test
    void aTokenIsRequiredWhenSet() throws Exception {
        start("s3cret", 4, 5);
        HttpResponse<String> none = post(HELLO);
        assertEquals(401, none.statusCode());
        assertEquals("authentication_error", json(none).getAsJsonObject("error").get("type").getAsString());
        assertEquals(401, post(HELLO, "x-api-key", "wrong").statusCode());
        fake.script.add(List.of(FakeClaude.result("4")));
        assertEquals(200, post(HELLO, "x-api-key", "s3cret").statusCode());
        fake.script.add(List.of(FakeClaude.result("4")));
        assertEquals(200, post(HELLO, "Authorization", "Bearer s3cret").statusCode());
    }

    @Test
    void withoutATokenAnyKeyWorks() throws Exception {
        start(null, 4, 5);
        fake.script.add(List.of(FakeClaude.result("4")));
        assertEquals(200, post(HELLO, "x-api-key", "sk-ant-anything").statusCode());
    }

    @Test
    void aForeignOriginIsRefused() throws Exception {
        start(null, 4, 5);
        HttpResponse<String> r = post(HELLO, "Origin", "https://evil.example");
        assertEquals(403, r.statusCode());
        assertEquals("permission_error", json(r).getAsJsonObject("error").get("type").getAsString());
        assertTrue(fake.procs.isEmpty(), "nothing started");
    }

    @Test
    void errorsUseTheApiShape() throws Exception {
        start(null, 4, 5);
        HttpResponse<String> r = post("{\"model\":\"m\",\"messages\":[]}");
        assertEquals(400, r.statusCode());
        JsonObject e = json(r);
        assertEquals("error", e.get("type").getAsString());
        assertEquals("invalid_request_error", e.getAsJsonObject("error").get("type").getAsString());
        assertEquals(400, post("not json").statusCode());
        assertEquals(400, post("{\"model\":\"--bad\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}").statusCode());
        assertEquals(400, post("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"},{\"role\":\"assistant\",\"content\":\"y\"}]}")
                .statusCode(), "prefill");
        HttpResponse<String> plain = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/messages"))
                .header("content-type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(HELLO)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, plain.statusCode(), "only JSON: a web page's simple POST is refused");
        HttpResponse<String> missing = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/nope"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missing.statusCode());
        assertEquals("not_found_error", json(missing).getAsJsonObject("error").get("type").getAsString());
    }

    @Test
    void notSignedInIsAnAuthenticationError() throws Exception {
        start(null, 4, 5);
        fake.script.add(List.of(FakeClaude.EXIT_NOT_LOGGED_IN));
        HttpResponse<String> r = post(HELLO);
        assertEquals(401, r.statusCode());
        assertTrue(json(r).getAsJsonObject("error").get("message").getAsString().contains("sign in"));
    }

    @Test
    void streamingSendsTheStandardEvents() throws Exception {
        start(null, 4, 5);
        fake.script.add(List.of(FakeClaude.result("", 0, "{\"text\":\"Sure.\",\"tool_calls\":[{\"name\":\"f\",\"input\":{\"a\":1}}]}")));
        HttpResponse<String> r = post("{\"model\":\"m\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"go\"}],"
                + "\"tools\":[{\"name\":\"f\",\"input_schema\":{\"type\":\"object\"}}]}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
        List<String> events = new ArrayList<>();
        for (String line : r.body().split("\n")) if (line.startsWith("event: ")) events.add(line.substring(7));
        assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop"), events);
        assertTrue(r.body().contains("\"partial_json\":\"{\\\"a\\\":1}\""), r.body());
        assertTrue(r.body().contains("\"stop_reason\":\"tool_use\""));
    }

    @Test
    void pastTheCapARequestGetsABusyError() throws Exception {
        start(null, 1, 3);
        CompletableFuture<HttpResponse<String>> slow = CompletableFuture.supplyAsync(() -> {
            try {
                return post(HELLO); // no script: this turn waits until its timeout
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        FakeClaude.await(() -> !fake.procs.isEmpty() && !fake.proc(0).written.isEmpty());
        HttpResponse<String> busy = post(HELLO.replace("2+2", "3+3"));
        assertEquals(529, busy.statusCode());
        assertEquals("overloaded_error", json(busy).getAsJsonObject("error").get("type").getAsString());
        assertEquals(504, slow.get().statusCode(), "the stuck turn times out");
    }

    @Test
    void healthReportsClaudeAndTheSessions() throws Exception {
        start("s3cret", 4, 5);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        JsonObject h = json(r);
        assertEquals("ok", h.get("status").getAsString());
        assertTrue(h.getAsJsonObject("claude").get("signed_in").getAsBoolean());
        assertEquals(4, h.getAsJsonObject("sessions").get("max").getAsInt());
        assertTrue(h.get("token_required").getAsBoolean());
        assertFalse(r.body().contains("s3cret"));
    }

    @Test
    void logsCarryIdsAndTokensButNoPromptsImagesOrToken() throws Exception {
        List<String> logged = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(new java.util.logging.SimpleFormatter().formatMessage(r));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger log = Logger.getLogger("claude-code-helper");
        log.addHandler(capture);
        try {
            start("TOKEN-SECRET-123", 4, 5);
            fake.script.add(List.of(FakeClaude.result("REPLY-SECRET")));
            String body = "{\"model\":\"claude-haiku-5-5\",\"system\":\"SYSTEM-SECRET\",\"messages\":[{\"role\":\"user\",\"content\":"
                    + "[{\"type\":\"text\",\"text\":\"PROMPT-SECRET\"},{\"type\":\"image\",\"source\":{\"type\":\"base64\","
                    + "\"media_type\":\"image/png\",\"data\":\"SU1BR0VTRUNSRVQ=\"}}]}]}";
            assertEquals(200, post(body, "x-api-key", "TOKEN-SECRET-123").statusCode());
            assertEquals(401, post(body, "x-api-key", "WRONG-SECRET").statusCode());
            assertEquals(400, post("{\"PROMPT-SECRET\":", "x-api-key", "TOKEN-SECRET-123").statusCode());
        } finally {
            log.removeHandler(capture);
        }
        String all = String.join("\n", logged);
        assertTrue(all.contains("req_") && all.contains("model=claude-haiku-5-5") && all.contains("out=40")
                && all.contains("stop=end_turn"), all);
        assertTrue(all.contains("401") && all.contains("400"), "failed requests are logged too: " + all);
        for (String secret : List.of("SECRET", "SU1BR0VTRUNSRVQ")) assertFalse(all.contains(secret), all);
    }
}
