package com.example.claudeplayer.core.memory;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.safety.WorldText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * The agent's two memories, both keyed by subject so a new entry about the same thing replaces the old one.
 *
 * <ul>
 *   <li>Short-term: exact, recent observations ("chest at 10,64,5: 15 oak_log, 17 cobblestone, 64 torch").
 *       Kept in RAM, oldest evicted past capacity, faded after short_term_minutes.
 *   <li>Long-term: durable facts the agent chose to keep ("the chest at 10,64,5 holds building materials").
 *       Saved through {@link Store} on every change; a full memory refuses new subjects until something is forgotten.
 * </ul>
 *
 * Both are shown to the model as data at the start of each order. The agent wrote them, possibly while reading
 * world text, so they're cleaned and capped like any other world text. Server thread only.
 */
public final class Memory {
    public static final int SUBJECT_MAX = 48;
    public static final int TEXT_MAX = 240;
    private static final int RENDER_BUDGET = 3000; // characters, about 750 tokens

    public record Entry(String subject, String text, long updatedMillis) {}

    /** Where long-term memory lives between sessions. */
    public interface Store {
        List<Entry> load();

        void save(List<Entry> entries);
    }

    private final int shortCapacity;
    private final long shortTtlMillis;
    private final int longCapacity;
    private final Store store;
    private final LongSupplier clockMillis;
    private final LinkedHashMap<String, Entry> shortTerm = new LinkedHashMap<>();
    private final LinkedHashMap<String, Entry> longTerm = new LinkedHashMap<>();

    public Memory(Settings settings, Store store, LongSupplier clockMillis) {
        this.shortCapacity = settings.shortTermCapacity();
        this.shortTtlMillis = settings.shortTermMinutes() * 60_000L;
        this.longCapacity = settings.longTermCapacity();
        this.store = store;
        this.clockMillis = clockMillis;
        for (Entry e : store.load()) {
            String subject = subject(e.subject());
            if (!subject.isEmpty()) longTerm.put(key(subject), new Entry(subject, text(e.text()), e.updatedMillis()));
        }
    }

    /** Short-term: replaces any note with the same subject. */
    public void note(String subject, String text) {
        String s = subject(subject);
        if (s.isEmpty()) return;
        shortTerm.remove(key(s)); // re-insert so insertion order stays oldest-first
        shortTerm.put(key(s), new Entry(s, text(text), clockMillis.getAsLong()));
        while (shortTerm.size() > shortCapacity) shortTerm.remove(shortTerm.keySet().iterator().next());
    }

    /** Long-term: replaces the memory with the same subject. False when memory is full and the subject is new. */
    public boolean remember(String subject, String text) {
        String s = subject(subject);
        if (s.isEmpty()) return false;
        if (!longTerm.containsKey(key(s)) && longTerm.size() >= longCapacity) return false;
        longTerm.remove(key(s));
        longTerm.put(key(s), new Entry(s, text(text), clockMillis.getAsLong()));
        store.save(longTerm());
        return true;
    }

    /** Removes the subject from both memories. False when neither had it. */
    public boolean forget(String subject) {
        String k = key(subject(subject));
        boolean fromShort = shortTerm.remove(k) != null;
        boolean fromLong = longTerm.remove(k) != null;
        if (fromLong) store.save(longTerm());
        return fromShort || fromLong;
    }

    public List<Entry> shortTerm() {
        long now = clockMillis.getAsLong();
        shortTerm.values().removeIf(e -> now - e.updatedMillis() > shortTtlMillis);
        return List.copyOf(shortTerm.values());
    }

    public List<Entry> longTerm() {
        return List.copyOf(longTerm.values());
    }

    /** The {@code <memory>} block for an order's first turn, newest first, within budget; empty when nothing is kept. */
    public String render() {
        List<Entry> shortList = new ArrayList<>(shortTerm());
        List<Entry> longList = new ArrayList<>(longTerm());
        if (shortList.isEmpty() && longList.isEmpty()) return "";
        long now = clockMillis.getAsLong();
        StringBuilder b = new StringBuilder("<memory>\n");
        int budget = RENDER_BUDGET;
        if (!longList.isEmpty()) {
            b.append("Long-term (facts you saved earlier):\n");
            for (int i = longList.size() - 1; i >= 0 && budget > 0; i--) {
                Entry e = longList.get(i);
                budget -= appendLine(b, e.subject() + ": " + e.text());
            }
        }
        if (!shortList.isEmpty() && budget > 0) {
            b.append("Short-term (recent observations; may be out of date):\n");
            for (int i = shortList.size() - 1; i >= 0 && budget > 0; i--) {
                Entry e = shortList.get(i);
                long minutes = (now - e.updatedMillis()) / 60_000;
                budget -= appendLine(b, e.subject() + " (" + (minutes < 1 ? "just now" : minutes + " min ago") + "): " + e.text());
            }
        }
        return b.append("</memory>").toString();
    }

    private static int appendLine(StringBuilder b, String line) {
        b.append("- ").append(line).append('\n');
        return line.length() + 3;
    }

    private static String subject(String raw) {
        return WorldText.clean(raw, SUBJECT_MAX);
    }

    private static String text(String raw) {
        return WorldText.clean(raw, TEXT_MAX);
    }

    private static String key(String subject) {
        return subject.toLowerCase(Locale.ROOT);
    }
}
