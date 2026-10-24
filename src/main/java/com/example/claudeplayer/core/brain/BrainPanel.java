package com.example.claudeplayer.core.brain;

import com.example.claudeplayer.core.act.ActionQueue;
import com.example.claudeplayer.core.safety.WorldText;
import java.util.ArrayList;
import java.util.List;

/**
 * The lines of the Claude Brain panel: the order header, then the queue, running first and then the waiting ones in
 * the order they will start. Pure data; the game side only wraps and draws the lines. World text is cleaned here.
 */
public final class BrainPanel {
    /** Most queue entries listed; any beyond become one "+N more" line. */
    public static final int MAX_ITEMS = 15;

    private BrainPanel() {}

    /**
     * @param orderFrom who the current order is from; "" for a reaction nobody ordered; null when idle
     * @param orderText the current order's text, shown only while an order runs
     * @param halted    the kill switch, shown only when idle
     * @param items     the queue, as {@link ActionQueue#snapshot()} gives it
     */
    public static List<String> lines(String orderFrom, String orderText, boolean halted, List<ActionQueue.Item> items) {
        List<String> lines = new ArrayList<>();
        lines.add(header(orderFrom, orderText, halted));
        if (items.isEmpty()) lines.add("Nothing queued");
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
        return List.copyOf(lines);
    }

    private static String header(String orderFrom, String orderText, boolean halted) {
        if (orderFrom == null) return halted ? "halted" : "idle";
        if (orderFrom.isEmpty()) return "reacting to a threat";
        return "order from " + WorldText.clean(orderFrom, WorldText.NAME_MAX) + ": \""
                + WorldText.clean(orderText, WorldText.ORDER_MAX) + "\"";
    }
}
