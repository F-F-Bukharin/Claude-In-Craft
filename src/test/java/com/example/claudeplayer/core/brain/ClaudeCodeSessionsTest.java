package com.example.claudeplayer.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Warm Claude Code sessions, driven through ClaudeCodeBrain with scripted fake processes instead of the CLI. */
class ClaudeCodeSessionsTest {
    private static final String RATE = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\"}}";

    @TempDir
    Path dir;

    private final List<FakeProc> procs = Collections.synchronizedList(new ArrayList<>());
    private final Deque<List<String>> script = new ArrayDeque<>(); // the events answering each written line
    private final AtomicLong now = new AtomicLong(1_000_000);

    private ClaudeCodeBrain brain(int timeoutSeconds) {
        Settings s = Settings.of(b -> {
            b.claudeCodePath = "claude-fake";
            b.claudeCodeTimeoutSeconds = timeoutSeconds;
        });
        return new ClaudeCodeBrain(s, dir, command -> {
            FakeProc p = new FakeProc();
            procs.add(p);
            return p;
        }, now::get);
    }

    @Test
    void openingTakesTheSpareAndStartsANewOne() throws BrainException {
        ClaudeCodeBrain brain = brain(5);
        brain.prewarm("You are Claude.", new JsonArray());
        assertEquals(1, procs.size(), "the spare is started ahead of the first order");
        script.add(List.of("{\"type\":\"system\",\"subtype\":\"init\"}", RATE, result(1, "[" + MOVE + "]")));

        Brain.Reply reply = brain.send(request(opening()));

        assertEquals("tool_use", reply.stopReason());
        assertEquals(1, procs.get(0).written.size(), "the opening went to the spare");
        assertTrue(text(procs.get(0).written.get(0)).contains("<order>come here</order>"));
        assertEquals(2, procs.size(), "a new spare took its place");
        assertTrue(procs.get(1).written.isEmpty() && procs.get(1).alive);
        assertEquals(RATE, brain.rateLimitEvent());
    }

    @Test
    void laterTurnWritesOnlyTheNewMessages() throws BrainException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(result(1, "[" + MOVE + "]")));
        JsonArray messages = opening();
        Brain.Reply first = brain.send(request(messages));
        script.add(List.of(result(900, "[" + MOVE + "]")));

        Brain.Reply second = brain.send(request(next(messages, first, "update two")));

        FakeProc session = procs.get(0);
        assertEquals(2, session.written.size(), "the same process got the second turn");
        String turn = text(session.written.get(1));
        assertTrue(turn.contains("result of move_to: ok"), turn);
        assertTrue(turn.contains("update two"));
        assertFalse(turn.contains("come here"), "the session already read the opening");
        assertFalse(turn.contains("call move_to"), "nor its own reply");
        assertEquals(900, second.usage().cacheRead());
        assertEquals(2, procs.size(), "no new process for a later turn");
    }

    @Test
    void prefixMismatchRestartsFromTheWholeTranscript() throws BrainException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(result(1, "[" + MOVE + "]")));
        JsonArray messages = opening();
        Brain.Reply first = brain.send(request(messages));
        JsonArray changed = next(messages, first, "update two");
        changed.get(0).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().addProperty("text", "<order>go away</order>");
        script.add(List.of(result(1, "[" + MOVE + "]")));

        brain.send(request(changed));

        assertFalse(procs.get(0).alive, "the old session is stopped");
        String turn = text(procs.get(1).written.get(0));
        assertTrue(turn.contains("go away") && turn.contains("call move_to") && turn.contains("update two"), turn);
    }

    @Test
    void endTurnStopsTheProcess() throws BrainException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(result(1, "[]")));
        Brain.Reply reply = brain.send(request(opening()));
        assertEquals("end_turn", reply.stopReason());
        assertFalse(procs.get(0).alive);
        assertTrue(procs.get(1).alive, "the spare stays for the next order");
    }

    @Test
    void resetKillsTheSessionButNotTheSpare() throws BrainException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(result(1, "[" + MOVE + "]")));
        brain.send(request(opening()));
        new DemoBrain(brain).reset();
        assertFalse(procs.get(0).alive);
        assertTrue(procs.get(1).alive);
    }

    @Test
    void noResultInTimeIsATimeout() {
        ClaudeCodeBrain brain = brain(1);
        script.add(List.of("{\"type\":\"system\"}"));
        BrainException e = assertThrows(BrainException.class, () -> brain.send(request(opening())));
        assertEquals(BrainException.Kind.TIMEOUT, e.kind());
        assertFalse(procs.get(0).alive);
    }

    @Test
    void exitBeforeTheResultReportsStderr() {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(FakeProc.EXIT_WITH_ERROR));
        BrainException e = assertThrows(BrainException.class, () -> brain.send(request(opening())));
        assertEquals(BrainException.Kind.CLI, e.kind());
        assertEquals("Not logged in. Please run /login", e.getMessage());
        assertFalse(procs.get(0).alive);
    }

    @Test
    void idleProcessesAreStopped() throws BrainException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(result(1, "[" + MOVE + "]")));
        brain.send(request(opening()));
        now.addAndGet(9 * 60_000);
        brain.sweepIdle();
        assertTrue(procs.get(0).alive && procs.get(1).alive, "not idle yet");
        now.addAndGet(60_000);
        brain.sweepIdle();
        assertFalse(procs.get(0).alive, "the session");
        assertFalse(procs.get(1).alive, "and the spare");
    }

    @Test
    void scrubRemovesEveryAnthropicVariable() {
        Map<String, String> env = new HashMap<>(Map.of("ANTHROPIC_API_KEY", "k", "ANTHROPIC_AUTH_TOKEN", "t",
                "ANTHROPIC_BASE_URL", "u", "ANTHROPIC_MODEL", "m", "Anthropic_Custom", "c", "PATH", "p", "HOME", "h"));
        ClaudeCodeEnv.scrub(env);
        assertEquals(Map.of("PATH", "p", "HOME", "h"), env);
    }

    @Test
    void scrubRemovesTheCloudProviderSwitches() {
        Map<String, String> env = new HashMap<>(Map.of("CLAUDE_CODE_USE_BEDROCK", "1", "CLAUDE_CODE_USE_VERTEX", "1",
                "claude_code_use_foundry", "1", "AWS_BEARER_TOKEN_BEDROCK", "b", "CLAUDE_CODE_PATH", "keep", "PATH", "p"));
        ClaudeCodeEnv.scrub(env);
        assertEquals(Map.of("CLAUDE_CODE_PATH", "keep", "PATH", "p"), env);
    }

    @Test
    void streamCommandKeepsTheLockdown() {
        List<String> cmd = ClaudeCodeBrain.command("claude.exe", Settings.DEFAULTS, Path.of("s.md"), false, true);
        assertEquals("stream-json", cmd.get(cmd.indexOf("--input-format") + 1));
        assertEquals("stream-json", cmd.get(cmd.indexOf("--output-format") + 1));
        assertTrue(cmd.contains("--verbose") && cmd.contains("--no-session-persistence") && cmd.contains("--strict-mcp-config"));
        assertEquals("", cmd.get(cmd.indexOf("--tools") + 1));
        assertFalse(cmd.contains("--bare"));
    }

    private static final String MOVE = "{\"name\":\"move_to\",\"input\":{\"x\":1,\"y\":64,\"z\":2}}";

    private static String result(int cacheRead, String calls) {
        return "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":\"s-1\","
                + "\"usage\":{\"input_tokens\":3,\"output_tokens\":40,\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":" + cacheRead + "},"
                + "\"structured_output\":{\"say\":\"ok\",\"calls\":" + calls + "}}";
    }

    private static Brain.Request request(JsonArray messages) {
        return new Brain.Request("You are Claude.", new JsonArray(), messages.deepCopy());
    }

    private static JsonArray opening() {
        return JsonParser.parseString("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"<order>come here</order>\"}]}]")
                .getAsJsonArray();
    }

    /** What the loop builds: the reply as the assistant turn, then the results and the update text. */
    private static JsonArray next(JsonArray messages, Brain.Reply reply, String update) {
        JsonArray m = messages.deepCopy();
        JsonObject assistant = new JsonObject();
        assistant.addProperty("role", "assistant");
        assistant.add("content", reply.content());
        m.add(assistant);
        m.add(JsonParser.parseString("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\""
                + reply.toolCalls().get(0).id() + "\",\"content\":\"ok\"},{\"type\":\"text\",\"text\":\"" + update + "\"}]}"));
        return m;
    }

    private static String text(String line) {
        JsonObject o = JsonParser.parseString(line).getAsJsonObject();
        assertEquals("user", o.get("type").getAsString());
        return o.getAsJsonObject("message").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    /** A pipe end the test writes and the session reads; closing it is EOF. */
    private static final class Pipe extends InputStream {
        private final BlockingQueue<Integer> bytes = new LinkedBlockingQueue<>();

        void write(String s) {
            for (byte b : s.getBytes(StandardCharsets.UTF_8)) bytes.add(b & 0xff);
        }

        void close0() {
            bytes.add(-1);
        }

        @Override
        public int read() {
            try {
                int b = bytes.take();
                if (b < 0) bytes.add(-1);
                return b;
            } catch (InterruptedException e) {
                return -1;
            }
        }

        /** Like a real pipe: blocks for the first byte only, then returns what has arrived. */
        @Override
        public int read(byte[] b, int off, int len) {
            int first = read();
            if (first < 0) return -1;
            b[off] = (byte) first;
            int n = 1;
            while (n < len && bytes.peek() != null && bytes.peek() >= 0) b[off + n++] = (byte) (int) bytes.poll();
            return n;
        }
    }

    /** Answers each stdin line with the next scripted events. */
    private final class FakeProc implements ClaudeCodeSessions.Proc {
        static final String EXIT_WITH_ERROR = "exit";
        final List<String> written = Collections.synchronizedList(new ArrayList<>());
        final Pipe out = new Pipe();
        final Pipe err = new Pipe();
        volatile boolean alive = true;

        @Override
        public OutputStream stdin() {
            return new OutputStream() {
                private final ByteArrayOutputStream line = new ByteArrayOutputStream();

                @Override
                public void write(int b) {
                    if (b != '\n') {
                        line.write(b);
                        return;
                    }
                    written.add(line.toString(StandardCharsets.UTF_8));
                    line.reset();
                    List<String> events = script.poll();
                    if (events == null) return;
                    for (String e : events) {
                        if (e.equals(EXIT_WITH_ERROR)) {
                            err.write("Not logged in. Please run /login\nmore\n");
                            err.close0();
                            out.close0();
                            alive = false;
                        } else {
                            out.write(e + "\n");
                        }
                    }
                }
            };
        }

        @Override
        public InputStream stdout() {
            return out;
        }

        @Override
        public InputStream stderr() {
            return err;
        }

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public void kill() {
            alive = false;
            out.close0();
            err.close0();
        }
    }
}
