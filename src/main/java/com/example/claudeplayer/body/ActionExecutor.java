package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.safety.CautionPolicy;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Runs one world action on the body, on the server thread. Checks come before effects (guardrails, then the
 * caution policy for attacks), so a denial reaches the model with its reason instead of the world changing.
 * Memory tools never get here; MemoryActions handles them in the core.
 */
public final class ActionExecutor implements ActionRunner {
    public static final double ATTACK_REACH = 3.0;

    private final AgentRuntime rt;
    private final ContainerActions containers;

    public ActionExecutor(AgentRuntime rt) {
        this.rt = rt;
        this.containers = new ContainerActions(rt);
    }

    @Override
    public Handle start(Action action) {
        AgentPlayer p = rt.body().player();
        if (p == null) return Handle.done(ActionResult.failed("no_body"));
        if (action instanceof Action.GetState) return Handle.done(ActionResult.ok(SnapshotBuilder.build(rt).toJson()));
        if (action instanceof Action.Look look) {
            return Handle.done(ActionResult.ok(SnapshotBuilder.map(p, look.radius(), rt.settings().hostileRange()).text()));
        }
        if (action instanceof Action.Say say) {
            rt.say(say.text());
            return Handle.done(ActionResult.ok());
        }
        if (action instanceof Action.Wait wait) return waitFor((int) Math.round(wait.seconds() * 20));
        if (action instanceof Action.Stop) {
            rt.navigator().stop(p);
            return Handle.done(ActionResult.ok());
        }
        if (action instanceof Action.MoveTo m) return rt.navigator().moveTo(p, new Vec3(m.x(), m.y(), m.z()));
        if (action instanceof Action.Flee) return flee(p);
        if (action instanceof Action.InspectContainer c) return Handle.done(containers.inspect(p, new BlockPos(c.x(), c.y(), c.z())));
        if (action instanceof Action.Store s) return Handle.done(containers.store(p, s));
        if (action instanceof Action.Take t) return Handle.done(containers.take(p, t));
        if (action instanceof Action.Eat e) return EatHandle.start(p, e.auto());
        if (action instanceof Action.FollowPlayer f) return follow(p, f);
        if (action instanceof Action.AttackEntity a) return Handle.done(attack(p, a.entityId()));
        if (action instanceof Action.MineBlock b) return Handle.done(blockEdit(b.x(), b.y(), b.z()));
        if (action instanceof Action.PlaceBlock b) return Handle.done(blockEdit(b.x(), b.y(), b.z()));
        // equip and craft land with the v2 tools (phase 6)
        return Handle.done(ActionResult.failed("not_implemented"));
    }

    private static Handle waitFor(int ticks) {
        return new Handle() {
            private int left = Math.max(1, ticks);

            @Override
            public ActionResult poll() {
                return --left <= 0 ? ActionResult.ok() : null;
            }

            @Override
            public int timeoutTicks() {
                return ticks + 20;
            }
        };
    }

    private Handle follow(AgentPlayer p, Action.FollowPlayer f) {
        ServerPlayer target = rt.server().getPlayerList().getPlayerByName(f.name());
        if (target == null || target == p) return Handle.done(ActionResult.failed("no_such_player"));
        return rt.navigator().follow(p, target, (int) Math.round(f.seconds() * 20));
    }

    /** Runs to the first reachable spot on the side away from the nearest hostile. */
    private Handle flee(AgentPlayer p) {
        List<Mob> threats = SnapshotBuilder.hostilesXZ(p, rt.settings().hostileRange());
        if (threats.isEmpty()) return Handle.done(ActionResult.ok("no_threats"));
        Vec3 dest = rt.escape().destination(p, threats);
        if (dest == null) return Handle.done(ActionResult.failed("no_escape"));
        return rt.navigator().moveTo(p, dest);
    }

    private ActionResult attack(AgentPlayer p, int entityId) {
        Entity target = p.serverLevel().getEntity(entityId);
        if (target == null || !target.isAlive()) return ActionResult.failed("no_target");
        double distance = p.distanceTo(target);
        CautionPolicy.Situation situation = new CautionPolicy.Situation(SnapshotBuilder.isHostile(target),
                target instanceof Player, distance, distance <= 2.0, p.getHealth() / p.getMaxHealth(),
                rt.escape().escapeExists(p, SnapshotBuilder.hostiles(p, rt.settings().hostileRange())),
                rt.damage().hurtWhileFleeing());
        CautionPolicy.Verdict verdict = CautionPolicy.check(rt.level(), situation);
        if (!verdict.allowed()) return ActionResult.denied(verdict.reason());
        if (distance > ATTACK_REACH) return ActionResult.failed("out_of_reach");
        if (p.getAttackStrengthScale(0.5f) < 0.9f) return ActionResult.failed("cooldown");
        p.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        p.attack(target);
        p.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.ok();
    }

    // The work-area check is live; the edit itself lands with the v2 world tools (phase 6).
    private ActionResult blockEdit(int x, int y, int z) {
        if (!rt.guardrails().allowBlockChange(x, y, z, SnapshotBuilder.home(rt))) return ActionResult.denied("outside_work_area");
        return ActionResult.failed("not_implemented");
    }
}
