package com.example.claudeplayer.core.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.safety.HuntRules.Facts;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HuntRulesTest {
    private static Facts plain(String type) {
        return new Facts(type, false, false, false, false, false, false, false);
    }

    @Test
    void anAdultPlainAnimalOfTheAskedKindIsAllowed() {
        assertNull(HuntRules.refusal("cow", plain("cow")));
        assertNull(HuntRules.refusal("cow", plain("minecraft:cow")));
        assertTrue(HuntRules.allowed("pig", plain("pig")));
        assertTrue(HuntRules.allowed("sheep", plain("minecraft:sheep")));
        assertTrue(HuntRules.allowed("chicken", plain("chicken")));
        assertTrue(HuntRules.allowed("rabbit", plain("rabbit")));
    }

    @Test
    void theAskedKindIsMatchedCaseInsensitively() {
        assertTrue(HuntRules.allowed("Cow", plain("cow")));
    }

    @Test
    void aPlayerIsNeverAllowed() {
        assertEquals("player", HuntRules.refusal("cow", new Facts("player", true, false, false, false, false, false, false)));
        // even if some facts claim it is a cow
        assertEquals("player", HuntRules.refusal("cow", new Facts("cow", true, false, false, false, false, false, false)));
    }

    @Test
    void aVillagerIsNeverAllowed() {
        assertEquals("villager", HuntRules.refusal("cow", new Facts("villager", false, true, false, false, false, false, false)));
    }

    @Test
    void aWanderingTraderIsNeverAllowed() {
        assertEquals("villager", HuntRules.refusal("cow", new Facts("wandering_trader", false, true, false, false, false, false, false)));
    }

    @Test
    void anIronGolemIsNeverAllowed() {
        assertEquals("iron_golem", HuntRules.refusal("cow", new Facts("iron_golem", false, false, true, false, false, false, false)));
    }

    @Test
    void aNamedAnimalIsNeverAllowed() {
        assertEquals("named", HuntRules.refusal("cow", new Facts("cow", false, false, false, true, false, false, false)));
    }

    @Test
    void aTamedAnimalIsNeverAllowed() {
        assertEquals("tamed", HuntRules.refusal("cow", new Facts("cow", false, false, false, false, true, false, false)));
    }

    @Test
    void aLeashedAnimalIsNeverAllowed() {
        assertEquals("leashed", HuntRules.refusal("pig", new Facts("pig", false, false, false, false, false, true, false)));
    }

    @Test
    void aBabyAnimalIsNeverAllowed() {
        assertEquals("baby", HuntRules.refusal("sheep", new Facts("sheep", false, false, false, false, false, false, true)));
    }

    @Test
    void anotherKindIsNotAllowed() {
        assertEquals("wrong_kind", HuntRules.refusal("cow", plain("pig")));
        assertEquals("wrong_kind", HuntRules.refusal("chicken", plain("minecraft:rabbit")));
    }

    @Test
    void aTypeOutsideTheFiveIsNotHuntableEvenWhenAsked() {
        assertEquals("not_huntable", HuntRules.refusal("cow", plain("wolf")));
        assertEquals("not_huntable", HuntRules.refusal("cow", plain("horse")));
        assertEquals("not_huntable", HuntRules.refusal("cow", plain("mooshroom"))); // a cow-like type, but not a cow
        assertEquals("not_huntable", HuntRules.refusal("wolf", plain("wolf")));
        assertEquals("not_huntable", HuntRules.refusal("horse", plain("minecraft:horse")));
        assertFalse(HuntRules.allowed("zombie", plain("zombie")));
    }

    @Test
    void protectedFactsWinOverTheKind() {
        // a named, tamed baby cow is refused for its first protective reason, never allowed
        assertEquals("named", HuntRules.refusal("cow", new Facts("cow", false, false, false, true, true, true, true)));
        assertFalse(HuntRules.allowed("cow", new Facts("cow", false, false, false, false, true, true, true)));
    }

    @Test
    void dangerHealthAndTimeStopTheHunt() {
        assertNull(HuntRules.stop(100, Double.POSITIVE_INFINITY, 1.0));
        assertEquals("danger", HuntRules.stop(100, HuntRules.DANGER_RANGE, 1.0));
        assertEquals("danger", HuntRules.stop(100, 3.0, 1.0));
        assertNull(HuntRules.stop(100, HuntRules.DANGER_RANGE + 0.1, 1.0));
        assertEquals("danger", HuntRules.stop(100, Double.POSITIVE_INFINITY, HuntRules.LOW_HEALTH - 0.01));
        assertNull(HuntRules.stop(100, Double.POSITIVE_INFINITY, HuntRules.LOW_HEALTH));
        assertEquals("timeout", HuntRules.stop(HuntRules.LIMIT_TICKS, Double.POSITIVE_INFINITY, 1.0));
        assertEquals("danger", HuntRules.stop(HuntRules.LIMIT_TICKS, 2.0, 1.0)); // danger outranks the clock
    }

    @Test
    void theHuntIsDoneWhenTheKillsReachTheCount() {
        assertFalse(HuntRules.done(0, 1));
        assertFalse(HuntRules.done(2, 3));
        assertTrue(HuntRules.done(3, 3));
        assertTrue(HuntRules.done(4, 3));
    }

    @Test
    void theBackstopSitsAboveTheOwnLimit() {
        assertTrue(HuntRules.BACKSTOP_TICKS > HuntRules.LIMIT_TICKS);
        assertEquals(24, HuntRules.SEARCH_RANGE);
    }

    @Test
    void theSummaryListsKillsAndDrops() {
        Map<String, Integer> drops = new LinkedHashMap<>();
        drops.put("beef", 3);
        drops.put("leather", 1);
        assertEquals("killed 2 cow; drops: 3 beef, 1 leather", HuntRules.summary("cow", 2, drops, "done"));
    }

    @Test
    void theSummaryNamesNoDropsAndTheStopReason() {
        assertEquals("killed 1 pig; drops: none", HuntRules.summary("pig", 1, Map.of(), null));
        assertEquals("killed 1 pig; drops: none; stopped: danger", HuntRules.summary("pig", 1, Map.of(), "danger"));
        assertEquals("killed 0 sheep; drops: none; stopped: timeout", HuntRules.summary("sheep", 0, Map.of(), "timeout"));
        assertEquals("killed 2 sheep; drops: 2 mutton; stopped: no_more_animals",
                HuntRules.summary("sheep", 2, Map.of("mutton", 2), "no_more_animals"));
    }
}
