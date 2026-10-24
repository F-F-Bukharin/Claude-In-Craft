package com.example.claudeplayer.core.brain;

import com.example.claudeplayer.core.act.ActionQueue;
import com.example.claudeplayer.core.safety.WorldText;
import java.util.ArrayList;
import java.util.List;

/**
 * The Claude Brain panel's content: five titled sections (Order, Thinking, Queue, Recent, Status). Pure data; the
 * game side only sends, wraps and draws the lines. Every line that carries world or model text is cleaned here.
 */
public final class BrainPanel {
    /** Most queue entries listed; any beyond become one "+N more" line. */
    public static final int MAX_ITEMS = 15;
    /** The plan's longest shown length. */
    public static final int PLAN_MAX = 120;

    /** One titled block of the panel. {@code lines} is never empty. */
    public record Section(String title, List<String> lines) {
        public Section {
            lines = List.copyOf(lines);
        }
    }

    /**
     * What the panel shows, read on the server thread.
     *
     * @param orderFrom     who the current order is from; "" for a goal or a reaction nobody ordered; null when idle
     * @param orderText     the current order's text
     * @param goal          the running order is an autonomous goal
     * @param halted        the kill switch
     * @param plan          the model's latest plan line; null if none
     * @param lastReplyText the reply text the order or goal last said in chat; null if none
     * @param inFlight      a model call is out
     * @param queue         the queue, as {@link ActionQueue#snapshot()} gives it
     * @param recent        the last action results, newest first
     */
    public record View(String orderFrom, String orderText, boolean goal, boolean halted,
                       String plan, String lastReplyText, boolean inFlight,
                       List<ActionQueue.Item> queue, List<AgentLoop.Recent> recent,
                       int step, int maxSteps,
                       int cautionLevel, String cautionTitle, boolean attacksOn,
                       int calls, long weightedTokens) {}

    private BrainPanel() {}

    public static List<Section> sections(View v) {
        return List.of(
                new Section("Order", order(v)),
                new Section("Thinking", thinking(v)),
                new Section("Queue", queue(v.queue())),
                new Section("Recent", recent(v.recent())),
                new Section("Status", status(v)));
    }

    private static List<String> order(View v) {
        if (v.orderFrom() == null) return List.of(v.halted() ? "halted" : "idle");
        if (v.orderFrom().isEmpty()) return List.of(v.goal() ? "autonomous goal" : "reacting to a threat");
        return List.of("from " + WorldText.clean(v.orderFrom(), WorldText.NAME_MAX),
                "\"" + WorldText.clean(v.orderText(), WorldText.ORDER_MAX) + "\"");
    }

    private static List<String> thinking(View v) {
        List<String> lines = new ArrayList<>();
        String plan = WorldText.clean(v.plan(), PLAN_MAX);
        lines.add(v.inFlight() ? "(waiting for Claude)" : plan.isEmpty() ? "(no plan)" : plan);
        String said = WorldText.clean(v.lastReplyText(), WorldText.TEXT_MAX);
        if (!said.isEmpty()) lines.add("said in chat: \"" + said + "\"");
        return lines;
    }

    private static List<String> queue(List<ActionQueue.Item> items) {
        if (items == null || items.isEmpty()) return List.of("Nothing queued");
        List<String> lines = new ArrayList<>();
        int waiting = 0;
        for (ActionQueue.Item item : items.subList(0, Math.min(items.size(), MAX_ITEMS))) {
            ActionQueue.Entry entry = item.entry();
            String what = WorldText.clean(entry.tool(), WorldText.NAME_MAX) + ", prio " + entry.prio();
            lines.add(switch (item.state()) {
                case RUNNING -> "running: " + what;
                case WAITING -> ++waiting + ". " + what;
                case PAUSED -> ++waiting + ". " + what + ", paused: resumes after a reflex";
            });
        }
        if (items.size() > MAX_ITEMS) lines.add("+" + (items.size() - MAX_ITEMS) + " more");
        return lines;
    }

    private static List<String> recent(List<AgentLoop.Recent> results) {
        if (results == null || results.isEmpty()) return List.of("Nothing yet");
        List<String> lines = new ArrayList<>();
        for (AgentLoop.Recent r : results) {
            lines.add(WorldText.clean(r.tool(), WorldText.NAME_MAX) + ": " + WorldText.clean(r.code(), WorldText.TEXT_MAX));
        }
        return lines;
    }

    private static List<String> status(View v) {
        List<String> lines = new ArrayList<>();
        lines.add("caution " + v.cautionLevel() + " (" + WorldText.clean(v.cautionTitle(), WorldText.NAME_MAX) + ")");
        lines.add(v.attacksOn() ? "attacks on" : "attacks off");
        lines.add("step " + v.step() + "/" + v.maxSteps());
        lines.add(v.calls() + " calls, " + v.weightedTokens() + " weighted tokens this hour"); // Guardrails' rolling hour
        if (v.halted()) lines.add("kill switch on");
        return lines;
    }
}
