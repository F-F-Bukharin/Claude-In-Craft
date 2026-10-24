package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.safety.CautionPolicy;
import com.example.claudeplayer.core.safety.FightRules;
import com.example.claudeplayer.nav.Navigator;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * fight: gears up (Hands.gearUp), then each tick asks CautionPolicy about the target. Within reach it stops, looks
 * and swings once the cooldown is charged; beyond reach it walks, re-planning about every 10 ticks. It ends ok when
 * the target dies ("won: ...") or leaves the level's range ("target out of range"), denied with any other policy
 * reason, failed:timeout after 30 s, failed:died, or failed:hazard_* when the next step would be deadly.
 */
final class FightHandle implements ActionRunner.Handle {
    static final int LIMIT_TICKS = 600;
    private static final int REPLAN_TICKS = 10;
    /** escapeExists plans a path, so it is asked at most once a second. */
    private static final int ESCAPE_TICKS = 20;

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final Entity target;
    private final String who;
    private final float hpBefore;
    private boolean escape;
    private String equipped = "";
    private int ticks;
    private Path path;
    private int node;

    private FightHandle(AgentRuntime rt, AgentPlayer body, Entity target) {
        this.rt = rt;
        this.body = body;
        this.target = target;
        this.who = SnapshotBuilder.typeId(target) + " #" + target.getId(); // no custom name: world text stays out
        this.hpBefore = body.getHealth();
        this.escape = escapeExists();
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Fight f) {
        Entity target = p.serverLevel().getEntity(f.entityId());
        if (target == null || !target.isAlive()) return ActionRunner.Handle.done(ActionResult.failed("no_target"));
        FightHandle fight = new FightHandle(rt, p, target);
        ActionResult early = fight.verdict(); // a target the policy refuses gets no gear change
        if (early != null) return ActionRunner.Handle.done(early);
        fight.equipped = String.join(", ", Hands.gearUp(p));
        return fight;
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) {
            cancel();
            return ActionResult.failed("died");
        }
        if (!target.isAlive() || target.isRemoved()) {
            cancel();
            return ActionResult.ok(FightRules.won(who, hpBefore - body.getHealth(), equipped));
        }
        if (++ticks > LIMIT_TICKS) {
            cancel();
            return ActionResult.failed("timeout");
        }
        if (ticks % ESCAPE_TICKS == 0) escape = escapeExists();
        ActionResult end = verdict();
        if (end != null) {
            cancel();
            return end;
        }
        double distance = body.distanceTo(target);
        if (distance <= ActionExecutor.ATTACK_REACH) {
            strike(distance);
            return null;
        }
        return approach();
    }

    /** The policy's verdict as an end result, or null while the fight goes on (strike or approach). */
    private ActionResult verdict() {
        if (target.level() != body.level()) return ActionResult.ok("target out of range");
        double distance = body.distanceTo(target);
        CautionPolicy.Verdict v = CautionPolicy.check(rt.level(), new CautionPolicy.Situation(SnapshotBuilder.isHostile(target),
                target instanceof Player, distance, distance <= 2.0, body.getHealth() / body.getMaxHealth(), escape,
                rt.damage().hurtWhileFleeing()));
        return switch (FightRules.step(v, distance, ActionExecutor.ATTACK_REACH)) {
            case DENIED -> ActionResult.denied(v.reason());
            case LEFT_RANGE -> ActionResult.ok("target out of range");
            case STRIKE, APPROACH -> null;
        };
    }

    private void strike(double distance) {
        Navigator.halt(body);
        path = null;
        body.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        if (FightRules.swingReady(distance, ActionExecutor.ATTACK_REACH, body.getAttackStrengthScale(0.5f))) {
            Swing.hit(body, target); // no sweep onto bystanders
        }
    }

    /** Walks along a fresh path toward the target, or straight at it when there is none (it may be mid-jump). */
    private ActionResult approach() {
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
        if (hazard != null) {
            cancel();
            return ActionResult.failed("hazard_" + hazard);
        }
        Navigator.steer(body, point);
        return null;
    }

    private boolean escapeExists() {
        return rt.escape().escapeExists(body, SnapshotBuilder.hostiles(body, rt.settings().hostileRange()));
    }

    @Override
    public void cancel() {
        path = null;
        Navigator.halt(body);
    }

    /** Its own 30 s limit ends the fight first; this is the backstop. */
    @Override
    public int timeoutTicks() {
        return LIMIT_TICKS + 20;
    }
}
