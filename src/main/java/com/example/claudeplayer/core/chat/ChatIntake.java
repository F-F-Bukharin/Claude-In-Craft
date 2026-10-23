package com.example.claudeplayer.core.chat;

import com.example.claudeplayer.core.safety.CautionLevel;
import com.example.claudeplayer.core.safety.WorldText;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one chat line into an order, a control command, or nothing. Only allowlisted players can do anything;
 * everyone else's chat is ignored. Control words (stop, respawn, level N, autonomy on and off) are handled in code,
 * never by the model.
 */
public final class ChatIntake {
    private ChatIntake() {}

    public sealed interface Intake {}

    public record Ignore() implements Intake {}

    public record Order(String player, String text) implements Intake {}

    public record Stop(String player) implements Intake {}

    public record Respawn(String player) implements Intake {}

    public record SetLevel(String player, CautionLevel level) implements Intake {}

    /** "C go do your thing" turns autonomy on; "C auto off" turns it off without the kill switch. */
    public record Autonomy(String player, boolean on) implements Intake {}

    private static final Intake IGNORE = new Ignore();
    private static final Pattern STOP = Pattern.compile("stop\\b.*");
    private static final Pattern LEVEL = Pattern.compile("(?:level|caution)\\s+([0-5])");
    // Matched against the words alone (punctuation dropped), so "go do your thing, I'll call you" counts.
    private static final Pattern AUTO_OFF = Pattern.compile("(?:auto off|stop auto)(?: .*)?");
    private static final Pattern AUTO_ON =
            Pattern.compile("(?:go do your thing|do your thing|go auto|auto|autonomous|free roam|survive)(?: .*)?");

    /** {@code triggers}: the configured one first, then aliases such as "Claude:". The first that matches wins. */
    public static Intake classify(String sender, String message, Collection<String> triggers, Collection<String> allowlist) {
        if (message == null) return IGNORE;
        String msg = message.strip();
        int length = -1;
        for (String trigger : triggers) {
            if (matches(msg, trigger)) {
                length = trigger.length();
                break;
            }
        }
        if (length < 0 || !allowed(sender, allowlist)) return IGNORE;
        String rest = msg.substring(length).stripLeading();
        if (rest.startsWith(":") || rest.startsWith(",")) rest = rest.substring(1); // "C: stop", "C, come here"
        String body = WorldText.clean(rest, WorldText.ORDER_MAX);
        if (body.isEmpty()) return IGNORE;
        String lower = body.toLowerCase(Locale.ROOT);
        String words = lower.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").strip();
        if (AUTO_OFF.matcher(words).matches()) return new Autonomy(sender, false); // before stop: "stop auto" doesn't halt
        if (STOP.matcher(lower).matches()) return new Stop(sender);
        if (lower.equals("respawn")) return new Respawn(sender);
        Matcher level = LEVEL.matcher(lower);
        if (level.matches()) return new SetLevel(sender, CautionLevel.of(Integer.parseInt(level.group(1))));
        if (AUTO_ON.matcher(words).matches()) return new Autonomy(sender, true);
        return new Order(sender, body);
    }

    /**
     * A trigger ending in punctuation ("claude:") is a prefix in any case. One ending in a letter or digit ("C") is
     * a word: exact case, then a space, ':' or ',' after it, so "C come here" counts and "Cool" or "c ya" don't.
     */
    static boolean matches(String msg, String trigger) {
        if (trigger == null || trigger.isBlank()) return false;
        if (!Character.isLetterOrDigit(trigger.charAt(trigger.length() - 1))) {
            return msg.regionMatches(true, 0, trigger, 0, trigger.length());
        }
        if (!msg.startsWith(trigger) || msg.length() == trigger.length()) return false;
        char next = msg.charAt(trigger.length());
        return Character.isWhitespace(next) || next == ':' || next == ',';
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
