package com.example.claudeplayer.core.brain;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.StateSnapshot;
import com.example.claudeplayer.core.WorldMap;
import com.example.claudeplayer.core.act.ActionQueue;
import com.example.claudeplayer.core.chat.Conversation;
import com.example.claudeplayer.core.safety.CautionLevel;
import com.example.claudeplayer.core.safety.WorldText;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the system prompt (fixed for the session, so it caches) and the user turns. World data goes into tagged
 * blocks that the system prompt marks as data; only the {@code <order>} and {@code <autonomy>} blocks carry
 * instructions.
 */
public final class PromptBuilder {
    public static final String DEFAULT_RESOURCE = "/claudeplayer/system_prompt.md";

    private static final int STATUS_MAX = 60;
    private static final int THREATS_SHOWN = 3;
    private static final int QUEUE_GROUPS = 6;
    private static final String NAMESPACE = "minecraft:";

    private final String system;

    public PromptBuilder(Settings settings, String template) {
        StringBuilder levels = new StringBuilder();
        for (CautionLevel l : CautionLevel.values()) {
            levels.append("  - ").append(l.number()).append(' ').append(l.title).append(": ").append(l.rule).append('\n');
        }
        double aps = settings.actionsPerSecond();
        this.system = template
                .replace("{{name}}", settings.bodyName())
                .replace("{{batch_size}}", String.valueOf(settings.batchSize()))
                .replace("{{actions_per_second}}", aps == Math.rint(aps) ? String.valueOf((int) aps) : String.valueOf(aps))
                .replace("{{action_timeout}}", String.valueOf(settings.actionTimeoutSeconds()))
                .replace("{{trigger}}", settings.trigger())
                .replace("{{short_minutes}}", String.valueOf(settings.shortTermMinutes()))
                .replace("{{map_key}}", WorldMap.KEY)
                .replace("{{levels}}", levels.toString().stripTrailing())
                .replace("{{attacks}}", attacks(settings));
    }

    /** How to fight, or, below the attack phase, that attacks are off: the prompt never offers a tool the list lacks. */
    private static String attacks(Settings settings) {
        if (settings.attacksEnabled()) {
            return "attack_entity takes the ids listed in world_state.hostiles. Use fight for a whole fight: it gears up,"
                    + " walks and swings by itself until it's done; attack_entity is a single swing.";
        }
        return "Attacks are switched off on this server (tool_phase " + settings.toolPhase() + "; fighting needs "
                + Settings.ATTACK_PHASE + "): you have no attack actions, and when your caution level says fight, your"
                + " body flees instead. If a player asks you to fight, say a server op must set tool_phase to "
                + Settings.ATTACK_PHASE + " in claudeplayer-common.toml and reload the world.";
    }

    /** The prompt from {@code override} when that file exists, else the copy bundled in the jar. */
    public static String loadTemplate(Path override) {
        try {
            if (override != null && Files.isRegularFile(override)) return Files.readString(override);
            try (InputStream in = PromptBuilder.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) throw new IllegalStateException("missing resource " + DEFAULT_RESOURCE);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String system() {
        return system;
    }

    /** First turn of an order. {@code order} is already cleaned by ChatIntake; {@code memory} is Memory.render(). */
    public String orderTurn(String player, String order, List<Conversation.Line> chat, String memory,
                            List<String> notes, String state) {
        StringBuilder b = new StringBuilder();
        b.append("<order from=\"").append(WorldText.clean(player, WorldText.NAME_MAX)).append("\">\n")
                .append(order).append("\n</order>\n");
        if (!chat.isEmpty()) {
            b.append("<recent_chat>\n");
            for (Conversation.Line line : chat) {
                b.append(WorldText.quote(line.speaker(), WorldText.NAME_MAX)).append(": ")
                        .append(WorldText.quote(line.text(), WorldText.TEXT_MAX)).append('\n');
            }
            b.append("</recent_chat>\n");
        }
        if (!memory.isEmpty()) b.append(memory).append('\n');
        return finish(b, notes, state);
    }

    /** First turn when nobody gave an order and the body has to react (a threat, a hit). */
    public String eventTurn(String event, String memory, List<String> notes, String state) {
        StringBuilder b = new StringBuilder("<event>\nNo one gave an order. ").append(event)
                .append("\nReact as your caution level says, then report in one short sentence.\n</event>\n");
        if (!memory.isEmpty()) b.append(memory).append('\n');
        return finish(b, notes, state);
    }

    /**
     * The only turn of an autonomous goal: nobody gave an order, so the model picks one goal. {@code by} turned
     * autonomy on; {@code outcomes} are the last goals' result codes, oldest first.
     */
    public String autonomyTurn(String by, List<String> outcomes, String memory, List<String> notes, String state) {
        StringBuilder b = new StringBuilder("<autonomy by=\"").append(WorldText.clean(by, WorldText.NAME_MAX)).append("\">\n")
                .append("No one gave an order. You are on your own until a player calls you.\n")
                .append("Survival ladder: wood, crafting table, wooden pickaxe, cobblestone, stone tools, then coal and iron"
                        + " (dig down, gather), furnace, smelt, iron tools and armor. Keep food above half by eating; you"
                        + " can't hunt yet, so gather food when you see it. At night stay near home or dig a small shelter."
                        + " Store surplus at home.\n")
                .append("Pick ONE goal and do it with as few calls as possible, preferring gather, dig, craft, smelt, store"
                        + " and eat over many small calls. This is your only reply for this goal: its calls run, then the"
                        + " next goal starts from fresh state. Keep your plan in a long-term memory with subject autonomy_plan."
                        + " Reply text goes to chat, so leave it empty unless a player must know something now.\n")
                .append("</autonomy>\n");
        if (!outcomes.isEmpty()) {
            b.append("<last_goals>\n");
            for (String outcome : outcomes) b.append("- ").append(outcome).append('\n');
            b.append("</last_goals>\n");
        }
        if (!memory.isEmpty()) b.append(memory).append('\n');
        return finish(b, notes, state);
    }

    /**
     * The one turn that replaces a long order's transcript (autocompact), built in code: the order again, one line per
     * earlier model turn ({@code omitted} older ones were dropped), the results the model was about to get as text,
     * then what an update turn carries. A blank {@code order} is a reaction nobody ordered.
     */
    public String compactTurn(String player, String order, List<String> earlier, int omitted, List<String> results,
                              String memory, List<String> notes, String state) {
        StringBuilder b = new StringBuilder();
        if (order.isEmpty()) {
            b.append("<event>\nNo one gave an order. You are reacting to a threat or a hit.\n</event>\n");
        } else {
            b.append("<order from=\"").append(WorldText.clean(player, WorldText.NAME_MAX)).append("\">\n")
                    .append(order).append("\n</order>\n");
        }
        b.append("<earlier>\nYour turns in this order so far, oldest first, each call as name\u2192result:\n");
        if (omitted > 0) b.append("- (").append(omitted).append(" earlier turns left out)\n");
        for (String line : earlier) b.append(line).append('\n');
        b.append("</earlier>\n");
        if (!results.isEmpty()) {
            b.append("<results>\n");
            for (String line : results) b.append(line).append('\n');
            b.append("</results>\n");
        }
        if (!memory.isEmpty()) b.append(memory).append('\n');
        return finish(b, notes, state);
    }

    /** Later turns: late results and interrupts, then fresh state. Follows the tool_result blocks. */
    public String updateTurn(List<String> notes, String state) {
        return finish(new StringBuilder(), notes, state);
    }

    /**
     * The Blockman status block, pinned to the end of every user turn (spec section 7), never in the system prompt.
     * A null or blank order reads "none"; a null {@code from} or {@code step} is left out or shows as "-".
     */
    public static String status(String order, String from, String step, StateSnapshot s, List<ActionQueue.Item> queue) {
        StringBuilder orderLine = new StringBuilder("ORDER: ");
        if (order == null || order.isBlank()) {
            orderLine.append("none");
        } else {
            orderLine.append(WorldText.quote(order, STATUS_MAX));
            String fromText = WorldText.clean(from, WorldText.NAME_MAX);
            if (!fromText.isEmpty()) orderLine.append(" (from ").append(fromText).append(')');
        }
        String stepText = WorldText.clean(step, STATUS_MAX);
        orderLine.append(" | STEP: ").append(stepText.isEmpty() ? "-" : stepText);

        String posLine;
        if (s.dead()) {
            posLine = "POS: dead";
        } else if (!s.present()) {
            posLine = "POS: no body";
        } else {
            posLine = "POS: " + (int) Math.floor(s.pos().x()) + " " + (int) Math.floor(s.pos().y()) + " "
                    + (int) Math.floor(s.pos().z()) + " " + bare(s.dimension())
                    + " | HP " + Math.round(s.health()) + "/" + Math.round(s.maxHealth())
                    + " | FOOD " + s.food() + "/20";
        }

        return "<status>\n" + orderLine + "\n" + posLine + "\nTHREATS: " + threats(s.hostiles())
                + " | QUEUE: " + queueText(queue) + "\n</status>";
    }

    private static String threats(List<StateSnapshot.Mob> hostiles) {
        if (hostiles.isEmpty()) return "none";
        List<StateSnapshot.Mob> near = hostiles.stream().sorted(Comparator.comparingDouble(StateSnapshot.Mob::distance)).toList();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(THREATS_SHOWN, near.size()); i++) {
            StateSnapshot.Mob m = near.get(i);
            if (i > 0) b.append(", ");
            b.append(bare(m.type())).append(' ').append(Math.round(m.distance())).append('m');
        }
        if (near.size() > THREATS_SHOWN) b.append(" +").append(near.size() - THREATS_SHOWN).append(" more");
        return b.toString();
    }

    /** Runs of the same prio and tool collapse to "p1 harvest x3"; past {@link #QUEUE_GROUPS} groups it ends in an ellipsis. */
    private static String queueText(List<ActionQueue.Item> queue) {
        if (queue.isEmpty()) return "empty";
        List<String> groups = new ArrayList<>();
        int i = 0;
        while (i < queue.size() && groups.size() < QUEUE_GROUPS) {
            ActionQueue.Entry first = queue.get(i).entry();
            int j = i + 1;
            while (j < queue.size() && queue.get(j).entry().prio() == first.prio()
                    && queue.get(j).entry().tool().equals(first.tool())) {
                j++;
            }
            int run = j - i;
            groups.add("p" + first.prio() + " " + first.tool() + (run > 1 ? " x" + run : ""));
            i = j;
        }
        String text = String.join(", ", groups);
        return i < queue.size() ? text + " …" : text;
    }

    /** A registry id without its "minecraft:" namespace, cleaned; a missing id reads as empty. */
    private static String bare(String id) {
        String text = id == null ? "" : id;
        if (text.startsWith(NAMESPACE)) text = text.substring(NAMESPACE.length());
        return WorldText.clean(text, WorldText.NAME_MAX);
    }

    private static String finish(StringBuilder b, List<String> notes, String state) {
        if (!notes.isEmpty()) {
            b.append("<updates>\n");
            for (String note : notes) b.append("- ").append(note).append('\n');
            b.append("</updates>\n");
        }
        return b.append("<world_state>\n").append(state).append("\n</world_state>").toString();
    }
}
