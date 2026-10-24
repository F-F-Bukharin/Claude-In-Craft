package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.BuildPlan;
import com.example.claudeplayer.core.work.GatherRule;
import com.example.claudeplayer.core.work.HousePlan;
import com.example.claudeplayer.nav.Navigator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

/**
 * build_house: checks the material (71 blocks, or the building block carried most), a wooden door and the site, has
 * BuildHandle raise the walls and roof (HousePlan's order), then, standing inside, places a bed it carries against
 * the back wall and makes it home, and last the door, facing out. Doors and beds take their facing from where the
 * body looks, so it places each from a spot in line with it (HousePlan) and sets the door's facing outright if it
 * still came out wrong. move_to only gets within a block of that spot, so the body then steers onto its centre
 * (HousePlan.STAND_TOLERANCE), which also keeps it out of the doorway and off the bed's cells. A site more than 64
 * blocks away or not loaded fails no_path before anything there is read. Reports once at the end.
 */
final class HouseHandle implements ActionRunner.Handle {
    private enum Phase { CHECK, BUILD, BED, DOOR }

    /** Walks to the bed's or door's spot before giving up with no_path. */
    private static final int WALK_TRIES = 2;
    /** After each walk, ticks of steering straight onto the stand cell's centre before walking again. */
    private static final int CENTRE_TICKS = 40;
    /** Centring is tried only this close (flat) to the centre; farther, it walks. */
    private static final double CENTRE_RANGE = 2.0;
    /** The centring walk's forward input: this times the distance, at least the minimum, at most a full walk. */
    private static final double CENTRE_GAIN = 1.5;
    private static final double CENTRE_MIN_SPEED = 0.2;
    /** Horizontal speed (blocks per tick) under which the body counts as standing still. */
    private static final double STILL = 0.03;

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final Action.BuildHouse a;
    private Phase phase = Phase.CHECK;
    private String material;
    private ActionRunner.Handle build;
    private String built = "";
    private String bedNote = "no bed";
    private ActionRunner.Handle walk;
    private int walkTicks;
    private int walkTries;
    /** Ticks spent centring on the stand cell, or -1 when not centring. */
    private int centreTicks = -1;

    private HouseHandle(AgentRuntime rt, AgentPlayer body, Action.BuildHouse a) {
        this.rt = rt;
        this.body = body;
        this.a = a;
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.BuildHouse a) {
        return new HouseHandle(rt, p, a);
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) return end("died");
        switch (phase) {
            case CHECK -> {
                ActionResult bad = check();
                if (bad != null) return bad;
                build = BuildHandle.run(rt, body, HousePlan.blocks(a.x(), a.y(), a.z(), a.facing(), material), "walls and roof");
                phase = Phase.BUILD;
                return null;
            }
            case BUILD -> {
                ActionResult r = build.poll();
                if (r == null) return null;
                build = null;
                if (r.isError()) {
                    cancel();
                    return r; // build's own code and progress, unchanged
                }
                built = r.data();
                BlockPos head = bedHead();
                if (head != null) { // left by an earlier, interrupted build_house
                    rt.data().setHome(body.serverLevel().dimension(), head);
                    bedNote = "bed was there, home set";
                }
                phase = head == null && slot(ItemTags.BEDS) >= 0 ? Phase.BED : Phase.DOOR;
                return null;
            }
            default -> {
                List<Mob> near = SnapshotBuilder.hostiles(body, GatherRule.DANGER_RANGE);
                String danger = BuildPlan.danger(near.isEmpty() ? Double.POSITIVE_INFINITY : near.get(0).distanceTo(body), body.getHealth() / body.getMaxHealth());
                if (danger != null) return end(danger);
                return phase == Phase.BED ? bed() : door();
            }
        }
    }

    /**
     * Site near and loaded, then material, count, door and site, in that order; null when the house can go up. The
     * first check comes before any world read, so a far coordinate never loads or generates a chunk.
     */
    private ActionResult check() {
        if (HousePlan.siteTooFar(a.x(), a.z(), body.getX(), body.getZ())) {
            return ActionResult.failed("no_path", "the site is more than " + BuildPlan.MAX_DISTANCE + " blocks away");
        }
        ServerLevel level = body.serverLevel();
        for (int[] corner : new int[][] {{0, 0}, {4, 0}, {0, 4}, {4, 4}}) { // a 5x5 site touches at most 4 chunks, each at a corner
            if (!level.hasChunk((a.x() + corner[0]) >> 4, (a.z() + corner[1]) >> 4)) {
                return ActionResult.failed("no_path", "the site isn't loaded");
            }
        }
        Map<String, Integer> carried = new HashMap<>();
        Map<String, Integer> candidates = new HashMap<>();
        for (ItemStack s : body.getInventory().items) {
            if (s.isEmpty()) continue;
            String id = SnapshotBuilder.itemId(s);
            carried.merge(id, s.getCount(), Integer::sum);
            if (buildingBlock(s)) candidates.merge(id, s.getCount(), Integer::sum);
        }
        material = a.material() != null ? a.material() : HousePlan.pickMaterial(candidates);
        if (material == null) return ActionResult.failed("missing_blocks", HousePlan.countText(HousePlan.BLOCKS, null, 0));
        HousePlan.Site site = site(level);
        int needed = HousePlan.needed(HousePlan.blocks(a.x(), a.y(), a.z(), a.facing(), material), site);
        int have = carried.getOrDefault(material, 0);
        if (have < needed) return ActionResult.failed("missing_blocks", HousePlan.countText(needed, material, have));
        if (slot(ItemTags.WOODEN_DOORS) < 0 && !doorThere()) return ActionResult.failed("missing_door");
        HousePlan.SiteProblem problem = HousePlan.checkSite(a.x(), a.y(), a.z(), a.facing(), material, site);
        if (problem != null) {
            HousePlan.Cell c = problem.at();
            return ActionResult.failed(problem.code(), c.x() + "," + c.y() + "," + c.z() + " " + problem.block());
        }
        return null;
    }

    /** A full solid cube that doesn't fall, holds nothing and isn't worth more than a wall: ores, storage blocks, TNT and leaves are out. */
    private static boolean buildingBlock(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi)) return false;
        Block block = bi.getBlock();
        BlockState state = block.defaultBlockState();
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) && !(block instanceof FallingBlock)
                && !(block instanceof EntityBlock) && block != Blocks.TNT && !state.is(BlockTags.LEAVES)
                && !state.is(Tags.Blocks.ORES) && !state.is(Tags.Blocks.STORAGE_BLOCKS);
    }

    private static HousePlan.Site site(ServerLevel level) {
        return new HousePlan.Site() {
            @Override
            public boolean solid(int x, int y, int z) {
                BlockPos p = new BlockPos(x, y, z);
                return level.getBlockState(p).isFaceSturdy(level, p, Direction.UP);
            }

            @Override
            public boolean clear(int x, int y, int z) {
                BlockState s = level.getBlockState(new BlockPos(x, y, z));
                return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty();
            }

            @Override
            public String block(int x, int y, int z) {
                return SnapshotBuilder.blockId(level.getBlockState(new BlockPos(x, y, z)));
            }
        };
    }

    private ActionResult bed() {
        HousePlan.Cell stand = HousePlan.bedStand(a.x(), a.y(), a.z(), a.facing());
        if (!standingOn(stand)) return walkTo(stand);
        int slot = slot(ItemTags.BEDS);
        if (slot < 0) {
            phase = Phase.DOOR;
            return null;
        }
        String item = SnapshotBuilder.itemId(body.getInventory().items.get(slot));
        ActionResult r = Placing.place(rt, body, pos(HousePlan.bedFoot(a.x(), a.y(), a.z(), a.facing())), item);
        if (r.isError()) bedNote = "bed not placed: " + r.reason();
        else {
            BlockPos head = bedHead();
            if (head == null) bedNote = "bed not placed: not_placed";
            else {
                rt.data().setHome(body.serverLevel().dimension(), head);
                bedNote = head.equals(pos(HousePlan.bedHead(a.x(), a.y(), a.z(), a.facing()))) ? "bed, home set" : "bed (turned), home set";
            }
        }
        phase = Phase.DOOR;
        walkTries = 0;
        return null;
    }

    private ActionResult door() {
        BlockPos door = pos(HousePlan.door(a.x(), a.y(), a.z(), a.facing()));
        if (doorThere()) return finish("door was there");
        HousePlan.Cell stand = HousePlan.doorStand(a.x(), a.y(), a.z(), a.facing());
        if (!standingOn(stand)) return walkTo(stand);
        int slot = slot(ItemTags.WOODEN_DOORS);
        if (slot < 0) return end("missing_door");
        ActionResult r = Placing.place(rt, body, door, SnapshotBuilder.itemId(body.getInventory().items.get(slot)));
        if (r.isError()) return end("door_not_placed:" + r.reason());
        faceDoorOut(body.serverLevel(), door);
        return finish("door");
    }

    /** Sets the door's facing to the house's facing on both halves, if it came out otherwise. */
    private void faceDoorOut(ServerLevel level, BlockPos lower) {
        Direction out = Direction.byName(HousePlan.doorFacing(a.facing()));
        BlockState state = level.getBlockState(lower);
        if (out == null || !(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.FACING) == out) return;
        BlockPos upper = lower.above();
        BlockState top = level.getBlockState(upper);
        level.setBlock(lower, state.setValue(DoorBlock.FACING, out), 3);
        if (top.getBlock() instanceof DoorBlock && top.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            level.setBlock(upper, top.setValue(DoorBlock.FACING, out), 3);
        }
    }

    /**
     * Walks to {@code stand}; null while walking, no_path (or the hazard) when it can't get there. move_to stops
     * within a block of the target (and at once when it starts that close), so each walk is followed by a short,
     * slow steer straight onto the cell's centre, which doesn't count as a try; only a centring that fails walks
     * again.
     */
    private ActionResult walkTo(HousePlan.Cell stand) {
        Vec3 centre = Vec3.atBottomCenterOf(pos(stand));
        if (walk != null) {
            ActionResult r = walk.poll();
            if (r == null && ++walkTicks < BuildPlan.WALK_TICKS) return null;
            walk = null;
            rt.navigator().stop(body);
            if (r != null && r.isError() && r.reason().startsWith("hazard_")) return end(r.reason());
            centreTicks = 0; // then centre, whatever the walk said: close enough is decided below
            return null;
        }
        boolean on = HousePlan.onStand(body.getX(), body.getY(), body.getZ(), stand);
        if (centreTicks >= 0 || on) { // on the cell but still sliding (only at a phase's start) settles the same way
            if (centreTicks < 0) centreTicks = 0;
            double flat = Math.hypot(centre.x - body.getX(), centre.z - body.getZ());
            if (centreTicks++ < CENTRE_TICKS && flat <= CENTRE_RANGE && Math.abs(body.getY() - stand.y()) < 1.0) {
                if (on) { // let it come to a stop; standingOn decides next tick
                    Navigator.halt(body);
                    return null;
                }
                String hazard = Navigator.hazardAhead(body, centre);
                if (hazard != null) return end("hazard_" + hazard);
                Navigator.steer(body, centre);
                body.zza = (float) Math.max(CENTRE_MIN_SPEED, Math.min(1.0, flat * CENTRE_GAIN)); // slow near the centre: no overshoot
                body.setJumping(false);
                return null;
            }
            Navigator.halt(body);
            centreTicks = -1; // too far, or it didn't settle: walk again
        }
        if (walkTries++ >= WALK_TRIES) return end("no_path");
        walk = rt.navigator().moveTo(body, centre);
        walkTicks = 0;
        return null;
    }

    /**
     * On the cell's centre (within {@link HousePlan#STAND_TOLERANCE}) and nearly still, so the aim from here gives the
     * door or bed its facing and the body is clear of the cell it places into. Halts the body once it is.
     */
    private boolean standingOn(HousePlan.Cell c) {
        if (walk != null || !HousePlan.onStand(body.getX(), body.getY(), body.getZ(), c)) return false;
        Navigator.halt(body);
        Vec3 v = body.getDeltaMovement();
        if (v.x * v.x + v.z * v.z > STILL * STILL) return false; // still sliding: the next tick decides
        centreTicks = -1;
        return true;
    }

    /** The head of the bed at the planned foot, or null when there is none. */
    private BlockPos bedHead() {
        BlockPos foot = pos(HousePlan.bedFoot(a.x(), a.y(), a.z(), a.facing()));
        BlockState state = body.serverLevel().getBlockState(foot);
        if (!(state.getBlock() instanceof BedBlock)) return null;
        Direction facing = state.getValue(BedBlock.FACING);
        return state.getValue(BedBlock.PART) == BedPart.FOOT ? foot.relative(facing) : foot;
    }

    private boolean doorThere() {
        return body.serverLevel().getBlockState(pos(HousePlan.door(a.x(), a.y(), a.z(), a.facing()))).getBlock() instanceof DoorBlock;
    }

    /** The main-inventory slot of the first item in the tag, or -1. */
    private int slot(TagKey<Item> tag) {
        List<ItemStack> items = body.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty() && items.get(i).is(tag)) return i;
        }
        return -1;
    }

    private static BlockPos pos(HousePlan.Cell c) {
        return new BlockPos(c.x(), c.y(), c.z());
    }

    private String where() {
        return "house at " + a.x() + "," + a.y() + "," + a.z() + " facing " + a.facing() + ", " + material;
    }

    private ActionResult finish(String doorNote) {
        cancel();
        return ActionResult.ok(where() + "; " + built + "; " + doorNote + "; " + bedNote);
    }

    private ActionResult end(String reason) {
        cancel();
        return ActionResult.failed(reason, where() + "; " + (built.isEmpty() ? "not built" : built) + "; " + bedNote);
    }

    @Override
    public void cancel() {
        if (build != null) build.cancel();
        build = null;
        walk = null;
        rt.navigator().stop(body);
    }

    /** The build's own limit plus time to walk in for the bed and the door. */
    @Override
    public int timeoutTicks() {
        return BuildPlan.timeoutTicks(HousePlan.BLOCKS) + 2 * (WALK_TRIES + 1) * (BuildPlan.WALK_TICKS + CENTRE_TICKS) + 200;
    }
}
