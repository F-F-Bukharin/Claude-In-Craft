package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.act.Action;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** build's pure parts: what counts as already there, where to stand, the stop and failure rules, and the report. */
class BuildPlanTest {
    private static final BuildPlan.Standable FLAT = (x, y, z) -> y == 64; // a flat floor: stand anywhere at y 64

    @Test
    void aBlockThatIsAlreadyThereIsSkipped() {
        assertTrue(BuildPlan.filled("oak_planks", "oak_planks"));
        assertTrue(BuildPlan.filled("minecraft:oak_planks", "oak_planks"), "the prefix doesn't matter");
        assertTrue(BuildPlan.filled("oak_planks", "minecraft:oak_planks"));
        assertTrue(BuildPlan.filled("Stone", "stone"), "case-blind");
        assertFalse(BuildPlan.filled("oak_planks", "air"));
        assertFalse(BuildPlan.filled("oak_planks", "spruce_planks"));
        assertFalse(BuildPlan.filled("oak_planks", null));
    }

    @Test
    void theFirstItemNotCarriedIsMissingIgnoringBlocksAlreadyThere() {
        List<Action.BuildBlock> blocks = List.of(new Action.BuildBlock(0, 64, 0, "stone"),
                new Action.BuildBlock(1, 64, 0, "glass"), new Action.BuildBlock(2, 64, 0, "dirt"));
        assertEquals("glass", BuildPlan.firstMissing(blocks, Map.of("stone", 1), i -> false));
        assertEquals("dirt", BuildPlan.firstMissing(blocks, Map.of("stone", 1), i -> i == 1), "a glass block already there needs no glass");
        assertNull(BuildPlan.firstMissing(blocks, Map.of("stone", 1, "glass", 2, "dirt", 3), i -> false));
        assertNull(BuildPlan.firstMissing(blocks, Map.of(), i -> true), "nothing to place, nothing missing");
    }

    @Test
    void standingSpotsAreInReachClosestFirstAndNeverOnTheBlock() {
        Action.BuildBlock target = new Action.BuildBlock(0, 64, 0, "stone");
        List<BuildPlan.Spot> spots = BuildPlan.spots(target, Set.of(), FLAT, 10.5, 64, 0.5, 5);
        assertEquals(5, spots.size());
        for (BuildPlan.Spot s : spots) {
            assertFalse(s.x() == 0 && s.z() == 0, "never in the block's column: " + s);
            assertTrue(BuildPlan.inReach(s, target), "in reach: " + s);
            assertEquals(64, s.y());
        }
        assertEquals(new BuildPlan.Spot(3, 64, 0), spots.get(0), "the reachable spot nearest the body, east of the block");
    }

    @Test
    void standingSpotsAvoidBlocksStillToBePlaced() {
        Action.BuildBlock target = new Action.BuildBlock(0, 64, 0, "stone");
        Set<BuildPlan.Spot> pending = Set.of(new BuildPlan.Spot(3, 64, 0), new BuildPlan.Spot(2, 65, 0));
        List<BuildPlan.Spot> spots = BuildPlan.spots(target, pending, FLAT, 10.5, 64, 0.5, 20);
        assertFalse(spots.contains(new BuildPlan.Spot(3, 64, 0)), "feet in a pending block");
        assertFalse(spots.contains(new BuildPlan.Spot(2, 64, 0)), "head in a pending block");
        assertFalse(spots.isEmpty());
    }

    @Test
    void noStandableGroundMeansNoSpot() {
        Action.BuildBlock target = new Action.BuildBlock(0, 64, 0, "stone");
        assertTrue(BuildPlan.spots(target, Set.of(), (x, y, z) -> false, 0.5, 64, 0.5, 5).isEmpty());
    }

    @Test
    void reachIsMeasuredFromTheEyesWithAMargin() {
        Action.BuildBlock target = new Action.BuildBlock(0, 64, 0, "stone");
        assertTrue(BuildPlan.inReach(new BuildPlan.Spot(3, 64, 0), target));
        assertFalse(BuildPlan.inReach(new BuildPlan.Spot(5, 64, 0), target));
        assertFalse(BuildPlan.inReach(new BuildPlan.Spot(0, 60, 3), target), "too far below");
    }

    @Test
    void dangerIsAHostileCloseByOrLowHealth() {
        assertNull(BuildPlan.danger(Double.POSITIVE_INFINITY, 1.0));
        assertEquals("danger", BuildPlan.danger(GatherRule.DANGER_RANGE, 1.0));
        assertNull(BuildPlan.danger(GatherRule.DANGER_RANGE + 0.1, 1.0));
        assertEquals("danger", BuildPlan.danger(Double.POSITIVE_INFINITY, GatherRule.LOW_HEALTH - 0.01));
        assertNull(BuildPlan.danger(Double.POSITIVE_INFINITY, GatherRule.LOW_HEALTH));
    }

    @Test
    void aFailureBeforeAnythingIsPlacedIsTheBareCode() {
        assertEquals("no_support", BuildPlan.failure(0, 0, "no_support"));
        assertEquals("blocked", BuildPlan.failure(3, 0, "blocked"), "only skipped so far: nothing changed");
        assertEquals("no_path", BuildPlan.failure(0, 0, "no_path"));
        assertEquals("missing_item:glass", BuildPlan.failure(0, 0, "missing_item:glass"));
    }

    @Test
    void aFailureAfterPlacingSaysWhereItStopped() {
        assertEquals("stopped_at:5:no_support", BuildPlan.failure(5, 4, "no_support"));
        assertEquals("stopped_at:7:missing_item:glass", BuildPlan.failure(7, 7, "missing_item:glass"));
    }

    @Test
    void dangerDeathHazardsAndTimeoutAlwaysSayWhereItStopped() {
        assertEquals("stopped_at:0:danger", BuildPlan.failure(0, 0, "danger"));
        assertEquals("stopped_at:2:died", BuildPlan.failure(2, 0, "died"));
        assertEquals("stopped_at:1:hazard_lava", BuildPlan.failure(1, 0, "hazard_lava"));
        assertEquals("stopped_at:9:timeout", BuildPlan.failure(9, 0, "timeout"));
    }

    @Test
    void placingFailuresMapToTheToolsCodes() {
        assertEquals("blocked", BuildPlan.placeFailure("occupied", "stone"));
        assertEquals("blocked", BuildPlan.placeFailure("not_placed", "stone"));
        assertEquals("blocked", BuildPlan.placeFailure("blocked", "stone"));
        assertEquals("no_path", BuildPlan.placeFailure("out_of_reach", "stone"));
        assertEquals("missing_item:stone", BuildPlan.placeFailure("missing_item", "stone"));
        assertEquals("not_a_block:stick", BuildPlan.placeFailure("not_a_block", "stick"));
        assertEquals("no_support", BuildPlan.placeFailure("no_support", "stone"));
    }

    @Test
    void theReportSaysPlacedAndSkipped() {
        assertEquals("placed 3, skipped 1 of 4", BuildPlan.progress(3, 1, 4));
        assertEquals("build: placed 3, skipped 1 of 4", BuildPlan.summary("build", 3, 1, 4));
        assertEquals("placed 1, skipped 0 of 1", BuildPlan.summary(null, 1, 0, 1));
    }

    @Test
    void theTimeLimitGrowsWithTheListAndTheBackstopComesAfterIt() {
        assertTrue(BuildPlan.limitTicks(64) > BuildPlan.limitTicks(1));
        assertTrue(BuildPlan.timeoutTicks(71) > BuildPlan.limitTicks(71), "its own limit reports first");
        assertTrue(BuildPlan.limitTicks(1) >= 20 * 20, "at least 20 s for one block");
    }

    @Test
    void aBlockMoreThan64BlocksAwayIsTooFar() {
        assertFalse(BuildPlan.tooFar(64, 0, 0.5, 0.5), "64 flat from the body is still near");
        assertTrue(BuildPlan.tooFar(65, 0, 0.5, 0.5));
        assertTrue(BuildPlan.tooFar(46, 46, 0.5, 0.5), "flat distance, not per axis");
        assertFalse(BuildPlan.tooFar(-40, -40, 0.5, 0.5));
        List<Action.BuildBlock> list = List.of(new Action.BuildBlock(1, 64, 1, "stone"),
                new Action.BuildBlock(1, 300, 1, "stone"), new Action.BuildBlock(100, 64, 1, "stone"));
        assertEquals(2, BuildPlan.firstTooFar(list, 0, 0), "height doesn't count, only the ground distance");
        assertEquals(-1, BuildPlan.firstTooFar(list.subList(0, 2), 0, 0));
    }
}
