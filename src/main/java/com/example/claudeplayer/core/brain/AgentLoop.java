package com.example.claudeplayer.core.brain;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.StateSnapshot;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionQueue;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.chat.Conversation;
import com.example.claudeplayer.core.memory.Memory;
import com.example.claudeplayer.core.safety.Guardrails;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Order → model → tool calls → queue → results → repeat, until the model answers without tool calls.
 *
 * <p>Threading: every method runs on the server thread. Only {@link Brain#send} runs on {@code brainThread}, and
 * its reply comes back through {@code mainThread}. Each order is one append-only transcript, which keeps prompt
 * caching and thinking blocks valid; a new order starts a new transcript.
 *
 * <p>Pipelining: a batch's results go out when the batch is done, or early once only {@code prefetch_at} of its
 * calls are still waiting to start, so the next reply usually lands before the queue runs dry. Calls still pending
 * at that point report {@code queued}, and their real outcome follows as a note in a later turn. If the reply is
 * late the agent waits; it never acts on a guess. A reply marked done ends its order without a report turn once its
 * calls all succeed; any other outcome goes back to the model as usual.
 *
 * <p>Priorities: each call runs at its {@code prio} level. A reflex is code acting first: its actions go in at
 * level 3, which scraps routine work and pauses level 2, and the model then hears what the body did and what was
 * displaced. A dropped order leaves its reflexes running, so a flee outlives the order that was interrupted.
 *
 * <p>Autonomy: while it is on and nothing else runs, a goal turn starts at most once per
 * {@code autonomy_interval_seconds}. A goal is one model call: its tool calls run and the goal ends without a
 * follow-up round, leaving its result codes for the next goal's prompt. Orders and reactions displace a goal like
 * any order; the cycle resumes once they are done.
 */
public final class AgentLoop implements ActionQueue.Listener {
    private static final Logger LOG = LoggerFactory.getLogger(AgentLoop.class);

    /** What the loop needs from the game. Called on the server thread. */
    public interface Host {
        StateSnapshot snapshot();

        List<Conversation.Line> recentChat();

        /** Chat as the body. */
        void say(String text);

        /** A system line in chat: limits, errors. */
        void notice(String text);
    }

    private enum Kind { ORDER, EVENT, GOAL }

    private record Opening(String player, String text, Kind kind) {}

    private static final int OUTCOMES_KEPT = 5;

    private static final class Call {
        final String id;
        final String name;
        ActionResult result; // null while queued or running
        boolean started;
        boolean reportedEarly; // answered "queued"; the real outcome goes out as a note

        Call(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Batch {
        final List<Call> calls = new ArrayList<>();
        boolean reported;

        boolean done() {
            return calls.stream().allMatch(c -> c.result != null);
        }

        boolean allOk() {
            return calls.stream().allMatch(c -> c.result.status() == ActionResult.Status.OK);
        }

        long notStarted() {
            return calls.stream().filter(c -> c.result == null && !c.started).count();
        }
    }

    private final Settings settings;
    private final Brain brain;
    private final Executor brainThread;
    private final Executor mainThread;
    private final ActionQueue queue;
    private final Guardrails guardrails;
    private final PromptBuilder prompts;
    private final ToolSchemas tools;
    private final Memory memory;
    private final Host host;
    private final LongSupplier clockMillis;

    private int epoch; // bumps whenever the order is dropped; replies from an older epoch are ignored
    private String orderFrom; // null when idle, "" for a reaction nobody ordered
    private String orderText; // the current order's text; "" for a reaction, null when idle
    private Opening opening; // the first turn, built when its call actually goes out
    private JsonArray messages = new JsonArray();
    private final Map<String, Call> pending = new HashMap<>();
    private JsonArray pendingResults = new JsonArray();
    private final List<String> notes = new ArrayList<>();
    private Batch batch;
    private boolean inFlight;
    private boolean deferred;
    private boolean cancelling;
    private boolean mustReport;
    private final List<String> interruptInFlight = new ArrayList<>(); // notes for the reply on its way; it was planned on stale state
    private String finalText;
    private String doneText; // a done reply's text while its batch runs; said if the batch succeeds, null otherwise
    private int steps;
    private int reflexCount; // numbers the reflex-<n> call ids
    private boolean goal; // the running order is an autonomous goal turn
    private String goalText; // the goal reply's text, said when the goal ends unless blank
    private String autonomyBy; // who turned autonomy on; null when off. Never persisted: a restart starts it off
    private long nextGoalAt; // clock millis; a skipped cycle waits for the next interval too
    private int goalCount;
    private final Deque<String> outcomes = new ArrayDeque<>(); // the last goals' result codes, oldest first
    private String lastGoalFailure; // the notice of the last failed goal call, so a repeat stays quiet

    public AgentLoop(Settings settings, Brain brain, Executor brainThread, Executor mainThread, ActionQueue queue,
                     Guardrails guardrails, PromptBuilder prompts, ToolSchemas tools, Memory memory, Host host,
                     LongSupplier clockMillis) {
        this.settings = settings;
        this.brain = brain;
        this.brainThread = brainThread;
        this.mainThread = mainThread;
        this.queue = queue;
        this.guardrails = guardrails;
        this.prompts = prompts;
        this.tools = tools;
        this.memory = memory;
        this.host = host;
        this.clockMillis = clockMillis;
        queue.setListener(this);
    }

    public boolean active() {
        return orderFrom != null;
    }

    /** Who the current order is from; "" for a reaction nobody ordered, null when idle. */
    public String orderFrom() {
        return orderFrom;
    }

    /** The current order's text; "" for a reaction, null when idle. */
    public String orderText() {
        return orderText;
    }

    /** A new order from an allowlisted player. Replaces whatever was running and lifts a kill-switch halt. */
    public void startOrder(String player, String text) {
        guardrails.resume();
        begin(new Opening(player, text, Kind.ORDER));
    }

    /** Autonomy on, from an allowlisted player. Lifts a kill-switch halt like an order does. */
    public void startAutonomy(String player) {
        guardrails.resume();
        autonomyBy = player;
    }

    /** Autonomy off: a running goal is dropped, a running order keeps going. */
    public void stopAutonomy() {
        autonomyOff();
        if (goal) abandon();
    }

    public boolean autonomous() {
        return autonomyBy != null;
    }

    public boolean goalRunning() {
        return goal;
    }

    public String autonomyStatus() {
        if (autonomyBy == null) return "autonomy off";
        if (goal) return "autonomy on (goal #" + goalCount + " running)";
        if (orderFrom != null) return "autonomy on (resumes after this order)";
        long wait = Math.max(0, nextGoalAt - clockMillis.getAsLong());
        return "autonomy on (next goal in " + (wait + 999) / 1000 + " s)";
    }

    /**
     * A threat or a hit: tell the model now. With reflexes on the queue is left alone (a reflex already acted);
     * with them off everything below level 3 is cancelled. With no order running (or only a goal) this starts a
     * reaction, unless idle reactions are off or the kill switch is on.
     */
    public void interrupt(String reason) {
        if (orderFrom == null || goal) {
            if (settings.idleReactions() && !guardrails.halted()) begin(new Opening("", reason, Kind.EVENT));
            return;
        }
        String note = "INTERRUPT: " + reason;
        if (settings.reflexes()) {
            report(note);
            return;
        }
        if (inFlight) {
            interruptInFlight.add(note);
        } else {
            notes.add(note);
            mustReport = true;
        }
        quietly(() -> queue.cancelBelow(3));
        checkProgress();
    }

    /**
     * Code acted before the model: queues {@code actions} at level 3, then tells the model what ran and what it
     * displaced. With no order running this starts a reaction, unless idle reactions are off or the kill switch
     * is on; the actions run either way.
     */
    public void reflex(String reason, List<Action> actions) {
        List<ActionQueue.Entry> entries = new ArrayList<>();
        for (Action a : actions) entries.add(new ActionQueue.Entry("reflex-" + ++reflexCount, reflexTool(a), a, 3));
        ActionQueue.Preemption displaced = enqueue(entries);
        String did = " Reflex: " + entries.stream().map(ActionQueue.Entry::tool).collect(Collectors.joining(", ")) + ".";
        if (orderFrom == null || goal) {
            if (settings.idleReactions() && !guardrails.halted()) begin(new Opening("", reason + did, Kind.EVENT));
            return;
        }
        report("INTERRUPT: " + reason + did + listed(" Paused: ", displaced.paused()) + listed(" Scrapped: ", displaced.scrapped()));
    }

    /** Kill switch: cancel everything, drop the order, end autonomy, block model calls until the next order. */
    public void stop() {
        guardrails.halt();
        autonomyOff();
        abandon();
        quietly(queue::cancelAll);
    }

    /** The body died or was removed: drop the order and end autonomy without another model call. */
    public void bodyGone() {
        autonomyOff();
        abandon();
        quietly(queue::cancelAll);
    }

    /** Once per server tick: sends a call that was waiting for the call-rate window, or starts a due goal. */
    public void tick() {
        if (deferred) flush();
        if (autonomyBy != null && orderFrom == null && queue.idle() && clockMillis.getAsLong() >= nextGoalAt) startGoal();
    }

    public String status() {
        if (orderFrom == null) return guardrails.halted() ? "halted" : "idle";
        String who = goal ? "autonomous goal #" + goalCount : orderFrom.isEmpty() ? "reacting to a threat" : "order from " + orderFrom;
        String wait = inFlight ? ", waiting for the model" : deferred ? ", waiting for the call-rate window" : "";
        return who + ", round " + steps + "/" + settings.maxSteps() + wait;
    }

    @Override
    public void onStarted(ActionQueue.Entry entry) {
        Call call = pending.get(entry.callId());
        if (call != null) call.started = true;
    }

    @Override
    public void onFinished(ActionQueue.Entry entry, ActionResult result) {
        Call call = pending.remove(entry.callId());
        if (call == null) { // a reflex, or a call from an abandoned order
            if (entry.callId().startsWith("reflex-") && result.isError() && orderFrom != null && !goal) {
                report("Reflex " + entry.tool() + ": " + result.code()); // the model hears when a reflex fails
            }
            checkProgress(); // a final reply may be waiting for the queue to go idle
            return;
        }
        if (call.reportedEarly) {
            notes.add(call.name + " " + call.id + ": " + result.code());
            if (result.isError()) mustReport = true;
        } else {
            call.result = result;
        }
        checkProgress();
    }

    /** One goal turn, unless a guardrail says no: then this cycle is skipped, not queued. */
    private void startGoal() {
        nextGoalAt = clockMillis.getAsLong() + settings.autonomyIntervalSeconds() * 1000L;
        switch (guardrails.allowCall()) {
            case OK -> {}
            case TOKEN_CAP -> {
                autonomyOff();
                host.notice("Session token cap reached (" + settings.sessionTokenCap() + "). Autonomy off.");
                return;
            }
            default -> {
                return;
            }
        }
        LOG.info("autonomy goal #{}", ++goalCount);
        begin(new Opening(autonomyBy, "", Kind.GOAL));
    }

    private void begin(Opening first) {
        if (goal) recordGoal(first.kind() == Kind.ORDER ? "; an order took over" : "; a threat took over");
        abandon();
        opening = first;
        goal = first.kind() == Kind.GOAL;
        orderFrom = goal ? "" : first.player();
        orderText = first.kind() == Kind.ORDER ? first.text() : "";
        flush();
    }

    private void onReply(int forEpoch, Brain.Reply reply, BrainException error) {
        if (forEpoch != epoch) return; // the order was replaced or stopped while the call was out
        inFlight = false;
        if (error != null) {
            fail(error);
            return;
        }
        guardrails.recordTokens(reply.usage().total());
        JsonArray content = reply.content();
        if (content.isEmpty()) content = single(textBlock("(no reply)")); // the API rejects empty assistant turns
        messages.add(message("assistant", content));
        if (!interruptInFlight.isEmpty()) {
            for (Brain.ToolCall call : reply.toolCalls()) pendingResults.add(toolResult(call.id(), ActionResult.cancelled()));
            notes.addAll(interruptInFlight);
            interruptInFlight.clear();
            mustReport = true;
            checkProgress();
            return;
        }
        if (goal) { // one call per goal: its tools run, and no results go back
            goalText = reply.text();
            if (reply.toolCalls().isEmpty() || "refusal".equals(reply.stopReason())) endGoal();
            else startBatch(reply.toolCalls());
            return;
        }
        if ("refusal".equals(reply.stopReason())) {
            end("Claude declined that order.");
            return;
        }
        if (reply.toolCalls().isEmpty()) {
            finalText = reply.text();
            checkProgress();
            return;
        }
        if (!reply.text().isEmpty()) LOG.debug("model text alongside tool calls: {}", reply.text());
        if (reply.done()) doneText = reply.text();
        startBatch(reply.toolCalls());
    }

    private void startBatch(List<Brain.ToolCall> calls) {
        Batch next = new Batch();
        List<ActionQueue.Entry> entries = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            Brain.ToolCall tc = calls.get(i);
            Call call = new Call(tc.id(), tc.name());
            next.calls.add(call);
            if (i >= settings.batchSize()) {
                call.result = ActionResult.denied("batch_limit");
                continue;
            }
            ToolSchemas.Parsed parsed = tools.parse(tc.name(), tc.input());
            if (parsed.error() != null) {
                call.result = parsed.error();
                continue;
            }
            if (parsed.prioLowered()) {
                notes.add(tc.name() + " " + tc.id() + ": prio_lowered to 2 (prio 3 is only for flee, stop and attack_entity)");
            }
            pending.put(tc.id(), call);
            entries.add(new ActionQueue.Entry(tc.id(), tc.name(), parsed.action(), parsed.prio()));
        }
        batch = next;
        enqueue(entries);
        checkProgress(); // a batch with nothing runnable reports at once
    }

    /** Tells the model now: unfinished calls of the current batch report {@code queued}. */
    private void report(String note) {
        if (inFlight) {
            interruptInFlight.add(note);
            return;
        }
        notes.add(note);
        mustReport = true;
        if (batch != null && !batch.reported) {
            reportBatch();
        } else {
            checkProgress();
        }
    }

    /** Decides whether to send results, finish the order, or keep waiting. */
    private void checkProgress() {
        if (orderFrom == null || inFlight || cancelling) return;
        if (goal) {
            if (batch != null && batch.done()) endGoal();
            return;
        }
        if (batch != null && !batch.reported) {
            if (doneText != null) { // a done batch never prefetches: it either ends the order or reports in full
                if (!batch.done()) return;
                if (!batch.allOk() || !notes.isEmpty() || mustReport) {
                    reportBatch();
                    return;
                }
                batch.reported = true;
                finalText = doneText;
                doneText = null;
            } else {
                boolean prefetch = batch.calls.size() > settings.prefetchAt() && batch.notStarted() <= settings.prefetchAt();
                if (batch.done() || prefetch) reportBatch();
                return;
            }
        }
        if (mustReport || !pendingResults.isEmpty()) {
            flush();
            return;
        }
        if (finalText != null && queue.idle()) finish(finalText);
    }

    private void reportBatch() {
        batch.reported = true;
        doneText = null;
        for (Call c : batch.calls) {
            if (c.result == null) c.reportedEarly = true;
            pendingResults.add(toolResult(c.id, c.result != null ? c.result : ActionResult.queued()));
        }
        flush();
    }

    /** Sends the next user turn (the opening or an update) if the guardrails allow a call now. */
    private void flush() {
        if (orderFrom == null || inFlight) return;
        if (steps >= settings.maxSteps()) {
            end("Stopping: that order used all " + settings.maxSteps() + " model rounds.");
            return;
        }
        switch (guardrails.allowCall()) {
            case OK -> deferred = false;
            case RATE_LIMITED -> {
                deferred = true;
                return;
            }
            case TOKEN_CAP -> {
                end("Session token cap reached (" + settings.sessionTokenCap() + "). No more model calls until the server restarts.");
                return;
            }
            case HALTED -> {
                abandon();
                return;
            }
        }
        StateSnapshot snapshot = host.snapshot();
        String state = snapshot.toJson();
        String text;
        if (opening == null) {
            text = prompts.updateTurn(notes, state);
        } else if (opening.kind() == Kind.GOAL) {
            text = prompts.autonomyTurn(opening.player(), List.copyOf(outcomes), memory.render(), notes, state);
        } else if (opening.kind() == Kind.EVENT) {
            text = prompts.eventTurn(opening.text(), memory.render(), notes, state);
        } else {
            text = prompts.orderTurn(opening.player(), opening.text(), host.recentChat(), memory.render(), notes, state);
        }
        text += "\n" + PromptBuilder.status(goal ? "own goal" : orderText, orderFrom, step(), snapshot, queue.snapshot());
        JsonArray content = pendingResults; // tool_result blocks must come first in the turn
        content.add(textBlock(text));
        messages.add(message("user", content));
        pendingResults = new JsonArray();
        notes.clear();
        opening = null;
        finalText = null;
        mustReport = false;
        send();
    }

    /** The model's own progress line for the status block: its short-term note with subject step, or null. */
    private String step() {
        return memory.shortTerm().stream().filter(e -> e.subject().equals("step")).map(Memory.Entry::text)
                .findFirst().orElse(null);
    }

    private void send() {
        steps++;
        inFlight = true;
        guardrails.recordCall();
        int forEpoch = epoch;
        Brain.Request request = new Brain.Request(prompts.system(), tools.definitions(), messages.deepCopy());
        try {
            brainThread.execute(() -> {
                Brain.Reply reply = null;
                BrainException error = null;
                try {
                    reply = brain.send(request);
                } catch (BrainException e) {
                    error = e;
                } catch (RuntimeException e) {
                    LOG.error("brain backend crashed", e);
                    error = new BrainException(BrainException.Kind.INTERNAL, e.toString());
                }
                Brain.Reply r = reply;
                BrainException err = error;
                mainThread.execute(() -> onReply(forEpoch, r, err));
            });
        } catch (RejectedExecutionException e) {
            LOG.warn("brain thread is shut down; dropping the order");
            abandon();
        }
    }

    private void fail(BrainException e) {
        LOG.warn("model call failed: {} {} (request {})", e.kind(), e.getMessage(), e.requestId());
        if (e.kind() == BrainException.Kind.AUTH) guardrails.halt();
        String notice = e.chatMessage(settings.apiKeyEnv());
        if (goal) {
            recordGoal("; the model call failed");
            // A goal retries every interval: say a failure once, not once a minute, until a goal works again.
            boolean repeat = notice.equals(lastGoalFailure);
            lastGoalFailure = notice;
            if (repeat) {
                abandon();
                return;
            }
        }
        end(notice);
    }

    private void finish(String text) {
        abandon();
        if (text != null && !text.isBlank()) host.say(text);
    }

    private void end(String notice) {
        abandon();
        host.notice(notice);
    }

    /** The goal's calls are done: keep their codes for the next goal and end quietly, unless the reply had words. */
    private void endGoal() {
        recordGoal("");
        lastGoalFailure = null;
        String text = goalText;
        abandon();
        if (text != null && !text.isBlank()) host.say(text);
    }

    private void recordGoal(String ending) {
        String calls = batch == null ? "no calls ran" : batch.calls.stream()
                .map(c -> c.name + " " + (c.result == null ? "unfinished" : c.result.code()))
                .collect(Collectors.joining(", "));
        outcomes.addLast("goal #" + goalCount + ": " + calls + ending);
        while (outcomes.size() > OUTCOMES_KEPT) outcomes.removeFirst();
    }

    private void autonomyOff() {
        autonomyBy = null;
        outcomes.clear();
    }

    /**
     * Drops the order quietly: cancels the order's queued calls but not the reflexes, forgets the transcript and lets
     * the brain release the order's session. A reply still in flight is ignored.
     */
    private void abandon() {
        epoch++;
        brain.reset();
        orderFrom = null;
        orderText = null;
        opening = null;
        goal = false;
        goalText = null;
        quietly(() -> queue.cancelIf(e -> !e.callId().startsWith("reflex-")));
        messages = new JsonArray();
        pending.clear();
        pendingResults = new JsonArray();
        notes.clear();
        batch = null;
        inFlight = false;
        deferred = false;
        mustReport = false;
        interruptInFlight.clear();
        finalText = null;
        doneText = null;
        steps = 0;
    }

    /**
     * Enqueues with progress checks held back: a level 3 entry scraps work before it is added, so the queue can
     * read idle inside those callbacks. The caller checks progress once the queue is settled.
     */
    private ActionQueue.Preemption enqueue(List<ActionQueue.Entry> entries) {
        boolean was = cancelling;
        cancelling = true;
        try {
            return queue.enqueue(entries);
        } finally {
            cancelling = was;
        }
    }

    /** Runs a queue cancel with progress checks held back. */
    private void quietly(Runnable cancel) {
        boolean was = cancelling;
        cancelling = true;
        try {
            cancel.run();
        } finally {
            cancelling = was;
        }
    }

    private static String listed(String label, List<ActionQueue.Entry> entries) {
        if (entries.isEmpty()) return "";
        return label + entries.stream().map(e -> e.tool() + " " + e.callId()).collect(Collectors.joining(", ")) + ".";
    }

    private static String reflexTool(Action action) {
        if (action instanceof Action.Flee) return "flee";
        if (action instanceof Action.AttackEntity) return "attack_entity";
        if (action instanceof Action.Say) return "say";
        if (action instanceof Action.Stop) return "stop";
        if (action instanceof Action.MoveTo) return "move_to";
        throw new IllegalArgumentException("not a reflex action: " + action);
    }

    private static JsonObject message(String role, JsonArray content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.add("content", content);
        return m;
    }

    private static JsonObject textBlock(String text) {
        JsonObject b = new JsonObject();
        b.addProperty("type", "text");
        b.addProperty("text", text);
        return b;
    }

    private static JsonArray single(JsonObject block) {
        JsonArray a = new JsonArray();
        a.add(block);
        return a;
    }

    private static JsonObject toolResult(String id, ActionResult r) {
        JsonObject b = new JsonObject();
        b.addProperty("type", "tool_result");
        b.addProperty("tool_use_id", id);
        b.addProperty("content", r.data() == null ? r.code() : r.code() + "\n" + r.data());
        if (r.isError()) b.addProperty("is_error", true);
        return b;
    }
}
