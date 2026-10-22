package com.example.claudeplayer.core.brain;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.StateSnapshot;
import com.example.claudeplayer.core.act.ActionQueue;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.chat.Conversation;
import com.example.claudeplayer.core.memory.Memory;
import com.example.claudeplayer.core.safety.Guardrails;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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
 * late the agent waits; it never acts on a guess.
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

    private record Opening(String player, String text, boolean event) {}

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

    private int epoch; // bumps whenever the order is dropped; replies from an older epoch are ignored
    private String orderFrom; // null when idle, "" for a reaction nobody ordered
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
    private String interruptInFlight;
    private String finalText;
    private int steps;

    public AgentLoop(Settings settings, Brain brain, Executor brainThread, Executor mainThread, ActionQueue queue,
                     Guardrails guardrails, PromptBuilder prompts, ToolSchemas tools, Memory memory, Host host) {
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
        queue.setListener(this);
    }

    public boolean active() {
        return orderFrom != null;
    }

    /** A new order from an allowlisted player. Replaces whatever was running and lifts a kill-switch halt. */
    public void startOrder(String player, String text) {
        guardrails.resume();
        begin(new Opening(player, text, false));
    }

    /**
     * A threat or a hit: cancel the queue and tell the model now. With no order running this starts a reaction,
     * unless idle reactions are off or the kill switch is on.
     */
    public void interrupt(String reason) {
        if (orderFrom == null) {
            if (settings.idleReactions() && !guardrails.halted()) begin(new Opening("", reason, true));
            return;
        }
        String note = "INTERRUPT: " + reason;
        if (inFlight) {
            interruptInFlight = note; // the reply on its way was planned on stale state
        } else {
            notes.add(note);
            mustReport = true;
        }
        cancelQueue();
        checkProgress();
    }

    /** Kill switch: cancel everything, drop the order, block model calls until the next order. */
    public void stop() {
        guardrails.halt();
        abandon();
    }

    /** The body died or was removed: drop the order without another model call. */
    public void bodyGone() {
        abandon();
    }

    /** Once per server tick: sends a call that was waiting for the call-rate window. */
    public void tick() {
        if (deferred) flush();
    }

    public String status() {
        if (orderFrom == null) return guardrails.halted() ? "halted" : "idle";
        String who = orderFrom.isEmpty() ? "reacting to a threat" : "order from " + orderFrom;
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
        if (call == null) return; // from an abandoned order
        if (call.reportedEarly) {
            notes.add(call.name + " " + call.id + ": " + result.code());
            if (result.isError()) mustReport = true;
        } else {
            call.result = result;
        }
        checkProgress();
    }

    private void begin(Opening first) {
        abandon();
        opening = first;
        orderFrom = first.player();
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
        if (interruptInFlight != null) {
            for (Brain.ToolCall call : reply.toolCalls()) pendingResults.add(toolResult(call.id(), ActionResult.cancelled()));
            notes.add(interruptInFlight);
            interruptInFlight = null;
            mustReport = true;
            checkProgress();
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
            pending.put(tc.id(), call);
            entries.add(new ActionQueue.Entry(tc.id(), tc.name(), parsed.action()));
        }
        batch = next;
        queue.enqueue(entries);
        checkProgress(); // a batch with nothing runnable reports at once
    }

    /** Decides whether to send results, finish the order, or keep waiting. */
    private void checkProgress() {
        if (orderFrom == null || inFlight || cancelling) return;
        if (batch != null && !batch.reported) {
            boolean prefetch = batch.calls.size() > settings.prefetchAt() && batch.notStarted() <= settings.prefetchAt();
            if (batch.done() || prefetch) reportBatch();
            return;
        }
        if (mustReport || !pendingResults.isEmpty()) {
            flush();
            return;
        }
        if (finalText != null && queue.idle()) finish(finalText);
    }

    private void reportBatch() {
        batch.reported = true;
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
        String state = host.snapshot().toJson();
        String text;
        if (opening == null) {
            text = prompts.updateTurn(notes, state);
        } else if (opening.event()) {
            text = prompts.eventTurn(opening.text(), memory.render(), notes, state);
        } else {
            text = prompts.orderTurn(opening.player(), opening.text(), host.recentChat(), memory.render(), notes, state);
        }
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
        end(e.chatMessage(settings.apiKeyEnv()));
    }

    private void finish(String text) {
        abandon();
        if (text != null && !text.isBlank()) host.say(text);
    }

    private void end(String notice) {
        abandon();
        host.notice(notice);
    }

    /** Drops the order quietly: cancels the queue and forgets the transcript. A reply still in flight is ignored. */
    private void abandon() {
        epoch++;
        orderFrom = null;
        opening = null;
        cancelQueue();
        messages = new JsonArray();
        pending.clear();
        pendingResults = new JsonArray();
        notes.clear();
        batch = null;
        inFlight = false;
        deferred = false;
        mustReport = false;
        interruptInFlight = null;
        finalText = null;
        steps = 0;
    }

    private void cancelQueue() {
        cancelling = true;
        try {
            queue.cancelAll();
        } finally {
            cancelling = false;
        }
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
