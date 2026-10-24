package com.example.claudeplayer.core.work;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure rules for trade and trade_offers: emeralds per trade, how many trades the carried items and the
 * trade_max_emeralds limit allow, and the result texts. The game side reads the offers and the inventory.
 */
public final class TradeRules {
    public static final String EMERALD = "emerald";
    /** trade_offers stays under this many characters. */
    public static final int TEXT_MAX = 900;

    private TradeRules() {
    }

    /**
     * One offer as the game would charge it now. {@code costB} is null (and {@code countB} 0) when the offer has
     * one cost; {@code resultExtra} is a short note such as enchantments, or null.
     */
    public record Offer(String costA, int countA, String costB, int countB, String result, int resultCount,
            String resultExtra, int usesLeft) {
        boolean hasB() {
            return costB != null && countB > 0;
        }
    }

    /** How many trades to do, and why fewer than asked (null when none was short). With 0 trades it is the failure. */
    public record Plan(int times, String stop) {}

    /** Emeralds one trade spends: the emerald costs only (an emerald block is not an emerald). */
    public static int emeraldsPerTrade(Offer o) {
        int n = EMERALD.equals(o.costA()) ? o.countA() : 0;
        if (o.hasB() && EMERALD.equals(o.costB())) n += o.countB();
        return n;
    }

    /** How many times the carried counts pay for the offer; the same id in both costs adds up. */
    public static int affordable(Offer o, Map<String, Integer> carried) {
        Map<String, Integer> need = new HashMap<>();
        if (o.countA() > 0) need.merge(o.costA(), o.countA(), Integer::sum);
        if (o.hasB()) need.merge(o.costB(), o.countB(), Integer::sum);
        int times = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            times = Math.min(times, carried.getOrDefault(e.getKey(), 0) / e.getValue());
        }
        return times == Integer.MAX_VALUE ? 0 : times;
    }

    /**
     * The trades to do for {@code times} asked. Failures, in order: a limit of 0 (trading off) or below gives
     * over_limit; no uses left, out_of_stock; can't pay once, missing_payment; one trade over the limit, over_limit.
     * Otherwise as many as every bound allows, naming the first bound (in that order) that stopped it short.
     */
    public static Plan plan(Offer o, int times, Map<String, Integer> carried, int maxEmeralds) {
        if (maxEmeralds <= 0) return new Plan(0, "over_limit");
        if (o.usesLeft() <= 0) return new Plan(0, "out_of_stock");
        int pay = affordable(o, carried);
        if (pay <= 0) return new Plan(0, "missing_payment");
        int per = emeraldsPerTrade(o);
        int byLimit = per > 0 ? maxEmeralds / per : Integer.MAX_VALUE;
        if (byLimit <= 0) return new Plan(0, "over_limit");
        int n = Math.min(Math.min(times, o.usesLeft()), Math.min(pay, byLimit));
        if (n >= times) return new Plan(times, null);
        String stop = n == o.usesLeft() ? "out_of_stock" : n == pay ? "missing_payment" : "over_limit";
        return new Plan(n, stop);
    }

    /** "3: 5 emerald + 1 book -> 1 enchanted_book (sharpness 3) (12 left)". */
    public static String offerText(int index, Offer o) {
        return index + ": " + cost(o, 1) + " -> " + result(o)
                + (o.usesLeft() > 0 ? " (" + o.usesLeft() + " left)" : " (out of stock)");
    }

    /** The header line (when not empty) and one line per offer, cut with "+N more" to stay under {@link #TEXT_MAX}. */
    public static String offersText(String header, List<Offer> offers) {
        StringBuilder sb = new StringBuilder(header == null ? "" : header);
        for (int i = 0; i < offers.size(); i++) {
            String line = offerText(i, offers.get(i));
            int left = offers.size() - i - 1;
            String tail = left > 0 ? "\n+" + left + " more" : "";
            int sep = sb.length() > 0 ? 1 : 0;
            if (sb.length() + sep + line.length() + tail.length() >= TEXT_MAX) {
                if (sb.length() > 0) sb.append('\n');
                return sb.append('+').append(offers.size() - i).append(" more").toString();
            }
            if (sep > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    /** "bought 3x 1 enchanted_book (sharpness 3) for 15 emerald + 3 book (stopped: out_of_stock)". */
    public static String boughtText(Offer o, int done, String stop) {
        return "bought " + done + "x " + result(o) + " for " + cost(o, done) + (stop != null ? " (stopped: " + stop + ")" : "");
    }

    private static String cost(Offer o, int times) {
        return o.countA() * times + " " + o.costA() + (o.hasB() ? " + " + o.countB() * times + " " + o.costB() : "");
    }

    private static String result(Offer o) {
        return o.resultCount() + " " + o.result() + (o.resultExtra() != null && !o.resultExtra().isEmpty() ? " (" + o.resultExtra() + ")" : "");
    }
}
