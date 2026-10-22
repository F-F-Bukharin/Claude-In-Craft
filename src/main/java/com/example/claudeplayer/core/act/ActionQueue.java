package com.example.claudeplayer.core.act;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts queued actions at a fixed rate, once per server tick. Pacing counts ticks, not wall time, so lag slows
 * the agent down instead of making it burst. A timed action (move, wait) holds the queue until it finishes.
 * Entries have a level, 1 (routine) to 3 (safety): higher levels start first, and a level 3 entry preempts.
 */
public final class ActionQueue {
    private static final Logger LOG = LoggerFactory.getLogger(ActionQueue.class);
    private static final long UNIT = 1000; // credit for one start, so 3/s is exactly 150 per tick

    private static final int LEVELS = 3;

    /** One queued tool call; {@code callId} is the tool_use id it answers. {@code prio} is the level, 1 to 3. */
    public record Entry(String callId, String tool, Action action, int prio) {
        public Entry {
            if (prio < 1 || prio > LEVELS) throw new IllegalArgumentException("prio must be 1 to 3: " + prio);
        }

        public Entry(String callId, String tool, Action action) {
            this(callId, tool, action, 1);
        }
    }

    /** What a level 3 enqueue displaced: {@code paused} holds the running level 2 entry, which restarts later. */
    public record Preemption(List<Entry> scrapped, List<Entry> paused) {
        public static final Preemption NONE = new Preemption(List.of(), List.of());

        public boolean isEmpty() {
            return scrapped.isEmpty() && paused.isEmpty();
        }
    }

    public interface Listener {
        default void onStarted(Entry entry) {}

        void onFinished(Entry entry, ActionResult result);
    }

    private final long creditPerTick;
    private final int defaultTimeoutTicks;
    private final ActionRunner runner;
    private final List<ArrayDeque<Entry>> waiting = List.of(new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>());
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

    /** Adds the entries; if any is level 3, it first scraps the waiting level 1 and interrupts the running level 1 or 2. */
    public Preemption enqueue(List<Entry> entries) {
        Preemption preemption = entries.stream().anyMatch(e -> e.prio() == LEVELS) ? preempt() : Preemption.NONE;
        for (Entry e : entries) level(e.prio()).add(e);
        return preemption;
    }

    /** Once per server tick. */
    public void tick() {
        credit += creditPerTick;
        if (running != null) poll();
        Entry next = running == null ? peekNext() : null;
        if (next != null && (credit >= UNIT || next.prio() == LEVELS)) {
            credit = Math.max(0, credit - UNIT);
            level(next.prio()).poll();
            start(next);
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

    /** Cancels the running action if it is below {@code prio}, and every waiting one below it; each reports {@code cancelled}. */
    public void cancelBelow(int prio) {
        if (running != null && running.prio() < prio) {
            cancelHandle();
            finish(ActionResult.cancelled());
        }
        for (int l = Math.min(prio - 1, LEVELS); l >= 1; l--) drain(level(l), ActionResult.cancelled());
    }

    public boolean idle() {
        return running == null && waitingCount() == 0;
    }

    public int waitingCount() {
        return waiting.stream().mapToInt(ArrayDeque::size).sum();
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
        for (int l = LEVELS; l >= 1; l--) drain(level(l), ActionResult.cancelled());
    }

    private void drain(ArrayDeque<Entry> queue, ActionResult result) {
        List<Entry> dropped = new ArrayList<>(queue);
        queue.clear();
        for (Entry e : dropped) listener.onFinished(e, result);
    }

    private ArrayDeque<Entry> level(int prio) {
        return waiting.get(prio - 1);
    }

    private Entry peekNext() {
        for (int l = LEVELS; l >= 1; l--) {
            Entry e = level(l).peek();
            if (e != null) return e;
        }
        return null;
    }

    private Preemption preempt() {
        List<Entry> scrapped = new ArrayList<>();
        List<Entry> paused = new ArrayList<>();
        if (running != null && running.prio() < LEVELS) {
            Entry r = running;
            cancelHandle();
            running = null;
            handle = null;
            if (r.prio() == 2) {
                level(2).addFirst(r); // restarts from scratch once level 3 is done
                paused.add(r);
            } else {
                scrapped.add(r);
                listener.onFinished(r, ActionResult.scrapped());
            }
        }
        List<Entry> routine = new ArrayList<>(level(1));
        level(1).clear();
        for (Entry e : routine) {
            scrapped.add(e);
            listener.onFinished(e, ActionResult.scrapped());
        }
        return new Preemption(scrapped, paused);
    }
}
