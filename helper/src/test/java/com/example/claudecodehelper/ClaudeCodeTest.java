package com.example.claudecodehelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Warm sessions, the spare, continuation, the cap and the bridge-bug fixes, all on a scripted fake claude. */
@Timeout(30)
class ClaudeCodeTest {
    @TempDir
    Path dir;

    private final FakeClaude fake = new FakeClaude();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private ClaudeCode cc;

    private ClaudeCode cc(int timeoutSeconds, int maxSessions) {
        cc = ClaudeCode.builder().claudePath("claude-fake").model("claude-haiku-5-5").systemPrompt("Be brief.")
                .turnTimeout(Duration.ofSeconds(timeoutSeconds)).maxSessions(maxSessions).workDir(dir)
                .launcher(fake).clock(now::get).build();
        return cc;
    }

    @AfterEach
    void close() {
        if (cc != null) cc.close();
    }

    @Test
    void openTakesTheSpareAndStartsANewOne() throws Exception {
        ClaudeCode cc = cc(5, 4);
        cc.prewarm(cc.defaults());
        assertEquals(1, fake.procs.size(), "the spare starts ahead of the first call");
        fake.script.add(List.of("{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"claude-haiku-5-5\"}", FakeClaude.RATE,
                FakeClaude.result("hi there", 0, null)));

        try (ClaudeCode.Session s = cc.open()) {
            Reply r = s.send("say hi");
            assertEquals("hi there", r.text());
            assertEquals("claude-haiku-5-5", r.model());
            assertEquals(40, r.usage().output());
            assertNotNull(r.rateLimit(), "the rate_limit_event of this turn");
            assertEquals("say hi", FakeClaude.text(fake.proc(0).written.get(0)), "the turn went to the spare");
            assertEquals(2, fake.procs.size(), "a new spare took its place");
            assertTrue(fake.proc(1).written.isEmpty() && fake.proc(1).alive);
        }
        assertFalse(waitDead(fake.proc(0)), "closing the session stops its process");
        assertNotNull(cc.lastRateLimit());
    }

    @Test
    void aSessionKeepsItsProcessAcrossTurns() throws Exception {
        ClaudeCode cc = cc(5, 4);
        fake.script.add(List.of(FakeClaude.result("one")));
        fake.script.add(List.of(FakeClaude.result("two", 900, null)));
        try (ClaudeCode.Session s = cc.open()) {
            s.send("first");
            Reply second = s.send("second");
            assertEquals(2, fake.proc(0).written.size(), "the same process got the second turn");
            assertEquals(900, second.usage().cacheRead());
            assertNull(second.rateLimit(), "no rate_limit_event came this turn");
        }
    }

    @Test
    void imagesGoOutAsImageBlocks() throws Exception {
        ClaudeCode cc = cc(5, 4);
        fake.script.add(List.of(FakeClaude.result("a red dot")));
        try (ClaudeCode.Session s = cc.open()) {
            s.send(List.of(Blocks.text("what is this?"), Blocks.png(new byte[] {1, 2, 3})));
        }
        JsonArray content = FakeClaude.content(fake.proc(0).written.get(0));
        assertEquals("image", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("AQID", content.get(1).getAsJsonObject().getAsJsonObject("source").get("data").getAsString());
        assertThrows(IllegalArgumentException.class, () -> Blocks.image("image/gif", "AQID"));
    }

    @Test
    void aLeaseContinuesWhenTheHistoryExtendsWhatTheSessionSaw() throws Exception {
        ClaudeCode cc = cc(5, 4);
        JsonArray first = history("{\"role\":\"user\",\"content\":\"hello\"}");
        fake.script.add(List.of(FakeClaude.result("hi")));
        try (ClaudeCode.Lease lease = cc.lease(cc.defaults(), first)) {
            assertEquals(0, lease.from());
            assertFalse(lease.reused());
            lease.send(blocks("hello"));
            lease.keep(with(first, "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}"));
        }
        // the client echoes the reply back with cache_control moved and a null field: still the same history
        JsonArray second = history("{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\",\"cache_control\":{\"type\":\"ephemeral\"}}]}",
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\",\"citations\":null}]}",
                "{\"role\":\"user\",\"content\":\"and again\"}");
        fake.script.add(List.of(FakeClaude.result("again", 1500, null)));
        try (ClaudeCode.Lease lease = cc.lease(cc.defaults(), second)) {
            assertTrue(lease.reused(), "the warm session continues");
            assertEquals(2, lease.from());
            assertEquals(1500, lease.send(blocks("and again")).usage().cacheRead());
            lease.keep(with(second, "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"again\"}]}"));
        }
        assertEquals(2, fake.proc(0).written.size());

        // an edited earlier message is a different conversation: a fresh session
        JsonArray edited = history("{\"role\":\"user\",\"content\":\"HELLO\"}",
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}",
                "{\"role\":\"user\",\"content\":\"and again\"}");
        fake.script.add(List.of(FakeClaude.result("fresh")));
        try (ClaudeCode.Lease lease = cc.lease(cc.defaults(), edited)) {
            assertEquals(0, lease.from());
            lease.send(blocks("whole transcript"));
            lease.keep(edited);
        }
        assertTrue(fake.proc(0).alive, "the other conversation stays warm");
        assertEquals(2, cc.liveSessions());
    }

    @Test
    void otherOptionsNeverContinue() throws Exception {
        ClaudeCode cc = cc(5, 4);
        JsonArray first = history("{\"role\":\"user\",\"content\":\"hello\"}");
        fake.script.add(List.of(FakeClaude.result("hi")));
        try (ClaudeCode.Lease lease = cc.lease(cc.defaults(), first)) {
            lease.send(blocks("hello"));
            lease.keep(with(first, "{\"role\":\"assistant\",\"content\":\"hi\"}"));
        }
        JsonArray second = with(with(first, "{\"role\":\"assistant\",\"content\":\"hi\"}"), "{\"role\":\"user\",\"content\":\"more\"}");
        try (ClaudeCode.Lease lease = cc.lease(cc.defaults().withModel("claude-sonnet-5-5"), second)) {
            assertEquals(0, lease.from(), "a different model is a different command");
        }
    }

    @Test
    void pastTheCapARequestIsBusyAndIdleSessionsAreEvicted() throws Exception {
        ClaudeCode cc = cc(5, 2);
        ClaudeCode.Lease a = cc.lease(cc.defaults(), history("{\"role\":\"user\",\"content\":\"a\"}"));
        ClaudeCode.Lease b = cc.lease(cc.defaults(), history("{\"role\":\"user\",\"content\":\"b\"}"));
        ClaudeCodeException busy = assertThrows(ClaudeCodeException.class,
                () -> cc.lease(cc.defaults(), history("{\"role\":\"user\",\"content\":\"c\"}")));
        assertEquals(ClaudeCodeException.Kind.BUSY, busy.kind());
        a.keep(history("{\"role\":\"user\",\"content\":\"a\"}", "{\"role\":\"assistant\",\"content\":\"x\"}"));
        try (ClaudeCode.Lease c = cc.lease(cc.defaults(), history("{\"role\":\"user\",\"content\":\"c\"}"))) {
            assertEquals(2, cc.liveSessions(), "the idle one made room");
        }
        b.close();
    }

    @Test
    void idleSweepStopsIdleProcessesButNeverABusyOne() throws Exception {
        ClaudeCode cc = cc(5, 4);
        JsonArray h = history("{\"role\":\"user\",\"content\":\"a\"}");
        fake.script.add(List.of(FakeClaude.result("x")));
        ClaudeCode.Lease busy = cc.lease(cc.defaults(), h);
        busy.send(blocks("a")); // starts the spare too
        now.addAndGet(11 * 60_000);
        cc.sweepIdle();
        assertTrue(fake.proc(0).alive, "a session mid-request is never idle");
        assertFalse(waitDead(fake.proc(1)), "the unused spare is stopped");
        busy.keep(with(h, "{\"role\":\"assistant\",\"content\":\"x\"}"));
        now.addAndGet(9 * 60_000);
        cc.sweepIdle();
        assertTrue(fake.proc(0).alive, "idle for 9 minutes only");
        now.addAndGet(60_000);
        cc.sweepIdle();
        assertFalse(waitDead(fake.proc(0)));
        assertEquals(0, cc.liveSessions());
    }

    @Test
    void theTimeoutCoversAStalledStdinWrite() throws Exception {
        ClaudeCode cc = cc(1, 4);
        fake.stallStdin = true;
        ClaudeCode.Session s = cc.open();
        long t0 = System.nanoTime();
        ClaudeCodeException e = assertThrows(ClaudeCodeException.class, () -> s.send("hello"));
        assertEquals(ClaudeCodeException.Kind.TIMEOUT, e.kind());
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(3), "the caller is not stuck in the write");
        assertFalse(waitDead(fake.proc(0)));
        assertFalse(s.alive());
    }

    @Test
    void closeDuringAStartKillsTheProcessOnceItStarts() throws Exception {
        ClaudeCode cc = cc(5, 4);
        fake.startGate = new CountDownLatch(1);
        CompletableFuture<ClaudeCode.Session> opening = CompletableFuture.supplyAsync(() -> {
            try {
                return cc.open();
            } catch (ClaudeCodeException e) {
                throw new RuntimeException(e);
            }
        });
        FakeClaude.await(() -> cc.liveSessions() > 0); // the start is under way
        cc.close();
        fake.startGate.countDown();
        Exception e = assertThrows(Exception.class, () -> opening.get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause().getCause() instanceof ClaudeCodeException c && c.kind() == ClaudeCodeException.Kind.CANCELLED, e.toString());
        assertFalse(waitDead(fake.proc(0)), "the process that finished starting after close is killed");
    }

    @Test
    void killsRunOnTheReaperThreadNotTheCaller() throws Exception {
        ClaudeCode cc = cc(5, 4);
        ClaudeCode.Session s = cc.open();
        fake.killGate = new CountDownLatch(1); // a slow tree walk
        long t0 = System.nanoTime();
        s.cancel();
        assertTrue(System.nanoTime() - t0 < TimeUnit.MILLISECONDS.toNanos(500), "cancel doesn't wait on the kill");
        fake.killGate.countDown();
        assertFalse(waitDead(fake.proc(0)));
        assertEquals("claude-code-reaper", fake.killThread);
    }

    @Test
    void cancelEndsATurnInFlight() throws Exception {
        ClaudeCode cc = cc(30, 4);
        ClaudeCode.Session s = cc.open(); // no script: the turn waits
        CompletableFuture<Void> turn = CompletableFuture.runAsync(() -> {
            try {
                s.send("hello");
            } catch (ClaudeCodeException e) {
                throw new RuntimeException(e);
            }
        });
        FakeClaude.await(() -> !fake.proc(0).written.isEmpty());
        s.cancel();
        Exception e = assertThrows(Exception.class, () -> turn.get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause().getCause() instanceof ClaudeCodeException c && c.kind() == ClaudeCodeException.Kind.CANCELLED);
    }

    @Test
    void exitBeforeTheResultSaysNotSignedIn() throws Exception {
        ClaudeCode cc = cc(5, 4);
        fake.script.add(List.of(FakeClaude.EXIT_NOT_LOGGED_IN));
        ClaudeCode.Session s = cc.open();
        ClaudeCodeException e = assertThrows(ClaudeCodeException.class, () -> s.send("hello"));
        assertEquals(ClaudeCodeException.Kind.NOT_SIGNED_IN, e.kind());
        assertEquals("Not logged in · Please run /login", e.getMessage());
    }

    @Test
    void anErrorResultIsClassified() throws Exception {
        ClaudeCode cc = cc(5, 4);
        fake.script.add(List.of("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":429,"
                + "\"result\":\"usage limit reached\"}"));
        ClaudeCode.Session s = cc.open();
        ClaudeCodeException e = assertThrows(ClaudeCodeException.class, () -> s.send("hello"));
        assertEquals(ClaudeCodeException.Kind.RATE_LIMITED, e.kind());
        assertEquals(429, e.status());
    }

    @Test
    void aSchemaSessionNeedsStructuredOutput() throws Exception {
        ClaudeCode cc = cc(5, 4);
        Options o = cc.defaults().withJsonSchema("{ \"type\" : \"object\" }");
        fake.script.add(List.of(FakeClaude.result("", 0, "{\"answer\":4}")));
        fake.script.add(List.of(FakeClaude.result("four")));
        try (ClaudeCode.Session s = cc.open(o)) {
            assertEquals(4, s.send("2+2").structured().get("answer").getAsInt());
            ClaudeCodeException e = assertThrows(ClaudeCodeException.class, () -> s.send("again"));
            assertEquals(ClaudeCodeException.Kind.BAD_OUTPUT, e.kind());
        }
        List<String> cmd = fake.commands.get(0);
        assertEquals("{\"type\":\"object\"}", Commands.WINDOWS
                ? cmd.get(cmd.indexOf("--json-schema") + 1).replace("\\\"", "\"") : cmd.get(cmd.indexOf("--json-schema") + 1));
    }

    @Test
    void anOversizedLineEndsTheTurn() throws Exception {
        cc = ClaudeCode.builder().claudePath("claude-fake").workDir(dir).launcher(fake).clock(now::get)
                .turnTimeout(Duration.ofSeconds(5)).maxLineBytes(1000).build();
        ClaudeCode.Session s = cc.open();
        fake.script.add(List.of("{\"type\":\"assistant\",\"pad\":\"" + "x".repeat(2000) + "\"}"));
        ClaudeCodeException e = assertThrows(ClaudeCodeException.class, () -> s.send("hello"));
        assertEquals(ClaudeCodeException.Kind.BAD_OUTPUT, e.kind());
    }

    @Test
    void theCommandKeepsTheLockdown() throws Exception {
        ClaudeCode cc = cc(5, 4);
        List<String> cmd = cc.command(cc.defaults().withEffort("low"));
        assertEquals("claude-fake", cmd.get(0));
        assertEquals("stream-json", cmd.get(cmd.indexOf("--input-format") + 1));
        assertEquals("stream-json", cmd.get(cmd.indexOf("--output-format") + 1));
        assertTrue(cmd.contains("--verbose") && cmd.contains("--strict-mcp-config") && cmd.contains("--no-session-persistence")
                && cmd.contains("--disable-slash-commands"));
        assertEquals("", cmd.get(cmd.indexOf("--tools") + 1));
        assertEquals("", cmd.get(cmd.indexOf("--setting-sources") + 1));
        assertFalse(cmd.contains("--bare"), "--bare ignores the subscription login");
        assertFalse(cmd.contains("--json-schema"), "no schema, plain text");
        assertEquals("claude-haiku-5-5", cmd.get(cmd.indexOf("--model") + 1));
        assertEquals("low", cmd.get(cmd.indexOf("--effort") + 1));
        Path system = Path.of(cmd.get(cmd.indexOf("--system-prompt-file") + 1));
        assertEquals("Be brief.", Files.readString(system));
        assertEquals(dir, system.getParent());
    }

    @Test
    void optionsRejectWhatCouldBeReadAsAFlag() {
        assertThrows(IllegalArgumentException.class, () -> new Options("--dangerously-skip-permissions", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Options("claude-haiku-5-5 --x", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Options(null, "--max", null, null));
        assertThrows(IllegalArgumentException.class, () -> new Options(null, null, null, "[1]"));
        assertEquals(Options.DEFAULT_SYSTEM, new Options(null, null, " ", null).systemPrompt());
    }

    @Test
    void windowsArgumentsKeepTheirQuotes() {
        assertEquals("{\\\"a\\\":1}", Commands.windowsArg("{\"a\":1}"));
        // an escaped quote inside a JSON string: \" becomes \\\" so the child reads \" again
        assertEquals("{\\\"d\\\":\\\"say \\\\\\\"hi\\\\\\\"\\\"}", Commands.windowsArg("{\"d\":\"say \\\"hi\\\"\"}"));
        assertEquals("a\\b", Commands.windowsArg("a\\b"));
    }

    @Test
    void findPrefersTheConfiguredPathThenPathThenLocalBin(@TempDir Path home) throws Exception {
        assertEquals("C:\\x\\claude.exe", Commands.find(" C:\\x\\claude.exe ", "", home, true));
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path local = Files.createDirectories(home.resolve(".local").resolve("bin"));
        Files.writeString(local.resolve("claude"), "");
        assertEquals(local.resolve("claude").toString(), Commands.find("", bin.toString(), home, false));
        Files.writeString(bin.resolve("claude"), "");
        assertEquals(bin.resolve("claude").toString(), Commands.find("", bin.toString(), home, false));
        assertNull(Commands.find("", "", Files.createDirectories(home.resolve("empty")), false));
    }

    @Test
    void redactedHidesImageData() {
        JsonObject img = Blocks.png(new byte[300]);
        String shown = Blocks.redacted(img).toString();
        assertFalse(shown.contains("AAAA"));
        assertTrue(shown.contains("<image 300 bytes>"), shown);
    }

    private boolean waitDead(FakeClaude.Proc p) throws InterruptedException {
        for (int i = 0; i < 200 && p.alive; i++) Thread.sleep(10);
        return p.alive;
    }

    private static JsonArray history(String... messages) {
        JsonArray a = new JsonArray();
        for (String m : messages) a.add(JsonParser.parseString(m));
        return a;
    }

    private static JsonArray with(JsonArray h, String message) {
        JsonArray a = h.deepCopy();
        a.add(JsonParser.parseString(message));
        return a;
    }

    private static JsonArray blocks(String text) {
        JsonArray a = new JsonArray();
        a.add(Blocks.text(text));
        return a;
    }

    @Test
    void sameObjectHelpers() {
        assertSame(Usage.NONE, Usage.of(null));
    }
}
