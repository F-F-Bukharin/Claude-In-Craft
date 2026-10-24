package com.example.claudeplayer.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.act.Action;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** build_house's pure parts: the block list and its order, the door and bed cells, the site check, the material pick. */
class HousePlanTest {
    private static final String[] FACINGS = {"north", "south", "east", "west"};
    private static final int X = 10, Y = 64, Z = -20;

    private static HousePlan.Cell cell(Action.BuildBlock b) {
        return new HousePlan.Cell(b.x(), b.y(), b.z());
    }

    private static boolean inside(HousePlan.Cell c) {
        return c.x() >= X + 1 && c.x() <= X + 3 && c.z() >= Z + 1 && c.z() <= Z + 3;
    }

    /** A flat, empty site: solid at y-1 and below, air above; {@code world} overrides block ids. */
    private static HousePlan.Site site(Map<HousePlan.Cell, String> world) {
        return new HousePlan.Site() {
            @Override
            public boolean solid(int x, int y, int z) {
                String id = world.get(new HousePlan.Cell(x, y, z));
                return id != null ? !id.equals("air") : y < Y;
            }

            @Override
            public boolean clear(int x, int y, int z) {
                String id = block(x, y, z);
                return id.equals("air") || id.equals("short_grass");
            }

            @Override
            public String block(int x, int y, int z) {
                String id = world.get(new HousePlan.Cell(x, y, z));
                return id != null ? id : y < Y ? "dirt" : "air";
            }
        };
    }

    @Test
    void seventyOneBlocksOfTheMaterialWithNoRepeatsForEveryFacing() {
        assertEquals(71, HousePlan.BLOCKS);
        for (String f : FACINGS) {
            List<Action.BuildBlock> blocks = HousePlan.blocks(X, Y, Z, f, "cobblestone");
            assertEquals(71, blocks.size(), f);
            Set<HousePlan.Cell> cells = new HashSet<>();
            for (Action.BuildBlock b : blocks) {
                assertTrue(cells.add(cell(b)), "repeat " + b);
                assertEquals("cobblestone", b.item());
                assertTrue(b.x() >= X && b.x() <= X + 4 && b.z() >= Z && b.z() <= Z + 4 && b.y() >= Y && b.y() <= Y + 3, "in the footprint " + b);
                if (b.y() < Y + 3) assertFalse(inside(cell(b)), "the 3x3 inside stays empty " + b);
            }
        }
    }

    @Test
    void theDoorwayIsTheMiddleOfTheFacingWallTwoHighAndLeftEmpty() {
        Map<String, HousePlan.Cell> doors = Map.of("south", new HousePlan.Cell(X + 2, Y, Z + 4), "north", new HousePlan.Cell(X + 2, Y, Z),
                "east", new HousePlan.Cell(X + 4, Y, Z + 2), "west", new HousePlan.Cell(X, Y, Z + 2));
        for (String f : FACINGS) {
            HousePlan.Cell door = HousePlan.door(X, Y, Z, f);
            assertEquals(doors.get(f), door, f);
            Set<HousePlan.Cell> cells = new HashSet<>();
            HousePlan.blocks(X, Y, Z, f, "stone").forEach(b -> cells.add(cell(b)));
            assertFalse(cells.contains(door), f);
            assertFalse(cells.contains(new HousePlan.Cell(door.x(), Y + 1, door.z())), f);
            assertTrue(cells.contains(new HousePlan.Cell(door.x(), Y + 2, door.z())), "a lintel over the door " + f);
        }
    }

    @Test
    void bottomLayerFirstThenUpAndTheRoofLast() {
        List<Action.BuildBlock> blocks = HousePlan.blocks(X, Y, Z, "south", "stone");
        for (int i = 1; i < blocks.size(); i++) assertTrue(blocks.get(i).y() >= blocks.get(i - 1).y(), "order at " + i);
        assertEquals(46, blocks.stream().filter(b -> b.y() < Y + 3).count(), "three wall rings less the doorway");
        assertEquals(25, blocks.stream().filter(b -> b.y() == Y + 3).count(), "a flat roof");
    }

    @Test
    void everyBlockHasSomethingPlacedOrSolidToLeanOn() {
        for (String f : FACINGS) {
            Set<HousePlan.Cell> placed = new HashSet<>();
            for (Action.BuildBlock b : HousePlan.blocks(X, Y, Z, f, "stone")) {
                HousePlan.Cell c = cell(b);
                boolean support = c.y() == Y || placed.contains(new HousePlan.Cell(c.x(), c.y() - 1, c.z()))
                        || placed.contains(new HousePlan.Cell(c.x() + 1, c.y(), c.z())) || placed.contains(new HousePlan.Cell(c.x() - 1, c.y(), c.z()))
                        || placed.contains(new HousePlan.Cell(c.x(), c.y(), c.z() + 1)) || placed.contains(new HousePlan.Cell(c.x(), c.y(), c.z() - 1));
                assertTrue(support, f + " no support for " + b);
                placed.add(c);
            }
        }
    }

    @Test
    void theRoofGoesOuterRingThenInnerRingThenTheCentre() {
        List<Action.BuildBlock> roof = HousePlan.blocks(X, Y, Z, "south", "stone").stream().filter(b -> b.y() == Y + 3).toList();
        for (int i = 0; i < 16; i++) assertFalse(inside(cell(roof.get(i))), "outer ring " + roof.get(i));
        for (int i = 16; i < 24; i++) {
            assertTrue(inside(cell(roof.get(i))), "inner ring " + roof.get(i));
            assertNotEquals(new HousePlan.Cell(X + 2, Y + 3, Z + 2), cell(roof.get(i)));
        }
        assertEquals(new HousePlan.Cell(X + 2, Y + 3, Z + 2), cell(roof.get(24)));
    }

    @Test
    void theDoorFacesOutAndIsPlacedFromJustInside() {
        for (String f : FACINGS) {
            assertEquals(f, HousePlan.doorFacing(f));
            HousePlan.Cell stand = HousePlan.doorStand(X, Y, Z, f);
            HousePlan.Cell door = HousePlan.door(X, Y, Z, f);
            assertTrue(inside(stand), f);
            int[] d = HousePlan.step(f);
            assertEquals(door, new HousePlan.Cell(stand.x() + d[0], Y, stand.z() + d[1]), "one step out from the stand is the door " + f);
        }
    }

    @Test
    void theBedLiesInsideAgainstTheBackWallOffTheWayIn() {
        for (String f : FACINGS) {
            HousePlan.Cell foot = HousePlan.bedFoot(X, Y, Z, f);
            HousePlan.Cell head = HousePlan.bedHead(X, Y, Z, f);
            HousePlan.Cell stand = HousePlan.bedStand(X, Y, Z, f);
            assertTrue(inside(foot) && inside(head) && inside(stand), f);
            int[] back = HousePlan.step(HousePlan.bedFacing(f));
            assertEquals(HousePlan.opposite(f), HousePlan.bedFacing(f));
            assertEquals(head, new HousePlan.Cell(foot.x() + back[0], Y, foot.z() + back[1]), "the head toward the back " + f);
            assertEquals(foot, new HousePlan.Cell(stand.x() + back[0], Y, stand.z() + back[1]), "the stand is in line, toward the door " + f);
            HousePlan.Cell wall = new HousePlan.Cell(head.x() + back[0], Y, head.z() + back[1]);
            assertFalse(inside(wall), "the head touches the back wall " + f);
            HousePlan.Cell doorStand = HousePlan.doorStand(X, Y, Z, f);
            assertFalse(foot.equals(doorStand) || head.equals(doorStand) || stand.equals(HousePlan.door(X, Y, Z, f)), f);
            assertFalse(foot.x() == X + 2 && foot.z() == Z + 2, "not in the centre, in the way in " + f);
        }
    }

    @Test
    void directionsAndOpposites() {
        assertEquals("north", HousePlan.opposite("south"));
        assertEquals("west", HousePlan.opposite("east"));
        assertEquals(0, HousePlan.step("north")[0]);
        assertEquals(-1, HousePlan.step("north")[1]);
        assertEquals(1, HousePlan.step("east")[0]);
    }

    @Test
    void aFlatEmptySiteIsFine() {
        assertNull(HousePlan.checkSite(X, Y, Z, "south", "stone", site(Map.of())));
    }

    @Test
    void aHoleUnderTheFootprintIsUnevenGround() {
        Map<HousePlan.Cell, String> world = new HashMap<>();
        world.put(new HousePlan.Cell(X + 4, Y - 1, Z + 4), "air");
        HousePlan.SiteProblem p = HousePlan.checkSite(X, Y, Z, "south", "stone", site(world));
        assertEquals("uneven_ground", p.code());
        assertEquals(new HousePlan.Cell(X + 4, Y - 1, Z + 4), p.at());
    }

    @Test
    void theFirstBlockingPositionBottomUpIsReported() {
        Map<HousePlan.Cell, String> world = new LinkedHashMap<>();
        world.put(new HousePlan.Cell(X + 1, Y + 2, Z + 1), "oak_leaves");
        world.put(new HousePlan.Cell(X + 3, Y, Z + 3), "oak_log");
        world.put(new HousePlan.Cell(X + 2, Y, Z + 2), "short_grass");
        HousePlan.SiteProblem p = HousePlan.checkSite(X, Y, Z, "south", "stone", site(world));
        assertEquals("site_blocked", p.code());
        assertEquals(new HousePlan.Cell(X + 3, Y, Z + 3), p.at(), "grass is fine; the log at floor level comes first");
        assertEquals("oak_log", p.block());
    }

    @Test
    void aHalfBuiltHouseOfTheSameMaterialIsNotInTheWay() {
        Map<HousePlan.Cell, String> world = new HashMap<>();
        List<Action.BuildBlock> blocks = HousePlan.blocks(X, Y, Z, "east", "stone");
        for (int i = 0; i < 30; i++) world.put(cell(blocks.get(i)), "stone");
        HousePlan.Cell door = HousePlan.door(X, Y, Z, "east");
        world.put(door, "oak_door");
        world.put(new HousePlan.Cell(door.x(), Y + 1, door.z()), "oak_door");
        world.put(HousePlan.bedFoot(X, Y, Z, "east"), "red_bed");
        assertNull(HousePlan.checkSite(X, Y, Z, "east", "stone", site(world)));
        assertEquals(41, HousePlan.needed(blocks, site(world)));
        world.put(cell(blocks.get(0)), "cobblestone");
        assertEquals("site_blocked", HousePlan.checkSite(X, Y, Z, "east", "stone", site(world)).code(), "another material is in the way");
    }

    @Test
    void theMaterialIsTheBuildingBlockCarriedMost() {
        assertEquals("cobblestone", HousePlan.pickMaterial(Map.of("dirt", 30, "cobblestone", 80, "oak_planks", 64)));
        assertEquals("birch_planks", HousePlan.pickMaterial(Map.of("oak_planks", 64, "birch_planks", 64)), "a tie goes alphabetical");
        assertNull(HousePlan.pickMaterial(Map.of()));
        assertNull(HousePlan.pickMaterial(Map.of("dirt", 0)));
    }

    @Test
    void theCountTextSaysNeededAndCarried() {
        assertEquals("needs 71 oak_planks, carries 40", HousePlan.countText(71, "oak_planks", 40));
        assertEquals("needs 71 blocks of one material, carries none", HousePlan.countText(71, null, 0));
    }

    @Test
    void aSiteTooFarFromTheBodyIsCaughtAtAnyCorner() {
        assertFalse(HousePlan.siteTooFar(X, Z, X + 2.5, Z + 2.5));
        assertFalse(HousePlan.siteTooFar(X, Z, X - 59, Z + 2.5), "the far corners are 63.5 east and 2 aside");
        assertTrue(HousePlan.siteTooFar(X, Z, X - 60.5, Z + 2.5), "the east side is past 64");
        assertTrue(HousePlan.siteTooFar(X, Z, X + 100, Z));
    }

    @Test
    void standingOnACellMeansNearItsCentre() {
        HousePlan.Cell c = new HousePlan.Cell(5, 64, -3);
        assertTrue(HousePlan.onStand(5.5, 64, -2.5, c));
        assertTrue(HousePlan.onStand(5.5 + 0.15, 64, -2.5 - 0.1, c));
        assertFalse(HousePlan.onStand(5.5 + 0.3, 64, -2.5, c), "the body would reach into the next cell");
        assertFalse(HousePlan.onStand(5.5, 65, -2.5, c), "a block up is another cell");
        assertFalse(HousePlan.onStand(4.9, 64, -2.5, c), "the block next to it");
        assertTrue(HousePlan.STAND_TOLERANCE + 0.3 <= 0.5, "a 0.6-wide body inside the tolerance stays inside the cell");
    }
}
