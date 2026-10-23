package com.example.claudeplayer.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private final List<String> ran = new ArrayList<>(); // the action classes the runner started, in order
    private ActionResult fleeResult = ActionResult.ok();
    private boolean movesFinish; // move_to finishes ok at once instead of running until cancelled

    /** move_to runs until cancelled, or finishes if {@code movesFinish}; flee ends with {@code fleeResult}; the rest finish. */
    private final ActionRunner runner = action -> {
        ran.add(action.getClass().getSimpleName());
        if (action instanceof Action.MoveTo) return movesFinish ? ActionRunner.Handle.done(ActionResult.ok()) : () -> null;
        if (action instanceof Action.Flee) return ActionRunner.Handle.done(fleeResult);
        return ActionRunner.Handle.done(ActionResult.ok());
    };

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
                new PromptBuilder(settings, "You are {{name}}."), new ToolSchemas(settings.toolPhase()), memory, host, () -> now);
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

    /** A reply whose calls finish the order: the model sets done, so no results need to go back. */
    private static Brain.Reply doneTools(String text, Brain.ToolCall... calls) {
        Brain.Reply r = tools(calls);
        return new Brain.Reply(r.id(), r.content(), r.toolCalls(), text, r.stopReason(), r.usage(), true);
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

    /** The same reply, but its call's prompt was {@code prompt} tokens long. */
    private static Brain.Reply promptOf(int prompt, Brain.Reply r) {
        return new Brain.Reply(r.id(), r.content(), r.toolCalls(), r.text(), r.stopReason(),
                new Brain.Usage(prompt - 500, 20, 300, 200), r.done());
    }

    /** Every tool_result in the request answers a tool_use that an earlier assistant turn of the same request made. */
    private void assertNoOrphans(int request) {
        java.util.Set<String> uses = new java.util.HashSet<>();
        for (JsonElement m : requests.get(request).messages()) {
            JsonObject msg = m.getAsJsonObject();
            for (JsonElement el : msg.getAsJsonArray("content")) {
                JsonObject b = el.getAsJsonObject();
                if (b.get("type").getAsString().equals("tool_use")) uses.add(b.get("id").getAsString());
                if (b.get("type").getAsString().equals("tool_result")) {
                    assertTrue(uses.contains(b.get("tool_use_id").getAsString()), "orphan tool_result in request " + request);
                }
            }
        }
    }

    private String firstText(int request) {
        JsonObject first = requests.get(request).messages().get(0).getAsJsonObject();
        assertEquals("user", first.get("role").getAsString());
        JsonArray content = first.getAsJsonArray("content");
        return content.get(content.size() - 1).getAsJsonObject().get("text").getAsString();
    }

    @Test
    void aLongPromptCollapsesTheNextTurnIntoOneSummaryTurn() {
        build(b -> b.compactAtTokens = 2000);
        movesFinish = true;
        script.add(promptOf(2000, tools(say("s1"), move("m1"))));
        script.add(text("All done."));
        loop.startOrder("F", "go to the tree");
        pump();
        ticks(40);
        assertEquals(2, requests.size());
        assertEquals(1, requests.get(1).messages().size(), "one fresh user turn");
        assertEquals(0, results(1).size(), "results are text now, not tool_result blocks");
        String turn = lastText(1);
        assertTrue(turn.startsWith("<order from=\"F\">\ngo to the tree\n</order>"), turn);
        assertTrue(turn.contains("<earlier>\n"), turn);
        assertTrue(turn.contains("say\u2192ok, move_to\u2192ok"), turn);
        assertTrue(turn.contains("<results>\n- say s1: ok\n- move_to m1: ok\n</results>"), turn);
        assertTrue(turn.contains("<world_state>"), turn);
        assertTrue(turn.contains("<status>"), turn);
        assertNoOrphans(1);
        assertEquals(List.of("All done."), said);
    }

    @Test
    void belowTheThresholdTheTranscriptStaysAppendOnly() {
        build(b -> b.compactAtTokens = 2000);
        script.add(promptOf(1999, tools(say("s1"))));
        script.add(tools(say("s2")));
        script.add(text("Done."));
        loop.startOrder("F", "say hi twice");
        pump();
        ticks(40);
        assertEquals(3, requests.size());
        assertEquals(3, requests.get(1).messages().size(), "user, assistant, user");
        assertEquals(5, requests.get(2).messages().size());
        assertEquals(List.of("ok"), results(2));
        assertFalse(firstText(2).contains("<earlier>"));
    }

    @Test
    void aCallStillRunningWhenTheOrderCompactsReportsItsResultAsANote() {
        build(b -> b.compactAtTokens = 2000);
        script.add(promptOf(5000, tools(say("a"), say("b"), say("c"), say("d"), say("e"), say("f"), say("g"), say("h"))));
        script.add(tools(say("x")));
        script.add(text("All done."));
        loop.startOrder("F", "chatter");
        pump();
        for (int i = 0; i < 100 && requests.size() < 2; i++) ticks(1);
        assertEquals(1, requests.get(1).messages().size());
        assertTrue(lastText(1).contains("say\u2192ok, say\u2192ok, say\u2192ok, say\u2192ok, say\u2192queued"), lastText(1));
        assertTrue(lastText(1).contains("- say e: queued"), lastText(1));
        ticks(100);
        assertEquals(3, requests.size());
        assertEquals(3, requests.get(2).messages().size(), "the order went on from the fresh turn");
        for (String late : List.of("say e: ok", "say f: ok", "say g: ok", "say h: ok")) {
            assertTrue(lastText(2).contains(late), lastText(2));
        }
        for (int i = 0; i < requests.size(); i++) assertNoOrphans(i);
        assertEquals(List.of("All done."), said);
    }

    @Test
    void anOrderCanCompactTwiceAndKeepsTheEarlierLines() {
        build(b -> b.compactAtTokens = 2000);
        movesFinish = true;
        script.add(promptOf(3000, tools(move("m1"))));
        script.add(promptOf(3000, tools(call("f", "flee", "{}"))));
        script.add(text("Safe."));
        fleeResult = ActionResult.failed("no_escape");
        loop.startOrder("F", "wander");
        pump();
        ticks(10);
        assertEquals(3, requests.size());
        assertEquals(1, requests.get(2).messages().size());
        String turn = lastText(2);
        assertTrue(turn.contains("move_to\u2192ok"), turn);
        assertTrue(turn.contains("flee\u2192failed:no_escape"), turn);
        assertTrue(turn.indexOf("move_to\u2192ok") < turn.indexOf("flee\u2192failed"), "oldest first");
        assertEquals(List.of("Safe."), said);
        assertFalse(loop.active());
    }

    @Test
    void theSummaryKeepsTheNewestFortyLines() {
        build(b -> {
            b.compactAtTokens = 2000;
            b.maxSteps = 100;
            b.maxCallsPerMinute = 120;
        });
        for (int i = 0; i < 44; i++) script.add(tools(say("t" + i)));
        script.add(promptOf(9000, tools(say("last"))));
        script.add(text("Done."));
        loop.startOrder("F", "talk a lot");
        pump();
        ticks(600);
        assertEquals(46, requests.size());
        String turn = lastText(45);
        assertEquals(1, requests.get(45).messages().size());
        long lines = turn.lines().filter(l -> l.matches("- \\d+: .*")).count();
        assertEquals(40, lines, turn);
        assertTrue(turn.contains("(5 earlier turns left out)"), turn);
        assertTrue(turn.contains("- 45: \"hi\" say\u2192ok"), turn);
    }

    @Test
    void aCavemanNoteJoinsARunningOrderOnceAtTheNextTurn() {
        build(b -> b.maxCallsPerMinute = 100);
        boolean[] on = {false};
        loop.caveman(() -> on[0], () -> "CAVEMAN RULES");
        script.add(tools(say("t1")));
        script.add(tools(say("t2")));
        script.add(text("Done!"));
        loop.startOrder("F", "say hi");
        pump();
        assertFalse(lastText(0).contains("CAVEMAN RULES"));
        assertFalse(requests.get(0).caveman(), "the order started with usage low");
        on[0] = true; // usage crosses the line while the order runs
        ticks(1);
        assertTrue(lastText(1).contains("CAVEMAN RULES"), lastText(1));
        ticks(10);
        assertEquals(3, requests.size());
        assertFalse(lastText(2).contains("CAVEMAN RULES"), "once per order, so the transcript only grows");
        assertFalse(requests.get(1).caveman() || requests.get(2).caveman(),
                "the system prompt doesn't change mid-order; the note carries the rules");
    }

    @Test
    void anOrderThatStartsWithCavemanGetsNoNoteAndEveryRequestSaysSo() {
        build(b -> {});
        loop.caveman(() -> true, () -> "CAVEMAN RULES");
        script.add(tools(say("t1")));
        script.add(text("Done!"));
        loop.startOrder("F", "say hi");
        pump();
        ticks(1);
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).caveman() && requests.get(1).caveman());
        assertFalse(lastText(0).contains("CAVEMAN RULES") || lastText(1).contains("CAVEMAN RULES"));
    }

    @Test
    void cavemanIsDecidedAgainForEachOrder() {
        build(b -> {});
        boolean[] on = {true};
        loop.caveman(() -> on[0], () -> "CAVEMAN RULES");
        script.add(text("One."));
        script.add(text("Two."));
        loop.startOrder("F", "first");
        pump();
        on[0] = false; // usage fell back below the line
        loop.startOrder("F", "second");
        pump();
        assertTrue(requests.get(0).caveman());
        assertFalse(requests.get(1).caveman());
    }

    @Test
    void aThinkHarderOrdersRequestsCarryTheThinkEffortUntilAReflex() {
        build(b -> b.maxCallsPerMinute = 100);
        script.add(tools(move("m")));
        script.add(tools(say("s")));
        script.add(text("Safe now."));
        loop.startOrder("F", "think harder and walk", true);
        pump();
        ticks(3); // m is running
        assertEquals("max", requests.get(0).effort(), "claude_think_effort");
        loop.reflex("zombie #7 is 4.0 blocks away.", List.of(new Action.Flee()));
        pump();
        assertEquals(2, requests.size());
        assertNull(requests.get(1).effort(), "the next turn drops back to the configured effort");
        ticks(10);
        assertEquals(3, requests.size());
        assertNull(requests.get(2).effort(), "and stays there for the rest of the order");
    }

    @Test
    void anInterruptWithoutAReflexAlsoEndsThinkHarder() {
        build(b -> b.maxCallsPerMinute = 100);
        script.add(tools(move("m")));
        script.add(text("Done."));
        loop.startOrder("F", "think harder and walk", true);
        pump();
        ticks(3); // m is running
        loop.interrupt("zombie #7 is 10.0 blocks away. Caution level 4 (ready) says: fight."); // out of reach: no reflex
        pump();
        assertEquals(2, requests.size());
        assertNull(requests.get(1).effort(), "any threat drops the next turn back to the configured effort");
    }

    @Test
    void thinkHarderEndsWithItsOrder() {
        build(b -> b.claudeThinkEffort = "max");
        script.add(text("One."));
        script.add(text("Two."));
        loop.startOrder("F", "think harder", true);
        pump();
        loop.startOrder("F", "plain order");
        pump();
        assertEquals("max", requests.get(0).effort());
        assertNull(requests.get(1).effort());
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
    void aDoneReplyWhoseCallsFinishOkEndsTheOrderWithoutAReportTurn() {
        build(b -> {});
        movesFinish = true;
        script.add(doneTools("On my way.", move("m")));
        loop.startOrder("F", "come here");
        pump();
        ticks(3);
        assertEquals(1, requests.size(), "the call finished ok, so no results go back");
        assertEquals(List.of("On my way."), said);
        assertFalse(loop.active());
    }

    @Test
    void aDoneReplyWhoseCallFailsGoesBackToTheModel() {
        build(b -> {});
        fleeResult = ActionResult.failed("no_escape");
        script.add(doneTools("Running!", call("f", "flee", "{}")));
        script.add(text("Could not get away."));
        loop.startOrder("F", "run");
        pump();
        ticks(3);
        assertEquals(2, requests.size());
        assertEquals(List.of("failed:no_escape"), results(1));
        assertEquals(List.of("Could not get away."), said);
    }

    @Test
    void aReflexBeforeADoneReplysCallsFinishSendsResultsBackAndForgetsDone() {
        build(b -> {});
        script.add(doneTools("On my way.", move("m")));
        script.add(tools(say("s"))); // not done: its results must go back, not end the order
        script.add(text("Noted."));
        loop.startOrder("F", "come here");
        pump();
        ticks(3); // m is running
        loop.reflex("creeper #9 is 4.0 blocks away, fuse lit.", List.of(new Action.Flee()));
        pump();
        assertEquals(2, requests.size());
        assertEquals(List.of("scrapped"), results(1));
        assertTrue(said.isEmpty(), "the done text is not said after an interrupt");
        ticks(40);
        assertEquals(3, requests.size(), "the next batch reports to the model");
        assertEquals(List.of("ok"), results(2));
        assertEquals(List.of("Noted."), said);
    }

    @Test
    void aReplyWithoutDoneKeepsTheReportTurn() {
        build(b -> {});
        script.add(tools(say("s")));
        script.add(text("On my way."));
        loop.startOrder("F", "come here");
        pump();
        ticks(3);
        assertEquals(2, requests.size());
        assertEquals(List.of("ok"), results(1));
        assertEquals(List.of("On my way."), said);
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
    void autonomyRunsOneCallPerGoalAndAtMostOneGoalPerInterval() {
        build(b -> b.autonomyIntervalSeconds = 60);
        script.add(tools(say("g1"), say("g2")));
        loop.startAutonomy("F");
        ticks(1);
        assertEquals(1, requests.size());
        assertTrue(lastText(0).startsWith("<autonomy by=\"F\">\nNo one gave an order."), lastText(0));
        assertTrue(loop.goalRunning());
        ticks(40);
        assertEquals(1, requests.size(), "the goal's results don't go back to the model");
        assertFalse(loop.active());
        assertEquals(List.of(), said, "a goal with no reply text ends quietly");

        now += 59_000;
        ticks(5);
        assertEquals(1, requests.size(), "the next goal waits for the interval");
        assertEquals("autonomy on (next goal in 1 s)", loop.autonomyStatus());
        script.add(text("I need food."));
        now += 1_000;
        ticks(1);
        assertEquals(2, requests.size());
        assertEquals(1, requests.get(1).messages().size(), "each goal is a fresh transcript");
        assertTrue(lastText(1).contains("<last_goals>\n- goal #1: say ok, say ok\n</last_goals>"), lastText(1));
        assertEquals(List.of("I need food."), said);
    }

    @Test
    void aGoalFailureIsSaidOnceUntilAGoalWorksAgain() {
        build(b -> b.autonomyIntervalSeconds = 60);
        BrainException down = new BrainException(BrainException.Kind.OVERLOADED, 529, "overloaded", null);
        script.add(down);
        loop.startAutonomy("F");
        ticks(1);
        assertEquals(1, notices.size());
        script.add(down);
        now += 60_000;
        ticks(1);
        assertEquals(2, requests.size());
        assertEquals(1, notices.size(), "the same failure a minute later stays quiet");
        script.add(tools(say("g")));
        now += 60_000;
        ticks(40);
        script.add(down);
        now += 60_000;
        ticks(1);
        assertEquals(4, requests.size());
        assertEquals(2, notices.size(), "after a goal works, a new failure is said again");
    }

    @Test
    void anOrderDisplacesAGoalRunsNormallyAndAutonomyResumes() {
        build(b -> {});
        script.add(tools(move("m")));
        loop.startAutonomy("F");
        ticks(3);
        assertEquals("move_to", queue.runningTool());
        script.add(tools(say("o1")));
        script.add(text("Here."));
        loop.startOrder("F", "come here");
        pump();
        ticks(20);
        assertEquals(3, requests.size(), "the goal's one call, then both rounds of the order");
        assertTrue(lastText(1).startsWith("<order from=\"F\">\ncome here"), lastText(1));
        assertEquals(List.of("ok"), results(2));
        assertEquals(List.of("Here."), said);
        assertTrue(loop.autonomous());

        script.add(tools(say("g2")));
        now += 60_000;
        ticks(1);
        assertEquals(4, requests.size());
        assertTrue(lastText(3).contains("- goal #1: move_to unfinished; an order took over"), lastText(3));
    }

    @Test
    void aThreatDuringAGoalStartsAReaction() {
        build(b -> {});
        script.add(tools(move("m")));
        loop.startAutonomy("F");
        ticks(3);
        script.add(text("Ran."));
        loop.reflex("creeper #9 is 3.0 blocks away, fuse lit.", List.of(new Action.Flee()));
        pump();
        assertEquals(2, requests.size());
        assertTrue(lastText(1).startsWith("<event>"), lastText(1));
        assertFalse(loop.goalRunning());
    }

    @Test
    void autoOffStopAndDeathEndAutonomy() {
        build(b -> {});
        script.add(tools(move("m")));
        loop.startAutonomy("F");
        ticks(3);
        assertEquals("move_to", queue.runningTool());
        loop.stopAutonomy();
        assertTrue(queue.idle(), "auto off drops the running goal");
        assertEquals(Guardrails.Check.OK, guardrails.allowCall(), "auto off is not the kill switch");
        assertEquals("autonomy off", loop.autonomyStatus());

        now += 60_000;
        loop.startAutonomy("F");
        loop.stop();
        assertEquals(Guardrails.Check.HALTED, guardrails.allowCall());
        loop.startAutonomy("F");
        loop.bodyGone();
        ticks(5);
        assertFalse(loop.autonomous());
        assertEquals(1, requests.size());
    }

    @Test
    void aGuardrailBlockedCycleIsSkippedNotQueued() {
        build(b -> b.maxCallsPerMinute = 1);
        script.add(text("Hi."));
        loop.startOrder("F", "say hi"); // the minute's only call, at 0 s
        pump();
        now = 1_000;
        loop.startAutonomy("F");
        ticks(3);
        assertEquals(1, requests.size(), "rate-limited: this cycle is skipped");
        now = 60_000;
        ticks(3);
        assertEquals(1, requests.size(), "the call window is open again, but the next cycle isn't due");
        script.add(tools(say("g1")));
        now = 61_000;
        ticks(1);
        assertEquals(2, requests.size());
    }

    @Test
    void theTokenCapPausesAutonomyUntilTheHourFrees() {
        build(b -> b.sessionTokenCap = 1_000);
        guardrails.recordTokens(1_000);
        loop.startAutonomy("F");
        ticks(3);
        now += 120_000;
        ticks(3);
        assertEquals(0, requests.size());
        assertTrue(loop.autonomous(), "a rolling cap frees itself, so autonomy waits instead of ending");
        assertEquals(List.of("Token cap reached (1000 per hour). Autonomy waits until it frees."), notices);
        script.add(text("Resting."));
        now += 60 * 60_000; // the capped tokens age out
        ticks(3);
        assertEquals(1, requests.size(), "the next goal runs once the hour frees");
    }

    @Test
    void aDroppedOrdersWaitingSafetyCallNeverRunsButAReflexStillDoes() {
        build(b -> b.idleReactions = false);
        loop.reflex("zombie #7 is 2.0 blocks away.", List.of(new Action.MoveTo(9, 64, 9)));
        ticks(1); // the reflex's move starts and holds level 3
        script.add(tools(call("f", "flee", "{\"prio\":3}")));
        loop.startOrder("F", "run");
        pump();
        assertEquals(1, queue.waitingCount(), "the model's flee waits behind the reflex");
        script.add(text("Again."));
        script.add(text("Noted.")); // the reflex's timeout is a failure, so the model hears it
        loop.startOrder("F", "again"); // drops the first order
        pump();
        ticks(250); // the reflex's move times out after 200 ticks
        assertEquals(List.of("MoveTo"), ran, "the dropped order's flee never ran; the reflex did");
        assertEquals(List.of("Noted."), said);
    }

    @Test
    void everyInterruptDuringOneCallReachesTheModel() {
        build(b -> {});
        script.add(tools(move("m")));
        script.add(text("Both noted."));
        loop.startOrder("F", "walk");
        brainThread.poll().run(); // the call is out; its reply waits on the main thread
        loop.reflex("zombie #7 is 4.0 blocks away.", List.of(new Action.Flee()));
        loop.reflex("creeper #9 is 3.0 blocks away, fuse lit.", List.of(new Action.Flee()));
        pump();
        assertEquals(2, requests.size());
        assertTrue(lastText(1).contains("INTERRUPT: zombie #7 is 4.0 blocks away. Reflex: flee."), lastText(1));
        assertTrue(lastText(1).contains("INTERRUPT: creeper #9 is 3.0 blocks away, fuse lit. Reflex: flee."), lastText(1));
    }

    @Test
    void aFailedReflexIsReportedToTheModelDuringAnOrder() {
        build(b -> {});
        script.add(tools(move("m")));
        script.add(text("Trying."));
        script.add(text("Noted."));
        loop.startOrder("F", "walk");
        pump();
        ticks(3); // m is running
        fleeResult = ActionResult.failed("no_escape");
        loop.reflex("creeper #9 is 2.0 blocks away, fuse lit.", List.of(new Action.Flee()));
        pump(); // the model hears the scrapped move and the reflex
        ticks(1); // the flee runs and fails
        assertEquals(3, requests.size());
        assertTrue(lastText(2).contains("Reflex flee: failed:no_escape"), lastText(2));
    }

    @Test
    void anOkReflexAddsNoOutcomeNote() {
        build(b -> {});
        script.add(tools(move("m")));
        script.add(text("Trying."));
        loop.startOrder("F", "walk");
        pump();
        ticks(3); // m is running
        loop.reflex("creeper #9 is 2.0 blocks away, fuse lit.", List.of(new Action.Flee()));
        pump();
        ticks(1); // the flee runs and is ok
        assertEquals(2, requests.size());
        assertFalse(lastText(1).contains("Reflex flee:"), lastText(1));
        assertEquals(List.of("Trying."), said);
    }

    @Test
    void everyTurnEndsWithTheStatusBlock() {
        build(b -> {});
        memory.note("step", "1/2 say hello");
        script.add(tools(say("s1")));
        script.add(text("Done."));
        loop.startOrder("F", "greet");
        pump();
        ticks(40);
        assertEquals(2, requests.size());
        for (int r = 0; r < 2; r++) {
            String text = lastText(r);
            assertTrue(text.endsWith("</status>"), text);
            assertTrue(text.contains("ORDER: \"greet\" (from F) | STEP: 1/2 say hello\n"), text);
        }
    }

    @Test
    void theBundledPromptFillsEveryPlaceholder() {
        String system = new PromptBuilder(Settings.DEFAULTS, PromptBuilder.loadTemplate(null)).system();
        assertFalse(system.contains("{{"), system);
        assertTrue(system.contains("5 Innocent: never attack"), system);
    }
}
