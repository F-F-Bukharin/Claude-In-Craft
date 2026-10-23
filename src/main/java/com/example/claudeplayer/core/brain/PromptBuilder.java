package com.example.claudeplayer.core.brain;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.WorldMap;
import com.example.claudeplayer.core.chat.Conversation;
import com.example.claudeplayer.core.safety.CautionLevel;
import com.example.claudeplayer.core.safety.WorldText;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the system prompt (fixed for the session, so it caches) and the user turns. World data goes into tagged
 * blocks that the system prompt marks as data; only the {@code <order>} and {@code <autonomy>} blocks carry
 * instructions.
 */
public final class PromptBuilder {
    public static final String DEFAULT_RESOURCE = "/claudeplayer/system_prompt.md";

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
                .replace("{{levels}}", levels.toString().stripTrailing());
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

    /** Later turns: late results and interrupts, then fresh state. Follows the tool_result blocks. */
    public String updateTurn(List<String> notes, String state) {
        return finish(new StringBuilder(), notes, state);
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
