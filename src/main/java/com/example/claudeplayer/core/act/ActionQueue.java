package com.example.claudeplayer.core.act;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts queued actions at a fixed rate, once per server tick. Pacing counts ticks, not wall time, so lag slows
 * the agent down instead of making it burst. A timed action (move, wait) holds the queue until it finishes.
 */
public final class ActionQueue {
    private static final Logger LOG = LoggerFactory.getLogger(ActionQueue.class);
    private static final long UNIT = 1000; // credit for one start, so 3/s is exactly 150 per tick

    /** One queued tool call; {@code callId} is the tool_use id it answers. */
    public record Entry(String callId, String tool, Action action) {}

    public interface Listener {
        default void onStarted(Entry entry) {}

        void onFinished(Entry entry, ActionResult result);
    }

    private final long creditPerTick;
    private final int defaultTimeoutTicks;
    private final ActionRunner runner;
    private final ArrayDeque<Entry> waiting = new ArrayDeque<>();
    private Listener listener = (entry, result) -> {};
    private Entry running;
    private ActionRunner.Handle handle;
    private int runningTicks;
    private long credit = UNIT;

    public ActionQueue(double actionsPerSecond, int defaultTimeoutTicks, ActionRunner runner) {
        this.creditPerTick = Math.max(1, Math.round(actionsPerSecond * UNIT / 20.0));
        this.defaultTimeoutTicks = defaultTimeoutTicks;
        this.runner = runner;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void enqueue(List<Entry> entries) {
        waiting.addAll(entries);
    }

    /** Once per server tick. */
    public void tick() {
        credit += creditPerTick;
        if (running != null) poll();
        if (running == null && !waiting.isEmpty() && credit >= UNIT) {
            credit -= UNIT;
            start(waiting.poll());
        }
        // Bank at most one start, so an empty queue or a long timed action never turns into a burst.
        credit = Math.min(credit, UNIT);
    }

    /** Cancels the running action and everything waiting; each one reports {@code cancelled}. */
    public void cancelAll() {
        if (running != null) {
            cancelHandle();
            finish(ActionResult.cancelled());
        }
        cancelWaiting();
    }

    public boolean idle() {
        return running == null && waiting.isEmpty();
    }

    public int waitingCount() {
        return waiting.size();
    }

    public String runningTool() {
        return running == null ? null : running.tool();
    }

    private void start(Entry entry) {
        running = entry;
        runningTicks = 0;
        listener.onStarted(entry);
        try {
            handle = runner.start(entry.action());
        } catch (RuntimeException e) {
            LOG.error("{} failed to start", entry.tool(), e);
            handle = ActionRunner.Handle.done(ActionResult.failed("error"));
        }
        poll(); // quick actions finish in the tick they start
        if (entry.action() instanceof Action.Stop) cancelWaiting();
    }

    private void poll() {
        ActionResult result;
        try {
            result = handle.poll();
        } catch (RuntimeException e) {
            LOG.error("{} failed while running", running.tool(), e);
            result = ActionResult.failed("error");
        }
        if (result == null) {
            int limit = handle.timeoutTicks() > 0 ? handle.timeoutTicks() : defaultTimeoutTicks;
            if (++runningTicks < limit) return;
            cancelHandle();
            result = ActionResult.failed("timeout");
        }
        finish(result);
    }

    private void cancelHandle() {
        try {
            handle.cancel();
        } catch (RuntimeException e) {
            LOG.error("{} failed to cancel", running.tool(), e);
        }
    }

    private void finish(ActionResult result) {
        Entry entry = running;
        running = null;
        handle = null;
        listener.onFinished(entry, result);
    }

    private void cancelWaiting() {
        List<Entry> dropped = new ArrayList<>(waiting);
        waiting.clear();
        for (Entry e : dropped) listener.onFinished(e, ActionResult.cancelled());
    }
}
