package com.example.claudeplayer.core.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ActionQueueTest {
    private int tick;
    private final List<Integer> startTicks = new ArrayList<>();
    private final List<String> results = new ArrayList<>();

    /** Say finishes at once; Wait(seconds) runs seconds*20 ticks; MoveTo never finishes on its own. */
    private final ActionRunner runner = action -> {
        startTicks.add(tick);
        if (action instanceof Action.Wait w) {
            int[] left = {(int) (w.seconds() * 20)};
            return () -> --left[0] <= 0 ? ActionResult.ok() : null;
        }
        if (action instanceof Action.MoveTo) return new ActionRunner.Handle() {
            @Override
            public ActionResult poll() {
                return null;
            }

            @Override
            public void cancel() {
                results.add("move cancelled");
            }
        };
        return ActionRunner.Handle.done(ActionResult.ok());
    };

    private ActionQueue queue(double actionsPerSecond) {
        ActionQueue q = new ActionQueue(actionsPerSecond, 200, runner);
        q.setListener((entry, result) -> results.add(entry.callId() + "=" + result.code()));
        return q;
    }

    private static List<ActionQueue.Entry> says(int n) {
        return IntStream.range(0, n).mapToObj(i -> new ActionQueue.Entry("s" + i, "say", new Action.Say("hi"))).toList();
    }

    private void run(ActionQueue q, int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick++;
            q.tick();
        }
    }

    private long startsBetween(int from, int to) {
        return startTicks.stream().filter(t -> t >= from && t <= to).count();
    }

    @Test
    void startsThreePerTwentyTicks() {
        ActionQueue q = queue(3.0);
        q.enqueue(says(100));
        run(q, 200);
        assertEquals(4, startsBetween(1, 20), "the first window includes the one banked start");
        for (int from = 21; from <= 181; from += 20) assertEquals(3, startsBetween(from, from + 19), "window from " + from);
    }

    @Test
    void noBurstAfterALongTimedAction() {
        ActionQueue q = queue(3.0);
        List<ActionQueue.Entry> entries = new ArrayList<>();
        entries.add(new ActionQueue.Entry("w", "wait", new Action.Wait(5))); // holds the queue for 100 ticks
        entries.addAll(says(10));
        q.enqueue(entries);
        run(q, 160);
        assertTrue(startTicks.size() >= 3);
        int firstAfter = startTicks.get(1);
        assertTrue(startTicks.get(2) - firstAfter >= 6, "starts after the stall are paced, not bunched: " + startTicks);
        assertTrue(startsBetween(firstAfter, firstAfter + 19) <= 4, "at most the banked start plus normal pace");
    }

    @Test
    void timedActionTimesOutAndIsCancelled() {
        ActionQueue q = queue(3.0);
        q.enqueue(List.of(new ActionQueue.Entry("m", "move_to", new Action.MoveTo(0, 64, 0))));
        run(q, 199);
        assertEquals(List.of(), results);
        run(q, 2);
        assertEquals(List.of("move cancelled", "m=failed:timeout"), results);
        assertTrue(q.idle());
    }

    @Test
    void cancelAllReportsEverythingCancelled() {
        ActionQueue q = queue(3.0);
        List<ActionQueue.Entry> entries = new ArrayList<>();
        entries.add(new ActionQueue.Entry("m", "move_to", new Action.MoveTo(0, 64, 0)));
        entries.addAll(says(3));
        q.enqueue(entries);
        run(q, 5);
        q.cancelAll();
        assertEquals(List.of("move cancelled", "m=cancelled", "s0=cancelled", "s1=cancelled", "s2=cancelled"), results);
        assertTrue(q.idle());
    }

    @Test
    void stopCancelsWhatWasQueuedAfterIt() {
        ActionQueue q = queue(3.0);
        q.enqueue(List.of(
                new ActionQueue.Entry("a", "say", new Action.Say("a")),
                new ActionQueue.Entry("b", "stop", new Action.Stop()),
                new ActionQueue.Entry("c", "say", new Action.Say("c")),
                new ActionQueue.Entry("d", "say", new Action.Say("d"))));
        run(q, 40);
        assertEquals(List.of("a=ok", "b=ok", "c=cancelled", "d=cancelled"), results);
    }

    @Test
    void runnerCrashBecomesAFailedResult() {
        ActionQueue q = new ActionQueue(3.0, 200, action -> {
            throw new IllegalStateException("boom");
        });
        List<String> seen = new ArrayList<>();
        q.setListener((entry, result) -> seen.add(result.code()));
        q.enqueue(says(1));
        q.tick();
        assertEquals(List.of("failed:error"), seen);
    }
}
