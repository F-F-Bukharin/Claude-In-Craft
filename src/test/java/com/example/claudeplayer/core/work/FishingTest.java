package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** fish's pure part: the cast, wait, bite, reel, collect and recast timing, when it stops, and what it says. */
class FishingTest {
    /** One scripted tick with the hook out, in water, and no bite. */
    private static Fishing.Step quiet(Fishing f) {
        return f.tick(true, true, false);
    }

    /** Ticks with nothing happening until the step is not NONE (or {@code max} ticks pass); returns how many ticks it took. */
    private static int until(Fishing f, Fishing.Step want, boolean biting, int max) {
        for (int i = 1; i <= max; i++) {
            if (f.tick(true, true, biting) == want) return i;
        }
        return -1;
    }

    private static Fishing cast(Fishing f) {
        assertEquals(Fishing.Step.CAST, f.tick(false, false, false), "the first tick casts");
        return f;
    }

    private static Map<String, Integer> items(Object... pairs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    @Test
    void castsOnTheFirstTickThenWaits() {
        Fishing f = new Fishing(3, 60);
        assertEquals(Fishing.Step.CAST, f.tick(false, false, false));
        for (int i = 0; i < 50; i++) assertEquals(Fishing.Step.NONE, quiet(f), "waiting for a bite, tick " + i);
        assertNull(f.stop());
        assertEquals(1, f.casts());
    }

    @Test
    void aBiteDuringSettlingIsIgnored() {
        Fishing f = cast(new Fishing(3, 60));
        for (int i = 0; i < Fishing.SETTLE_TICKS; i++) assertEquals(Fishing.Step.NONE, f.tick(true, true, true), "settling, tick " + i);
        // the bite is still there once the hook has settled: it is noticed now and the reel follows after the reaction delay
        int ticks = until(f, Fishing.Step.REEL, true, 100);
        assertEquals(Fishing.REEL_DELAY_TICKS + 1, ticks, "noticed on the first watching tick, reeled REEL_DELAY_TICKS later");
    }

    @Test
    void biteThenReelThenCollectThenRecast() {
        Fishing f = cast(new Fishing(3, 60));
        for (int i = 0; i < 200; i++) quiet(f); // a long quiet wait
        assertEquals(Fishing.Step.NONE, f.tick(true, true, true), "bite seen");
        for (int i = 1; i < Fishing.REEL_DELAY_TICKS; i++) assertEquals(Fishing.Step.NONE, f.tick(true, true, false), "reacting " + i);
        assertEquals(Fishing.Step.REEL, f.tick(true, true, false));
        // after the reel the loot flies in; nothing happens until the collect window is over
        for (int i = 1; i < Fishing.COLLECT_TICKS; i++) assertEquals(Fishing.Step.NONE, f.tick(false, false, false), "collecting " + i);
        assertEquals(Fishing.Step.HARVEST, f.tick(false, false, false));
        f.landed(items("cod", 1));
        assertEquals(1, f.catches());
        assertNull(f.stop());
        // then the recast delay, then a second cast
        for (int i = 1; i <= Fishing.RECAST_DELAY_TICKS; i++) assertEquals(Fishing.Step.NONE, f.tick(false, false, false), "recast delay " + i);
        assertEquals(Fishing.Step.CAST, f.tick(false, false, false));
        assertEquals(2, f.casts());
    }

    @Test
    void noBiteGivesUpTheCastAndRecastsWithoutCollecting() {
        Fishing f = cast(new Fishing(3, 120));
        int ticks = until(f, Fishing.Step.REEL, false, Fishing.CAST_LIMIT_TICKS + 10);
        assertEquals(Fishing.CAST_LIMIT_TICKS, ticks, "a quiet cast is reeled in after CAST_LIMIT_TICKS");
        for (int i = 1; i <= Fishing.RECAST_DELAY_TICKS; i++) assertEquals(Fishing.Step.NONE, f.tick(false, false, false), "recast delay " + i);
        assertEquals(Fishing.Step.CAST, f.tick(false, false, false), "straight to the next cast: nothing to collect after a miss");
        assertEquals(2, f.casts());
        assertEquals(0, f.catches());
    }

    @Test
    void theGiveUpOutlastsTheLongestNaturalWait() {
        // vanilla: 100 to 600 ticks until the fish is lured, then 20 to 80 until it bites (no Lure enchantment)
        assertTrue(Fishing.CAST_LIMIT_TICKS >= 680, "must not recast before a fish can bite");
        assertTrue(Fishing.REEL_DELAY_TICKS < 20, "the bite lasts 20 to 40 ticks: reel inside it");
    }

    @Test
    void aHookThatStaysOutOfWaterIsReeledIn() {
        Fishing f = cast(new Fishing(3, 60));
        for (int i = 0; i < Fishing.SETTLE_TICKS; i++) f.tick(true, false, false);
        for (int i = 1; i < Fishing.DRY_LIMIT_TICKS; i++) assertEquals(Fishing.Step.NONE, f.tick(true, false, false), "dry " + i);
        assertEquals(Fishing.Step.REEL, f.tick(true, false, false));
    }

    @Test
    void aHookThatTouchesWaterAgainStartsCountingDryAgain() {
        Fishing f = cast(new Fishing(3, 60));
        for (int i = 0; i < Fishing.SETTLE_TICKS; i++) f.tick(true, false, false);
        for (int i = 1; i < Fishing.DRY_LIMIT_TICKS; i++) f.tick(true, false, false);
        assertEquals(Fishing.Step.NONE, f.tick(true, true, false), "back in the water");
        for (int i = 1; i < Fishing.DRY_LIMIT_TICKS; i++) assertEquals(Fishing.Step.NONE, f.tick(true, false, false), "dry again " + i);
    }

    @Test
    void aLostHookIsRecastAndRepeatedLossesStop() {
        Fishing f = cast(new Fishing(3, 120));
        for (int round = 1; round < Fishing.LOST_LIMIT; round++) {
            assertEquals(Fishing.Step.NONE, f.tick(false, false, false), "the hook is gone");
            for (int i = 1; i <= Fishing.RECAST_DELAY_TICKS; i++) f.tick(false, false, false);
            assertEquals(Fishing.Step.CAST, f.tick(false, false, false), "recast " + round);
            assertNull(f.stop());
        }
        f.tick(false, false, false);
        assertEquals("cannot_cast", f.stop());
    }

    @Test
    void aGoodCastResetsTheLostCounter() {
        Fishing f = cast(new Fishing(3, 120));
        for (int round = 0; round < Fishing.LOST_LIMIT * 2; round++) {
            assertEquals(Fishing.Step.NONE, f.tick(false, false, false), "the hook is gone, round " + round);
            for (int i = 0; i < Fishing.RECAST_DELAY_TICKS; i++) f.tick(false, false, false);
            assertEquals(Fishing.Step.CAST, f.tick(false, false, false), "recast, round " + round);
            quiet(f); // this one holds: the count starts again
            assertNull(f.stop(), "round " + round);
        }
    }

    @Test
    void stopsOnCount() {
        Fishing f = new Fishing(2, 120);
        f.landed(items("cod", 1));
        assertNull(f.stop());
        f.landed(items("salmon", 1));
        assertEquals("done", f.stop());
        assertEquals(Fishing.Step.NONE, f.tick(true, true, true), "nothing more once done");
        assertEquals(Fishing.Step.NONE, f.tick(false, false, false));
    }

    @Test
    void aMultiItemHaulCountsEveryItem() {
        Fishing f = new Fishing(3, 120);
        f.landed(items("cod", 2, "string", 1));
        assertEquals(3, f.catches());
        assertEquals("done", f.stop());
    }

    @Test
    void stopsOnTimeWhileWaiting() {
        Fishing f = cast(new Fishing(8, 10)); // 200 ticks, the cast gives up at 700
        int ticks = 1;
        while (f.stop() == null && ticks < 1000) {
            quiet(f);
            ticks++;
        }
        assertEquals("timeout", f.stop());
        assertEquals(10 * 20, ticks, "ends on the last tick of the allowed time");
    }

    @Test
    void aBiteAtTheLastMomentStillGetsReeledAndCollected() {
        Fishing f = cast(new Fishing(8, 10));
        for (int i = 1; i < 195; i++) quiet(f);
        assertEquals(Fishing.Step.NONE, f.tick(true, true, true), "a bite right at the end of the time");
        assertNull(f.stop(), "not stopped while the bite is being played out");
        Fishing.Step step = Fishing.Step.NONE;
        for (int i = 0; i < 20 && step != Fishing.Step.REEL; i++) step = f.tick(true, true, false);
        assertEquals(Fishing.Step.REEL, step, "reeled in even though the time ran out meanwhile");
        for (int i = 0; i < Fishing.COLLECT_TICKS && step != Fishing.Step.HARVEST; i++) step = f.tick(false, false, false);
        assertEquals(Fishing.Step.HARVEST, step);
        f.landed(items("salmon", 1));
        assertEquals(1, f.catches());
        f.tick(false, false, false);
        assertEquals("timeout", f.stop());
    }

    @Test
    void abortKeepsTheFirstReason() {
        Fishing f = cast(new Fishing(3, 60));
        f.abort("danger");
        f.abort("timeout");
        assertEquals("danger", f.stop());
        assertEquals(Fishing.Step.NONE, quiet(f));
    }

    @Test
    void summaryListsTheCatchById() {
        Fishing f = new Fishing(5, 60);
        castTimes(f, 1);
        f.landed(items("cod", 1));
        f.landed(items("salmon", 1));
        f.landed(items("cod", 1));
        assertEquals("caught 3: cod x2, salmon x1 (1 cast); stopped: timeout", withStop(f, "timeout"));
    }

    @Test
    void summaryWhenDoneHasNoStopSuffix() {
        Fishing f = new Fishing(3, 60);
        castTimes(f, 5);
        f.landed(items("cod", 2, "salmon", 1));
        assertEquals("done", f.stop());
        assertEquals("caught 3: cod x2, salmon x1 (5 casts)", f.summary());
    }

    @Test
    void summaryWhenNothingBit() {
        Fishing f = new Fishing(3, 60);
        castTimes(f, 4);
        f.abort("timeout");
        assertEquals("caught nothing (4 casts); stopped: timeout", f.summary());
    }

    @Test
    void summarySaysWhyItStopped() {
        Fishing f = new Fishing(3, 60);
        castTimes(f, 1);
        f.abort("danger");
        assertEquals("caught nothing (1 cast); stopped: danger", f.summary());
    }

    @Test
    void theTimeLimitIsSecondsInTicks() {
        assertEquals(10 * 20, new Fishing(1, 10).limitTicks());
        assertEquals(120 * 20, new Fishing(1, 120).limitTicks());
    }

    @Test
    void aDeclinedCastIsNotCountedAndIsTriedAgainAfterTheDelay() {
        Fishing f = new Fishing(3, 60);
        assertEquals(Fishing.Step.CAST, f.tick(false, false, false));
        f.declined(); // somebody stood in the way
        assertEquals(0, f.casts());
        for (int i = 1; i <= Fishing.RECAST_DELAY_TICKS; i++) assertEquals(Fishing.Step.NONE, f.tick(false, false, false), "delay " + i);
        assertEquals(Fishing.Step.CAST, f.tick(false, false, false));
        assertEquals(1, f.casts());
        assertNull(f.stop(), "a declined cast is not a lost hook");
    }

    @Test
    void collectingIsTrueOnlyAfterAReelThatFollowedABite() {
        Fishing f = cast(new Fishing(3, 120));
        assertFalse(f.collecting());
        for (int i = 0; i <= Fishing.SETTLE_TICKS; i++) f.tick(true, true, true); // the bite is noticed
        assertTrue(until(f, Fishing.Step.REEL, false, 20) > 0, "reeled in");
        assertTrue(f.collecting(), "waiting for the loot");
        Fishing miss = cast(new Fishing(3, 120));
        until(miss, Fishing.Step.REEL, false, Fishing.CAST_LIMIT_TICKS + 10);
        assertFalse(miss.collecting(), "a given-up cast has nothing to collect");
    }

    @Test
    void theCastPitchLandsWhereAimedInItsOwnPhysics() {
        float near = Fishing.castPitch(3, 1.6);
        float mid = Fishing.castPitch(5, 1.6);
        float far = Fishing.castPitch(8, 1.6);
        for (float p : new float[] {near, mid, far}) assertTrue(p > 0 && p < 80, "a downward pitch: " + p);
        assertTrue(near > mid && mid > far, "closer water needs a steeper cast: " + near + " " + mid + " " + far);
        assertEquals(Fishing.castLanding(5, 1.6, mid), 5.0, 1.0, "lands within a block of the aim");
    }

    @Test
    void waterOutOfRangeHasNoPitch() {
        assertTrue(Float.isNaN(Fishing.castPitch(40, 1.6)), "a cast does not carry that far");
        assertTrue(Float.isNaN(Fishing.castPitch(3, -5)), "water above the eyes");
    }

    private static String withStop(Fishing f, String reason) {
        f.abort(reason);
        return f.summary();
    }

    /** Runs {@code n} casts that each land out of water and are reeled in as misses. */
    private static void castTimes(Fishing f, int n) {
        for (int c = 0; c < n; c++) {
            Fishing.Step s = Fishing.Step.NONE;
            for (int i = 0; i < 5000 && s != Fishing.Step.CAST; i++) s = f.tick(false, false, false);
            assertEquals(Fishing.Step.CAST, s);
            for (int i = 0; i < 5000; i++) {
                if (f.tick(true, false, false) == Fishing.Step.REEL) break;
            }
        }
    }
}
