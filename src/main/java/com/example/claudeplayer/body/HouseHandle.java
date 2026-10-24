package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.BuildPlan;
import com.example.claudeplayer.core.work.GatherRule;
import com.example.claudeplayer.core.work.HousePlan;
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
 * still came out wrong. Reports once at the end.
 */
final class HouseHandle implements ActionRunner.Handle {
    private enum Phase { CHECK, BUILD, BED, DOOR }

    /** Walks to the bed's or door's spot before giving up with no_path. */
    private static final int WALK_TRIES = 2;

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

    /** Material, count, door and site, in that order; null when the house can go up. */
    private ActionResult check() {
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
        ServerLevel level = body.serverLevel();
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

    /** Walks to {@code stand}; null while walking, no_path (or the hazard) when it can't get there. */
    private ActionResult walkTo(HousePlan.Cell stand) {
        if (walk == null) {
            if (walkTries++ >= WALK_TRIES) return end("no_path");
            walk = rt.navigator().moveTo(body, Vec3.atBottomCenterOf(pos(stand)));
            walkTicks = 0;
            return null;
        }
        ActionResult r = walk.poll();
        if (r == null && ++walkTicks < BuildPlan.WALK_TICKS) return null;
        walk = null;
        rt.navigator().stop(body);
        if (r != null && r.isError() && r.reason().startsWith("hazard_")) return end(r.reason());
        return null; // the next poll places if it got there, else walks again
    }

    private boolean standingOn(HousePlan.Cell c) {
        BlockPos feet = body.blockPosition();
        return walk == null && feet.getX() == c.x() && feet.getZ() == c.z() && feet.getY() == c.y();
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
        return BuildPlan.timeoutTicks(HousePlan.BLOCKS) + 2 * WALK_TRIES * BuildPlan.WALK_TICKS + 200;
    }
}
