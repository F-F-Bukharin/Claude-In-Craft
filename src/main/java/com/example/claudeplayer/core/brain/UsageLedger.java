package com.example.claudeplayer.core.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claude Code usage: the rate limit windows from stream-json, a warning per threshold once per window, and token
 * totals per order and per day. Fed on the brain thread, read on the server thread, so every method is synchronized.
 */
public final class UsageLedger {
    private static final Logger LOG = LoggerFactory.getLogger(UsageLedger.class);
    private static final String[] WINDOWS = {"five_hour", "seven_day"};
    private static final DateTimeFormatter RESET_TIME =
            DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** Where the state lives between runs. Loading returns an empty object when there is nothing saved. */
    public interface Store {
        JsonObject load();

        void save(JsonObject state);
    }

    private static final class Window {
        double utilization;
        long resetsAt; // epoch seconds
        final Set<Double> fired = new TreeSet<>();
    }

    private final List<Double> warnAt;
    private final Store store;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new LinkedHashMap<>();
    private final List<String> pending = new ArrayList<>();
    private String status;
    private String day;
    private long dayTokens;
    private long orderTokens;

    public UsageLedger(List<Double> warnAt, Store store, LongSupplier clockMillis) {
        this.warnAt = warnAt.stream().sorted().toList();
        this.store = store;
        this.clock = clockMillis;
        restore(store.load());
        rollDay();
    }

    /** Reads one raw stream-json line. A line that doesn't parse, or carries nothing known, changes nothing. */
    public synchronized void onRateLimitEvent(String line) {
        String seenStatus = null;
        Map<String, JsonObject> seen = new LinkedHashMap<>();
        try {
            JsonElement root = JsonParser.parseString(line);
            if (!root.isJsonObject()) return;
            JsonObject o = root.getAsJsonObject();
            seenStatus = findStatus(o.has("rate_limit_info") ? o.get("rate_limit_info") : o);
            for (String name : WINDOWS) {
                JsonObject w = findWindow(o, name);
                if (w != null) seen.put(name, w);
            }
        } catch (RuntimeException e) {
            LOG.debug("Skipped a rate limit line that didn't parse", e);
            return;
        }
        if (seenStatus == null && seen.isEmpty()) return;
        if (seenStatus != null) status = seenStatus;
        long nowMs = clock.getAsLong();
        for (Map.Entry<String, JsonObject> e : seen.entrySet()) {
            reading(e.getKey(), num(e.getValue(), "utilization"), (long) num(e.getValue(), "resetsAt"), nowMs);
        }
        persist();
    }

    /** Adds tokens to the running order's total and to today's total. */
    public synchronized void onTokens(long tokens) {
        rollDay();
        orderTokens += tokens;
        dayTokens += tokens;
        persist();
    }

    /** The order total goes back to zero. */
    public synchronized void startOrder() {
        orderTokens = 0;
        persist();
    }

    /** Pending chat lines, oldest first. Taking them clears them. */
    public synchronized List<String> drainWarnings() {
        List<String> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    /** The highest utilization of a window that hasn't reset yet, 0 when there is none. */
    public synchronized double utilization() {
        long now = clock.getAsLong();
        double max = 0;
        for (Window w : windows.values()) {
            if (live(w, now)) max = Math.max(max, w.utilization);
        }
        return max;
    }

    /**
     * True when a live window is at its limit, or the last status was "rejected" and a window it came with hasn't
     * reset yet (events come only on a session's first turn, so a rejection must not outlive its window).
     */
    public synchronized boolean limitReached() {
        long now = clock.getAsLong();
        boolean anyLive = false;
        for (Window w : windows.values()) {
            if (!live(w, now)) continue;
            anyLive = true;
            if (w.utilization >= 1.0) return true;
        }
        return "rejected".equals(status) && (anyLive || windows.isEmpty());
    }

    /** The soonest reset of a window at its limit, or of any live window. Empty when unknown. */
    public synchronized String resetsText() {
        long now = clock.getAsLong();
        long at = soonestReset(now, true);
        if (at == 0) at = soonestReset(now, false);
        return at == 0 ? "" : resetText(at);
    }

    /** One line per known window, then the token totals. */
    public synchronized List<String> statusLines() {
        if (rollDay()) persist();
        List<String> lines = new ArrayList<>();
        for (String name : WINDOWS) {
            Window w = windows.get(name);
            if (w == null) continue;
            lines.add(label(name) + " " + percent(w.utilization) + "%"
                    + (w.resetsAt > 0 ? ", resets " + resetText(w.resetsAt) : ""));
        }
        lines.add("tokens: order " + orderTokens + ", today " + dayTokens);
        return lines;
    }

    private void reading(String name, double utilization, long resetsAt, long nowMs) {
        Window w = windows.computeIfAbsent(name, k -> new Window());
        // A later reset time, or a clock past the old one, is a new period: its warnings re-arm.
        if (w.resetsAt != 0 && (resetsAt > w.resetsAt || nowMs >= w.resetsAt * 1000L)) {
            w.fired.clear();
        }
        w.utilization = utilization;
        w.resetsAt = resetsAt;
        if (resetsAt * 1000L <= nowMs) return; // the window is already over: nothing to warn about
        Double top = null;
        for (double t : warnAt) {
            if (utilization >= t && w.fired.add(t)) top = t; // thresholds ascend, so the last one is the highest
        }
        if (top != null) {
            pending.add("Claude usage: " + label(name) + " at " + percent(utilization) + "%, resets "
                    + resetText(resetsAt));
            LOG.info("Claude usage warning: {} at {}%", label(name), percent(utilization));
        }
    }

    private long soonestReset(long nowMs, boolean atLimitOnly) {
        long best = 0;
        for (Window w : windows.values()) {
            if (!live(w, nowMs) || (atLimitOnly && w.utilization < 1.0)) continue;
            if (best == 0 || w.resetsAt < best) best = w.resetsAt;
        }
        return best;
    }

    /** Moves the day on when the clock has. Returns true when it did. */
    private boolean rollDay() {
        String today = Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneOffset.UTC).toLocalDate().toString();
        if (today.equals(day)) return false;
        day = today;
        dayTokens = 0;
        return true;
    }

    private void restore(JsonObject state) {
        if (state.has("windows") && state.get("windows").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : state.getAsJsonObject("windows").entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject o = e.getValue().getAsJsonObject();
                Window w = new Window();
                w.utilization = num(o, "utilization");
                w.resetsAt = (long) num(o, "resetsAt");
                if (o.has("fired") && o.get("fired").isJsonArray()) {
                    for (JsonElement t : o.getAsJsonArray("fired")) {
                        if (isNumber(t)) w.fired.add(t.getAsDouble());
                    }
                }
                windows.put(e.getKey(), w);
            }
        }
        status = str(state, "status");
        day = str(state, "day");
        dayTokens = (long) num(state, "dayTokens");
        orderTokens = (long) num(state, "orderTokens");
    }

    private void persist() {
        JsonObject state = new JsonObject();
        JsonObject saved = new JsonObject();
        for (Map.Entry<String, Window> e : windows.entrySet()) {
            Window w = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("utilization", w.utilization);
            o.addProperty("resetsAt", w.resetsAt);
            JsonArray fired = new JsonArray();
            for (double t : w.fired) fired.add(t);
            o.add("fired", fired);
            saved.add(e.getKey(), o);
        }
        state.add("windows", saved);
        if (status != null) state.addProperty("status", status);
        state.addProperty("day", day);
        state.addProperty("dayTokens", dayTokens);
        state.addProperty("orderTokens", orderTokens);
        store.save(state);
    }

    private boolean live(Window w, long nowMs) {
        return w.resetsAt * 1000L > nowMs;
    }

    /** Whole percent, floored. The small epsilon stops 0.29 * 100 from reading as 28. */
    private static long percent(double utilization) {
        return (long) Math.floor(utilization * 100 + 1e-9);
    }

    private static String label(String name) {
        return switch (name) {
            case "five_hour" -> "5-hour";
            case "seven_day" -> "7-day";
            default -> name;
        };
    }

    private static String resetText(long epochSeconds) {
        return RESET_TIME.format(Instant.ofEpochSecond(epochSeconds));
    }

    /** The first "status" string under the element, at any depth. */
    private static String findStatus(JsonElement el) {
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (isString(o.get("status"))) return o.get("status").getAsString();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                String found = findStatus(e.getValue());
                if (found != null) return found;
            }
        } else if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) {
                String found = findStatus(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The first object named {@code name} at any depth that has a numeric utilization and resetsAt. */
    private static JsonObject findWindow(JsonElement el, String name) {
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has(name) && o.get(name).isJsonObject()) {
                JsonObject w = o.getAsJsonObject(name);
                if (isNumber(w.get("utilization")) && isNumber(w.get("resetsAt"))) return w;
            }
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                JsonObject found = findWindow(e.getValue(), name);
                if (found != null) return found;
            }
        } else if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) {
                JsonObject found = findWindow(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isNumber(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static boolean isString(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static double num(JsonObject o, String key) {
        return isNumber(o.get(key)) ? o.get(key).getAsDouble() : 0;
    }

    private static String str(JsonObject o, String key) {
        return isString(o.get(key)) ? o.get(key).getAsString() : null;
    }
}
