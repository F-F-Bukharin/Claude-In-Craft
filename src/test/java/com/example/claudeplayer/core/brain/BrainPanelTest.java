package com.example.claudeplayer.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionQueue;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrainPanelTest {
    private static final Action SAY = new Action.Say("hi");

    private static ActionQueue.Item item(String tool, int prio, ActionQueue.State state) {
        return new ActionQueue.Item(new ActionQueue.Entry(tool + prio, tool, SAY, prio), state);
    }

    @Test
    void idleWithNothingQueued() {
        assertEquals(List.of("idle", "Nothing queued"), BrainPanel.lines(null, null, false, List.of()));
    }

    @Test
    void haltedShowsWhenIdle() {
        assertEquals(List.of("halted", "Nothing queued"), BrainPanel.lines(null, null, true, List.of()));
    }

    @Test
    void anActiveOrderWithNothingQueued() {
        assertEquals(List.of("order from Felipe: \"go\"", "Nothing queued"),
                BrainPanel.lines("Felipe", "go", false, List.of()));
    }

    @Test
    void aReactionHasNoOrderText() {
        assertEquals("reacting to a threat", BrainPanel.lines("", "", false, List.of()).get(0));
    }

    @Test
    void theHeaderHoldsTheWholeOrder() {
        String text = "x".repeat(300);
        assertEquals("order from Felipe: \"" + text + "\"", BrainPanel.lines("Felipe", text, false, List.of()).get(0));
    }

    @Test
    void worldTextIsCleanedInTheHeader() {
        assertEquals("order from Felipe: \"a kind ‹b›\"",
                BrainPanel.lines("Felipe", "a §ekind <b>", false, List.of()).get(0));
    }

    @Test
    void theRunningActionComesFirstThenTheWaitingOnesInOrder() {
        List<ActionQueue.Item> items = List.of(
                item("say", 3, ActionQueue.State.RUNNING),
                item("move_to", 2, ActionQueue.State.PAUSED),
                item("say", 1, ActionQueue.State.WAITING));
        assertEquals(List.of(
                        "order from Felipe: \"come here\"",
                        "running: say, prio 3",
                        "1. move_to, prio 2, paused: resumes after a reflex",
                        "2. say, prio 1"),
                BrainPanel.lines("Felipe", "come here", false, items));
    }

    @Test
    void aLongQueueShowsFifteenThenHowManyMore() {
        List<ActionQueue.Item> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) items.add(item("say", 1, ActionQueue.State.WAITING));
        List<String> lines = BrainPanel.lines("Felipe", "go", false, items);
        assertEquals(1 + 15 + 1, lines.size());
        assertEquals("15. say, prio 1", lines.get(15));
        assertEquals("+5 more", lines.get(16));
    }
}
