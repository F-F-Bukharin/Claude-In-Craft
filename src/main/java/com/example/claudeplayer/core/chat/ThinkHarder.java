package com.example.claudeplayer.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The think-harder offer: when an order asks for more thought, Claude asks the ordering player whether to switch to
 * max effort for that order. Only that player's y or n counts, and an offer nobody answers in time runs at normal
 * effort. Not thread-safe: use it on the server thread only.
 */
public final class ThinkHarder {
    public enum Answer { YES, NO, NONE }

    private static final String WORD = "[\\p{L}\\p{N}]";

    private final Pattern phrases;
    private final int timeoutSeconds;
    private final LongSupplier clockMillis;

    private String player;
    private String order;
    private long openedAt;
    private boolean open;

    /** {@code phrases}: lowercase-insensitive words or phrases such as "think harder". Blank ones are skipped. */
    public ThinkHarder(List<String> phrases, int timeoutSeconds, LongSupplier clockMillis) {
        this.phrases = compile(phrases);
        this.timeoutSeconds = timeoutSeconds;
        this.clockMillis = clockMillis;
    }

    private static Pattern compile(List<String> phrases) {
        List<String> alternatives = new ArrayList<>();
        if (phrases != null) {
            for (String phrase : phrases) {
                if (phrase == null || phrase.isBlank()) continue;
                List<String> words = new ArrayList<>();
                for (String word : phrase.strip().split("\\s+")) words.add(Pattern.quote(word));
                alternatives.add(String.join("\\s+", words));
            }
        }
        if (alternatives.isEmpty()) return null;
        // A phrase counts only as whole words: no letter or digit may touch either end.
        return Pattern.compile(
                "(?<!" + WORD + ")(?:" + String.join("|", alternatives) + ")(?!" + WORD + ")",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** True when the order contains one of the phrases. */
    public boolean wants(String order) {
        return phrases != null && order != null && phrases.matcher(order).find();
    }

    /** The reason the offer is refused: the nearest hostile within 16 blocks, by XZ distance. */
    public static String gate(String nearestHostileType, double xzDistance) {
        String name = nearestHostileType == null || nearestHostileType.isBlank()
                ? "hostile"
                : nearestHostileType.strip().replaceFirst("^minecraft:", "");
        long blocks = Math.round(xzDistance);
        return "Not now: " + name + " " + blocks + (blocks == 1 ? " block" : " blocks") + " away.";
    }

    /** Opens the offer for this player, replacing any open one, and returns the prompt line. */
    public String offer(String player, String order, String trigger) {
        this.player = player;
        this.order = order;
        this.openedAt = clockMillis.getAsLong();
        this.open = true;
        return "Switch to max effort for this order? " + trigger + " y / " + trigger + " n";
    }

    /** Reads an answer. Only the player who was offered can answer, and only y, yes, n or no closes the offer. */
    public Answer answer(String player, String text) {
        if (!open || player == null || this.player == null || text == null
                || !player.strip().equalsIgnoreCase(this.player.strip())) {
            return Answer.NONE;
        }
        String word = text.strip().replaceAll("[\\p{Punct}\\s]+$", "").toLowerCase(Locale.ROOT);
        Answer answer = switch (word) {
            case "y", "yes" -> Answer.YES;
            case "n", "no" -> Answer.NO;
            default -> Answer.NONE;
        };
        if (answer != Answer.NONE) open = false; // the order stays until takeOrder()
        return answer;
    }

    /** The order of the open or answered offer, or null. */
    public String pendingOrder() {
        return order;
    }

    /** Returns the offered order and forgets it, along with any open offer. */
    public String takeOrder() {
        String taken = order;
        order = null;
        player = null;
        open = false;
        return taken;
    }

    /** If an open offer has passed its timeout, closes it and returns its order to run at normal effort; else null. */
    public String expired() {
        if (!open || clockMillis.getAsLong() - openedAt < timeoutSeconds * 1000L) return null;
        return takeOrder();
    }

    public boolean open() {
        return open;
    }
}
