package com.example.claudeplayer.event;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.body.AgentPlayer;
import com.example.claudeplayer.body.SnapshotBuilder;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.safety.CautionLevel;
import com.example.claudeplayer.core.safety.CautionPolicy;
import com.example.claudeplayer.core.safety.Reflexes;
import com.example.claudeplayer.core.safety.ThreatTracker;
import com.example.claudeplayer.core.safety.WorldText;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import org.slf4j.Logger;

/**
 * Turns hits and approaching hostiles into reflexes and loop interrupts, debounced by {@link ThreatTracker} (one
 * per threat, at most one every 5 s; lit creepers have their own tracker with no gap). {@link Reflexes} picks the
 * move; it is held here and delivered on the next runtime tick, never from inside another entity's tick. A mob
 * the model heard about that keeps chasing within {@link Reflexes#CHASE_RANGE} gets the decision re-run
 * ({@link Reflexes#chaseDue}), delivered only when it yields a move, and quietly: only the first reflex for a threat
 * tells the model. At caution level 5 it also calls for help below 30% HP.
 */
public final class DamageWatcher {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int SCAN_TICKS = 2;
    private static final int HURT_WINDOW_TICKS = 60;
    private static final int HELP_COOLDOWN_TICKS = 300;

    private static final int REARM_TICKS = 600;

    /**
     * {@code quiet}: a chase re-fire, which only queues its actions; the model heard about the threat already.
     * {@code threat}: the mob's id, null without an attacker.
     */
    private record Pending(String reason, List<Action> actions, boolean quiet, @Nullable Integer threat) {}

    private final AgentRuntime rt;
    private final ThreatTracker threats = new ThreatTracker(100, REARM_TICKS);
    private final ThreatTracker fuses = new ThreatTracker(0, 60);
    private final Map<Integer, Long> chased = new HashMap<>(); // mob id -> tick its decision last ran
    // mob id -> tick last seen, for mobs whose first reflex reached the model; kept on the threats' re-arm horizon
    // (not dropped on leaving range), since their near: key stays reported that long
    private final Map<Integer, Long> heard = new HashMap<>();
    private long lastHurt = -1_000;
    private long lastHelp = -1_000;
    private boolean hurtWhileMoving;
    private Pending pending;

    public DamageWatcher(AgentRuntime rt) {
        this.rt = rt;
    }

    public void onHurt(LivingHurtEvent event) {
        AgentPlayer p = rt.body().player();
        if (p == null || event.getEntity() != p) return;
        long now = rt.ticks();
        lastHurt = now;
        String running = rt.queue().runningTool();
        hurtWhileMoving = "move_to".equals(running) || "follow_player".equals(running) || "flee".equals(running);
        Entity attacker = event.getSource().getEntity();
        float left = Math.max(0, p.getHealth() - event.getAmount());
        String who = attacker != null ? describe(attacker) : event.getSource().getMsgId();
        if (rt.level() == CautionLevel.INNOCENT && left / p.getMaxHealth() < 0.3f && now - lastHelp >= HELP_COOLDOWN_TICKS) {
            lastHelp = now;
            rt.say("Help! I'm being attacked at " + p.blockPosition().toShortString() + "!");
        }
        if (threats.report("hit:" + (attacker != null ? attacker.getId() : who), now)) {
            pending = react(p, String.format(Locale.ROOT, "hit by %s for %.1f HP (%.1f/%.0f left).",
                    who, event.getAmount(), left, p.getMaxHealth()), attacker);
        }
    }

    public void tick() {
        AgentPlayer p = rt.body().player();
        if (p == null) {
            pending = null;
            return;
        }
        long now = rt.ticks();
        if (pending == null && now % SCAN_TICKS == 0) {
            List<Mob> near = SnapshotBuilder.hostiles(p, rt.settings().hostileRange());
            chased.keySet().retainAll(near.stream().map(Entity::getId).collect(Collectors.toSet())); // gone or out of range
            for (Mob mob : near) heard.computeIfPresent(mob.getId(), (id, seen) -> now);
            heard.values().removeIf(seen -> now - seen > REARM_TICKS);
            boolean levelThree = rt.queue().snapshot().stream().anyMatch(i -> i.entry().prio() == 3); // running or next
            for (Mob mob : near) {
                boolean lit = SnapshotBuilder.litCreeper(mob);
                if (!lit && mob instanceof Creeper) fuses.forget("lit:" + mob.getId()); // a re-lit fuse is a new one
                if (lit ? fuses.report("lit:" + mob.getId(), now) : threats.report("near:" + mob.getId(), now)) {
                    chased.put(mob.getId(), now);
                    pending = react(p, String.format(Locale.ROOT, "%s is %.1f blocks away%s.",
                            describe(mob), p.distanceTo(mob), lit ? ", fuse lit" : ""), mob);
                    break;
                }
                if (rt.settings().reflexes() && Reflexes.chaseDue(p.distanceTo(mob), now,
                        chased.getOrDefault(mob.getId(), Long.MIN_VALUE / 2), heard.containsKey(mob.getId()), levelThree)) {
                    chased.put(mob.getId(), now);
                    Pending chase = react(p, String.format(Locale.ROOT, "%s is still chasing, %.1f blocks away%s.",
                            describe(mob), p.distanceTo(mob), lit ? ", fuse lit" : ""), mob);
                    if (!chase.actions().isEmpty()) { // a HOLD stays quiet, or a mob near the body would spam the model
                        pending = new Pending(chase.reason(), chase.actions(), true, chase.threat());
                        break;
                    }
                }
            }
        }
        if (pending != null) {
            Pending due = pending;
            pending = null;
            if (due.threat() != null) heard.put(due.threat(), now); // a near:, lit: or hit: report reached the model
            if (due.actions().isEmpty() || !rt.settings().reflexes()) rt.loop().interrupt(due.reason());
            else if (due.quiet()) rt.loop().reflexQuiet(due.actions());
            else rt.loop().reflex(due.reason(), due.actions());
        }
    }

    /** Hurt in the last 3 s while moving: the policy's proxy for "escape failed". */
    public boolean hurtWhileFleeing() {
        return hurtWhileMoving && rt.ticks() - lastHurt <= HURT_WINDOW_TICKS;
    }

    /** The reason with the level's advice, and the reflex move for the threat (none without an attacker). */
    private Pending react(AgentPlayer p, String what, @Nullable Entity threat) {
        CautionLevel level = rt.level();
        CautionPolicy.Situation s = situation(p, threat);
        boolean lit = SnapshotBuilder.litCreeper(threat); // false for no threat
        String stance = lit ? "flee (fuse lit)" // matches the reflex, never "fight" a lit creeper
                : CautionPolicy.advise(level, s).name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String reason = what + " Caution level " + level.number() + " (" + level.title + ") says: " + stance + ".";
        if (threat == null) return new Pending(reason, List.of(), false, null);
        Reflexes.Move move = Reflexes.decide(level, s, lit, rt.settings().toolPhase() >= 4);
        List<Action> actions = switch (move) {
            case FLEE -> List.of(new Action.Flee());
            case FIGHT -> List.of(new Action.Fight(threat.getId()));
            case NONE -> List.of();
        };
        if (!actions.isEmpty() && rt.settings().reflexes()) {
            LOG.info("reflex {} for {} #{}", move, SnapshotBuilder.typeId(threat), threat.getId());
        }
        return new Pending(reason, actions, false, threat.getId());
    }

    private CautionPolicy.Situation situation(AgentPlayer p, @Nullable Entity threat) {
        double distance = threat == null ? 0 : p.distanceTo(threat);
        return new CautionPolicy.Situation(threat != null && SnapshotBuilder.isHostile(threat),
                threat instanceof Player, distance, distance <= 2.0, p.getHealth() / p.getMaxHealth(),
                rt.escape().escapeExists(p, SnapshotBuilder.hostiles(p, rt.settings().hostileRange())), hurtWhileFleeing());
    }

    private static String describe(Entity e) {
        String name = e instanceof Player player ? WorldText.clean(player.getGameProfile().getName(), WorldText.NAME_MAX)
                : SnapshotBuilder.customName(e);
        String type = SnapshotBuilder.typeId(e);
        return (name != null ? type + " \"" + name + "\"" : type) + " #" + e.getId();
    }
}
