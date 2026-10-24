package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.GatherRule;
import com.example.claudeplayer.core.work.StorePlan;
import com.example.claudeplayer.nav.Navigator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.Container;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

/**
 * go_home: walks to the saved home, in legs when it is far (vanilla pathing reaches about 64 blocks, so a leg goes
 * to the end of the partial path and the next one plans again from there). With store it then puts the loot that
 * {@link StorePlan} picks into the chests, trapped chests and barrels within 6 blocks of home, nearest first,
 * through {@link ContainerActions}, and moves on to the next one when a container is full.
 * Fails no_home, no_path (also for a home in another dimension), no_container, danger, timeout, hazard_* or died.
 * Legs are planned through closed wooden doors, which the walk opens and closes behind it.
 * One report at the end; nothing is said per step.
 */
final class HomeHandle implements ActionRunner.Handle {
    /** "Within 6 blocks of home": from the home block's bottom centre (where the body stands) to the container's centre. */
    private static final double STORE_RANGE = 6.0;
    /** ContainerActions refuses past 6.0 from the feet; walk when farther than this, to keep a margin. */
    private static final double REACH = 5.9;
    /** A spot to stand at is picked this close to the container (feet to centre), well inside the reach. */
    private static final double SPOT_REACH = 4.5;
    private static final double HOME_ARRIVE = 1.5;
    /** After a failed leg this close to home, the body counts as home. */
    private static final double HOME_NEAR = 3.0;
    /** A partial path has to bring the body at least this much closer to home, or it is no way there. */
    private static final double MIN_PROGRESS = 2.0;
    private static final int LEG_TICKS = 900;
    private static final int MAX_FAILED_LEGS = 2;
    private static final int REACH_TICKS = 300;
    /** Five minutes: a walk of well over a thousand blocks, then the storing. */
    private static final int LIMIT_TICKS = 6000;

    private enum Phase { HOME, CONTAINERS }

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final BlockPos home;
    private final boolean store;
    private final ContainerActions containers;
    private Phase phase = Phase.HOME;
    private int ticks;

    private ActionRunner.Handle walk;
    private int legTicks;
    private int failedLegs;

    private List<BlockPos> queue = List.of();
    private int next;
    private BlockPos current;
    private int unreached;
    private final Map<String, Integer> remaining = new LinkedHashMap<>();
    private final List<StorePlan.Placed> placed = new ArrayList<>();

    private HomeHandle(AgentRuntime rt, AgentPlayer body, BlockPos home, boolean store) {
        this.rt = rt;
        this.body = body;
        this.home = home;
        this.store = store;
        this.containers = new ContainerActions(rt);
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.GoHome a) {
        BlockPos home = rt.data().home();
        if (home == null) return ActionRunner.Handle.done(ActionResult.failed("no_home"));
        if (!rt.data().homeDimension().equals(p.serverLevel().dimension())) {
            return ActionRunner.Handle.done(ActionResult.failed("no_path", "home is in " + rt.data().homeDimension().location()
                    + ", not in this dimension"));
        }
        return new HomeHandle(rt, p, home, a.store());
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) {
            cancel();
            return ActionResult.failed("died");
        }
        if (++ticks >= LIMIT_TICKS) return end(ActionResult.failed("timeout", progress()));
        if (!SnapshotBuilder.hostiles(body, GatherRule.DANGER_RANGE).isEmpty()) return end(ActionResult.failed("danger", progress()));
        return phase == Phase.HOME ? walkHome() : storing();
    }

    // ---- the walk home -------------------------------------------------------------------------------------------

    private ActionResult walkHome() {
        if (arrived(HOME_ARRIVE, 2)) return atHome();
        if (walk == null) return planLeg();
        ActionResult r = walk.poll();
        if (r == null && ++legTicks < LEG_TICKS) return null;
        walk = null;
        rt.navigator().stop(body);
        if (r != null && r.isError() && r.reason().startsWith("hazard_")) return end(ActionResult.failed(r.reason(), progress()));
        if (r == null || r.isError()) {
            if (arrived(HOME_NEAR, 3)) return atHome();
            if (++failedLegs > MAX_FAILED_LEGS) return end(ActionResult.failed("no_path", progress()));
        }
        return null;
    }

    /** Starts the next leg: all the way when vanilla finds a full path, else to the end of the partial one. */
    private @Nullable ActionResult planLeg() {
        Vec3 homeAt = Vec3.atBottomCenterOf(home);
        // through closed wooden doors, as move_to walks (it opens them on the way): a bed indoors is a normal home
        Path path = rt.navigator().findPath(body, home, 1, true);
        if (path == null) return end(ActionResult.failed("no_path", progress()));
        Vec3 goal = homeAt;
        if (!path.canReach()) {
            Node endNode = path.getEndNode();
            if (endNode == null) return end(ActionResult.failed("no_path", progress()));
            goal = Navigator.center(endNode);
            if (goal.distanceTo(homeAt) > body.position().distanceTo(homeAt) - MIN_PROGRESS) {
                return end(ActionResult.failed("no_path", progress())); // the best it found goes nowhere nearer
            }
        }
        walk = rt.navigator().moveTo(body, goal);
        legTicks = 0;
        return null;
    }

    private boolean arrived(double horizontal, double vertical) {
        double dx = body.getX() - (home.getX() + 0.5);
        double dz = body.getZ() - (home.getZ() + 0.5);
        return dx * dx + dz * dz < horizontal * horizontal && Math.abs(body.getY() - home.getY()) < vertical;
    }

    private @Nullable ActionResult atHome() {
        walk = null;
        rt.navigator().stop(body);
        if (!store) return ActionResult.ok("home at " + home.toShortString());
        queue = findContainers();
        if (queue.isEmpty()) return ActionResult.failed("no_container");
        remaining.putAll(StorePlan.toStore(stacks()));
        if (remaining.isEmpty()) return ActionResult.ok("home; nothing to store");
        phase = Phase.CONTAINERS;
        return null;
    }

    // ---- storing -------------------------------------------------------------------------------------------------

    private @Nullable ActionResult storing() {
        if (walk != null) {
            ActionResult r = walk.poll();
            if (r == null && ++legTicks < REACH_TICKS) return null;
            walk = null;
            rt.navigator().stop(body);
            if (r == null || r.isError() || !inReach(current)) unreached++;
            else storeInto(current);
            current = null;
            return null;
        }
        if (remaining.isEmpty() || next >= queue.size()) return finish();
        current = queue.get(next++);
        if (inReach(current)) {
            storeInto(current);
            current = null;
            return null;
        }
        BlockPos spot = standSpot(current);
        if (spot == null) {
            unreached++;
            current = null;
            return null;
        }
        walk = rt.navigator().moveTo(body, Vec3.atBottomCenterOf(spot));
        legTicks = 0;
        return null;
    }

    private boolean inReach(BlockPos pos) {
        return body.position().distanceTo(Vec3.atCenterOf(pos)) <= REACH;
    }

    /** Offers every id still to store to this container; what it takes is read off the inventory. */
    private void storeInto(BlockPos pos) {
        Map<String, Integer> moved = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : new ArrayList<>(remaining.entrySet())) {
            String id = e.getKey();
            int had = Hands.counts(body.getInventory()).getOrDefault(id, 0);
            ActionResult r = containers.store(body, new Action.Store(pos.getX(), pos.getY(), pos.getZ(), id, e.getValue()));
            if (r.isError() && !r.reason().equals("container_full") && !r.reason().equals("missing_item")) {
                unreached++; // out of reach after all, or not a container any more
                break;
            }
            int n = had - Hands.counts(body.getInventory()).getOrDefault(id, 0);
            if (n <= 0) continue;
            moved.put(id, n);
            if (e.getValue() <= n) remaining.remove(id);
            else remaining.put(id, e.getValue() - n);
        }
        placed.add(new StorePlan.Placed(SnapshotBuilder.blockId(body.serverLevel().getBlockState(pos)) + " at " + pos.toShortString(), moved));
    }

    private ActionResult finish() {
        rt.navigator().stop(body);
        return ActionResult.ok("home; " + StorePlan.report(placed, remaining, unreached));
    }

    /** What went in so far, or how far from home the walk stopped; the data of a failure. */
    private String progress() {
        if (phase == Phase.CONTAINERS) return StorePlan.report(placed, remaining, unreached);
        return (int) Math.round(body.position().distanceTo(Vec3.atBottomCenterOf(home))) + " blocks from home";
    }

    private ActionResult end(ActionResult result) {
        cancel();
        return result;
    }

    // ---- the inventory and the world -----------------------------------------------------------------------------

    private List<StorePlan.Stack> stacks() {
        List<StorePlan.Stack> out = new ArrayList<>();
        Inventory inv = body.getInventory();
        for (ItemStack s : inv.items) {
            if (!s.isEmpty()) out.add(new StorePlan.Stack(SnapshotBuilder.itemId(s), s.getCount(), kind(s), s.getMaxStackSize()));
        }
        return out;
    }

    /**
     * Tools (anything tiered that isn't a sword, shears, rod, flint and steel, buckets), weapons (swords, bows,
     * crossbows, tridents and arrows to shoot), armor (worn pieces, shields, elytra and the totem), food, torches,
     * beds, and building blocks: a full solid cube without a block entity, that does not fall, and is not an ore,
     * a storage block, leaves or TNT. Everything else is OTHER.
     */
    private static StorePlan.Kind kind(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof SwordItem || item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem
                || item instanceof ArrowItem) return StorePlan.Kind.WEAPON;
        if (item instanceof ArmorItem || item instanceof ShieldItem || item instanceof ElytraItem || stack.is(Items.TOTEM_OF_UNDYING)) {
            return StorePlan.Kind.ARMOR;
        }
        if (item instanceof TieredItem || item instanceof ShearsItem || item instanceof FishingRodItem
                || item instanceof FlintAndSteelItem || item instanceof BucketItem) return StorePlan.Kind.TOOL;
        String id = SnapshotBuilder.itemId(stack);
        if (id.equals("torch") || id.equals("soul_torch")) return StorePlan.Kind.TORCH;
        if (item instanceof BedItem) return StorePlan.Kind.BED;
        if (stack.isEdible()) return StorePlan.Kind.FOOD;
        if (item instanceof BlockItem blockItem && buildingBlock(blockItem.getBlock())) return StorePlan.Kind.BUILDING_BLOCK;
        return StorePlan.Kind.OTHER;
    }

    private static boolean buildingBlock(Block block) {
        BlockState state = block.defaultBlockState();
        return !state.hasBlockEntity() && !(block instanceof FallingBlock) && !(block instanceof TntBlock)
                && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && !state.is(Tags.Blocks.ORES) && !state.is(Tags.Blocks.STORAGE_BLOCKS) && !state.is(BlockTags.LEAVES);
    }

    /**
     * Chests, trapped chests and barrels within STORE_RANGE of home, nearest to the body first, a double chest once.
     * Not ender chests (personal), furnaces, hoppers, brewing stands, droppers, dispensers or lecterns (they use
     * their contents), and not shulker boxes (often carried, or kept for something).
     */
    private List<BlockPos> findContainers() {
        ServerLevel level = body.serverLevel();
        Vec3 centre = Vec3.atBottomCenterOf(home);
        Map<Long, BlockPos> units = new LinkedHashMap<>(); // one entry per chest or double chest
        for (BlockPos pos : BlockPos.betweenClosed(home.offset(-6, -6, -6), home.offset(6, 6, 6))) {
            if (!level.isLoaded(pos) || Vec3.atCenterOf(pos).distanceTo(centre) > STORE_RANGE) continue;
            BlockState state = level.getBlockState(pos);
            boolean chest = state.getBlock() instanceof ChestBlock;
            if (!chest && !state.is(Blocks.BARREL) || !(level.getBlockEntity(pos) instanceof Container)) continue;
            BlockPos at = pos.immutable();
            long key = at.asLong();
            if (chest && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                key = Math.min(key, at.relative(ChestBlock.getConnectedDirection(state)).asLong());
            }
            BlockPos known = units.get(key);
            if (known == null || at.distToCenterSqr(centre) < known.distToCenterSqr(centre)) units.put(key, at);
        }
        List<BlockPos> sorted = new ArrayList<>(units.values());
        sorted.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(body.position())));
        return sorted;
    }

    /** The standable spot nearest the body that is within SPOT_REACH of the container; null when there is none. */
    private @Nullable BlockPos standSpot(BlockPos pos) {
        ServerLevel level = body.serverLevel();
        Vec3 centre = Vec3.atCenterOf(pos);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos s : BlockPos.betweenClosed(pos.offset(-4, -4, -4), pos.offset(4, 3, 4))) {
            if (centre.distanceTo(Vec3.atBottomCenterOf(s)) > SPOT_REACH) continue;
            double dist = s.distToCenterSqr(body.position());
            if (dist < bestDist && standable(level, s)) {
                best = s.immutable();
                bestDist = dist;
            }
        }
        return best;
    }

    private static boolean standable(ServerLevel level, BlockPos s) {
        return open(level, s) && open(level, s.above()) && !level.getBlockState(s.below()).getCollisionShape(level, s.below()).isEmpty()
                && level.getFluidState(s.below()).isEmpty();
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        BlockState state = level.getBlockState(p);
        return state.getCollisionShape(level, p).isEmpty() && state.getFluidState().isEmpty() && !state.is(BlockTags.FIRE);
    }

    // ---- the handle ----------------------------------------------------------------------------------------------

    @Override
    public void cancel() {
        walk = null;
        rt.navigator().stop(body);
    }

    /** Its own five minutes end the run with a report first; this is the backstop. */
    @Override
    public int timeoutTicks() {
        return LIMIT_TICKS + 200;
    }
}
