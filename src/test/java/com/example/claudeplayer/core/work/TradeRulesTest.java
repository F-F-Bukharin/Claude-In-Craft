package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.work.TradeRules.Offer;
import com.example.claudeplayer.core.work.TradeRules.Plan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** trade and trade_offers: the emerald math, how many trades the inventory and the limit allow, and the texts. */
class TradeRulesTest {
    /** 5 emerald + 1 book -> 1 enchanted_book. */
    private static final Offer BOOK = new Offer("emerald", 5, "book", 1, "enchanted_book", 1, "sharpness 3", 12);
    /** 24 paper -> 1 emerald: costs no emeralds. */
    private static final Offer PAPER = new Offer("paper", 24, null, 0, "emerald", 1, null, 16);

    @Test
    void emeraldsPerTradeCountsEmeraldCostsOnly() {
        assertEquals(5, TradeRules.emeraldsPerTrade(BOOK), "A is emerald");
        assertEquals(0, TradeRules.emeraldsPerTrade(PAPER), "no emerald cost");
        assertEquals(3, TradeRules.emeraldsPerTrade(new Offer("book", 1, "emerald", 3, "bookshelf", 1, null, 4)), "B is emerald");
        assertEquals(9, TradeRules.emeraldsPerTrade(new Offer("emerald", 4, "emerald", 5, "map", 1, null, 4)), "both");
        assertEquals(0, TradeRules.emeraldsPerTrade(new Offer("emerald_block", 1, null, 0, "diamond", 1, null, 4)), "an emerald block is not an emerald");
    }

    @Test
    void affordableUsesTheCarriedCountsAndAddsTheSameIdTwice() {
        assertEquals(2, TradeRules.affordable(BOOK, Map.of("emerald", 12, "book", 5)), "emeralds bind");
        assertEquals(1, TradeRules.affordable(BOOK, Map.of("emerald", 64, "book", 1)), "books bind");
        assertEquals(0, TradeRules.affordable(BOOK, Map.of("emerald", 64)), "no B at all");
        assertEquals(0, TradeRules.affordable(PAPER, Map.of("paper", 23)));
        assertEquals(3, TradeRules.affordable(PAPER, Map.of("paper", 72, "book", 1)), "B absent is ignored");
        Offer twice = new Offer("emerald", 4, "emerald", 5, "map", 1, null, 4);
        assertEquals(2, TradeRules.affordable(twice, Map.of("emerald", 18)), "4 + 5 per trade");
        assertEquals(1, TradeRules.affordable(twice, Map.of("emerald", 17)));
    }

    @Test
    void aLimitOfZeroTurnsTradingOffEvenWithoutEmeralds() {
        assertEquals(new Plan(0, "over_limit"), TradeRules.plan(PAPER, 1, Map.of("paper", 64), 0));
        assertEquals(new Plan(0, "over_limit"), TradeRules.plan(BOOK, 1, Map.of("emerald", 64, "book", 64), 0));
    }

    @Test
    void failuresComeInOrderOutOfStockThenPaymentThenLimit() {
        Offer empty = new Offer("emerald", 50, null, 0, "diamond_sword", 1, null, 0);
        assertEquals(new Plan(0, "out_of_stock"), TradeRules.plan(empty, 1, Map.of(), 32), "out of stock before payment and limit");
        assertEquals(new Plan(0, "missing_payment"), TradeRules.plan(BOOK, 1, Map.of("emerald", 4, "book", 1), 32));
        Offer pricey = new Offer("emerald", 40, null, 0, "diamond_sword", 1, null, 3);
        assertEquals(new Plan(0, "missing_payment"), TradeRules.plan(pricey, 1, Map.of("emerald", 39), 32), "payment before limit");
        assertEquals(new Plan(0, "over_limit"), TradeRules.plan(pricey, 1, Map.of("emerald", 64), 32), "one trade costs more than the limit");
    }

    @Test
    void theLimitCapsTheNumberOfTrades() {
        Offer five = new Offer("emerald", 5, null, 0, "bread", 6, null, 16);
        assertEquals(new Plan(6, "over_limit"), TradeRules.plan(five, 16, Map.of("emerald", 64), 32), "32 / 5 = 6");
        assertEquals(new Plan(6, null), TradeRules.plan(five, 6, Map.of("emerald", 64), 32), "exactly the request");
        assertEquals(new Plan(16, null), TradeRules.plan(PAPER, 16, Map.of("paper", 24 * 16), 1), "no emerald cost, no cap");
    }

    @Test
    void partialPlansSayWhichBoundStoppedThem() {
        assertEquals(new Plan(12, "out_of_stock"), TradeRules.plan(BOOK, 16, Map.of("emerald", 640, "book", 64), 640));
        assertEquals(new Plan(2, "missing_payment"), TradeRules.plan(BOOK, 16, Map.of("emerald", 10, "book", 64), 640));
        assertEquals(new Plan(3, null), TradeRules.plan(BOOK, 3, Map.of("emerald", 64, "book", 64), 32));
        Offer two = new Offer("emerald", 5, null, 0, "bread", 6, null, 2);
        assertEquals(new Plan(2, "out_of_stock"), TradeRules.plan(two, 4, Map.of("emerald", 10), 10), "a tie names out_of_stock first");
    }

    @Test
    void offerTextShowsCostBOnlyWhenPresentAndTheUsesLeft() {
        assertEquals("0: 24 paper -> 1 emerald (16 left)", TradeRules.offerText(0, PAPER));
        assertEquals("3: 5 emerald + 1 book -> 1 enchanted_book (sharpness 3) (12 left)", TradeRules.offerText(3, BOOK));
        Offer gone = new Offer("emerald", 1, null, 0, "bread", 6, null, 0);
        assertEquals("1: 1 emerald -> 6 bread (out of stock)", TradeRules.offerText(1, gone));
    }

    @Test
    void offersTextHasTheHeaderAndOneLinePerOffer() {
        String text = TradeRules.offersText("librarian level 2", List.of(PAPER, BOOK));
        assertEquals("librarian level 2\n0: 24 paper -> 1 emerald (16 left)\n1: 5 emerald + 1 book -> 1 enchanted_book (sharpness 3) (12 left)", text);
        assertEquals("0: 24 paper -> 1 emerald (16 left)", TradeRules.offersText("", List.of(PAPER)), "no header line when empty");
    }

    @Test
    void offersTextStaysUnder900WithAMoreTail() {
        List<Offer> many = new ArrayList<>();
        for (int i = 0; i < 32; i++) many.add(new Offer("emerald", 10 + i, "writable_book", 1, "enchanted_book", 1, "unbreaking 3, mending 1, efficiency 5", 12));
        String text = TradeRules.offersText("wandering_trader", many);
        assertTrue(text.length() < 900, "length " + text.length());
        assertTrue(text.matches("(?s).*\n\\+\\d+ more$"), text);
        int shown = (int) text.lines().filter(l -> l.matches("\\d+: .*")).count();
        int more = Integer.parseInt(text.substring(text.lastIndexOf('+') + 1, text.lastIndexOf(" more")));
        assertEquals(32, shown + more, "every offer is shown or counted");
        assertFalse(TradeRules.offersText("x", List.of(PAPER)).contains("more"));
    }

    @Test
    void boughtTextTotalsWhatWasPaidAndTheStop() {
        assertEquals("bought 3x 1 enchanted_book (sharpness 3) for 15 emerald + 3 book", TradeRules.boughtText(BOOK, 3, null));
        assertEquals("bought 2x 1 emerald for 48 paper (stopped: missing_payment)", TradeRules.boughtText(PAPER, 2, "missing_payment"));
    }
}
