package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.GatherRule;
import com.example.claudeplayer.core.work.SortPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

/**
 * sort_chests: finds the chests, trapped chests and barrels within radius of the body (a double chest counts once;
 * ender chests, shulker boxes, furnaces, hoppers and the like are left alone), lets SortPlan pick a kind per container
 * and the moves, then carries the items through the body's inventory with ContainerActions' take and store, walking
 * to each container in turn. At a container it first stores what it carries for it, then takes what leaves it, then
 * stores again (so two full containers can swap). A load its target can't hold goes back where it came from.
 * Items the body uses (held, worn, offhand, and any non-stackable it carries, such as its tools) are never moved:
 * take and store work by item id, so moving the same id could swap the body's own one into a chest.
 * Every container ends with a fresh short-term note (take and store write it; untouched ones are inspected).
 * Stops on danger like gather; one result at the end.
 */
final class SortHandle implements ActionRunner.Handle {
    private static final double VISIT = 5.5; // close enough to use a container (ContainerActions allows 6.0)
    private static final int WALK_TICKS = 200;

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final ContainerActions containers;
    private final List<BlockPos> boxes;
    private final List<String> names;
    private final SortPlan.Plan plan;
    private final int[] left; // per move, what is still to take from its source
    private final List<Load> loads = new ArrayList<>();
    private final boolean[] noted;
    private final Set<Integer> unreachable = new LinkedHashSet<>();
    private final Set<String> keptInPlace; // misplaced items the body uses, left where they are
    private final Map<String, Integer> maxStack = new LinkedHashMap<>(); // per item, as the containers hold it
    private final int limitTicks;
    private int ticks;
    private int moved; // items stored in their planned container
    private final Set<Integer> movesDone = new HashSet<>();
    private int goal = -1;
    private ActionRunner.Handle walk;
    private int walkTicks;

    /** Items carried for move {@code move}, on the way to box {@code to} (its source when returning). */
    private static final class Load {
        final int move;
        final String item;
        int count;
        int to;
        boolean returning;

        Load(int move, String item, int count, int to) {
            this.move = move;
            this.item = item;
            this.count = count;
            this.to = to;
        }
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.SortChests a) {
        List<BlockPos> found = containersAround(p, a.radius());
        if (found.size() < 2) return ActionRunner.Handle.done(ActionResult.failed("no_chests"));
        return new SortHandle(rt, p, found);
    }

    private SortHandle(AgentRuntime rt, AgentPlayer body, List<BlockPos> boxes) {
        this.rt = rt;
        this.body = body;
        this.containers = new ContainerActions(rt);
        this.boxes = boxes;
        this.names = new ArrayList<>();
        List<SortPlan.Box> input = new ArrayList<>();
        for (BlockPos pos : boxes) {
            names.add(SnapshotBuilder.blockId(body.serverLevel().getBlockState(pos)) + " at " + pos.toShortString());
            input.add(read(body.serverLevel(), pos));
        }
        Set<String> inUse = inUse(body);
        this.plan = SortPlan.plan(input, inUse);
        this.left = plan.moves().stream().mapToInt(SortPlan.Move::count).toArray();
        this.noted = new boolean[boxes.size()];
        this.keptInPlace = new LinkedHashSet<>();
        for (int b = 0; b < input.size(); b++) {
            for (SortPlan.Stack s : input.get(b).stacks()) {
                maxStack.putIfAbsent(s.item(), s.maxStack());
                if (inUse.contains(s.item()) && plan.kinds().get(b) != s.kind() && plan.kinds().contains(s.kind())) keptInPlace.add(s.item());
            }
        }
        this.limitTicks = Math.min(6000, 1200 + 200 * (plan.moves().size() + boxes.size()));
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) {
            cancel();
            return ActionResult.failed("died");
        }
        List<Mob> near = SnapshotBuilder.hostiles(body, GatherRule.DANGER_RANGE);
        String stop = GatherRule.stop(0, Integer.MAX_VALUE, 0, near.isEmpty() ? Double.POSITIVE_INFINITY : near.get(0).distanceTo(body),
                body.getHealth() / body.getMaxHealth(), false);
        if (stop != null) return end(stop);
        if (++ticks >= limitTicks) return end("timeout");
        noteInReach();
        if (walk != null) {
            if (close(goal)) {
                rt.navigator().stop(body);
                walk = null;
                visit(goal);
                return null;
            }
            ActionResult r = walk.poll();
            if (r == null && ++walkTicks < WALK_TICKS) return null;
            walk = null;
            rt.navigator().stop(body);
            if (r != null && r.isError() && r.reason().startsWith("hazard_")) return end(r.reason());
            if (inReach(goal)) visit(goal);
            else giveUp(goal);
            return null;
        }
        goal = nextGoal();
        if (goal < 0) return end(blocked() ? "inventory_full" : "done");
        if (close(goal)) visit(goal);
        else {
            walk = rt.navigator().moveTo(body, stand(boxes.get(goal)));
            walkTicks = 0;
        }
        return null;
    }

    /** Store what is carried for this box, take what leaves it, store again; a box nothing happened at is inspected. */
    private void visit(int b) {
        boolean touched = storeHere(b);
        List<SortPlan.Move> moves = plan.moves();
        Inventory inv = body.getInventory();
        for (int m = 0; m < moves.size(); m++) {
            SortPlan.Move mv = moves.get(m);
            if (mv.from() != b || left[m] <= 0) continue;
            if (unreachable.contains(mv.to())) {
                left[m] = 0;
                continue;
            }
            int want = Math.min(left[m], room(inv, mv.item()));
            if (want <= 0) continue;
            int before = count(inv, mv.item());
            containers.take(body, new Action.Take(boxes.get(b).getX(), boxes.get(b).getY(), boxes.get(b).getZ(), mv.item(), want));
            int took = count(inv, mv.item()) - before;
            if (took > 0) {
                touched = true;
                loads.add(new Load(m, mv.item(), took, mv.to()));
            }
            left[m] -= took;
            if (took < want && room(inv, mv.item()) > 0) left[m] = 0; // fewer than asked with room left: the box changed
        }
        touched |= storeHere(b);
        // What this box still refuses, now that nothing more leaves it, goes back (or, already going back, stays carried).
        for (Load l : loads) if (l.count > 0 && l.to == b) retarget(l);
        loads.removeIf(l -> l.count <= 0);
        if (!touched) inspect(b);
        noted[b] = true;
    }

    /** Stores every load bound for box b; true if anything went in. */
    private boolean storeHere(int b) {
        boolean any = false;
        Inventory inv = body.getInventory();
        for (Load l : loads) {
            if (l.count <= 0 || l.to != b) continue;
            int before = count(inv, l.item);
            containers.store(body, new Action.Store(boxes.get(b).getX(), boxes.get(b).getY(), boxes.get(b).getZ(), l.item, l.count));
            int stored = before - count(inv, l.item);
            if (stored <= 0) continue;
            any = true;
            l.count -= stored;
            if (!l.returning) {
                moved += stored;
                movesDone.add(l.move);
            }
        }
        return any;
    }

    /** A load its target can't take goes back to its source; one that can't go back either stays carried. */
    private void retarget(Load l) {
        if (l.returning) {
            l.to = -1;
            return;
        }
        l.returning = true;
        l.to = plan.moves().get(l.move).from();
        if (unreachable.contains(l.to)) l.to = -1;
    }

    private void giveUp(int b) {
        unreachable.add(b);
        noted[b] = true; // it can't be reached to note it
        for (int m = 0; m < left.length; m++) {
            SortPlan.Move mv = plan.moves().get(m);
            if (mv.from() == b || mv.to() == b) left[m] = 0;
        }
        for (Load l : loads) if (l.count > 0 && l.to == b) retarget(l);
    }

    /** Carried loads first (nearest target), then the nearest box with something to take, then un-noted boxes. */
    private int nextGoal() {
        Set<Integer> wanted = new LinkedHashSet<>();
        for (Load l : loads) if (l.count > 0 && l.to >= 0) wanted.add(l.to);
        if (wanted.isEmpty()) {
            Inventory inv = body.getInventory();
            for (int m = 0; m < left.length; m++) {
                SortPlan.Move mv = plan.moves().get(m);
                if (left[m] > 0 && !unreachable.contains(mv.from()) && room(inv, mv.item()) > 0) wanted.add(mv.from());
            }
        }
        if (wanted.isEmpty()) {
            for (int b = 0; b < boxes.size(); b++) if (!noted[b]) wanted.add(b);
        }
        return wanted.stream().min(Comparator.comparingDouble(b -> boxes.get(b).distToCenterSqr(body.position()))).orElse(-1);
    }

    /** Moves are left but none can start: no room in the inventory for any of them. */
    private boolean blocked() {
        for (int m = 0; m < left.length; m++) if (left[m] > 0 && !unreachable.contains(plan.moves().get(m).from())) return true;
        return false;
    }

    /** Untouched containers in reach get their note on the way, so few need a walk of their own. */
    private void noteInReach() {
        for (int b = 0; b < boxes.size(); b++) {
            if (!noted[b] && inReach(b) && !hasWork(b)) {
                inspect(b);
                noted[b] = true;
            }
        }
    }

    private boolean hasWork(int b) {
        for (int m = 0; m < left.length; m++) if (left[m] > 0 && plan.moves().get(m).from() == b) return true;
        for (Load l : loads) if (l.count > 0 && l.to == b) return true;
        for (int m = 0; m < left.length; m++) if (left[m] > 0 && plan.moves().get(m).to() == b) return true;
        return false;
    }

    private void inspect(int b) {
        containers.inspect(body, boxes.get(b));
    }

    /** Before stopping: whatever is still carried goes into a container in reach (its target, its source, any). */
    private void putBack() {
        for (Load l : loads) {
            if (l.count <= 0) continue;
            List<Integer> order = new ArrayList<>();
            if (l.to >= 0) order.add(l.to);
            order.add(plan.moves().get(l.move).from());
            for (int b = 0; b < boxes.size(); b++) order.add(b);
            for (int b : order) {
                if (l.count <= 0) break;
                if (unreachable.contains(b) || !inReach(b)) continue;
                int before = count(body.getInventory(), l.item);
                containers.store(body, new Action.Store(boxes.get(b).getX(), boxes.get(b).getY(), boxes.get(b).getZ(), l.item, l.count));
                l.count -= before - count(body.getInventory(), l.item);
            }
        }
        loads.removeIf(l -> l.count <= 0);
    }

    private ActionResult end(String stop) {
        putBack();
        cancel();
        List<String> tail = new ArrayList<>();
        if (!keptInPlace.isEmpty()) tail.add("left in place (you use them): " + String.join(", ", keptInPlace));
        if (!unreachable.isEmpty()) tail.add("couldn't reach " + unreachable.stream().map(names::get).collect(Collectors.joining(", ")));
        int remaining = 0;
        for (int l : left) remaining += l;
        if (!stop.equals("done")) tail.add("stopped: " + stop + (remaining > 0 ? ", " + remaining + " items left to move" : ""));
        Map<String, Integer> carried = new LinkedHashMap<>();
        for (Load l : loads) carried.merge(l.item, l.count, Integer::sum);
        if (!carried.isEmpty()) {
            tail.add("still carrying " + carried.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", ")));
        }
        String summary = SortPlan.summary(names, plan.kinds(), moved, movesDone.size(), String.join("; ", tail));
        if (stop.equals("danger")) return ActionResult.failed("danger", summary);
        if (stop.equals("inventory_full") && moved == 0) return ActionResult.failed("inventory_full", summary);
        return ActionResult.ok(summary);
    }

    @Override
    public void cancel() {
        walk = null;
        rt.navigator().stop(body);
    }

    @Override
    public int timeoutTicks() {
        return limitTicks + 200; // its own limit reports first; this is the backstop
    }

    // --- the world ---

    /** Chests, trapped chests and barrels within radius (loaded chunks only), nearest first; a double chest once. */
    static List<BlockPos> containersAround(AgentPlayer p, int radius) {
        ServerLevel level = p.serverLevel();
        BlockPos c = p.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-radius, -radius, -radius), c.offset(radius, radius, radius))) {
            if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos) || seen.contains(pos)) continue;
            if (p.position().distanceTo(Vec3.atCenterOf(pos)) > radius) continue;
            BlockState state = level.getBlockState(pos);
            if (!state.hasBlockEntity()) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) continue; // ender chests aren't ChestBlockEntity
            BlockPos at = pos.immutable();
            found.add(at);
            seen.add(at);
            if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                seen.add(at.relative(ChestBlock.getConnectedDirection(state)));
            }
        }
        found.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distToCenterSqr(p.position())).thenComparingLong(BlockPos::asLong));
        return found;
    }

    /** The container as ContainerActions sees it (both halves of a double chest), as SortPlan's input. */
    private static SortPlan.Box read(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Container container = state.getBlock() instanceof ChestBlock chest
                ? ChestBlock.getContainer(chest, state, level, pos, true)
                : level.getBlockEntity(pos) instanceof Container cont ? cont : null;
        if (container == null) return new SortPlan.Box(0, List.of());
        List<SortPlan.Stack> stacks = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty()) continue;
            stacks.add(new SortPlan.Stack(SnapshotBuilder.itemId(s), s.getCount(), Math.min(s.getMaxStackSize(), container.getMaxStackSize()), kind(s)));
        }
        return new SortPlan.Box(container.getContainerSize(), stacks);
    }

    /** Tools, weapons and armor; food; ores, ingots and gems (Forge tags and ids); blocks; the rest. In that order. */
    static SortPlan.Kind kind(ItemStack s) {
        Item item = s.getItem();
        if (item instanceof TieredItem || item instanceof ArmorItem || item instanceof ElytraItem || item instanceof ProjectileWeaponItem
                || item instanceof TridentItem || item instanceof ShieldItem || item instanceof FishingRodItem || item instanceof ShearsItem
                || item instanceof FlintAndSteelItem) return SortPlan.Kind.GEAR;
        if (s.isEdible()) return SortPlan.Kind.FOOD;
        if (s.is(Tags.Items.ORES) || s.is(Tags.Items.INGOTS) || s.is(Tags.Items.GEMS) || s.is(Tags.Items.NUGGETS) || s.is(Tags.Items.RAW_MATERIALS)
                || SortPlan.isMaterial(SnapshotBuilder.itemId(s))) return SortPlan.Kind.MATERIALS;
        if (item instanceof BlockItem) return SortPlan.Kind.BLOCKS;
        return SortPlan.Kind.OTHER;
    }

    /** Ids the body is using: held, offhand, worn, and every non-stackable it carries (its tools and weapons). */
    private static Set<String> inUse(AgentPlayer p) {
        Set<String> ids = new HashSet<>();
        Inventory inv = p.getInventory();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = p.getItemBySlot(slot);
            if (!s.isEmpty()) ids.add(SnapshotBuilder.itemId(s));
        }
        for (ItemStack s : inv.items) if (!s.isEmpty() && s.getMaxStackSize() == 1) ids.add(SnapshotBuilder.itemId(s));
        return ids;
    }

    /** Room for more of this item in the main inventory: empty slots and partial stacks of it. */
    private int room(Inventory inv, String item) {
        int max = maxStack.getOrDefault(item, 64);
        int room = 0;
        for (ItemStack s : inv.items) {
            if (s.isEmpty()) room += max;
            else if (SnapshotBuilder.itemId(s).equals(item)) room += Math.max(0, s.getMaxStackSize() - s.getCount());
        }
        return room;
    }

    private static int count(Inventory inv, String item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && SnapshotBuilder.itemId(s).equals(item)) n += s.getCount();
        }
        return n;
    }

    private boolean inReach(int b) {
        return body.position().distanceTo(Vec3.atCenterOf(boxes.get(b))) <= ActionExecutor.CONTAINER_REACH;
    }

    private boolean close(int b) {
        return body.position().distanceTo(Vec3.atCenterOf(boxes.get(b))) <= VISIT;
    }

    /** A free spot beside the container with a floor (nearest to the body), else on top of it. */
    private Vec3 stand(BlockPos pos) {
        ServerLevel level = body.serverLevel();
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos feet = pos.relative(d).above(dy);
                if (!open(level, feet) || !open(level, feet.above()) || open(level, feet.below())) continue;
                Vec3 v = Vec3.atBottomCenterOf(feet);
                double dist = v.distanceToSqr(body.position());
                if (dist < bestDist) {
                    best = v;
                    bestDist = dist;
                }
            }
        }
        return best != null ? best : Vec3.atBottomCenterOf(pos.above());
    }

    private static boolean open(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }
}
