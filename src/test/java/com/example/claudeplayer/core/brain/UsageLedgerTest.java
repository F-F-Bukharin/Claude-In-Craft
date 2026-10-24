package com.example.claudeplayer.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageLedgerTest {
    private static final List<Double> WARN_AT = List.of(0.8, 0.9);
    private static final long MON_14 = Instant.parse("2026-10-26T14:00:00Z").getEpochSecond(); // a Monday
    private static final long NEXT_MON_14 = Instant.parse("2026-11-02T14:00:00Z").getEpochSecond();

    private final AtomicLong now = new AtomicLong(Instant.parse("2026-10-23T12:00:00Z").toEpochMilli());
    private final MemoryStore store = new MemoryStore();

    private UsageLedger ledger() {
        return new UsageLedger(WARN_AT, store, now::get);
    }

    /** A recorded-style rate_limit_event line. */
    private static String line(String status, double fiveHour, long fiveResets, double sevenDay, long sevenResets) {
        return "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"" + status
                + "\",\"unifiedWindows\":{\"five_hour\":{\"utilization\":" + fiveHour + ",\"resetsAt\":" + fiveResets
                + "},\"seven_day\":{\"utilization\":" + sevenDay + ",\"resetsAt\":" + sevenResets + "}}}}";
    }

    @Test
    void sevenDayWarnsOnceAt80() {
        UsageLedger ledger = ledger();
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.81, MON_14));
        assertEquals(List.of("Claude usage: 7-day at 81%, resets Mon 14:00"), ledger.drainWarnings());
        assertTrue(ledger.drainWarnings().isEmpty());
    }

    @Test
    void sameReadingDoesNotWarnAgain() {
        UsageLedger ledger = ledger();
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.81, MON_14));
        ledger.drainWarnings();
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.81, MON_14));
        assertTrue(ledger.drainWarnings().isEmpty());
    }

    @Test
    void warnsAt90AfterA91Reading() {
        UsageLedger ledger = ledger();
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.81, MON_14));
        ledger.drainWarnings();
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.91, MON_14));
        assertEquals(List.of("Claude usage: 7-day at 91%, resets Mon 14:00"), ledger.drainWarnings());
    }

    @Test
    void jumpPastBothThresholdsQueuesOnlyTheHighest() {
        UsageLedger ledger = ledger();
        ledger.onRateLimitEvent(line("allowed", 0.10, MON_14, 0.50, MON_14));
        assertTrue(ledger.drainWarnings().isEmpty());
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.95, MON_14));
        assertEquals(List.of("Claude usage: 7-day at 95%, resets Mon 14:00"), ledger.drainWarnings());
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.96, MON_14));
        assertTrue(ledger.drainWarnings().isEmpty());
    }

    @Test
    void rearmsAfterResetsAtPasses() {
        UsageLedger ledger = ledger();
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.81, MON_14));
        assertEquals(1, ledger.drainWarnings().size());
        now.set((MON_14 + 1) * 1000);
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, NEXT_MON_14, 0.85, NEXT_MON_14));
        assertEquals(List.of("Claude usage: 7-day at 85%, resets Mon 14:00"), ledger.drainWarnings());
    }

    @Test
    void rejectedStatusLimitsTheOrder() {
        UsageLedger ledger = ledger();
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.81, MON_14));
        assertFalse(ledger.limitReached());
        ledger.onRateLimitEvent(line("rejected", 0.10, MON_14, 1.0, MON_14));
        assertTrue(ledger.limitReached());
        assertEquals("Mon 14:00", ledger.resetsText());
    }

    @Test
    void aRejectedStatusEndsWhenItsWindowsReset() {
        UsageLedger ledger = ledger();
        ledger.onRateLimitEvent(line("rejected", 0.10, MON_14, 1.0, MON_14));
        now.set((MON_14 + 1) * 1000); // no new event comes until a session's first turn
        assertFalse(ledger.limitReached(), "a stale rejection must not block the next period");
    }

    @Test
    void garbageLineChangesNothing() {
        UsageLedger ledger = ledger();
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.81, MON_14));
        ledger.drainWarnings();
        int saves = store.saves;
        ledger.onRateLimitEvent("not json {");
        ledger.onRateLimitEvent("[1,2]");
        ledger.onRateLimitEvent("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"unifiedWindows\":"
                + "{\"seven_day\":{\"utilization\":\"high\",\"resetsAt\":" + MON_14 + "}}}}");
        assertTrue(ledger.drainWarnings().isEmpty());
        assertEquals(0.81, ledger.utilization(), 1e-9);
        assertEquals(saves, store.saves);
    }

    @Test
    void utilizationIgnoresWindowsThatHaveReset() {
        UsageLedger ledger = ledger();
        ledger.onRateLimitEvent(line("allowed", 0.10, MON_14, 0.81, MON_14));
        assertEquals(0.81, ledger.utilization(), 1e-9);
        now.set((MON_14 + 1) * 1000);
        assertEquals(0.0, ledger.utilization(), 1e-9);
        assertEquals("", ledger.resetsText());
    }

    @Test
    void resetsTextPicksTheSoonestWindowAtTheLimit() {
        UsageLedger ledger = ledger();
        ledger.onRateLimitEvent(line("allowed", 1.0, MON_14 - 3600, 1.0, MON_14));
        assertEquals("Mon 13:00", ledger.resetsText());
        ledger.onRateLimitEvent(line("allowed", 0.20, MON_14 - 3600, 1.0, MON_14));
        assertEquals("Mon 14:00", ledger.resetsText());
    }

    @Test
    void statusLinesShowWindowsAndTokens() {
        UsageLedger ledger = ledger();
        assertEquals(List.of("tokens: order 0, today 0"), ledger.statusLines());
        ledger.onRateLimitEvent(line("allowed", 0.29, MON_14, 0.42, MON_14));
        assertEquals(List.of("5-hour 29%, resets Mon 14:00", "7-day 42%, resets Mon 14:00",
                "tokens: order 0, today 0"), ledger.statusLines());
    }

    @Test
    void tokensCountPerOrderAndPerDay() {
        UsageLedger ledger = ledger();
        ledger.onTokens(100);
        ledger.onTokens(50);
        assertEquals(List.of("tokens: order 150, today 150"), ledger.statusLines());
        ledger.startOrder();
        ledger.onTokens(10);
        assertEquals(List.of("tokens: order 10, today 160"), ledger.statusLines());
        now.set(Instant.parse("2026-10-24T00:30:00Z").toEpochMilli());
        ledger.onTokens(5);
        assertEquals(List.of("tokens: order 15, today 5"), ledger.statusLines());
    }

    @Test
    void restoredLedgerKeepsFiredThresholdsAndTotals() {
        UsageLedger first = ledger();
        first.onRateLimitEvent(line("rejected", 0.10, MON_14, 0.81, MON_14));
        assertEquals(1, first.drainWarnings().size());
        first.onTokens(40);

        UsageLedger restored = ledger();
        assertTrue(restored.limitReached());
        restored.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.82, MON_14));
        assertTrue(restored.drainWarnings().isEmpty());
        assertEquals(List.of("5-hour 10%, resets Mon 14:00", "7-day 82%, resets Mon 14:00",
                "tokens: order 40, today 40"), restored.statusLines());

        now.set(Instant.parse("2026-10-24T09:00:00Z").toEpochMilli());
        assertEquals(List.of("5-hour 10%, resets Mon 14:00", "7-day 82%, resets Mon 14:00",
                "tokens: order 40, today 0"), ledger().statusLines());
    }

    @Test
    void jsonFileRoundTrips(@TempDir Path dir) {
        Path file = dir.resolve("config").resolve("claudeplayer_usage.json");
        JsonUsageFile jsonStore = new JsonUsageFile(file);
        assertEquals(0, jsonStore.load().size());

        UsageLedger ledger = new UsageLedger(WARN_AT, jsonStore, now::get);
        ledger.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.81, MON_14));
        assertEquals(1, ledger.drainWarnings().size());
        ledger.onTokens(25);

        UsageLedger again = new UsageLedger(WARN_AT, new JsonUsageFile(file), now::get);
        again.onRateLimitEvent(line("allowed_warning", 0.10, MON_14, 0.83, MON_14));
        assertTrue(again.drainWarnings().isEmpty());
        assertEquals(List.of("5-hour 10%, resets Mon 14:00", "7-day 83%, resets Mon 14:00",
                "tokens: order 25, today 25"), again.statusLines());
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
    }

    @Test
    void corruptFileLoadsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("claudeplayer_usage.json");
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        assertEquals(0, new JsonUsageFile(file).load().size());
        UsageLedger ledger = new UsageLedger(WARN_AT, new JsonUsageFile(file), now::get);
        assertEquals(0.0, ledger.utilization(), 1e-9);
    }

    /** Keeps the saved state in memory and counts saves. */
    private static final class MemoryStore implements UsageLedger.Store {
        private JsonObject state = new JsonObject();
        private int saves;

        @Override
        public JsonObject load() {
            return JsonParser.parseString(state.toString()).getAsJsonObject();
        }

        @Override
        public void save(JsonObject saved) {
            state = JsonParser.parseString(saved.toString()).getAsJsonObject();
            saves++;
        }
    }
}
