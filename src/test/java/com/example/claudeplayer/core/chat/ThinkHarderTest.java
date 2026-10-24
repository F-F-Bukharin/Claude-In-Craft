package com.example.claudeplayer.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ThinkHarderTest {
    private static final List<String> PHRASES = List.of("think harder", "think hard");

    private long now;
    private ThinkHarder harder;

    private ThinkHarder make(int timeoutSeconds) {
        now = 0L;
        return new ThinkHarder(PHRASES, timeoutSeconds, () -> now);
    }

    @Test
    void phraseMatchesAnywhereInTheOrderIgnoringCase() {
        harder = make(30);
        assertTrue(harder.wants("please think harder, then build"));
        assertTrue(harder.wants("THINK HARDER"));
        assertTrue(harder.wants("Think Hard about the bridge"));
        assertTrue(harder.wants("think   harder"));
    }

    @Test
    void phraseNeedsWordBoundaries() {
        harder = make(30);
        assertFalse(harder.wants("rethink hardware"));
        assertFalse(harder.wants("rethink hard"));
        assertFalse(harder.wants("think hardware"));
        assertFalse(harder.wants("thinkharder"));
    }

    @Test
    void nothingToMatchMeansNoOffer() {
        harder = make(30);
        assertFalse(harder.wants(null));
        assertFalse(harder.wants(""));
        assertFalse(harder.wants("come here"));
        assertFalse(new ThinkHarder(List.of(), 30, () -> 0L).wants("think harder"));
    }

    @Test
    void gateNamesTheMobAndRoundsTheDistance() {
        assertEquals("Not now: zombie 9 blocks away.", ThinkHarder.gate("minecraft:zombie", 9.4));
        assertEquals("Not now: creeper 9 blocks away.", ThinkHarder.gate("minecraft:creeper", 8.6));
        assertEquals("Not now: zombie 1 block away.", ThinkHarder.gate("minecraft:zombie", 1.0));
    }

    @Test
    void offerReturnsThePromptLine() {
        harder = make(30);
        assertEquals("Switch to max effort for this order? C y / C n", harder.offer("F", "think harder", "C"));
        assertTrue(harder.open());
        assertEquals("think harder", harder.pendingOrder());
    }

    @Test
    void yesFromTheSamePlayerTakesTheOrder() {
        harder = make(30);
        harder.offer("F", "please think harder, then build", "C");

        assertEquals(ThinkHarder.Answer.YES, harder.answer("F", "y"));
        assertFalse(harder.open());
        assertEquals("please think harder, then build", harder.pendingOrder());
        assertEquals("please think harder, then build", harder.takeOrder());
        assertNull(harder.pendingOrder());
        assertNull(harder.takeOrder());
    }

    @Test
    void noFromTheSamePlayerIsNoAndTheOrderStaysReadable() {
        harder = make(30);
        harder.offer("F", "think harder about the wall", "C");

        assertEquals(ThinkHarder.Answer.NO, harder.answer("F", "No."));
        assertFalse(harder.open());
        assertEquals("think harder about the wall", harder.pendingOrder());
        assertEquals("think harder about the wall", harder.takeOrder());
    }

    @Test
    void answersAcceptShortWordsWithAnyCaseAndTrailingPunctuation() {
        harder = make(30);
        harder.offer("F", "think hard", "C");
        assertEquals(ThinkHarder.Answer.YES, harder.answer("F", "  YES!  "));

        harder.offer("F", "think hard", "C");
        assertEquals(ThinkHarder.Answer.NO, harder.answer("F", "n,"));

        harder.offer("F", "think hard", "C");
        assertEquals(ThinkHarder.Answer.YES, harder.answer("F", "y"));
    }

    @Test
    void anotherPlayersYesIsIgnoredAndTheOfferStays() {
        harder = make(30);
        harder.offer("F", "think harder", "C");

        assertEquals(ThinkHarder.Answer.NONE, harder.answer("Alex", "y"));
        assertTrue(harder.open());
        assertEquals(ThinkHarder.Answer.YES, harder.answer("F", "y"));
    }

    @Test
    void anythingElseIsNoneAndTheOfferStays() {
        harder = make(30);
        harder.offer("F", "think harder", "C");

        assertEquals(ThinkHarder.Answer.NONE, harder.answer("F", "go left"));
        assertEquals(ThinkHarder.Answer.NONE, harder.answer("F", "yes please"));
        assertEquals(ThinkHarder.Answer.NONE, harder.answer("F", null));
        assertTrue(harder.open());
        assertEquals("think harder", harder.pendingOrder());
    }

    @Test
    void noOpenOfferIsNone() {
        harder = make(30);
        assertEquals(ThinkHarder.Answer.NONE, harder.answer("F", "y"));
        assertFalse(harder.open());
    }

    @Test
    void timeoutReturnsTheOrderOnlyAfterTheWindow() {
        harder = make(30);
        harder.offer("F", "think harder", "C");

        now = 29_999L;
        assertNull(harder.expired());
        assertTrue(harder.open());

        now = 30_000L;
        assertEquals("think harder", harder.expired());
        assertFalse(harder.open());
        assertNull(harder.pendingOrder());
        assertNull(harder.expired());
    }

    @Test
    void answeredOfferDoesNotExpire() {
        harder = make(30);
        harder.offer("F", "think harder", "C");
        assertEquals(ThinkHarder.Answer.YES, harder.answer("F", "y"));

        now = 60_000L;
        assertNull(harder.expired());
        assertEquals("think harder", harder.takeOrder());
    }

    @Test
    void newOfferReplacesTheOldOneAndRestartsTheClock() {
        harder = make(30);
        harder.offer("F", "think harder", "C");

        now = 20_000L;
        harder.offer("Alex", "think hard on the roof", "C");
        assertEquals("think hard on the roof", harder.pendingOrder());

        assertEquals(ThinkHarder.Answer.NONE, harder.answer("F", "y"));
        assertEquals(ThinkHarder.Answer.YES, harder.answer("Alex", "y"));
        assertEquals("think hard on the roof", harder.takeOrder());

        harder.offer("F", "think harder", "C");
        harder.offer("Alex", "think hard on the roof", "C");
        now = 30_000L;
        assertNull(harder.expired());
        now = 50_000L;
        assertEquals("think hard on the roof", harder.expired());
    }
}
