package com.example.claudeplayer.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.StateSnapshot;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionQueue;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.chat.Conversation;
import com.example.claudeplayer.core.memory.Memory;
import com.example.claudeplayer.core.safety.Guardrails;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The loop against a scripted brain. Both executors are manual queues, so a test decides exactly when a model
 * call runs and when its reply lands, like the server thread and the brain thread would.
 */
class AgentLoopTest {
    private final Deque<Runnable> brainThread = new ArrayDeque<>();
    private final Deque<Runnable> mainThread = new ArrayDeque<>();
    private final Deque<Object> script = new ArrayDeque<>(); // Brain.Reply or BrainException, in call order
    private final List<Brain.Request> requests = new ArrayList<>();
    private final List<String> said = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private long now;
    private ActionQueue queue;
    private Guardrails guardrails;
    private Memory memory;
    private AgentLoop loop;

    private final Brain brain = request -> {
        requests.add(request);
        Object next = script.poll();
        if (next instanceof BrainException e) throw e;
        if (next == null) throw new AssertionError("unexpected model call #" + requests.size());
        return (Brain.Reply) next;
    };

    /** move_to runs until cancelled; everything else finishes at once. */
    private final ActionRunner runner = action ->
            action instanceof Action.MoveTo ? () -> null : ActionRunner.Handle.done(ActionResult.ok());

    private final AgentLoop.Host host = new AgentLoop.Host() {
        @Override
        public StateSnapshot snapshot() {
            return StateSnapshot.absent(false, 3, null);
        }

        @Override
        public List<Conversation.Line> recentChat() {
            return List.of();
        }

        @Override
        public void say(String text) {
            said.add(text);
        }

        @Override
        public void notice(String text) {
            notices.add(text);
        }
    };

    private void build(Consumer<Settings.Builder> edits) {
        Settings settings = Settings.of(edits);
        guardrails = new Guardrails(settings, () -> now);
        queue = new ActionQueue(settings.actionsPerSecond(), 200, runner);
        memory = new Memory(settings, new Memory.Store() {
            @Override
            public List<Memory.Entry> load() {
                return List.of();
            }

            @Override
            public void save(List<Memory.Entry> entries) {}
        }, () -> now);
        loop = new AgentLoop(settings, brain, brainThread::add, mainThread::add, queue, guardrails,
                new PromptBuilder(settings, "You are {{name}}."), new ToolSchemas(settings.toolPhase()), memory, host);
    }

    /** Runs queued model calls and delivers their replies until both queues are empty. */
    private void pump() {
        while (!brainThread.isEmpty() || !mainThread.isEmpty()) {
            (brainThread.isEmpty() ? mainThread : brainThread).poll().run();
        }
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            queue.tick();
            loop.tick();
            pump();
        }
    }

    private static Brain.ToolCall call(String id, String name, String input) {
        return new Brain.ToolCall(id, name, JsonParser.parseString(input).getAsJsonObject());
    }

    private static Brain.ToolCall say(String id) {
        return call(id, "say", "{\"text\":\"hi\"}");
    }

    private static Brain.ToolCall move(String id) {
        return call(id, "move_to", "{\"x\":5,\"y\":64,\"z\":5}");
    }

    private static Brain.Reply tools(Brain.ToolCall... calls) {
        JsonArray content = new JsonArray();
        for (Brain.ToolCall c : calls) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "tool_use");
            block.addProperty("id", c.id());
            block.addProperty("name", c.name());
            block.add("input", c.input());
            content.add(block);
        }
        return new Brain.Reply("msg", content, List.of(calls), "", "tool_use", new Brain.Usage(100, 20, 0, 0));
    }

    private static Brain.Reply text(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(block);
        return new Brain.Reply("msg", content, List.of(), text, "end_turn", new Brain.Usage(100, 10, 0, 0));
    }

    private JsonArray lastTurn(int request) {
        JsonArray messages = requests.get(request).messages();
        return messages.get(messages.size() - 1).getAsJsonObject().getAsJsonArray("content");
    }

    /** The tool_result contents of a request's newest turn, in order. */
    private List<String> results(int request) {
        List<String> out = new ArrayList<>();
        for (JsonElement el : lastTurn(request)) {
            JsonObject b = el.getAsJsonObject();
            if (b.get("type").getAsString().equals("tool_result")) out.add(b.get("content").getAsString());
        }
        return out;
    }

    private String lastText(int request) {
        JsonArray turn = lastTurn(request);
        return turn.get(turn.size() - 1).getAsJsonObject().get("text").getAsString();
    }

    @Test
    void anOrderRunsItsToolsThenPostsTheFinalText() {
        build(b -> {});
        script.add(tools(say("t1")));
        script.add(text("Done!"));
        loop.startOrder("F", "say hi");
        pump();
        assertTrue(lastText(0).startsWith("<order from=\"F\">\nsay hi\n</order>"), lastText(0));
        ticks(1);
        assertEquals(2, requests.size());
        assertEquals(List.of("ok"), results(1));
        assertEquals(3, requests.get(1).messages().size(), "user, assistant, user");
        assertEquals(List.of("Done!"), said);
        assertFalse(loop.active());
    }

    @Test
    void longBatchesReportEarlyAndTheFinalTextWaitsForTheQueue() {
        build(b -> {}); // batch_size 12, prefetch_at 4
        script.add(tools(say("a"), say("b"), say("c"), say("d"), say("e"), say("f"), say("g"), say("h")));
        script.add(text("All done."));
        loop.startOrder("F", "chatter");
        pump();
        for (int i = 0; i < 100 && requests.size() < 2; i++) ticks(1);
        assertEquals(List.of("ok", "ok", "ok", "ok", "queued", "queued", "queued", "queued"), results(1));
        assertTrue(said.isEmpty(), "four actions are still queued");
        ticks(100);
        assertEquals(List.of("All done."), said);
        assertEquals(2, requests.size(), "late ok results don't cost another round");
    }

    @Test
    void theStepCapEndsARunawayLoop() {
        build(b -> b.maxSteps = 3);
        for (int i = 0; i < 5; i++) script.add(tools(say("t" + i)));
        loop.startOrder("F", "loop forever");
        pump();
        ticks(60);
        assertEquals(3, requests.size());
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("3 model rounds"), notices.get(0));
        assertFalse(loop.active());
    }

    @Test
    void cacheReadsWeighATenth() {
        assertEquals(270, new Brain.Usage(100, 20, 50, 1000).weighted());
        assertEquals(1170, new Brain.Usage(100, 20, 50, 1000).total(), "total still counts everything");
    }

    @Test
    void cacheReadsAloneDontTripTheTokenCap() {
        build(b -> b.sessionTokenCap = 1000);
        Brain.Reply cached = tools(say("t1"));
        script.add(new Brain.Reply(cached.id(), cached.content(), cached.toolCalls(), cached.text(), cached.stopReason(),
                new Brain.Usage(100, 20, 0, 5000))); // 5120 in all, 620 weighted
        script.add(text("Done!"));
        loop.startOrder("F", "say hi");
        pump();
        ticks(1);
        assertEquals(2, requests.size(), "the second call went out");
        assertEquals(List.of("Done!"), said);
        assertTrue(notices.isEmpty(), notices.toString());
    }

    @Test
    void stopCancelsTheQueueAndALateReplyIsIgnored() {
        build(b -> {});
        script.add(tools(move("m")));
        loop.startOrder("F", "walk");
        pump();
        ticks(5);
        assertEquals("move_to", queue.runningTool());
        loop.stop();
        assertTrue(queue.idle());
        assertFalse(loop.active());
        assertEquals(Guardrails.Check.HALTED, guardrails.allowCall(), "calls blocked until the next order");

        script.add(tools(say("late")));
        loop.startOrder("F", "again"); // lifts the halt; the call is queued but not answered yet
        loop.stop();
        pump(); // the reply lands after the stop
        ticks(20);
        assertEquals(2, requests.size());
        assertTrue(queue.idle(), "the stale reply's calls never ran");
    }

    @Test
    void anInterruptCancelsTheQueueAndReportsAtOnce() {
        build(b -> b.reflexes = false);
        script.add(tools(move("m"), say("s")));
        script.add(text("Backed off."));
        loop.startOrder("F", "walk");
        pump();
        ticks(3);
        loop.interrupt("zombie #7 is 3.0 blocks away");
        pump();
        assertEquals(2, requests.size());
        assertEquals(List.of("cancelled", "cancelled"), results(1));
        assertTrue(lastText(1).contains("INTERRUPT: zombie #7 is 3.0 blocks away"), lastText(1));
        assertEquals(List.of("Backed off."), said);
    }

    @Test
    void anInterruptDuringACallVoidsTheRepliesPlan() {
        build(b -> {});
        script.add(tools(say("a"), say("b"), say("c"), say("d"), say("e"), say("f")));
        script.add(tools(say("g"))); // planned before the hit
        script.add(text("ok"));
        loop.startOrder("F", "chatter");
        pump();
        for (int i = 0; i < 100 && brainThread.isEmpty(); i++) {
            queue.tick(); // run the queue without delivering the early-report call
            loop.tick();
        }
        loop.interrupt("hit by zombie #7");
        pump();
        assertEquals(3, requests.size());
        assertEquals(List.of("cancelled"), results(2), "g never ran");
        assertTrue(lastText(2).contains("INTERRUPT: hit by zombie #7"), lastText(2));
        ticks(40); // interrupts keep the queue with reflexes on, so the final text waits for c..f
        assertEquals(List.of("ok"), said);
    }

    @Test
    void aReflexRunsBeforeTheModelHearsAndScrapsRoutineWork() {
        build(b -> {});
        script.add(tools(move("m"), say("s")));
        script.add(text("Safe now."));
        loop.startOrder("F", "walk");
        pump();
        ticks(3); // m is running
        loop.reflex("creeper #9 is 4.0 blocks away, fuse lit.", List.of(new Action.Flee()));
        pump();
        assertEquals(2, requests.size());
        assertEquals(List.of("scrapped", "scrapped"), results(1));
        assertTrue(lastText(1).contains("INTERRUPT: creeper #9 is 4.0 blocks away, fuse lit. Reflex: flee. Scrapped: move_to m, say s."), lastText(1));
        ticks(2);
        assertEquals(List.of("Safe now."), said);
    }

    @Test
    void levelTwoWorkIsPausedAndResumes() {
        build(b -> {});
        script.add(tools(call("m", "move_to", "{\"x\":5,\"y\":64,\"z\":5,\"prio\":2}")));
        script.add(text("Back to it."));
        loop.startOrder("F", "walk");
        pump();
        ticks(3);
        loop.reflex("zombie #7 is 2.0 blocks away.", List.of(new Action.Flee()));
        pump();
        assertEquals(List.of("queued"), results(1));
        assertTrue(lastText(1).contains("Reflex: flee. Paused: move_to m."), lastText(1));
        ticks(10);
        assertEquals("move_to", queue.runningTool(), "m restarted after the flee");
        assertEquals(List.of(), said, "the order waits for m");
    }

    @Test
    void prioThreeOnANonSafetyToolRunsAtTwoAndSaysSo() {
        build(b -> {});
        script.add(tools(call("s", "say", "{\"text\":\"hi\",\"prio\":3}")));
        script.add(text("done"));
        loop.startOrder("F", "greet");
        pump();
        ticks(3);
        assertTrue(lastText(1).contains("say s: prio_lowered to 2"), lastText(1));
    }

    @Test
    void anInterruptWithReflexesOnKeepsTheQueue() {
        build(b -> {});
        script.add(tools(move("m"), say("s")));
        script.add(text("noted"));
        loop.startOrder("F", "walk");
        pump();
        ticks(3);
        loop.interrupt("zombie #7 is 10.0 blocks away.");
        pump();
        assertEquals(2, requests.size());
        assertEquals(List.of("queued", "queued"), results(1));
        assertTrue(lastText(1).contains("INTERRUPT: zombie #7 is 10.0 blocks away."), lastText(1));
        assertEquals("move_to", queue.runningTool());
    }

    @Test
    void aReflexDuringACallVoidsTheRepliesPlan() {
        build(b -> {});
        script.add(tools(say("a"), say("b"), say("c"), say("d"), say("e"), say("f")));
        script.add(tools(say("g"))); // planned before the hit
        script.add(text("ok"));
        loop.startOrder("F", "chatter");
        pump();
        for (int i = 0; i < 100 && brainThread.isEmpty(); i++) {
            queue.tick(); // run the queue without delivering the early-report call
            loop.tick();
        }
        loop.reflex("hit by zombie #7.", List.of(new Action.Flee()));
        pump();
        assertEquals(3, requests.size());
        assertEquals(List.of("cancelled"), results(2), "g never ran");
        assertTrue(lastText(2).contains("INTERRUPT: hit by zombie #7. Reflex: flee."), lastText(2));
    }

    @Test
    void aReflexWhileIdleStartsAReactionThatSaysWhatTheBodyDid() {
        build(b -> {});
        script.add(text("Ran."));
        loop.reflex("creeper #9 is 3.0 blocks away, fuse lit.", List.of(new Action.Flee()));
        pump();
        assertEquals(1, requests.size());
        assertTrue(lastText(0).contains("fuse lit. Reflex: flee."), lastText(0));
    }

    @Test
    void aQuietReflexDuringAnOrderCallsNoModelAndLeavesTheBatchWaiting() {
        build(b -> {});
        script.add(tools(call("m", "move_to", "{\"x\":5,\"y\":64,\"z\":5,\"prio\":2}")));
        script.add(text("unused"));
        loop.startOrder("F", "walk");
        pump();
        ticks(3);
        loop.reflexQuiet(List.of(new Action.Flee()));
        pump();
        assertEquals(1, requests.size(), "no interrupt for a chase re-fire");
        ticks(10);
        assertEquals("move_to", queue.runningTool(), "m restarted after the flee");
        assertEquals(1, requests.size(), "the batch waits for m");
    }

    @Test
    void aQuietReflexWhileIdleStartsNoReactionButRuns() {
        build(b -> {}); // idle_reactions on
        script.add(text("unused"));
        loop.reflexQuiet(List.of(new Action.MoveTo(9, 64, 9)));
        pump();
        ticks(2);
        assertEquals("move_to", queue.runningTool());
        assertEquals(0, requests.size());
        assertFalse(loop.active());
    }

    @Test
    void aNewOrderKeepsARunningReflexAndStopEndsIt() {
        build(b -> b.idleReactions = false);
        loop.reflex("zombie #7 is 2.0 blocks away.", List.of(new Action.MoveTo(9, 64, 9)));
        ticks(2);
        assertEquals(0, requests.size());
        script.add(text("hello"));
        loop.startOrder("F", "say hi");
        pump();
        assertEquals("move_to", queue.runningTool(), "the new order didn't cancel the reflex");
        loop.stop();
        assertTrue(queue.idle());
    }

    @Test
    void aReflexAfterStopStillRunsButCallsNoModel() {
        build(b -> {});
        loop.stop();
        loop.reflex("creeper #9 is 3.0 blocks away, fuse lit.", List.of(new Action.MoveTo(9, 64, 9)));
        pump();
        ticks(2);
        assertEquals("move_to", queue.runningTool());
        assertEquals(0, requests.size());
    }

    @Test
    void badCallsAreAnsweredWithoutRunning() {
        build(b -> {});
        script.add(tools(call("t1", "move_to", "{\"x\":1,\"y\":64}"), call("t2", "fly", "{}")));
        script.add(text("Couldn't do that."));
        loop.startOrder("F", "go");
        pump();
        assertEquals(2, requests.size(), "nothing runnable, so the results go back at once");
        assertEquals(List.of("failed:bad_input:z", "denied:unknown_tool"), results(1));
        assertTrue(lastTurn(1).get(0).getAsJsonObject().get("is_error").getAsBoolean());
        assertEquals(List.of("Couldn't do that."), said);
    }

    @Test
    void aRejectedKeyHaltsAndSaysWhy() {
        build(b -> {});
        script.add(new BrainException(BrainException.Kind.AUTH, 401, "invalid x-api-key", "req_1"));
        loop.startOrder("F", "hi");
        pump();
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("rejected"), notices.get(0));
        assertEquals(Guardrails.Check.HALTED, guardrails.allowCall());
        assertFalse(loop.active());
    }

    @Test
    void theCallRateCapDefersInsteadOfDropping() {
        build(b -> b.maxCallsPerMinute = 1);
        script.add(tools(say("t1")));
        script.add(text("done"));
        loop.startOrder("F", "hi");
        pump();
        ticks(5);
        assertEquals(1, requests.size());
        assertTrue(loop.status().contains("call-rate"), loop.status());
        now += 60_000;
        ticks(1);
        assertEquals(2, requests.size());
        assertEquals(List.of("done"), said);
    }

    @Test
    void aThreatWhileIdleStartsAReactionThatSeesMemory() {
        build(b -> {});
        memory.remember("chest@1,2,3", "building materials");
        memory.note("container@1,2,3", "chest at 1, 2, 3: 15 oak_log");
        script.add(text("Ran off."));
        loop.interrupt("zombie #7 is 4.0 blocks away");
        pump();
        String turn = lastText(0);
        assertTrue(turn.startsWith("<event>"), turn);
        assertTrue(turn.contains("chest@1,2,3: building materials"), turn);
        assertTrue(turn.contains("container@1,2,3 (just now): chest at 1, 2, 3: 15 oak_log"), turn);
        assertEquals(List.of("Ran off."), said);

        loop.stop();
        loop.interrupt("another zombie");
        pump();
        assertEquals(1, requests.size(), "no reactions while the kill switch is on");
    }

    @Test
    void theDemoOrderWalksASquareWithoutTheApi() throws BrainException {
        JsonArray first = JsonParser.parseString("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":"
                + "\"<order from=\\\"F\\\">\\ndemo\\n</order>\\n<world_state>\\n{\\\"level\\\":3,\\\"pos\\\":[10.5,64.0,-2.0]}\\n</world_state>\"}]}]")
                .getAsJsonArray();
        Brain demo = new DemoBrain(request -> {
            throw new AssertionError("the API must not be called for the demo");
        });
        Brain.Reply reply = demo.send(new Brain.Request("", new JsonArray(), first));
        assertEquals(List.of("say", "move_to", "move_to", "move_to", "move_to", "wait", "say"),
                reply.toolCalls().stream().map(Brain.ToolCall::name).toList());
        ToolSchemas tools = new ToolSchemas(2);
        assertEquals(new Action.MoveTo(13.5, 64, -2), tools.parse("move_to", reply.toolCalls().get(1).input()).action());
        reply.toolCalls().forEach(c -> assertEquals(null, tools.parse(c.name(), c.input()).error(), c.name()));

        Brain.Reply passthrough = new DemoBrain(request -> text("from the API")).send(new Brain.Request("", new JsonArray(),
                JsonParser.parseString("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"<order from=\\\"F\\\">\\ncome here\\n</order>\"}]}]").getAsJsonArray()));
        assertEquals("from the API", passthrough.text());
    }

    @Test
    void theBundledPromptFillsEveryPlaceholder() {
        String system = new PromptBuilder(Settings.DEFAULTS, PromptBuilder.loadTemplate(null)).system();
        assertFalse(system.contains("{{"), system);
        assertTrue(system.contains("5 Innocent: never attack"), system);
    }
}
