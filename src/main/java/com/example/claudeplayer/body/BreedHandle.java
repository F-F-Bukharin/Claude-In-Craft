package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.Breeding;
import com.example.claudeplayer.core.work.GatherRule;
import com.example.claudeplayer.nav.Navigator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * breed: for each pair asked, walks to two ready adults of the kind (grown, off cooldown, not in love) and feeds each
 * the right food the way a player does (food in the main hand, a look, then interactOn). It ends ok when all the pairs
 * are fed, and fails with missing_food, no_pair (fewer than two of the kind near, babies included), not_ready (two or
 * more near but fewer than two ready adults: babies, cooldown, in love), danger (a hostile close, or low health),
 * hazard_*, died or timeout. A failure after some pairs
 * were bred carries how many in its detail. Animals that can't be reached in time or refuse the food are skipped.
 */
final class BreedHandle implements ActionRunner.Handle {
    /** The distance to an animal (blocks between positions) at which it is fed; inside vanilla's 3 block reach. */
    private static final double FEED_REACH = 2.5;
    private static final int REPLAN_TICKS = 10;
    /** One animal that can't be reached in this long is given up on (it may have wandered or be fenced off). */
    private static final int WALK_TICKS = 300;

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final String kind;
    private final int pairs;
    private final Map<String, Integer> used = new LinkedHashMap<>();
    private final Set<Integer> skipped = new HashSet<>();
    private int ticks;
    private int bred;
    /** How many of the current pair are fed: 0, or 1 once the first is in love. */
    private int fed;
    private Animal first;
    private Animal second;
    private int walkTicks;
    private Path path;
    private int node;

    private BreedHandle(AgentRuntime rt, AgentPlayer body, String kind, int pairs) {
        this.rt = rt;
        this.body = body;
        this.kind = kind;
        this.pairs = pairs;
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Breed a) {
        String kind = Breeding.shortId(a.animal());
        if (!Breeding.known(kind)) return ActionRunner.Handle.done(ActionResult.failed("unknown_animal"));
        int pairs = Math.max(1, Math.min(4, a.pairs()));
        int food = Breeding.carried(kind, carried(p));
        if (food < Breeding.foodFor(1)) {
            return ActionRunner.Handle.done(ActionResult.failed("missing_food",
                    "need " + Breeding.foodFor(1) + " of " + String.join(", ", Breeding.foods(kind)) + ", carrying " + food));
        }
        return new BreedHandle(rt, p, kind, pairs);
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) return fail("died");
        List<Mob> near = SnapshotBuilder.hostiles(body, GatherRule.DANGER_RANGE);
        if (!near.isEmpty() || body.getHealth() / body.getMaxHealth() < GatherRule.LOW_HEALTH) return fail("danger");
        if (bred >= pairs) {
            cancel();
            return ActionResult.ok(Breeding.summary(kind, bred, used));
        }
        if (++ticks > Breeding.LIMIT_TICKS) return fail("timeout");
        if (Breeding.chooseFood(kind, carried(body)) == null) return fail("missing_food");
        Animal target = fed == 0 ? first : second;
        if (target == null || !ready(target)) {
            ActionResult end = plan();
            if (end != null) return end;
            target = fed == 0 ? first : second;
            walkTicks = 0;
            path = null;
        }
        if (body.distanceTo(target) <= FEED_REACH) return feed(target);
        if (++walkTicks > WALK_TICKS) {
            giveUp(target);
            return null;
        }
        return approach(target);
    }

    /**
     * Picks the animals for the pair in hand: the ready adult nearest the body, then the one nearest to it. With the
     * first already fed, only the second is picked. Returns the failure (no_pair, not_ready) or null.
     */
    private @Nullable ActionResult plan() {
        List<Animal> candidates = candidates();
        List<Animal> ready = new ArrayList<>();
        for (Animal a : candidates) {
            if (ready(a)) ready.add(a);
        }
        // Babies count as candidates that aren't ready: two calves are not_ready (wait for them), not no_pair.
        int candidateCount = candidates.size();
        int readyCount = ready.size();
        if (fed == 1) { // the one in love counts as the pair's first, wherever it has wandered
            if (!candidates.contains(first)) candidateCount++;
            readyCount++;
        }
        String refusal = Breeding.check(candidateCount, readyCount);
        if (refusal != null) return fail(refusal);
        if (fed == 0) {
            first = nearest(ready, body.position(), null);
            second = nearest(ready, first.position(), first);
        } else {
            second = nearest(ready, first.position(), first);
        }
        return null;
    }

    /** Animals of the kind within range, babies included, that haven't been given up on. Only ready() ones are fed. */
    private List<Animal> candidates() {
        List<Animal> found = body.serverLevel().getEntitiesOfClass(Animal.class, body.getBoundingBox().inflate(Breeding.SEARCH_RANGE),
                a -> a.isAlive() && !skipped.contains(a.getId()) && SnapshotBuilder.typeId(a).equals(kind)
                        && a.distanceTo(body) <= Breeding.SEARCH_RANGE);
        return new ArrayList<>(found);
    }

    /** Grown, off its age cooldown, and not in love already. */
    private static boolean ready(Animal a) {
        return a.isAlive() && !a.isRemoved() && !a.isBaby() && a.getAge() == 0 && a.canFallInLove();
    }

    private static @Nullable Animal nearest(List<Animal> from, Vec3 to, @Nullable Animal except) {
        return from.stream().filter(a -> a != except).min(Comparator.comparingDouble(a -> a.position().distanceToSqr(to))).orElse(null);
    }

    /** Walks along a fresh path toward the animal, or straight at it when there is none. */
    private ActionResult approach(Animal target) {
        if (path == null || ticks % REPLAN_TICKS == 0) {
            path = rt.navigator().findPath(body, target.blockPosition(), 1);
            node = 0;
        }
        Vec3 point = target.position();
        if (path != null) {
            while (node < path.getNodeCount() && Navigator.reached(body, path.getNode(node))) node++;
            if (node < path.getNodeCount()) point = Navigator.center(path.getNode(node));
        }
        String hazard = Navigator.hazardAhead(body, point);
        if (hazard != null) return fail("hazard_" + hazard);
        Navigator.steer(body, point);
        return null;
    }

    /** Holds the food, looks at the animal and uses it on the animal. An animal that doesn't take it is skipped. */
    private ActionResult feed(Animal target) {
        Navigator.halt(body);
        path = null;
        String food = Breeding.chooseFood(kind, carried(body));
        int slot = food == null ? -1 : Hands.find(body.getInventory(), food);
        if (slot < 0) return fail("missing_food");
        Hands.hold(body.getInventory(), slot);
        body.lookAt(EntityAnchorArgument.Anchor.EYES, target.position().add(0, target.getBbHeight() / 2.0, 0));
        InteractionResult result = body.interactOn(target, InteractionHand.MAIN_HAND);
        body.swing(InteractionHand.MAIN_HAND, true);
        if (!result.consumesAction() || !target.isInLove()) {
            giveUp(target);
            return null;
        }
        used.merge(food, 1, Integer::sum);
        walkTicks = 0;
        if (fed == 0) {
            fed = 1;
        } else {
            fed = 0;
            bred++;
            first = null;
            second = null;
        }
        return null;
    }

    /** Leaves this animal out for the rest of the run and picks again next tick. */
    private void giveUp(Animal target) {
        skipped.add(target.getId());
        path = null;
        if (fed == 0) {
            first = null;
            second = null;
        } else {
            second = null;
        }
    }

    /** The failure, with how many pairs were bred before it when there were any. */
    private ActionResult fail(String reason) {
        cancel();
        return bred > 0 ? ActionResult.failed(reason, Breeding.shortfall(kind, bred, pairs, used)) : ActionResult.failed(reason);
    }

    /** Item counts by short id over the main inventory, which is where Hands.find looks. */
    private static Map<String, Integer> carried(AgentPlayer p) {
        Inventory inv = p.getInventory();
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack s : inv.items) {
            if (!s.isEmpty()) counts.merge(SnapshotBuilder.itemId(s), s.getCount(), Integer::sum);
        }
        return counts;
    }

    @Override
    public void cancel() {
        path = null;
        Navigator.halt(body);
    }

    /** Its own 60 s limit ends the run first; this is the backstop. */
    @Override
    public int timeoutTicks() {
        return Breeding.LIMIT_TICKS + 200;
    }
}
