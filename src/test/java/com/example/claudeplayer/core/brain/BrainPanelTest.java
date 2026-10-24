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

    /** An idle panel: no order, no plan, nothing queued, nothing recent, caution 3, attacks on, no calls yet. */
    private static BrainPanel.View idle() {
        return new BrainPanel.View(null, null, false, false, null, null, false, List.of(), List.of(),
                0, 12, 3, "Reasonable", true, 0, 0);
    }

    private static BrainPanel.View order(String from, String text) {
        return new BrainPanel.View(from, text, false, false, null, null, false, List.of(), List.of(),
                0, 12, 3, "Reasonable", true, 0, 0);
    }

    private static BrainPanel.View thinking(String plan, String said, boolean inFlight) {
        return new BrainPanel.View("Felipe", "go", false, false, plan, said, inFlight, List.of(), List.of(),
                2, 12, 3, "Reasonable", true, 0, 0);
    }

    private static BrainPanel.View queued(List<ActionQueue.Item> items) {
        return new BrainPanel.View("Felipe", "go", false, false, null, null, false, items, List.of(),
                0, 12, 3, "Reasonable", true, 0, 0);
    }

    private static BrainPanel.Section section(BrainPanel.View view, String title) {
        for (BrainPanel.Section s : BrainPanel.sections(view)) if (s.title().equals(title)) return s;
        throw new AssertionError("no section " + title);
    }

    private static List<String> lines(BrainPanel.View view, String title) {
        return section(view, title).lines();
    }

    @Test
    void fiveSectionsInOrder() {
        List<String> titles = BrainPanel.sections(idle()).stream().map(BrainPanel.Section::title).toList();
        assertEquals(List.of("Order", "Thinking", "Queue", "Recent", "Status"), titles);
    }

    @Test
    void anIdlePanel() {
        BrainPanel.View v = idle();
        assertEquals(List.of("idle"), lines(v, "Order"));
        assertEquals(List.of("(no plan)"), lines(v, "Thinking"));
        assertEquals(List.of("Nothing queued"), lines(v, "Queue"));
        assertEquals(List.of("Nothing yet"), lines(v, "Recent"));
        assertEquals(List.of("caution 3 (Reasonable)", "attacks on", "step 0/12", "0 calls, 0 weighted tokens this hour"),
                lines(v, "Status"));
    }

    @Test
    void haltedShowsWhenIdleAndInStatus() {
        BrainPanel.View v = new BrainPanel.View(null, null, false, true, null, null, false, List.of(), List.of(),
                0, 12, 3, "Reasonable", true, 0, 0);
        assertEquals(List.of("halted"), lines(v, "Order"));
        assertEquals("kill switch on", lines(v, "Status").get(4));
    }

    @Test
    void anOrderShowsWhoAndTheWholeText() {
        String text = "x".repeat(300);
        assertEquals(List.of("from Felipe", "\"" + text + "\""), lines(order("Felipe", text), "Order"));
    }

    @Test
    void aReactionAndAGoalHaveNoOrderText() {
        assertEquals(List.of("reacting to a threat"), lines(order("", ""), "Order"));
        BrainPanel.View goal = new BrainPanel.View("", "", true, false, null, null, false, List.of(), List.of(),
                0, 12, 3, "Reasonable", true, 0, 0);
        assertEquals(List.of("autonomous goal"), lines(goal, "Order"));
    }

    @Test
    void worldTextIsCleanedInTheOrder() {
        assertEquals(List.of("from Fe lipe", "\"a kind ‹b›\""), lines(order("Fe§a lipe", "a §ekind <b>"), "Order"));
    }

    @Test
    void thinkingShowsThePlanAndWhatWasSaid() {
        assertEquals(List.of("Mining oak to the north: F wants planks", "said in chat: \"On my way.\""),
                lines(thinking("Mining oak to the north: F wants planks", "On my way.", false), "Thinking"));
    }

    @Test
    void thinkingWaitsDuringACall() {
        assertEquals(List.of("(waiting for Claude)"), lines(thinking("old plan", null, true), "Thinking"));
    }

    @Test
    void aBlankReplyTextAddsNoSaidLine() {
        assertEquals(List.of("(no plan)"), lines(thinking(null, "  ", false), "Thinking"));
    }

    @Test
    void thePlanIsCleanedAndClipped() {
        assertEquals("‹b› go", lines(thinking("§c<b>  go", null, false), "Thinking").get(0));
        String plan = lines(thinking("p".repeat(300), null, false), "Thinking").get(0);
        assertEquals(BrainPanel.PLAN_MAX, plan.length());
        assertEquals('…', plan.charAt(plan.length() - 1));
    }

    @Test
    void theRunningActionComesFirstThenTheWaitingOnesInOrder() {
        List<ActionQueue.Item> items = List.of(
                item("say", 3, ActionQueue.State.RUNNING),
                item("move_to", 2, ActionQueue.State.PAUSED),
                item("say", 1, ActionQueue.State.WAITING));
        assertEquals(List.of(
                        "running: say, prio 3",
                        "1. move_to, prio 2, paused: resumes after a reflex",
                        "2. say, prio 1"),
                lines(queued(items), "Queue"));
    }

    @Test
    void aLongQueueShowsFifteenThenHowManyMore() {
        List<ActionQueue.Item> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) items.add(item("say", 1, ActionQueue.State.WAITING));
        List<String> lines = lines(queued(items), "Queue");
        assertEquals(15 + 1, lines.size());
        assertEquals("15. say, prio 1", lines.get(14));
        assertEquals("+5 more", lines.get(15));
    }

    @Test
    void recentResultsKeepTheirOrderAndAreCleaned() {
        List<AgentLoop.Recent> recent = List.of(new AgentLoop.Recent("move_to", "failed:no path to ‹x›"),
                new AgentLoop.Recent("say", "ok"), new AgentLoop.Recent("dig<", "denied:§4<too far>"));
        BrainPanel.View v = new BrainPanel.View(null, null, false, false, null, null, false, List.of(), recent,
                0, 12, 3, "Reasonable", true, 0, 0);
        assertEquals(List.of("move_to: failed:no path to ‹x›", "say: ok", "dig‹: denied:‹too far›"), lines(v, "Recent"));
    }

    @Test
    void statusShowsCautionAttacksStepsAndTokens() {
        BrainPanel.View v = new BrainPanel.View("Felipe", "go", false, false, null, null, false, List.of(), List.of(),
                4, 12, 5, "Innocent", false, 7, 12345);
        assertEquals(List.of("caution 5 (Innocent)", "attacks off", "step 4/12", "7 calls, 12345 weighted tokens this hour"),
                lines(v, "Status"));
    }

    @Test
    void everySectionHasALine() {
        for (BrainPanel.Section s : BrainPanel.sections(idle())) assertEquals(false, s.lines().isEmpty(), s.title());
    }
}
