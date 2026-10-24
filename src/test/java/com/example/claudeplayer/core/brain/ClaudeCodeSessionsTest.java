package com.example.claudeplayer.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Warm Claude Code sessions, driven through ClaudeCodeBrain with scripted fake processes instead of the CLI. */
class ClaudeCodeSessionsTest {
    private static final String RATE = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\"}}";

    @TempDir
    Path dir;

    private final List<FakeProc> procs = Collections.synchronizedList(new ArrayList<>());
    private final Deque<List<String>> script = new ArrayDeque<>(); // the events answering each written line
    private final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>()); // one per started process
    private final AtomicLong now = new AtomicLong(1_000_000);
    private volatile boolean stallStdin; // the children never read their stdin
    private final CountDownLatch writing = new CountDownLatch(1); // a write to a stalled child has begun
    private final CountDownLatch lineRead = new CountDownLatch(1); // a child has read a whole line
    private final AtomicInteger launches = new AtomicInteger();
    private volatile int holdLaunch = -1; // this start (0 = the first) waits for startGate
    private final CountDownLatch starting = new CountDownLatch(1); // the held start has begun
    private final CountDownLatch startGate = new CountDownLatch(1);

    private ClaudeCodeBrain brain(int timeoutSeconds) {
        return brain(timeoutSeconds, Runnable::run, null); // kills run at once, so the checks below see them
    }

    private ClaudeCodeBrain brain(int timeoutSeconds, Executor reaper) {
        return brain(timeoutSeconds, reaper, null);
    }

    private ClaudeCodeBrain brain(int timeoutSeconds, UsageLedger ledger) {
        return brain(timeoutSeconds, Runnable::run, ledger);
    }

    /** {@code ledger} null: the brain keeps its own, in memory. */
    private ClaudeCodeBrain brain(int timeoutSeconds, Executor reaper, UsageLedger ledger) {
        Settings s = Settings.of(b -> {
            b.claudeCodePath = "claude-fake";
            b.claudeCodeTimeoutSeconds = timeoutSeconds;
        });
        ClaudeCodeSessions.Launcher launcher = command -> {
            if (launches.getAndIncrement() == holdLaunch) {
                starting.countDown();
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
            FakeProc p = new FakeProc();
            procs.add(p);
            commands.add(command);
            return p;
        };
        return ledger == null ? new ClaudeCodeBrain(s, dir, launcher, now::get, reaper)
                : new ClaudeCodeBrain(s, dir, launcher, now::get, reaper, ledger);
    }

    private UsageLedger ledger() {
        return new UsageLedger(List.of(0.8, 0.9), new UsageLedger.Store() {
            @Override
            public JsonObject load() {
                return new JsonObject();
            }

            @Override
            public void save(JsonObject state) {}
        }, now::get);
    }

    /** Runs a model call on its own thread, as the brain thread would. */
    private static Future<Brain.Reply> async(ClaudeCodeBrain brain, JsonArray messages) {
        FutureTask<Brain.Reply> call = new FutureTask<>(() -> brain.send(request(messages)));
        Thread t = new Thread(call, "test-brain");
        t.setDaemon(true);
        t.start();
        return call;
    }

    private static void assertCancelled(Future<Brain.Reply> call) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS),
                "the call ends with an error, not a reply");
        BrainException b = assertInstanceOf(BrainException.class, e.getCause());
        assertEquals(BrainException.Kind.INTERRUPTED, b.kind(), b.getMessage());
    }

    /** A rate_limit_event line; resets are epoch seconds, well after the fake clock. */
    private static String limitLine(String status, double fiveHour, double sevenDay) {
        return "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"" + status
                + "\",\"unifiedWindows\":{\"five_hour\":{\"utilization\":" + fiveHour + ",\"resetsAt\":20000},"
                + "\"seven_day\":{\"utilization\":" + sevenDay + ",\"resetsAt\":400000}}}}";
    }

    private static final String LIMIT_ERROR = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
            + "\"api_error_status\":429,\"result\":\"usage limit reached\"}";

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
    void aTurnsRateLimitEventAndTokensFeedTheLedger() throws BrainException {
        UsageLedger ledger = ledger();
        ClaudeCodeBrain brain = brain(5, ledger);
        script.add(List.of(limitLine("allowed_warning", 0.5, 0.85), result(1, "[" + MOVE + "]")));
        JsonArray messages = opening();
        Brain.Reply first = brain.send(request(messages));

        assertEquals(0.85, ledger.utilization(), 1e-9);
        assertEquals(1, ledger.drainWarnings().size(), "7-day crossed 80%");
        assertTrue(ledger.statusLines().contains("tokens: order 44, today 44"), ledger.statusLines().toString());

        // A later turn brings no new event: the old one is not fed twice, but its tokens are added.
        script.add(List.of(result(1, "[" + MOVE + "]")));
        brain.send(request(next(messages, first, "update two")));
        assertTrue(ledger.drainWarnings().isEmpty());
        assertTrue(ledger.statusLines().contains("tokens: order 88, today 88"), ledger.statusLines().toString());
    }

    @Test
    void aNewOrdersTranscriptStartsItsTokenCountOver() throws BrainException {
        UsageLedger ledger = ledger();
        ClaudeCodeBrain brain = brain(5, ledger);
        script.add(List.of(result(1, "[]")));
        brain.send(request(opening()));
        script.add(List.of(result(1, "[]")));
        brain.send(request(opening()));
        assertTrue(ledger.statusLines().contains("tokens: order 44, today 88"), ledger.statusLines().toString());
    }

    @Test
    void aFailedTurnAtTheLimitSaysSoWithTheResetTime() {
        UsageLedger ledger = ledger();
        ClaudeCodeBrain brain = brain(5, ledger);
        script.add(List.of(limitLine("rejected", 0.4, 1.0), LIMIT_ERROR));

        BrainException e = assertThrows(BrainException.class, () -> brain.send(request(opening())));

        assertEquals(BrainException.Kind.LIMIT, e.kind());
        assertFalse(e.kind().retryable);
        assertEquals("Claude Code limit reached, resets " + ledger.resetsText(), e.chatMessage("ANTHROPIC_API_KEY"));
        assertFalse(ledger.resetsText().isEmpty());
        assertFalse(procs.get(0).alive, "the failed session is ended");
    }

    @Test
    void theLimitMessageDropsTheResetWhenNoneIsKnown() {
        UsageLedger ledger = ledger();
        ledger.onRateLimitEvent("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\"}}");
        ClaudeCodeBrain brain = brain(5, ledger);
        script.add(List.of(LIMIT_ERROR));

        BrainException e = assertThrows(BrainException.class, () -> brain.send(request(opening())));

        assertEquals("Claude Code limit reached", e.chatMessage("x"));
    }

    @Test
    void aFailureBelowTheLimitStaysItself() {
        ClaudeCodeBrain brain = brain(5, ledger());
        script.add(List.of(limitLine("allowed", 0.2, 0.3), LIMIT_ERROR));

        BrainException e = assertThrows(BrainException.class, () -> brain.send(request(opening())));

        assertEquals(BrainException.Kind.RATE_LIMITED, e.kind());
    }

    @Test
    void anOrderThatStartsWithCavemanEndsItsSystemPromptWithTheRules() throws BrainException, IOException {
        ClaudeCodeBrain brain = brain(5);
        CavemanSkill skill = new CavemanSkill("Me caveman. Few word.", "abc123", Path.of("SKILL.md"));
        brain.caveman(skill);
        script.add(List.of(result(1, "[]")));
        script.add(List.of(result(1, "[]")));

        brain.send(new Brain.Request("You are Claude.", new JsonArray(), opening(), true, null));
        brain.send(request(opening()));

        String on = systemFile(commands.get(0));
        assertTrue(on.endsWith(skill.promptSection()), on);
        String off = systemFile(commands.get(2));
        assertFalse(off.contains("Me caveman"), "an order that starts without caveman has no rules");
        assertEquals(off + skill.promptSection(), on, "the rules are the only difference");
    }

    @Test
    void aSpareBuiltWithoutCavemanIsNotUsedForACavemanOrder() throws BrainException, IOException {
        ClaudeCodeBrain brain = brain(5);
        brain.caveman(new CavemanSkill("Me caveman.", "abc123", Path.of("SKILL.md")));
        brain.prewarm("You are Claude.", new JsonArray());
        script.add(List.of(result(1, "[" + MOVE + "]")));

        brain.send(new Brain.Request("You are Claude.", new JsonArray(), opening(), true, null));

        assertTrue(procs.get(0).written.isEmpty(), "the plain spare didn't take the order");
        assertFalse(procs.get(0).alive, "and was replaced");
        assertEquals(1, procs.get(1).written.size(), "a process with the rules did");
        assertTrue(systemFile(commands.get(1)).contains("Me caveman."));
        assertTrue(systemFile(commands.get(2)).contains("Me caveman."), "the next spare matches the order just run");
    }

    @Test
    void aMaxEffortRequestOnAWarmSessionRestartsFromTheWholeTranscriptAndKeepsTheSpareNormal() throws BrainException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(result(1, "[" + MOVE + "]")));
        JsonArray messages = opening();
        Brain.Reply first = brain.send(request(messages));
        assertEquals("high", effort(commands.get(0)));
        script.add(List.of(result(1, "[" + MOVE + "]")));

        JsonArray two = next(messages, first, "update two");
        Brain.Reply second = brain.send(new Brain.Request("You are Claude.", new JsonArray(), two.deepCopy(), false, "max"));

        assertFalse(procs.get(0).alive, "the normal-effort session is replaced");
        assertFalse(procs.get(1).alive, "and so is the normal spare, which can't serve a max order");
        assertEquals("max", effort(commands.get(2)));
        String turn = text(procs.get(2).written.get(0));
        assertTrue(turn.contains("come here") && turn.contains("call move_to") && turn.contains("update two"), turn);
        assertEquals("high", effort(commands.get(3)), "the new spare is a normal-effort process");
        assertTrue(procs.get(3).written.isEmpty() && procs.get(3).alive);

        // The reflex drops the order back to the normal effort: the spare takes over with the whole transcript.
        script.add(List.of(result(1, "[]")));
        brain.send(new Brain.Request("You are Claude.", new JsonArray(), next(two, second, "update three"), false, null));
        assertFalse(procs.get(2).alive);
        String back = text(procs.get(3).written.get(0));
        assertTrue(back.contains("come here") && back.contains("update three"), back);
    }

    @Test
    void aMaxEffortOrderThatStartsMaxDoesNotRestartOnItsLaterTurns() throws BrainException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(result(1, "[" + MOVE + "]")));
        JsonArray messages = opening();
        Brain.Reply first = brain.send(new Brain.Request("You are Claude.", new JsonArray(), messages.deepCopy(), false, "max"));
        assertEquals("max", effort(commands.get(0)));
        script.add(List.of(result(1, "[" + MOVE + "]")));

        brain.send(new Brain.Request("You are Claude.", new JsonArray(), next(messages, first, "update two"), false, "max"));

        assertEquals(2, procs.get(0).written.size(), "the same session got the second turn");
        assertTrue(procs.get(0).alive);
        assertFalse(text(procs.get(0).written.get(1)).contains("come here"), "only the new messages");
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
    void aResetBetweenCallsDoesntCancelTheNext() throws BrainException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(result(1, "[" + MOVE + "]")));
        brain.send(request(opening()));
        brain.reset();
        script.add(List.of(result(1, "[" + MOVE + "]")));
        assertEquals("tool_use", brain.send(request(opening())).stopReason());
        assertEquals(1, procs.get(1).written.size(), "the new order took the spare");
    }

    @Test
    void aResetWhileTheChildStartsCancelsTheCall() throws InterruptedException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(result(1, "[" + MOVE + "]")));
        holdLaunch = 0;
        Future<Brain.Reply> call = async(brain, opening());
        assertTrue(starting.await(5, TimeUnit.SECONDS));
        brain.reset();
        startGate.countDown();
        assertCancelled(call);
        assertTrue(procs.get(0).written.isEmpty(), "the prompt never went out");
        assertFalse(procs.get(0).alive, "the child is stopped");
    }

    @Test
    void aResetMidWriteCancelsTheCall() throws InterruptedException {
        ClaudeCodeBrain brain = brain(5);
        stallStdin = true;
        Future<Brain.Reply> call = async(brain, opening());
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        brain.reset();
        assertCancelled(call);
        assertFalse(procs.get(0).alive);
    }

    @Test
    void aResetWhileTheModelThinksCancelsTheCall() throws InterruptedException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of("{\"type\":\"system\",\"subtype\":\"init\"}")); // read, but no answer yet
        Future<Brain.Reply> call = async(brain, opening());
        assertTrue(lineRead.await(5, TimeUnit.SECONDS));
        brain.reset();
        assertCancelled(call);
        assertFalse(procs.get(0).alive);
    }

    @Test
    void aResetAfterTheAnswerArrivedStillCancelsTheCall() throws InterruptedException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(result(1, "[" + MOVE + "]")));
        holdLaunch = 1; // the next spare's start, after the prompt went out
        Future<Brain.Reply> call = async(brain, opening());
        assertTrue(starting.await(5, TimeUnit.SECONDS));
        assertTrue(lineRead.await(5, TimeUnit.SECONDS));
        brain.reset();
        startGate.countDown();
        assertCancelled(call);
        assertFalse(procs.get(0).alive);
        assertTrue(procs.get(1).alive, "the spare stays");
    }

    @Test
    void aResetOnlyFlagsTheCallAndLeavesTheKillToTheReaper() throws InterruptedException {
        BlockingQueue<Runnable> reaper = new LinkedBlockingQueue<>(); // the test runs the kills by hand
        ClaudeCodeBrain brain = brain(5, reaper::add);
        script.add(List.of("{\"type\":\"system\",\"subtype\":\"init\"}"));
        Future<Brain.Reply> call = async(brain, opening());
        assertTrue(lineRead.await(5, TimeUnit.SECONDS));

        brain.reset(); // what AgentLoop.abandon does on the server thread

        assertTrue(procs.get(0).alive, "no process walk or pid file on the resetting thread");
        assertCancelled(call); // woken by the flag, not by the process dying
        assertFalse(reaper.isEmpty(), "the kill waits for the reaper");
        for (Runnable kill; (kill = reaper.poll()) != null; ) kill.run();
        assertFalse(procs.get(0).alive, "the reaper stops it");
        assertTrue(procs.get(1).alive, "the spare stays");
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
    void aChildThatNeverReadsItsPromptTimesOut() {
        ClaudeCodeBrain brain = brain(1);
        stallStdin = true;
        BrainException e = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(BrainException.class, () -> brain.send(request(opening()))),
                "the timeout covers the write too");
        assertEquals(BrainException.Kind.TIMEOUT, e.kind());
        assertFalse(procs.get(0).alive, "the stalled child is killed");
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
    void theSweepNeverStopsTheSessionALaterTurnClaimed() throws BrainException {
        ClaudeCodeBrain brain = brain(5);
        script.add(List.of(result(1, "[" + MOVE + "]")));
        JsonArray messages = opening();
        Brain.Reply first = brain.send(request(messages));
        now.addAndGet(10 * 60_000); // a long action: the session has idled the whole time
        brain.picked = brain::sweepIdle;
        script.add(List.of(result(1, "[" + MOVE + "]")));

        Brain.Reply second = brain.send(request(next(messages, first, "update two")));

        assertEquals("tool_use", second.stopReason());
        assertEquals(2, procs.get(0).written.size(), "the claimed session answered");
        assertTrue(procs.get(0).alive);
    }

    @Test
    void theSweepNeverStopsTheSpareAnOpeningClaimed() throws BrainException {
        ClaudeCodeBrain brain = brain(5);
        brain.prewarm("You are Claude.", new JsonArray());
        now.addAndGet(10 * 60_000 - 1); // the spare is a moment from idle
        brain.picked = () -> {
            now.addAndGet(2);
            brain.sweepIdle();
        };
        script.add(List.of(result(1, "[" + MOVE + "]")));

        assertEquals("tool_use", brain.send(request(opening())).stopReason());
        assertEquals(1, procs.get(0).written.size(), "the opening went to the spare it took");
        assertTrue(procs.get(0).alive);
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

    /** The value after --effort in a command. */
    private static String effort(List<String> command) {
        return command.get(command.indexOf("--effort") + 1);
    }

    /** The system prompt file a process was started with. */
    private static String systemFile(List<String> command) throws IOException {
        return java.nio.file.Files.readString(Path.of(command.get(command.indexOf("--system-prompt-file") + 1)));
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

    /**
     * Answers each stdin line with the next scripted events. Like a real pipe, a write after {@link #kill} fails;
     * with {@link #stallStdin} the child never reads, so a write blocks until the process is killed.
     */
    private final class FakeProc implements ClaudeCodeSessions.Proc {
        static final String EXIT_WITH_ERROR = "exit";
        final List<String> written = Collections.synchronizedList(new ArrayList<>());
        final Pipe out = new Pipe();
        final Pipe err = new Pipe();
        final CountDownLatch killed = new CountDownLatch(1);
        volatile boolean alive = true;

        @Override
        public OutputStream stdin() {
            return new OutputStream() {
                private final ByteArrayOutputStream line = new ByteArrayOutputStream();

                @Override
                public void write(int b) throws IOException {
                    if (stallStdin) {
                        writing.countDown();
                        try {
                            killed.await();
                        } catch (InterruptedException e) {
                            throw new InterruptedIOException();
                        }
                    }
                    if (!alive) throw new IOException("The pipe is being closed");
                    if (b != '\n') {
                        line.write(b);
                        return;
                    }
                    written.add(line.toString(StandardCharsets.UTF_8));
                    line.reset();
                    try {
                        answer(script.poll());
                    } finally {
                        lineRead.countDown(); // after the answer is on stdout
                    }
                }

                private void answer(List<String> events) {
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
            killed.countDown();
        }
    }
}
