package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.work.DigPlan.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** dig's step planner: which cells each direction clears, the staircase, the y floor and the walls. */
class DigPlanTest {
    private static final Pos FEET = new Pos(10, 64, 5);

    @Test
    void horizontalStepsOpenFeetAndHeadAheadNeverTheFloor() {
        assertEquals(new DigPlan.Step(List.of(new Pos(10, 64, 4), new Pos(10, 65, 4)), new Pos(10, 64, 4)), DigPlan.step("north", "east", FEET));
        assertEquals(new DigPlan.Step(List.of(new Pos(10, 64, 6), new Pos(10, 65, 6)), new Pos(10, 64, 6)), DigPlan.step("south", "east", FEET));
        assertEquals(new DigPlan.Step(List.of(new Pos(11, 64, 5), new Pos(11, 65, 5)), new Pos(11, 64, 5)), DigPlan.step("east", "north", FEET));
        assertEquals(new DigPlan.Step(List.of(new Pos(9, 64, 5), new Pos(9, 65, 5)), new Pos(9, 64, 5)), DigPlan.step("west", "north", FEET));
        for (String d : new String[] {"north", "south", "east", "west"}) {
            for (Pos cell : DigPlan.step(d, "north", FEET).clear()) assertTrue(cell.y() >= FEET.y(), d + " digs nothing below the feet");
        }
    }

    @Test
    void downIsAStaircaseInTheFacingDirection() {
        DigPlan.Step s = DigPlan.step("down", "east", FEET);
        assertEquals(List.of(new Pos(11, 65, 5), new Pos(11, 64, 5), new Pos(11, 63, 5)), s.clear(), "head room first, then the two cells one lower");
        assertEquals(new Pos(11, 63, 5), s.feet());
        assertEquals(new Pos(10, 64, 3), DigPlan.step("down", "north", new Pos(10, 65, 4)).feet());
        Pos feet = FEET;
        for (int i = 0; i < 3; i++) feet = DigPlan.step("down", "south", feet).feet();
        assertEquals(new Pos(10, 61, 8), feet, "one block lower per step");
    }

    @Test
    void downStopsAtTheFloor() {
        assertNotNull(DigPlan.step("down", "east", new Pos(0, -59, 0)), "-59 may step to -60");
        assertNull(DigPlan.step("down", "east", new Pos(0, -60, 0)));
        assertNotNull(DigPlan.step("east", "east", new Pos(0, -60, 0)), "a level tunnel at the floor is fine");
    }

    @Test
    void wallsSurroundTheStepButLeaveTheTunnelOut() {
        DigPlan.Step s = DigPlan.step("east", "north", FEET);
        List<Pos> walls = DigPlan.walls(s, FEET);
        assertEquals(8, walls.size(), "two cells, six faces each, minus the shared face and the way back");
        assertTrue(walls.contains(new Pos(11, 63, 5)), "the floor");
        assertTrue(walls.contains(new Pos(11, 66, 5)), "the ceiling");
        assertTrue(walls.contains(new Pos(12, 64, 5)), "the face ahead");
        assertTrue(walls.contains(new Pos(11, 65, 4)));
        assertFalse(walls.contains(FEET));
        assertFalse(walls.contains(new Pos(11, 65, 5)));
    }

    @Test
    void oresAndSummary() {
        assertTrue(DigPlan.isOre("iron_ore"));
        assertTrue(DigPlan.isOre("deepslate_diamond_ore"));
        assertTrue(DigPlan.isOre("ancient_debris"));
        assertFalse(DigPlan.isOre("raw_iron_block"));
        assertEquals("dug 12/15 steps down to y=52 (36 blocks broken); saw iron_ore at 10,41,5; stopped: y_floor",
                DigPlan.summary(12, 15, "down", new Pos(22, 52, 5), 36, List.of("iron_ore at 10,41,5"), "y_floor"));
        assertEquals("dug 1/1 steps north to 10,64,4 (1 block broken)", DigPlan.summary(1, 1, "north", new Pos(10, 64, 4), 1, List.of(), null));
        List<String> many = List.of("a_ore at 1", "b_ore at 2", "c_ore at 3", "d_ore at 4", "e_ore at 5", "f_ore at 6");
        assertFalse(DigPlan.summary(1, 1, "west", FEET, 2, many, null).contains("f_ore"), "at most five");
    }
}
