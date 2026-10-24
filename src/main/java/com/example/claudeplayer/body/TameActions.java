package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.GatherRule;
import com.example.claudeplayer.core.work.Taming;
import com.example.claudeplayer.nav.Navigator;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * tame and pet. tame: wolf with bones, cat with raw cod or salmon, parrot with seeds; fails missing_item, no_animal,
 * not_tamed. pet: sit or follow for an animal of its own; fails not_mine, no_target.
 * Contract: the tool descriptions in core/brain/ToolSchemas.java and the records Action.Tame and Action.Pet in
 * core/act/Action.java. The rules and texts are in core/work/Taming.java; this is the game side.
 */
final class TameActions {
    private TameActions() {
    }

    /**
     * Targets the entity_id (an untamed animal of the asked kind, else no_animal; one the body already owns ends ok),
     * or the nearest untamed one within {@link Taming#SEARCH_RANGE}. missing_item when no food for it is carried.
     */
    static ActionRunner.Handle tame(AgentRuntime rt, AgentPlayer p, Action.Tame a) {
        String animal = Taming.normalize(a.animal());
        if (!Taming.isAnimal(animal)) return ActionRunner.Handle.done(ActionResult.failed("no_animal"));
        TamableAnimal target;
        if (a.entityId() != null) {
            Entity e = p.serverLevel().getEntity(a.entityId());
            if (!(e instanceof TamableAnimal) || !e.isAlive() || !SnapshotBuilder.typeId(e).equals(animal)) {
                return ActionRunner.Handle.done(ActionResult.failed("no_animal"));
            }
            target = (TamableAnimal) e;
            if (target.isTame()) {
                return ActionRunner.Handle.done(target.isOwnedBy(p) ? ActionResult.ok(Taming.already(animal, target.getId()))
                        : ActionResult.failed("no_animal")); // somebody else's
            }
        } else {
            target = nearestUntamed(p, animal);
            if (target == null) return ActionRunner.Handle.done(ActionResult.failed("no_animal"));
        }
        if (Taming.chooseFood(animal, Hands.counts(p.getInventory())) == null) {
            return ActionRunner.Handle.done(ActionResult.failed("missing_item", Taming.missing(animal)));
        }
        return new TameHandle(rt, p, target, animal);
    }

    /**
     * Sets the pet's sit order the way the owner's right-click does in 1.20.1 (setOrderedToSit, then no jump, no
     * path, no target), but to the asked state, so asking for the state it is already in is still ok. No range
     * check: the entity only has to be loaded. no_target for a missing, dead or non-tameable entity, not_mine
     * when the body is not its owner.
     */
    static ActionRunner.Handle pet(AgentRuntime rt, AgentPlayer p, Action.Pet a) {
        Entity e = p.serverLevel().getEntity(a.entityId());
        if (!(e instanceof TamableAnimal) || !e.isAlive()) return ActionRunner.Handle.done(ActionResult.failed("no_target"));
        TamableAnimal pet = (TamableAnimal) e;
        if (!pet.isOwnedBy(p)) return ActionRunner.Handle.done(ActionResult.failed("not_mine"));
        Boolean sit = Taming.sitting(a.command());
        if (sit == null) return ActionRunner.Handle.done(ActionResult.failed("bad_command"));
        pet.setOrderedToSit(sit);
        pet.setInSittingPose(sit && pet.onGround()); // a pet in the air sits down when it lands (SitWhenOrderedToGoal)
        pet.setJumping(false);
        pet.getNavigation().stop();
        pet.setTarget(null);
        return ActionRunner.Handle.done(ActionResult.ok(Taming.petText(SnapshotBuilder.typeId(pet), pet.getId(), sit)));
    }

    private static TamableAnimal nearestUntamed(AgentPlayer p, String animal) {
        return p.serverLevel().getEntitiesOfClass(TamableAnimal.class, p.getBoundingBox().inflate(Taming.SEARCH_RANGE),
                        t -> t.isAlive() && !t.isTame() && SnapshotBuilder.typeId(t).equals(animal) && p.distanceTo(t) <= Taming.SEARCH_RANGE)
                .stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
    }

    /**
     * Walks to the animal, then feeds it every {@link Taming#FEED_GAP_TICKS} ticks by right-clicking it with the
     * food in the main hand, standing still (a cat runs from a moving player). Taming is chance-based: it ends ok
     * when the animal is the body's, failed:not_tamed after {@link Taming#MAX_TRIES} feeds, failed:missing_item when
     * the food runs out, failed:danger, failed:hazard_*, failed:no_animal when the animal is gone or someone else
     * tamed it, failed:died and failed:timeout. Reports once, at the end.
     */
    private static final class TameHandle implements ActionRunner.Handle {
        private static final int REPLAN_TICKS = 10;

        private final AgentRuntime rt;
        private final AgentPlayer body;
        private final TamableAnimal target;
        private final String animal;
        private final int id;
        private final Map<String, Integer> used = new LinkedHashMap<>();
        private int ticks;
        private int feeds;
        private int refusals;
        private int wait = Taming.SETTLE_TICKS;
        private Path path;
        private int node;

        TameHandle(AgentRuntime rt, AgentPlayer body, TamableAnimal target, String animal) {
            this.rt = rt;
            this.body = body;
            this.target = target;
            this.animal = animal;
            this.id = target.getId();
        }

        @Override
        public ActionResult poll() {
            if (!body.isAlive()) {
                cancel();
                return ActionResult.failed("died");
            }
            List<Mob> near = SnapshotBuilder.hostiles(body, GatherRule.DANGER_RANGE);
            String stop = Taming.stop(++ticks, near.isEmpty() ? Double.POSITIVE_INFINITY : near.get(0).distanceTo(body),
                    body.getHealth() / body.getMaxHealth());
            if (stop != null) {
                cancel();
                return ActionResult.failed(stop, Taming.interrupted(stop.equals("danger") ? "danger" : "time limit", animal, id, used));
            }
            ActionResult gone = outcome();
            if (gone != null) {
                cancel();
                return gone;
            }
            if (target.level() != body.level()) {
                cancel();
                return ActionResult.failed("no_animal", Taming.interrupted("animal lost", animal, id, used));
            }
            if (body.distanceTo(target) > Taming.FEED_REACH) {
                wait = Taming.SETTLE_TICKS; // stand still a moment after arriving, before the first feed
                return approach();
            }
            Navigator.halt(body);
            path = null;
            body.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
            if (wait > 0) {
                wait--;
                return null;
            }
            return feed();
        }

        /** The end result when the animal is tamed, gone or someone else's; null while it is wild and here. */
        private ActionResult outcome() {
            if (!target.isAlive() || target.isRemoved()) return ActionResult.failed("no_animal", Taming.interrupted("animal lost", animal, id, used));
            if (target.isTame()) {
                return target.isOwnedBy(body) ? ActionResult.ok(Taming.tamed(animal, id, used)) : ActionResult.failed("no_animal");
            }
            return null;
        }

        private ActionResult feed() {
            Map<String, Integer> counts = Hands.counts(body.getInventory());
            String food = Taming.chooseFood(animal, counts);
            int slot = food == null ? -1 : Hands.find(body.getInventory(), food); // counts include the offhand, find doesn't
            if (slot < 0) {
                cancel();
                return ActionResult.failed("missing_item", Taming.ranOut(animal, id, used));
            }
            Hands.hold(body.getInventory(), slot);
            InteractionResult r = body.interactOn(target, InteractionHand.MAIN_HAND);
            body.swing(InteractionHand.MAIN_HAND, true);
            boolean ate = r.consumesAction() || Hands.counts(body.getInventory()).getOrDefault(food, 0) < counts.get(food);
            if (ate) {
                feeds++;
                used.merge(food, 1, Integer::sum);
            } else {
                refusals++;
            }
            wait = Taming.FEED_GAP_TICKS;
            ActionResult end = outcome(); // the feed that tames ends the run, even if it is the last try
            if (end == null && Taming.giveUp(feeds, refusals)) {
                end = ActionResult.failed("not_tamed", Taming.gaveUp(animal, id, used));
            }
            if (end != null) cancel();
            return end;
        }

        /** Walks along a fresh path toward the animal, or straight at it when there is none (it may be in the air). */
        private ActionResult approach() {
            if (path == null || ticks % REPLAN_TICKS == 0) {
                path = rt.navigator().findPath(body, target.blockPosition(), 1);
                node = 0;
            }
            Vec3 point = new Vec3(target.getX(), body.getY(), target.getZ());
            if (path != null) {
                while (node < path.getNodeCount() && Navigator.reached(body, path.getNode(node))) node++;
                if (node < path.getNodeCount()) point = Navigator.center(path.getNode(node));
            }
            String hazard = Navigator.hazardAhead(body, point);
            if (hazard != null) {
                cancel();
                return ActionResult.failed("hazard_" + hazard, Taming.interrupted("hazard " + hazard, animal, id, used));
            }
            Navigator.steer(body, point);
            return null;
        }

        @Override
        public void cancel() {
            path = null;
            Navigator.halt(body);
        }

        /** Its own 60 s limit ends the run with a report first; this is the backstop. */
        @Override
        public int timeoutTicks() {
            return Taming.LIMIT_TICKS + 100;
        }
    }
}
