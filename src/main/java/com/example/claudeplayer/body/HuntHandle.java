package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.safety.FightRules;
import com.example.claudeplayer.core.safety.HuntRules;
import com.example.claudeplayer.nav.Navigator;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * hunt: gears up, then repeats: pick the nearest animal of the asked kind that HuntRules allows within 24 blocks,
 * walk to it and swing on the vanilla cooldown as fight does (HuntRules is asked again before every swing), and after
 * a kill walk onto the drops near the kill spot for a few seconds. Ends ok when the count is reached or no allowed
 * animal is left (with what it has), failed:no_animals when nothing was killed, failed:danger (a hostile close or low
 * health), failed:timeout after 120 s, failed:died, or failed:hazard_* when the next step would be deadly. Reports
 * once, at the end; the drops are the inventory gain since the start.
 */
final class HuntHandle implements ActionRunner.Handle {
    private static final int REPLAN_TICKS = 10;

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final String asked;
    private final int count;
    private final Map<String, Integer> before;
    private final Set<Integer> skipped = new HashSet<>(); // entity ids left alone: unreachable, got away, or refused
    private int ticks;
    private int kills;
    private @Nullable LivingEntity target;
    private int targetTicks;
    private boolean struck; // swung at the current target, so its death counts as a kill of ours
    private Path path;
    private int node;
    private @Nullable Vec3 killSpot; // non-null while collecting drops
    private int collectTicks;
    private final Set<Integer> skippedItems = new HashSet<>();
    private ActionRunner.Handle walk;
    private ItemEntity walkingTo;

    private HuntHandle(AgentRuntime rt, AgentPlayer body, String asked, int count) {
        this.rt = rt;
        this.body = body;
        this.asked = asked;
        this.count = count;
        this.before = Hands.counts(body.getInventory());
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Hunt a) {
        if (!rt.settings().attacksEnabled()) return ActionRunner.Handle.done(ActionResult.denied("attacks_off"));
        HuntHandle hunt = new HuntHandle(rt, p, a.animal(), a.count());
        hunt.target = hunt.nearest();
        if (hunt.target == null) return ActionRunner.Handle.done(ActionResult.failed("no_animals")); // no gear change for nothing
        Hands.gearUp(p);
        return hunt;
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) {
            cancel();
            return ActionResult.failed("died");
        }
        List<Mob> near = SnapshotBuilder.hostiles(body, HuntRules.DANGER_RANGE);
        String stop = HuntRules.stop(++ticks, near.isEmpty() ? Double.POSITIVE_INFINITY : near.get(0).distanceTo(body),
                body.getHealth() / body.getMaxHealth());
        if (stop != null) return end(stop);
        if (killSpot != null && collect()) return null;
        if (HuntRules.done(kills, count)) return end("done");
        if (target == null) {
            target = nearest();
            if (target == null) return end("no_more_animals");
            targetTicks = 0;
            struck = false;
        }
        return pursue(target);
    }

    /** One tick on the current target: notice its death, drop it if it is no longer allowed, strike or approach. */
    private ActionResult pursue(LivingEntity t) {
        if (!t.isAlive() || t.isRemoved()) {
            if (struck && t.isDeadOrDying()) {
                kills++;
                killSpot = t.position();
                collectTicks = 0;
                walk = null;
            } else {
                skipped.add(t.getId());
            }
            leave();
            return null;
        }
        if (t.level() != body.level() || ++targetTicks > HuntRules.CHASE_TICKS || HuntRules.refusal(asked, facts(t)) != null) {
            skipped.add(t.getId());
            leave();
            return null;
        }
        double distance = body.distanceTo(t);
        if (distance <= ActionExecutor.ATTACK_REACH) {
            strike(t, distance);
            return null;
        }
        return approach(t);
    }

    private void leave() {
        target = null;
        path = null;
        Navigator.halt(body);
    }

    private void strike(LivingEntity t, double distance) {
        Navigator.halt(body);
        path = null;
        body.lookAt(EntityAnchorArgument.Anchor.EYES, t.getEyePosition());
        if (FightRules.swingReady(distance, ActionExecutor.ATTACK_REACH, body.getAttackStrengthScale(0.5f))) {
            Swing.hit(body, t); // HuntRules was asked about t earlier in this same tick; no sweep onto bystanders
            struck = true;
        }
    }

    /** Walks along a fresh path toward the target, or straight at it when there is none (it may be mid-jump). */
    private ActionResult approach(LivingEntity t) {
        if (path == null || ticks % REPLAN_TICKS == 0) {
            path = rt.navigator().findPath(body, t.blockPosition(), 1);
            node = 0;
        }
        Vec3 point = t.position();
        if (path != null) {
            while (node < path.getNodeCount() && Navigator.reached(body, path.getNode(node))) node++;
            if (node < path.getNodeCount()) point = Navigator.center(path.getNode(node));
        }
        String hazard = Navigator.hazardAhead(body, point);
        if (hazard != null) {
            cancel();
            return ActionResult.failed("hazard_" + hazard, HuntRules.summary(asked, kills, gained(), "hazard_" + hazard));
        }
        Navigator.steer(body, point);
        return null;
    }

    /** Walks onto the drops near the kill spot for a few seconds; true while that is still going on. */
    private boolean collect() {
        ServerLevel level = body.serverLevel();
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, AABB.ofSize(killSpot, 2 * HuntRules.DROP_RANGE,
                2 * HuntRules.DROP_RANGE, 2 * HuntRules.DROP_RANGE),
                e -> e.isAlive() && !skippedItems.contains(e.getId()) && e.getAge() <= HuntRules.DROP_TICKS + 40);
        if (drops.isEmpty() || ++collectTicks > HuntRules.DROP_TICKS) {
            stopCollecting();
            return false;
        }
        if (walk == null || walkingTo == null || !walkingTo.isAlive()) {
            walkingTo = drops.stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElseThrow();
            walk = rt.navigator().moveTo(body, walkingTo.position());
        }
        ActionResult step = walk.poll();
        if (step != null) {
            if (step.isError()) skippedItems.add(walkingTo.getId());
            walk = null;
        }
        return true;
    }

    private void stopCollecting() {
        rt.navigator().stop(body);
        walk = null;
        walkingTo = null;
        killSpot = null;
        skippedItems.clear();
    }

    /** The nearest allowed animal of the asked kind within the search range that has not been given up on. */
    private @Nullable LivingEntity nearest() {
        List<LivingEntity> found = body.serverLevel().getEntitiesOfClass(LivingEntity.class,
                body.getBoundingBox().inflate(HuntRules.SEARCH_RANGE),
                e -> e != body && e.isAlive() && !skipped.contains(e.getId()) && e.distanceTo(body) <= HuntRules.SEARCH_RANGE
                        && HuntRules.allowed(asked, facts(e)));
        return found.stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
    }

    private static HuntRules.Facts facts(Entity e) {
        return new HuntRules.Facts(SnapshotBuilder.typeId(e), e instanceof Player, e instanceof AbstractVillager, e instanceof IronGolem,
                e.hasCustomName(), e instanceof OwnableEntity o && o.getOwnerUUID() != null, e instanceof Mob m && m.isLeashed(),
                e instanceof LivingEntity l && l.isBaby(), e.isVehicle() || e instanceof Saddleable s && s.isSaddled());
    }

    /** Items gained since the start, by id, in a stable order. */
    private Map<String, Integer> gained() {
        Map<String, Integer> now = Hands.counts(body.getInventory());
        Map<String, Integer> gained = new TreeMap<>();
        for (Map.Entry<String, Integer> e : now.entrySet()) {
            int n = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (n > 0) gained.put(e.getKey(), n);
        }
        return new LinkedHashMap<>(gained);
    }

    private ActionResult end(String stop) {
        cancel();
        String summary = HuntRules.summary(asked, kills, gained(), stop);
        return switch (stop) {
            case "danger", "timeout" -> ActionResult.failed(stop, summary);
            case "no_more_animals" -> kills == 0 ? ActionResult.failed("no_animals") : ActionResult.ok(summary);
            default -> ActionResult.ok(summary);
        };
    }

    @Override
    public void cancel() {
        path = null;
        target = null;
        walk = null;
        killSpot = null;
        rt.navigator().stop(body);
        Navigator.halt(body);
    }

    /** Its own 120 s limit ends the hunt with a report first; this is the backstop. */
    @Override
    public int timeoutTicks() {
        return HuntRules.BACKSTOP_TICKS;
    }
}
