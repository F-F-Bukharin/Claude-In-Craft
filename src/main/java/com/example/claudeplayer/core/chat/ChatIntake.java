package com.example.claudeplayer.core.chat;

import com.example.claudeplayer.core.safety.CautionLevel;
import com.example.claudeplayer.core.safety.WorldText;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one chat line into an order, a control command, or nothing. Only allowlisted players can do anything;
 * everyone else's chat is ignored. Control words (stop, respawn, level N) are handled in code, never by the model.
 */
public final class ChatIntake {
    private ChatIntake() {}

    public sealed interface Intake {}

    public record Ignore() implements Intake {}

    public record Order(String player, String text) implements Intake {}

    public record Stop(String player) implements Intake {}

    public record Respawn(String player) implements Intake {}

    public record SetLevel(String player, CautionLevel level) implements Intake {}

    private static final Intake IGNORE = new Ignore();
    private static final Pattern STOP = Pattern.compile("stop\\b.*");
    private static final Pattern LEVEL = Pattern.compile("(?:level|caution)\\s+([0-5])");

    public static Intake classify(String sender, String message, String trigger, Collection<String> allowlist) {
        if (message == null || trigger == null || trigger.isEmpty()) return IGNORE;
        String msg = message.strip();
        if (!msg.regionMatches(true, 0, trigger, 0, trigger.length())) return IGNORE;
        if (!allowed(sender, allowlist)) return IGNORE;
        String body = WorldText.clean(msg.substring(trigger.length()), WorldText.ORDER_MAX);
        if (body.isEmpty()) return IGNORE;
        String lower = body.toLowerCase(Locale.ROOT);
        if (STOP.matcher(lower).matches()) return new Stop(sender);
        if (lower.equals("respawn")) return new Respawn(sender);
        Matcher level = LEVEL.matcher(lower);
        if (level.matches()) return new SetLevel(sender, CautionLevel.of(Integer.parseInt(level.group(1))));
        return new Order(sender, body);
    }

    /** Minecraft names are unique regardless of case, so the allowlist is too. */
    public static boolean allowed(String player, Collection<String> allowlist) {
        if (player == null) return false;
        for (String name : allowlist) {
            if (name.strip().equalsIgnoreCase(player.strip())) return true;
        }
        return false;
    }
}
